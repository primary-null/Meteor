package com.meteor.app.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest
import java.util.UUID
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoField

data class VmDisk(val name: String, val size: Long, val device: String, val attached: Boolean, val readOnly: Boolean)
data class VmNetwork(val name: String, val device: String, val mac: String, val attached: Boolean)
data class VmDetails(
    val authority: String, val uuid: String, val name: String, val description: String, val power: String,
    val host: String?, val vcpus: Int, val memoryBytes: Long, val disks: List<VmDisk>, val networks: List<VmNetwork>,
    val guestToolsLive: Boolean, val os: String?, val addresses: List<String>, val bootTime: String?,
    val allowed: Set<String>, val currentOperations: Boolean, val observedAt: Long,
)
enum class PowerAction(val title: String, val operation: String, val before: String, val after: String) {
    START("Start", "start", "Halted", "Running"),
    SHUTDOWN("Shut down", "clean_shutdown", "Running", "Halted"),
    REBOOT("Reboot", "clean_reboot", "Running", "Running"),
}
enum class PowerPhase { PREPARED, SUBMITTING, TRACKING, VERIFYING, UNKNOWN, SUCCEEDED, FAILED }
data class PowerOperation(
    val id: String, val profileId: String, val origin: String, val authority: String, val vmUuid: String,
    val vmName: String, val action: PowerAction, val beforeBoot: String?, val phase: PowerPhase,
    val createdAt: Long, val updatedAt: Long, val taskRef: String? = null, val taskUuid: String? = null,
    val message: String = "", val manualResolution: ManualResolution? = null,
) {
    init {
        require(manualResolution == null || (phase == PowerPhase.UNKNOWN && manualResolution.reviewedAt > 0 &&
            manualResolution.origin.matches(Regex("[0-9a-f]{64}")))) { "Invalid manual resolution." }
    }
    val unresolved get() = manualResolution == null && phase !in setOf(PowerPhase.SUCCEEDED, PowerPhase.FAILED)
}

/** Separate from task success: the original action and its unknown outcome are retained. */
data class ManualResolution(val reviewedAt: Long, val origin: String, val power: String, val bootTime: String?)
data class PowerReview(val operation: PowerOperation, val profileId: String, val origin: String, val details: VmDetails)
data class PowerConfirmation(val profileId: String, val origin: String, val details: VmDetails, val action: PowerAction)
data class TaskObservation(val status: String, val progress: Double)

fun Profile.origin(): String = MessageDigest.getInstance("SHA-256")
    .digest((Endpoints.normalize(endpoint) + "|" + certificateSha256 + "|" + username).toByteArray())
    .joinToString("") { "%02x".format(it) }

object PowerRules {
    private val compactBootFormat = DateTimeFormatterBuilder().appendPattern("yyyyMMdd'T'HH:mm:ss")
        .optionalStart().appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true).optionalEnd().toFormatter()
    fun eligible(vm: VmDetails, action: PowerAction): Boolean = vm.power == action.before &&
        !vm.currentOperations && action.operation in vm.allowed &&
        (action != PowerAction.REBOOT || validBoot(vm.bootTime))
    fun validBoot(value: String?): Boolean {
        if (value.isNullOrBlank()) return false
        val instant = runCatching { Instant.parse(value) }.getOrElse {
            runCatching { LocalDateTime.parse(value.removeSuffix("Z"), compactBootFormat).toInstant(ZoneOffset.UTC) }.getOrNull()
        }
        return instant != null && instant.epochSecond > 0
    }
    fun confirm(profile: Profile, confirmation: PowerConfirmation, current: VmDetails, now: Long) {
        require(profile.id == confirmation.profileId && profile.origin() == confirmation.origin) { "Connection changed. Review the action again." }
        require(current.authority == confirmation.details.authority && current.uuid == confirmation.details.uuid) { "VM identity changed." }
        require(now - confirmation.details.observedAt in 0..30_000) { "Confirmation expired. Review the action again." }
        require(eligible(current, confirmation.action)) { "This action is no longer available. Refresh the VM." }
        require(current.power == confirmation.details.power && current.bootTime == confirmation.details.bootTime) { "VM changed. Review the action again." }
    }
    fun verified(operation: PowerOperation, current: VmDetails): Boolean =
        operation.authority == current.authority && operation.vmUuid == current.uuid && current.power == operation.action.after &&
            (operation.action != PowerAction.REBOOT || (validBoot(current.bootTime) && current.bootTime != operation.beforeBoot))
}

