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
    val topologyObservedAt: Long = observedAt, val topology: VmTopology? = null,
    // --- A2 operations ---
    /** UUID of the resident host, read with the same VM record. Null when halted or unknown. */
    val residentHostUuid: String? = null,
    // --- end A2 operations ---
)
/**
 * Every journaled VM operation kind. The enum name is the journal key; never rename an entry.
 * `operation` is both the allowed_operations key and the XAPI method suffix (`Async.VM.<operation>`).
 */
enum class PowerAction(val title: String, val operation: String, val before: String, val after: String,
    // --- A2 operations ---
    /** Power states this kind may start from. [before] remains the primary state. */
    val allowedFrom: Set<String> = setOf(before),
    val confirmation: ConfirmationStrength = ConfirmationStrength.SINGLE,
    val hostTarget: HostTarget = HostTarget.NONE,
    /** Success also needs a valid boot marker that differs from the preflight marker. */
    val requiresBootChange: Boolean = false,
    /** Lowest static XAPI role normally allowed; used only to explain, never to block. */
    val minimumRole: XapiRole = XapiRole.VM_OPERATOR,
    val forced: Boolean = false,
    val description: String = "",
    val notAllowedHint: String = "The server does not currently allow this action.",
    val requestable: Boolean = true,
    // --- end A2 operations ---
) {
    START("Start", "start", "Halted", "Running",
        description = "Boot this virtual machine. Meteor will track the server task and verify its state."),
    SHUTDOWN("Shut down", "clean_shutdown", "Running", "Halted",
        description = "Ask the guest to shut down gracefully. Running services will be interrupted."),
    REBOOT("Reboot", "clean_reboot", "Running", "Running", requiresBootChange = true,
        description = "Ask the guest to reboot gracefully. Running services will be interrupted."),
    // --- A2 operations ---
    HARD_SHUTDOWN("Force shut down", "hard_shutdown", "Running", "Halted",
        allowedFrom = setOf("Running", "Paused", "Suspended"), confirmation = ConfirmationStrength.TYPED_NAME, forced = true,
        description = "Cut the VM's power immediately without asking the guest. Unsaved guest data can be lost and file systems may need repair. A suspended VM's saved memory image is discarded."),
    HARD_REBOOT("Force reboot", "hard_reboot", "Running", "Running",
        confirmation = ConfirmationStrength.TYPED_NAME, requiresBootChange = true, forced = true,
        description = "Reset the VM immediately without asking the guest. Unsaved guest data can be lost and file systems may need repair."),
    SUSPEND("Suspend", "suspend", "Running", "Suspended",
        description = "Save the VM's memory to storage and stop it. Resume continues where it left off.",
        notAllowedHint = "The server does not currently allow suspend. XAPI omits it for VMs with a vTPM, PCI or USB passthrough, or a guest that cannot be suspended."),
    RESUME("Resume", "resume", "Suspended", "Running",
        description = "Restore the saved memory image and continue running (not paused, without forcing compatibility checks)."),
    PAUSE("Pause", "pause", "Running", "Paused",
        description = "Freeze the VM in memory. Its clock and network stall until it is unpaused. A paused VM does not survive a host restart."),
    UNPAUSE("Unpause", "unpause", "Paused", "Running",
        description = "Continue running this paused VM."),
    START_ON("Start on host", "start_on", "Halted", "Running",
        hostTarget = HostTarget.ANY_LIVE_HOST, minimumRole = XapiRole.VM_POWER_ADMIN,
        description = "Boot this VM on the chosen host. Meteor verifies that it is running there."),
    POOL_MIGRATE("Migrate to host", "pool_migrate", "Running", "Running",
        hostTarget = HostTarget.OTHER_LIVE_HOST, confirmation = ConfirmationStrength.TYPED_NAME, minimumRole = XapiRole.VM_POWER_ADMIN,
        description = "Live-migrate this running VM to another host in the same pool. Its disks must be on storage the target host can reach. Meteor verifies that it is running there."),
    /** A journal record whose kind this version does not know. Never requested, observed or resent. */
    UNRECOGNIZED("Unrecognized action", "", "", "", allowedFrom = emptySet(), requestable = false),
    // --- end A2 operations ---
    // --- B2 storage/host ---
    /**
     * A B2 storage/host record: the kind is [PowerOperation.targetAction]. Not requestable, so the
     * VM send, VM verification and VM review paths always refuse it. Never written to the journal.
     */
    TARGET("Storage or host action", "", "", "", allowedFrom = emptySet(), requestable = false);
    // --- end B2 storage/host ---

    // --- A2 operations ---
    val xapiMethod: String get() = "Async.VM.$operation"
    /** Arguments after the session reference. Booleans are start_paused=false and force=false. */
    fun xapiArguments(vm: String, host: String?): List<Any?> {
        require(requestable) { "This action cannot be sent." }
        require((host != null) == (hostTarget != HostTarget.NONE)) { "Host target does not match the action." }
        return when (this) {
            START, RESUME -> listOf(vm, false, false)
            START_ON -> listOf(vm, host, false, false)
            POOL_MIGRATE -> listOf(vm, host, mapOf("live" to "true"))
            else -> listOf(vm)
        }
    }
    fun confirmationTitle(vmName: String, hostName: String?): String = when (this) {
        START_ON -> "Start $vmName on ${hostName ?: "host"}?"
        POOL_MIGRATE -> "Migrate $vmName to ${hostName ?: "host"}?"
        else -> "$title $vmName?"
    }
    // --- end A2 operations ---
}
enum class PowerPhase { PREPARED, SUBMITTING, TRACKING, VERIFYING, UNKNOWN, SUCCEEDED, FAILED }
data class PowerOperation(
    val id: String, val profileId: String, val origin: String, val authority: String, val vmUuid: String,
    val vmName: String, val action: PowerAction, val beforeBoot: String?, val phase: PowerPhase,
    val createdAt: Long, val updatedAt: Long, val taskRef: String? = null, val taskUuid: String? = null,
    val message: String = "", val manualResolution: ManualResolution? = null,
    // --- A2 operations ---
    val targetHostUuid: String? = null, val targetHostName: String? = null,
    /** Raw journal fields of a record this version cannot interpret; written back unchanged. */
    val foreign: Map<String, Any?>? = null,
    // --- end A2 operations ---
    // --- B2 storage/host --- See TargetOperations.kt for the journal keys.
    val targetAction: TargetAction? = null, val targetUuid: String? = null, val targetName: String? = null,
    /** The chosen ISO VDI UUID for an insert, or the created snapshot's UUID once a take is verified. */
    val argumentUuid: String? = null,
    /** The ISO name for an insert, or the requested snapshot name for a take. */
    val argumentName: String? = null,
    val marker: Map<String, String>? = null, val steps: List<OperationStep>? = null,
    // --- end B2 storage/host ---
) {
    init {
        require(manualResolution == null || (phase == PowerPhase.UNKNOWN && manualResolution.reviewedAt > 0 &&
            manualResolution.origin.matches(Regex("[0-9a-f]{64}")))) { "Invalid manual resolution." }
    }
    // --- B2 storage/host ---
    init {
        require((action == PowerAction.TARGET) == (targetAction != null)) { "Storage/host kind mismatch." }
        require(targetAction == null || (foreign == null && !targetUuid.isNullOrBlank())) { "Storage/host record needs a target." }
    }
    // --- end B2 storage/host ---
    val unresolved get() = manualResolution == null && phase !in setOf(PowerPhase.SUCCEEDED, PowerPhase.FAILED)
}