interface OperationStore {
    suspend fun load(): List<PowerOperation>
    suspend fun put(operation: PowerOperation)
}
interface PowerConnection {
    suspend fun details(authority: String, uuid: String): VmDetails
    suspend fun submit(authority: String, uuid: String, action: PowerAction): String
    suspend fun taskUuid(reference: String): String
    suspend fun taskReference(uuid: String): String
    suspend fun observeTask(reference: String): TaskObservation
}
interface PowerBackend {
    suspend fun <T> powerConnection(profile: Profile, action: suspend PowerConnection.() -> T): T
}

/** All sends are journaled once. Recovery observes only and never calls submit. */
class PowerService(
    private val backend: PowerBackend, private val store: OperationStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val wait: suspend () -> Unit = { delay(1500) }, private val attempts: Int = 80,
) {
    // Multiple activities/viewmodels still share one submit gate in this app process.
    private companion object { val processMutex = Mutex() }
    // A failed disk cannot durably retain a ticket. Keep it for a later write in this process.
    private var retainedTicket: PowerOperation? = null
    private suspend fun save(op: PowerOperation, changed: suspend (PowerOperation) -> Unit): PowerOperation {
        val updated = op.copy(updatedAt = clock())
        if (updated.taskRef != null) retainedTicket = updated
        store.put(updated); changed(updated); return updated
    }
    suspend fun execute(profile: Profile, confirmation: PowerConfirmation, changed: suspend (PowerOperation) -> Unit = {}): PowerOperation = processMutex.withLock {
        require(store.load().none { it.unresolved }) { "Resolve the pending operation before sending another." }
        backend.powerConnection(profile) {
            val vm = details(confirmation.details.authority, confirmation.details.uuid)
            PowerRules.confirm(profile, confirmation, vm, clock())
            var op = PowerOperation(UUID.randomUUID().toString(), profile.id, profile.origin(), vm.authority, vm.uuid,
                vm.name, confirmation.action, vm.bootTime, PowerPhase.PREPARED, clock(), clock())
            op = save(op, changed)
            // Write-ahead barrier: from here any lost result is uncertain, never retryable automatically.
            op = save(op.copy(phase = PowerPhase.SUBMITTING, message = "Sending once…"), changed)
            try {
                val reference = submit(op.authority, op.vmUuid, op.action)
                op = op.copy(phase = PowerPhase.TRACKING, taskRef = reference, message = "Waiting for server task…")
                op = save(op, changed)
                val uuid = taskUuid(reference)
                op = op.copy(taskUuid = uuid)
                op = save(op, changed)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                // An explicit XAPI rejection before a task ticket is definitive. Anything else is uncertain.
                val rejected = error is RpcFailure && op.taskRef == null
                return@powerConnection save(op.copy(phase = if (rejected) PowerPhase.FAILED else PowerPhase.UNKNOWN,
                    message = if (rejected) "Server rejected the request (${error.code})." else "Result unknown. Check status; do not repeat the action."), changed)
            }
            track(this, op, changed)
        }
    }
    suspend fun reconcile(profile: Profile, operation: PowerOperation, changed: suspend (PowerOperation) -> Unit = {}): PowerOperation = processMutex.withLock {
        val persisted = store.load().single { it.id == operation.id }
        if (!persisted.unresolved) return@withLock persisted
        require(persisted.profileId == profile.id && persisted.origin == profile.origin()) { "Original connection required for recovery." }
        val ticket = retainedTicket?.takeIf { saved ->
            saved.id == persisted.id && saved.profileId == persisted.profileId && saved.origin == persisted.origin &&
                saved.authority == persisted.authority && saved.vmUuid == persisted.vmUuid && saved.action == persisted.action &&
                saved.createdAt == persisted.createdAt && saved.beforeBoot == persisted.beforeBoot &&
                (persisted.taskRef == null || persisted.taskRef == saved.taskRef) &&
                (persisted.taskUuid == null || persisted.taskUuid == saved.taskUuid)
        }
        val latest = if (persisted.unresolved && ticket != null &&
            (persisted.taskRef == null || persisted.taskUuid == null && ticket.taskUuid != null)) {
            save(persisted.copy(taskRef = persisted.taskRef ?: ticket.taskRef,
                taskUuid = persisted.taskUuid ?: ticket.taskUuid), changed)
        } else persisted
        if (!latest.unresolved) return@withLock latest
        require(latest.profileId == profile.id && latest.origin == profile.origin()) { "Original connection required for recovery." }
        if (latest.phase == PowerPhase.PREPARED) return@withLock save(latest.copy(phase = PowerPhase.FAILED, message = "App stopped before submission. No request was sent."), changed)
        if (latest.taskRef == null && latest.taskUuid == null) return@withLock save(latest.copy(phase = PowerPhase.UNKNOWN,
            message = "No task ticket was saved. Check the VM and server task history. Meteor will not resend this action."), changed)
        try {
            backend.powerConnection(profile) {
                // Reads the pool/VM identity before looking at a task from the saved origin.
                details(latest.authority, latest.vmUuid)
                val ref = latest.taskUuid?.let { taskReference(it) } ?: requireNotNull(latest.taskRef)
                val identity = latest.taskUuid ?: taskUuid(ref)
                val resumed = save(latest.copy(taskRef = ref, taskUuid = identity), changed)
                track(this, resumed, changed)
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            save(latest.copy(phase = PowerPhase.UNKNOWN, message = "Could not reconcile securely. Keep this operation pending and check again."), changed)
        }
    }
    suspend fun review(profile: Profile, operation: PowerOperation): PowerReview = processMutex.withLock {
        val latest = store.load().single { it.id == operation.id }
        require(latest.unresolved) { "This operation is already closed." }
        backend.powerConnection(profile) {
            val current = details(latest.authority, latest.vmUuid)
            requireReviewIdentity(latest, current)
            PowerReview(latest, profile.id, profile.origin(), current)
        }
    }
    suspend fun resolveAfterReview(profile: Profile, review: PowerReview, serverHistoryChecked: Boolean,
        changed: suspend (PowerOperation) -> Unit = {}): PowerOperation = processMutex.withLock {
        require(serverHistoryChecked) { "Review the server task history first." }
        val latest = store.load().single { it.id == review.operation.id }
        require(latest == review.operation && latest.unresolved) { "Operation changed. Review it again." }
        require(profile.id == review.profileId && profile.origin() == review.origin) { "Connection changed. Review it again." }
        require(clock() - review.details.observedAt in 0..30_000) { "Review expired. Read the VM again." }
        backend.powerConnection(profile) {
            val current = details(latest.authority, latest.vmUuid)
            requireReviewIdentity(latest, current)
            require(current.power == review.details.power && current.bootTime == review.details.bootTime) { "VM changed. Review it again." }
            save(latest.copy(phase = PowerPhase.UNKNOWN, manualResolution = ManualResolution(clock(), profile.origin(), current.power, current.bootTime),
                message = "Closed after manual review: ${current.power}. Original task outcome remains unverified. No action was resent."), changed)
        }
    }
    private fun requireReviewIdentity(operation: PowerOperation, current: VmDetails) {
        require(current.authority == operation.authority && current.uuid == operation.vmUuid) { "Original pool and VM required." }
        require(!current.currentOperations) { "The VM still has an active operation. Check status later." }
    }
    private suspend fun track(connection: PowerConnection, initial: PowerOperation, changed: suspend (PowerOperation) -> Unit): PowerOperation {
        var op = initial
        try {
            repeat(attempts) {
                val task = connection.observeTask(requireNotNull(op.taskRef))
                when (task.status) {
                    "failure", "cancelled" -> return save(op.copy(phase = PowerPhase.FAILED, message = "Server task ${task.status}. Refresh the VM before another action."), changed)
                    "success" -> {
                        if (op.phase != PowerPhase.VERIFYING) op = save(op.copy(phase = PowerPhase.VERIFYING, message = "Task finished. Verifying VM state…"), changed)
                        val current = connection.details(op.authority, op.vmUuid)
                        if (PowerRules.verified(op, current)) return save(op.copy(phase = PowerPhase.SUCCEEDED,
                            message = if (op.action == PowerAction.REBOOT) "Reboot verified: boot timestamp changed." else "${op.action.title} verified: ${current.power}."), changed)
                    }
                    "pending", "cancelling" -> if (op.phase != PowerPhase.TRACKING) op = save(op.copy(phase = PowerPhase.TRACKING, message = "Waiting for server task…"), changed)
                    else -> throw IllegalStateException("Unknown task state")
                }
                wait()
            }
        } catch (error: Exception) { if (error is CancellationException) throw error }
        return save(op.copy(phase = PowerPhase.UNKNOWN, message = "Still unverified. Check status to continue observing; the action will not be resent."), changed)
    }
}