/** Separate from task success: the original action and its unknown outcome are retained. */
data class ManualResolution(val reviewedAt: Long, val origin: String, val power: String, val bootTime: String?)
data class PowerReview(val operation: PowerOperation, val profileId: String, val origin: String, val details: VmDetails)
data class PowerConfirmation(val profileId: String, val origin: String, val details: VmDetails, val action: PowerAction,
    // --- A2 operations ---
    val targetHost: HostChoice? = null, val typedName: String? = null,
)
/** [errorCode] is the first `error_info` element of a failed task, when it is a valid XAPI code. */
data class TaskObservation(val status: String, val progress: Double, val errorCode: String? = null)

fun Profile.origin(): String = MessageDigest.getInstance("SHA-256")
    .digest((Endpoints.normalize(endpoint) + "|" + certificateSha256 + "|" + username).toByteArray())
    .joinToString("") { "%02x".format(it) }

object PowerRules {
    private val compactBootFormat = DateTimeFormatterBuilder().appendPattern("yyyyMMdd'T'HH:mm:ss")
        .optionalStart().appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true).optionalEnd().toFormatter()
    // One gate for every kind: a control is enabled exactly when there is no reason to disable it.
    fun eligible(vm: VmDetails, action: PowerAction): Boolean = unavailableReason(vm, action) == null
    fun unavailableReason(vm: VmDetails, action: PowerAction): String? = when {
        !action.requestable -> "This action is not available in this version of Meteor."
        vm.currentOperations -> "Another VM operation is active."
        vm.power !in action.allowedFrom -> "Requires the VM to be ${states(action.allowedFrom)}."
        action.operation !in vm.allowed -> action.notAllowedHint
        action.requiresBootChange && !validBoot(vm.bootTime) -> "A valid last-boot time is needed to verify a reboot."
        action.hostTarget == HostTarget.OTHER_LIVE_HOST && vm.residentHostUuid == null -> "The VM's current host is unknown. Refresh the VM."
        else -> null
    }
    // --- A2 operations ---
    private fun states(values: Set<String>): String {
        val names = values.map { it.lowercase() }
        return if (names.size <= 1) names.joinToString() else names.dropLast(1).joinToString() + " or " + names.last()
    }
    /** Hosts the picker may offer: enabled, live, and for migration not the current host. */
    fun hostCandidates(action: PowerAction, hosts: List<HostChoice>, residentHostUuid: String?): List<HostChoice> =
        hosts.filter { targetProblem(action, it, residentHostUuid) == null }
    private fun targetProblem(action: PowerAction, target: HostChoice?, residentHostUuid: String?): String? = when {
        action.hostTarget == HostTarget.NONE -> if (target == null) null else "This action does not take a host."
        target == null || target.uuid.isBlank() -> "Choose a host."
        !target.enabled -> "${target.name} is disabled for new VMs."
        !target.live -> "${target.name} is not reporting as live."
        action.hostTarget == HostTarget.OTHER_LIVE_HOST && target.uuid == residentHostUuid -> "The VM is already on ${target.name}."
        else -> null
    }
    fun targetProblem(vm: VmDetails, action: PowerAction, target: HostChoice?): String? = targetProblem(action, target, vm.residentHostUuid)
    /** The exact, case-sensitive text a typed confirmation needs. */
    fun confirmationPhrase(details: VmDetails): String = details.name.ifBlank { details.uuid }
    fun typedNameMatches(confirmation: PowerConfirmation): Boolean =
        confirmation.action.confirmation != ConfirmationStrength.TYPED_NAME ||
            confirmation.typedName == confirmationPhrase(confirmation.details)
    // --- end A2 operations ---
    private fun bootInstant(value: String?): Instant? {
        if (value.isNullOrBlank()) return null
        val instant = runCatching { Instant.parse(value) }.getOrElse {
            runCatching { LocalDateTime.parse(value.removeSuffix("Z"), compactBootFormat).toInstant(ZoneOffset.UTC) }.getOrNull()
        }
        return instant?.takeIf { it.epochSecond > 0 }
    }
    fun validBoot(value: String?): Boolean = bootInstant(value) != null
    fun confirm(profile: Profile, confirmation: PowerConfirmation, current: VmDetails, now: Long) {
        require(profile.id == confirmation.profileId && profile.origin() == confirmation.origin) { "Connection changed. Review the action again." }
        require(current.authority == confirmation.details.authority && current.uuid == confirmation.details.uuid) { "VM identity changed." }
        require(now - confirmation.details.observedAt in 0..30_000) { "Confirmation expired. Review the action again." }
        require(eligible(current, confirmation.action)) { "This action is no longer available. Refresh the VM." }
        require(current.power == confirmation.details.power && current.bootTime == confirmation.details.bootTime) { "VM changed. Review the action again." }
        // --- A2 operations ---
        require(current.residentHostUuid == confirmation.details.residentHostUuid) { "VM changed. Review the action again." }
        require(current.name == confirmation.details.name && typedNameMatches(confirmation)) { "The typed VM name does not match. Review the action again." }
        targetProblem(current, confirmation.action, confirmation.targetHost)?.let { throw IllegalArgumentException(it) }
        // --- end A2 operations ---
    }
    fun verified(operation: PowerOperation, current: VmDetails): Boolean {
        if (operation.foreign != null || !operation.action.requestable) return false
        if (operation.authority != current.authority || operation.vmUuid != current.uuid || current.power != operation.action.after) return false
        // --- A2 operations ---
        if (operation.action.hostTarget != HostTarget.NONE &&
            (operation.targetHostUuid == null || current.residentHostUuid != operation.targetHostUuid)) return false
        // --- end A2 operations ---
        if (!operation.action.requiresBootChange) return true
        val before = bootInstant(operation.beforeBoot) ?: return false
        val after = bootInstant(current.bootTime) ?: return false
        return after != before
    }
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
    // --- A2 operations ---
    /** Sends one kind. Kinds without a host keep using [submit], so older fakes and callers still work. */
    suspend fun submitOperation(authority: String, uuid: String, action: PowerAction, targetHost: String?): String =
        if (targetHost == null) submit(authority, uuid, action) else throw UnsupportedOperationException("Host-targeted sends are unavailable.")
    /** Fresh read of one host in this pool, used before a host-targeted send. */
    suspend fun host(authority: String, uuid: String): HostChoice = throw UnsupportedOperationException("Host reads are unavailable.")
    // --- end A2 operations ---
    // --- B2 storage/host ---
    /** Fresh read of one target in this pool. UUID_INVALID is returned as [MissingTarget]; other failures throw. */
    suspend fun target(authority: String, kind: TargetKind, uuid: String): TargetState = throw UnsupportedOperationException("Target reads are unavailable.")
    /** Fresh read of one ISO before it is inserted. */
    suspend fun iso(authority: String, uuid: String): IsoChoice = throw UnsupportedOperationException("ISO reads are unavailable.")
    /** Sends one single-step kind (with the documented arity fallback). Callers journal SUBMITTING first. */
    suspend fun submitTarget(authority: String, action: TargetAction, target: String, argument: String?): String =
        throw UnsupportedOperationException("Storage/host sends are unavailable.")
    /** True only when the server answers UUID_INVALID for the step's object. */
    suspend fun absent(authority: String, step: OperationStep): Boolean = throw UnsupportedOperationException("Target reads are unavailable.")
    /** Sends one delete sub-step after re-checking at send time that it is a snapshot object ([NotSent] otherwise). */
    suspend fun submitStep(authority: String, step: OperationStep): String = throw UnsupportedOperationException("Storage/host sends are unavailable.")
    // --- end B2 storage/host ---
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
            // --- A2 operations --- A chosen host is read again before anything is journaled or sent.
            val target = confirmation.targetHost?.let { chosen ->
                host(vm.authority, chosen.uuid).also { fresh ->
                    require(fresh.uuid == chosen.uuid) { "Host identity changed. Choose the host again." }
                    PowerRules.targetProblem(vm, confirmation.action, fresh)?.let { throw IllegalArgumentException(it) }
                }
            }
            var op = PowerOperation(UUID.randomUUID().toString(), profile.id, profile.origin(), vm.authority, vm.uuid,
                vm.name, confirmation.action, vm.bootTime, PowerPhase.PREPARED, clock(), clock(),
                targetHostUuid = target?.uuid, targetHostName = target?.name)
            op = save(op, changed)
            // Write-ahead barrier: from here any lost result is uncertain, never retryable automatically.
            op = save(op.copy(phase = PowerPhase.SUBMITTING, message = "Sending once…"), changed)
            try {
                val reference = submitOperation(op.authority, op.vmUuid, op.action, op.targetHostUuid)
                op = op.copy(phase = PowerPhase.TRACKING, taskRef = reference, message = "Waiting for server task…")
                op = save(op, changed)
                val uuid = taskUuid(reference)
                op = op.copy(taskUuid = uuid)
                op = save(op, changed)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                // An explicit XAPI rejection before a task ticket is definitive. Anything else is uncertain.
                val rejected = error is RpcFailure && error.explicitRejection && op.taskRef == null
                return@powerConnection save(op.copy(phase = if (rejected) PowerPhase.FAILED else PowerPhase.UNKNOWN,
                    message = if (rejected) "Server rejected the request (${error.code})." else "Result unknown. Check status; do not repeat the action."), changed)
            }
            track(this, op, changed)
        }
    }
    suspend fun reconcile(profile: Profile, operation: PowerOperation, changed: suspend (PowerOperation) -> Unit = {}): PowerOperation = processMutex.withLock {
        val persisted = store.load().single { it.id == operation.id }
        if (!persisted.unresolved) return@withLock persisted
        // --- B2 storage/host --- Target records reconcile through their own object reads, never the VM path.
        if (persisted.targetAction != null && persisted.foreign == null) return@withLock reconcileTarget(profile, persisted, changed)
        // --- end B2 storage/host ---
        // --- A2 operations --- A record this version cannot interpret is never observed, verified or resent.
        require(persisted.foreign == null && persisted.action.requestable) {
            "This action was recorded by a different Meteor version. Review it manually; it will not be checked or resent automatically."
        }
        require(persisted.profileId == profile.id && persisted.origin == profile.origin()) { "Original connection required for recovery." }
        val ticket = retainedTicket?.takeIf { saved ->
            saved.id == persisted.id && saved.profileId == persisted.profileId && saved.origin == persisted.origin &&
                saved.authority == persisted.authority && saved.vmUuid == persisted.vmUuid && saved.action == persisted.action &&
                saved.createdAt == persisted.createdAt && saved.beforeBoot == persisted.beforeBoot &&
                saved.targetHostUuid == persisted.targetHostUuid &&
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
        require(latest.targetAction == null) { "Review this storage or host action from its own record." } // --- B2 storage/host ---
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
        require(latest.targetAction == null) { "Review this storage or host action from its own record." } // --- B2 storage/host ---
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
    // --- A2 operations ---
    private fun verifiedMessage(op: PowerOperation, current: VmDetails): String = when {
        op.action == PowerAction.REBOOT -> "Reboot verified: boot timestamp changed."
        op.action.requiresBootChange -> "${op.action.title} verified: boot timestamp changed."
        op.action.hostTarget != HostTarget.NONE -> "${op.action.title} verified: ${current.power} on ${op.targetHostName ?: current.host ?: "the chosen host"}."
        else -> "${op.action.title} verified: ${current.power}."
    }
    // --- end A2 operations ---
    private suspend fun track(connection: PowerConnection, initial: PowerOperation, changed: suspend (PowerOperation) -> Unit): PowerOperation {
        var op = initial
        try {
            repeat(attempts) {
                val task = connection.observeTask(requireNotNull(op.taskRef))
                when (task.status) {
                    "failure", "cancelled" -> return save(op.copy(phase = PowerPhase.FAILED,
                        message = "Server task ${task.status}${task.errorCode?.let { " ($it)" }.orEmpty()}. Refresh the VM before another action."), changed)
                    "success" -> {
                        if (op.phase != PowerPhase.VERIFYING) op = save(op.copy(phase = PowerPhase.VERIFYING, message = "Task finished. Verifying VM state…"), changed)
                        val current = connection.details(op.authority, op.vmUuid)
                        if (PowerRules.verified(op, current)) return save(op.copy(phase = PowerPhase.SUCCEEDED,
                            message = verifiedMessage(op, current)), changed)
                    }
                    "pending", "cancelling" -> if (op.phase != PowerPhase.TRACKING) op = save(op.copy(phase = PowerPhase.TRACKING, message = "Waiting for server task…"), changed)
                    else -> throw IllegalStateException("Unknown task state")
                }
                wait()
            }
        } catch (error: Exception) { if (error is CancellationException) throw error }
        return save(op.copy(phase = PowerPhase.UNKNOWN, message = "Still unverified. Check status to continue observing; the action will not be resent."), changed)
    }
    // --- B2 storage/host ---
    /**
     * Sends one confirmed storage/host operation once. Same lock, barrier, uncertainty and proof rules
     * as the VM kinds: the target is read again, the record is journaled PREPARED then SUBMITTING
     * before the socket, and only a fresh read after the task proves the outcome.
     */
    suspend fun executeTarget(profile: Profile, confirmation: TargetConfirmation, changed: suspend (PowerOperation) -> Unit = {}): PowerOperation = processMutex.withLock {
        require(store.load().none { it.unresolved }) { "Resolve the pending operation before sending another." }
        backend.powerConnection(profile) {
            val action = confirmation.action
            val current = target(confirmation.state.authority, action.targetKind, confirmation.state.uuid)
            TargetRules.confirm(profile, confirmation, current, clock())
            // The chosen ISO is read again before anything is journaled or sent.
            val chosenIso = confirmation.argument?.let { chosen ->
                iso(current.authority, chosen.uuid).also { fresh -> require(fresh.uuid == chosen.uuid) { "ISO identity changed. Choose the ISO again." } }
            }
            val subject = when (current) { is HostState -> current.name; is PbdState -> current.srName; else -> current.vmName.orEmpty() }
            var op = PowerOperation(UUID.randomUUID().toString(), profile.id, profile.origin(), current.authority, current.vmUuid.orEmpty(),
                subject.ifBlank { current.uuid }, PowerAction.TARGET,
                (current as? HostState)?.bootMarker?.takeIf { action == TargetAction.HOST_REBOOT }, PowerPhase.PREPARED, clock(), clock(),
                targetAction = action, targetUuid = current.uuid, targetName = current.name.ifBlank { current.uuid },
                argumentUuid = chosenIso?.uuid,
                argumentName = chosenIso?.name ?: confirmation.snapshotName.takeIf { action == TargetAction.SNAPSHOT_TAKE },
                marker = TargetRules.marker(action, current),
                steps = if (action == TargetAction.SNAPSHOT_DELETE) TargetRules.deletePlan(current as SnapshotTarget) else null)
            op = save(op, changed)
            // Multi-step: each sub-step has its own write-ahead record and is never re-run blindly.
            if (action == TargetAction.SNAPSHOT_DELETE) return@powerConnection runSteps(this, op, changed)
            // Write-ahead barrier: from here any lost result is uncertain, never retryable automatically.
            op = save(op.copy(phase = PowerPhase.SUBMITTING, message = "Sending once…"), changed)
            try {
                val argument = if (action == TargetAction.CD_INSERT) op.argumentUuid else op.argumentName
                val reference = submitTarget(op.authority, action, requireNotNull(op.targetUuid), argument)
                op = save(op.copy(phase = PowerPhase.TRACKING, taskRef = reference, message = "Waiting for server task…"), changed)
                op = save(op.copy(taskUuid = taskUuid(reference)), changed)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                // An explicit XAPI rejection, or a send-time check that stopped before the write, is definitive.
                val rejected = (error is RpcFailure && error.explicitRejection || error is NotSent) && op.taskRef == null
                return@powerConnection save(op.copy(phase = if (rejected) PowerPhase.FAILED else PowerPhase.UNKNOWN, message = when {
                    error is NotSent && rejected -> "Not sent: ${error.message}"
                    rejected -> "Server rejected the request (${(error as RpcFailure).code})."
                    // A coordinator reboot or shutdown can drop the connection because it worked.
                    op.taskRef != null || TargetRules.wasMaster(op) && (action == TargetAction.HOST_REBOOT || action == TargetAction.HOST_SHUTDOWN) ->
                        TargetRules.unverifiedMessage(op)
                    else -> "Result unknown. Check status; do not repeat the action."
                }), changed)
            }
            trackTarget(this, op, changed)
        }
    }

    private suspend fun trackTarget(connection: PowerConnection, initial: PowerOperation, changed: suspend (PowerOperation) -> Unit): PowerOperation {
        var op = initial
        val action = requireNotNull(op.targetAction)
        try {
            repeat(attempts) {
                val task = connection.observeTask(requireNotNull(op.taskRef))
                when (task.status) {
                    "failure", "cancelled" -> return save(op.copy(phase = PowerPhase.FAILED,
                        message = "Server task ${task.status}${task.errorCode?.let { " ($it)" }.orEmpty()}. Refresh before another action."), changed)
                    "success" -> {
                        if (op.phase != PowerPhase.VERIFYING) op = save(op.copy(phase = PowerPhase.VERIFYING, message = "Task finished. Verifying server state…"), changed)
                        val proof = connection.target(op.authority, action.proofKind, TargetRules.proofUuid(op))
                        if (TargetRules.verified(op, proof)) return save(op.copy(phase = PowerPhase.SUCCEEDED, message = TargetRules.verifiedMessage(op, proof),
                            argumentUuid = TargetRules.newSnapshot(op, proof)?.uuid ?: op.argumentUuid), changed)
                    }
                    "pending", "cancelling" -> if (op.phase != PowerPhase.TRACKING) op = save(op.copy(phase = PowerPhase.TRACKING, message = "Waiting for server task…"), changed)
                    else -> throw IllegalStateException("Unknown task state")
                }
                wait()
            }
        } catch (error: Exception) { if (error is CancellationException) throw error }
        return save(op.copy(phase = PowerPhase.UNKNOWN, message = TargetRules.unverifiedMessage(op)), changed)
    }

    /** Runs the recorded sub-steps in order. Each is journaled SUBMITTING before it is sent; any doubt stops the run. */
    private suspend fun runSteps(connection: PowerConnection, initial: PowerOperation, changed: suspend (PowerOperation) -> Unit): PowerOperation {
        var op = initial
        for (index in op.steps.orEmpty().indices) {
            val step = op.steps.orEmpty()[index]
            if (step.state != StepState.PENDING) continue
            // Read only: an object that is already gone is recorded and skipped; nothing is sent for it.
            val gone = try { connection.absent(op.authority, step) } catch (error: Exception) {
                if (error is CancellationException) throw error
                return finishSteps(op, definite = false, changed, note = "Could not read “${step.name}” before sending.")
            }
            if (gone) { op = save(op.withStep(index) { it.copy(state = StepState.ABSENT) }.copy(message = "“${step.name}” was already absent."), changed); continue }
            op = save(op.withStep(index) { it.copy(state = StepState.SUBMITTING) }.copy(phase = PowerPhase.SUBMITTING, taskRef = null, taskUuid = null,
                message = "Sending once: ${step.kind.title.lowercase()} “${step.name}”…"), changed)
            try {
                val reference = connection.submitStep(op.authority, step)
                op = save(op.withStep(index) { it.copy(state = StepState.TRACKING, taskRef = reference) }
                    .copy(phase = PowerPhase.TRACKING, taskRef = reference, message = "Waiting for server task…"), changed)
                val uuid = connection.taskUuid(reference)
                op = save(op.withStep(index) { it.copy(taskUuid = uuid) }.copy(taskUuid = uuid), changed)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                val definite = (error is RpcFailure && error.explicitRejection || error is NotSent) && op.steps.orEmpty()[index].taskRef == null
                op = op.withStep(index) { it.copy(state = if (definite) StepState.FAILED else StepState.UNKNOWN,
                    code = (error as? RpcFailure)?.code?.takeIf { definite }) }
                return finishSteps(op, definite, changed, note = (error as? NotSent)?.message?.let { "Not sent: $it" })
            }
            op = observeStep(connection, op, index, changed)
            val state = op.steps.orEmpty()[index].state
            if (state != StepState.DONE) return finishSteps(op, definite = state == StepState.FAILED, changed)
        }
        return verifySteps(connection, op, changed)
    }

    /** Observes one sent sub-step: done only after task success and a read showing its object gone. */
    private suspend fun observeStep(connection: PowerConnection, initial: PowerOperation, index: Int, changed: suspend (PowerOperation) -> Unit): PowerOperation {
        var op = initial
        try {
            repeat(attempts) {
                val step = op.steps.orEmpty()[index]
                val task = connection.observeTask(requireNotNull(step.taskRef))
                when (task.status) {
                    "failure", "cancelled" -> return op.withStep(index) { it.copy(state = StepState.FAILED, code = task.errorCode ?: task.status.uppercase()) }
                    "success" -> {
                        if (op.phase != PowerPhase.VERIFYING) op = save(op.copy(phase = PowerPhase.VERIFYING, message = "Task finished. Verifying “${step.name}” is gone…"), changed)
                        if (connection.absent(op.authority, step)) return save(op.withStep(index) { it.copy(state = StepState.DONE) }
                            .copy(message = "Destroyed “${step.name}”."), changed)
                    }
                    "pending", "cancelling" -> if (op.phase != PowerPhase.TRACKING) op = save(op.copy(phase = PowerPhase.TRACKING, message = "Waiting for server task…"), changed)
                    else -> throw IllegalStateException("Unknown task state")
                }
                wait()
            }
        } catch (error: Exception) { if (error is CancellationException) throw error }
        return op.withStep(index) { it.copy(state = StepState.UNKNOWN) }
    }

    private suspend fun verifySteps(connection: PowerConnection, op: PowerOperation, changed: suspend (PowerOperation) -> Unit): PowerOperation {
        try {
            val proof = connection.target(op.authority, TargetKind.SNAPSHOT, requireNotNull(op.targetUuid))
            if (TargetRules.verified(op, proof)) return save(op.copy(phase = PowerPhase.SUCCEEDED, message = TargetRules.verifiedMessage(op, proof)), changed)
        } catch (error: Exception) { if (error is CancellationException) throw error }
        return save(op.copy(phase = PowerPhase.UNKNOWN, message = "Every sub-step finished, but the snapshot is not proven gone. " +
            "${TargetRules.stepSummary(op.steps.orEmpty())} Check status later; nothing will be resent."), changed)
    }

    /** Ends a stopped delete. Uncertain or interrupted runs stay UNKNOWN; the remaining steps are never sent automatically. */
    private suspend fun finishSteps(op: PowerOperation, definite: Boolean, changed: suspend (PowerOperation) -> Unit, note: String? = null): PowerOperation {
        val steps = op.steps.orEmpty()
        val prefix = note?.let { "$it " }.orEmpty()
        val summary = TargetRules.stepSummary(steps)
        val sent = steps.any { it.state != StepState.PENDING && it.state != StepState.ABSENT }
        val uncertain = steps.any { it.state == StepState.SUBMITTING || it.state == StepState.TRACKING || it.state == StepState.UNKNOWN }
        return when {
            !sent -> save(op.copy(phase = PowerPhase.FAILED, message = "${prefix}No request was sent. $summary"), changed)
            !uncertain && definite -> save(op.copy(phase = PowerPhase.FAILED,
                message = "${prefix}Snapshot delete stopped. $summary Refresh the snapshot before another action."), changed)
            else -> save(op.copy(phase = PowerPhase.UNKNOWN, message = "${prefix}Snapshot delete interrupted. $summary " +
                "Meteor never sends the remaining steps automatically. Check status, then review this record."), changed)
        }
    }

    private suspend fun reconcileTarget(profile: Profile, persisted: PowerOperation, changed: suspend (PowerOperation) -> Unit): PowerOperation {
        require(persisted.profileId == profile.id && persisted.origin == profile.origin()) { "Original connection required for recovery." }
        val ticket = retainedTicket?.takeIf { saved ->
            saved.id == persisted.id && saved.profileId == persisted.profileId && saved.origin == persisted.origin &&
                saved.authority == persisted.authority && saved.targetAction == persisted.targetAction && saved.targetUuid == persisted.targetUuid &&
                saved.createdAt == persisted.createdAt && saved.beforeBoot == persisted.beforeBoot &&
                (persisted.taskRef == null || persisted.taskRef == saved.taskRef) &&
                (persisted.taskUuid == null || persisted.taskUuid == saved.taskUuid)
        }
        var latest = persisted
        if (ticket != null) {
            val steps = TargetRules.mergeTicketSteps(persisted.steps, ticket.steps)
            if (persisted.taskRef == null && ticket.taskRef != null || persisted.taskUuid == null && ticket.taskUuid != null || steps != persisted.steps)
                latest = save(persisted.copy(taskRef = persisted.taskRef ?: ticket.taskRef, taskUuid = persisted.taskUuid ?: ticket.taskUuid, steps = steps), changed)
        }
        if (latest.phase == PowerPhase.PREPARED) return save(latest.copy(phase = PowerPhase.FAILED, message = "App stopped before submission. No request was sent."), changed)
        if (latest.steps != null) return reconcileSteps(profile, latest, changed)
        if (latest.taskRef == null && latest.taskUuid == null) return save(latest.copy(phase = PowerPhase.UNKNOWN,
            message = "No task ticket was saved. Check the server and its task history. Meteor will not resend this action."), changed)
        return try {
            backend.powerConnection(profile) {
                // Reads the pool and object identity before looking at a task from the saved origin.
                target(latest.authority, requireNotNull(latest.targetAction).proofKind, TargetRules.proofUuid(latest))
                val ref = latest.taskUuid?.let { taskReference(it) } ?: requireNotNull(latest.taskRef)
                val identity = latest.taskUuid ?: taskUuid(ref)
                trackTarget(this, save(latest.copy(taskRef = ref, taskUuid = identity), changed), changed)
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            save(latest.copy(phase = PowerPhase.UNKNOWN, message = if (latest.targetAction == TargetAction.HOST_REBOOT || latest.targetAction == TargetAction.HOST_SHUTDOWN)
                TargetRules.unverifiedMessage(latest) else "Could not reconcile securely. Keep this operation pending and check again."), changed)
        }
    }

    /** Observes the one uncertain sub-step, if any. Steps that were never sent stay unsent. */
    private suspend fun reconcileSteps(profile: Profile, latest: PowerOperation, changed: suspend (PowerOperation) -> Unit): PowerOperation {
        val steps = latest.steps.orEmpty()
        val index = steps.indexOfFirst { it.state == StepState.SUBMITTING || it.state == StepState.TRACKING || it.state == StepState.UNKNOWN }
        if (index < 0) {
            if (steps.isNotEmpty() && steps.all { it.state == StepState.DONE || it.state == StepState.ABSENT }) return try {
                backend.powerConnection(profile) { verifySteps(this, latest, changed) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                finishSteps(latest, definite = false, changed, note = "Could not reconcile securely.")
            }
            return finishSteps(latest, definite = false, changed)
        }
        val step = steps[index]
        if (step.taskRef == null && step.taskUuid == null)
            return finishSteps(latest.withStep(index) { it.copy(state = StepState.UNKNOWN) }, false, changed, note = "No task ticket was saved for “${step.name}”.")
        return try {
            backend.powerConnection(profile) {
                target(latest.authority, TargetKind.SNAPSHOT, requireNotNull(latest.targetUuid)) // pool identity before any task read
                val ref = step.taskUuid?.let { taskReference(it) } ?: requireNotNull(step.taskRef)
                val identity = step.taskUuid ?: taskUuid(ref)
                var op = save(latest.withStep(index) { it.copy(state = StepState.TRACKING, taskRef = ref, taskUuid = identity) }
                    .copy(phase = PowerPhase.TRACKING, taskRef = ref, taskUuid = identity), changed)
                op = observeStep(this, op, index, changed)
                val after = op.steps.orEmpty()
                when {
                    after[index].state != StepState.DONE -> finishSteps(op, after[index].state == StepState.FAILED, changed)
                    after.all { it.state == StepState.DONE || it.state == StepState.ABSENT } -> verifySteps(this, op, changed)
                    else -> finishSteps(op, definite = false, changed)
                }
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            finishSteps(latest.withStep(index) { it.copy(state = StepState.UNKNOWN) }, false, changed, note = "Could not reconcile securely.")
        }
    }

    /** Reads the original pool and object for a manual review. Any connection to the same pool may review. */
    suspend fun reviewTarget(profile: Profile, operation: PowerOperation): TargetReview = processMutex.withLock {
        val latest = store.load().single { it.id == operation.id }
        require(latest.unresolved) { "This operation is already closed." }
        val action = requireNotNull(latest.targetAction?.takeIf { latest.foreign == null }) { "This record is not a storage or host action." }
        backend.powerConnection(profile) {
            val current = target(latest.authority, action.proofKind, TargetRules.proofUuid(latest))
            requireTargetReviewIdentity(latest, current)
            TargetReview(latest, profile.id, profile.origin(), current)
        }
    }
    /** Closes a reviewed record. The UNKNOWN outcome is kept; a separate resolution is added; nothing is resent. */
    suspend fun resolveTargetAfterReview(profile: Profile, review: TargetReview, serverHistoryChecked: Boolean,
        changed: suspend (PowerOperation) -> Unit = {}): PowerOperation = processMutex.withLock {
        require(serverHistoryChecked) { "Review the server task history first." }
        val latest = store.load().single { it.id == review.operation.id }
        require(latest == review.operation && latest.unresolved) { "Operation changed. Review it again." }
        require(profile.id == review.profileId && profile.origin() == review.origin) { "Connection changed. Review it again." }
        require(clock() - review.state.observedAt in 0..30_000) { "Review expired. Read the object again." }
        val action = requireNotNull(latest.targetAction)
        backend.powerConnection(profile) {
            val current = target(latest.authority, action.proofKind, TargetRules.proofUuid(latest))
            requireTargetReviewIdentity(latest, current)
            require(current.fence == review.state.fence) { "The ${current.kind.label} changed. Review it again." }
            val steps = latest.steps?.let { " ${TargetRules.stepSummary(it)}" }.orEmpty()
            save(latest.copy(phase = PowerPhase.UNKNOWN, manualResolution = ManualResolution(clock(), profile.origin(), current.summary, (current as? HostState)?.bootMarker),
                message = "Closed after manual review: ${current.summary}.$steps Original outcome remains unverified. No action was resent."), changed)
        }
    }
    private fun requireTargetReviewIdentity(operation: PowerOperation, current: TargetState) {
        require(current.authority == operation.authority && current.kind == operation.targetAction?.proofKind &&
            current.uuid == TargetRules.proofUuid(operation)) { "Original pool and object required." }
        require(!current.busy) { "The ${current.kind.label} still has an active operation. Check status later." }
    }
    // --- end B2 storage/host ---
}
