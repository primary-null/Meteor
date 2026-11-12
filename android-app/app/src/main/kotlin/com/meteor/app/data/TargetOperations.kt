package com.meteor.app.data

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/*
 * B2 storage/host operations on the A2 framework.
 *
 * A2 kinds act on one VM. B2 kinds act on a VM, a VM snapshot, a CD drive (VBD), a host or a
 * storage connection (PBD). They share A2's journal record (PowerOperation), process mutex,
 * "one unresolved operation blocks every write" rule, write-ahead barrier, task tracking,
 * uncertainty handling and manual review. Their record carries:
 *
 * - `action`        the TargetAction name (never a PowerAction name, so an A2-era reader treats the
 *                   record as foreign: locked, never observed or resent, written back verbatim);
 * - `targetKind`    which object `target` names;
 * - `target`        that object's UUID; `targetName` its display name;
 * - `vm`            the owning VM for VM-scoped targets (VM, snapshot, CD drive), empty for host/PBD;
 * - `name`          the subject shown first (VM name, host name or SR name);
 * - `argument`/`argumentName`  the chosen ISO VDI, or the requested snapshot name;
 * - `marker`        preflight facts used as proof (string map, see [TargetRules.marker]);
 * - `steps`         the sub-steps of a multi-step kind (snapshot delete), each journaled before it is sent.
 *
 * In memory the record's [PowerOperation.action] is [PowerAction.TARGET], which is not requestable,
 * so every A2 VM path (send, verify, VM review) refuses it. Identity is the profile origin, the pool
 * UUID and the object UUID, exactly as for A2.
 */

/** The object an operation acts on. [xapiClass] is the XAPI class whose `get_by_uuid` resolves it. */
enum class TargetKind(val label: String, val xapiClass: String) {
    VM("VM", "VM"), SNAPSHOT("snapshot", "VM"), VBD("CD drive", "VBD"), HOST("host", "host"), PBD("storage connection", "PBD")
}

/**
 * Every journaled B2 kind. The enum name is the journal key; never rename an entry, and never reuse
 * a [PowerAction] name. [allowedKey] is the target's allowed_operations key, or null when XAPI does
 * not publish one for this call. [proofKind] is the object read to verify or review the outcome.
 */
enum class TargetAction(
    val title: String, val targetKind: TargetKind, val xapiMethod: String, val allowedKey: String?,
    val confirmation: ConfirmationStrength, val minimumRole: XapiRole, val proofKind: TargetKind, val description: String,
) {
    SNAPSHOT_TAKE("Take snapshot", TargetKind.VM, "Async.VM.snapshot", "snapshot", ConfirmationStrength.SINGLE,
        XapiRole.VM_POWER_ADMIN, TargetKind.VM,
        "Create a disk snapshot of this VM. It uses storage space until it is deleted. Meteor verifies that the named snapshot exists."),
    SNAPSHOT_REVERT("Revert to snapshot", TargetKind.SNAPSHOT, "Async.VM.revert", "revert", ConfirmationStrength.TYPED_NAME,
        XapiRole.VM_POWER_ADMIN, TargetKind.VM,
        "Replace the VM's current disks with this snapshot. The current disk state is lost, and a running VM is stopped. This cannot be undone unless you took another snapshot first."),
    SNAPSHOT_DELETE("Delete snapshot", TargetKind.SNAPSHOT, "", "destroy", ConfirmationStrength.TYPED_NAME,
        XapiRole.VM_POWER_ADMIN, TargetKind.SNAPSHOT,
        "Destroy this snapshot's own disk snapshots and then the snapshot record. The VM and its current disks are not touched."),
    CD_INSERT("Insert ISO", TargetKind.VBD, "Async.VBD.insert", "insert", ConfirmationStrength.SINGLE,
        XapiRole.VM_OPERATOR, TargetKind.VBD, "Put the chosen ISO into this virtual CD drive."),
    CD_EJECT("Eject ISO", TargetKind.VBD, "Async.VBD.eject", "eject", ConfirmationStrength.SINGLE,
        XapiRole.VM_OPERATOR, TargetKind.VBD, "Remove the ISO from this virtual CD drive. The guest may still be using it."),
    HOST_DISABLE("Disable host", TargetKind.HOST, "Async.host.disable", null, ConfirmationStrength.SINGLE,
        XapiRole.POOL_OPERATOR, TargetKind.HOST, "Stop new VMs from starting on this host. Running VMs are not moved."),
    HOST_EVACUATE("Evacuate host", TargetKind.HOST, "Async.host.evacuate", "evacuate", ConfirmationStrength.TYPED_NAME,
        XapiRole.POOL_OPERATOR, TargetKind.HOST,
        "Live-migrate every VM off this host to other hosts in the pool. Meteor verifies that no VM remains on it."),
    HOST_ENABLE("Enable host", TargetKind.HOST, "Async.host.enable", null, ConfirmationStrength.SINGLE,
        XapiRole.POOL_OPERATOR, TargetKind.HOST, "Allow VMs to start on this host again."),
    HOST_REBOOT("Reboot host", TargetKind.HOST, "Async.host.reboot", "reboot", ConfirmationStrength.TYPED_NAME,
        XapiRole.POOL_OPERATOR, TargetKind.HOST,
        "Restart this empty, disabled host. Meteor verifies a changed host boot time once the host is live again."),
    HOST_SHUTDOWN("Shut down host", TargetKind.HOST, "Async.host.shutdown", "shutdown", ConfirmationStrength.TYPED_NAME,
        XapiRole.POOL_OPERATOR, TargetKind.HOST,
        "Power off this empty, disabled host. It must be powered on again physically or out of band."),
    PBD_PLUG("Plug storage connection", TargetKind.PBD, "Async.PBD.plug", null, ConfirmationStrength.SINGLE,
        XapiRole.POOL_OPERATOR, TargetKind.PBD, "Ask this host to attach the storage repository again (repair)."),
    ;

    /** Arguments after the session reference, in the form older XAPI versions accept. */
    fun xapiArguments(target: String, argument: String?): List<Any?> = when (this) {
        SNAPSHOT_TAKE -> listOf(target, requireNotNull(argument) { "A snapshot name is required." })
        CD_INSERT -> listOf(target, requireNotNull(argument) { "An ISO is required." })
        SNAPSHOT_DELETE -> throw IllegalArgumentException("Snapshot delete is sent as recorded sub-steps.")
        else -> listOf(target)
    }

    /**
     * The other documented arity, sent at most once and only after the server explicitly rejected
     * the first form with MESSAGE_PARAMETER_COUNT_MISMATCH. Newer XAPI adds `ignore_vdis` to
     * VM.snapshot, `auto_enable` to host.disable and `network`/`evacuate_batch_size` to
     * host.evacuate; the values below are the documented defaults. XAPI sends int64 as a string.
     */
    fun alternateArguments(target: String, argument: String?): List<Any?>? = when (this) {
        SNAPSHOT_TAKE -> listOf(target, requireNotNull(argument), emptyList<String>())
        HOST_DISABLE -> listOf(target, true)
        HOST_EVACUATE -> listOf(target, "OpaqueRef:NULL", "0")
        else -> null
    }
}

enum class StepKind(val title: String, val xapiMethod: String, val xapiClass: String) {
    VDI_DESTROY("Destroy snapshot disk", "Async.VDI.destroy", "VDI"),
    VM_DESTROY("Destroy snapshot record", "Async.VM.destroy", "VM"),
}
/** ABSENT: the object was already gone when its turn came, so nothing was sent for it. */
enum class StepState { PENDING, SUBMITTING, TRACKING, DONE, ABSENT, FAILED, UNKNOWN }

/** One journaled sub-step of a multi-step kind. [code] is an explicit XAPI error, when one was returned. */
data class OperationStep(val kind: StepKind, val uuid: String, val name: String, val state: StepState = StepState.PENDING,
    val taskRef: String? = null, val taskUuid: String? = null, val code: String? = null)

/** A copy of this record with one sub-step changed. */
fun PowerOperation.withStep(index: Int, change: (OperationStep) -> OperationStep): PowerOperation =
    copy(steps = requireNotNull(steps).toMutableList().also { it[index] = change(it[index]) })

/** A precondition checked at send time failed before any write call was made. Definitive: nothing was sent. */
class NotSent(reason: String) : Exception(reason)

// Fresh reads of B2 targets. Every state is bound to its pool ([authority]) and object UUID.

sealed interface TargetState {
    val authority: String
    val kind: TargetKind
    val uuid: String
    val name: String
    val observedAt: Long
    /** The object has a current operation, so new requests wait. */
    val busy: Boolean
    /** The owning VM of a VM-scoped target; journaled as the record's `vm`. */
    val vmUuid: String? get() = null
    val vmName: String? get() = null
    /** Facts that must be unchanged between confirmation and send, and between review and closure. */
    val fence: List<Any?>
    /** Short description of the current state, shown in review and kept with a manual closure. */
    val summary: String
}

data class SnapshotInfo(val uuid: String, val name: String, val time: String?, val withMemory: Boolean,
    val allowed: Set<String> = emptySet(), val busy: Boolean = false)

/** A VM read for snapshot and CD work. [diskVdis] are the UUIDs of its non-empty disk VBDs' VDIs. */
data class VmTarget(
    override val authority: String, override val uuid: String, override val name: String, val power: String,
    val allowed: Set<String>, override val busy: Boolean, override val observedAt: Long,
    val snapshots: List<SnapshotInfo> = emptyList(), val snapshotsTruncated: Boolean = false,
    val parentUuid: String? = null, val diskVdis: List<String> = emptyList(), val cdDrives: List<CdDrive> = emptyList(),
) : TargetState {
    override val kind get() = TargetKind.VM
    override val vmUuid get() = uuid
    override val vmName get() = name
    override val fence get() = listOf(name, power, busy, allowed.sorted(), snapshots.map { it.uuid }.sorted(), parentUuid, diskVdis.sorted())
    override val summary get() = "$power · ${snapshots.size} snapshot${if (snapshots.size == 1) "" else "s"}"
}

/**
 * One disk slot of a snapshot as read from the server. [onlyThisSnapshot] is true when every VBD
 * of the VDI belongs to this snapshot. [inLiveVm] is true when the live VM references the VDI.
 */
data class SnapshotDisk(val vbdUuid: String, val type: String, val empty: Boolean, val vdiUuid: String?, val vdiName: String,
    val vdiIsSnapshot: Boolean, val onlyThisSnapshot: Boolean, val inLiveVm: Boolean)

/** A VM snapshot and, read in the same pass, the live VM it belongs to. */
data class SnapshotTarget(
    override val authority: String, override val uuid: String, override val name: String,
    override val vmUuid: String, override val vmName: String, val time: String?, val withMemory: Boolean, val power: String,
    val allowed: Set<String>, override val busy: Boolean, override val observedAt: Long, val disks: List<SnapshotDisk>,
    val livePower: String, val liveParentUuid: String?, val liveDisks: List<String>, val liveBusy: Boolean,
) : TargetState {
    override val kind get() = TargetKind.SNAPSHOT
    override val fence get() = listOf(name, vmUuid, vmName, power, busy, allowed.sorted(), disks, livePower, liveParentUuid, liveDisks.sorted(), liveBusy)
    override val summary get() = "Snapshot of $vmName · VM $livePower"
}

data class CdDrive(
    override val authority: String, override val uuid: String, val device: String,
    override val vmUuid: String, override val vmName: String, val vmPower: String,
    val empty: Boolean, val vdiUuid: String?, val vdiName: String?, val allowed: Set<String>,
    override val busy: Boolean, override val observedAt: Long,
) : TargetState {
    override val kind get() = TargetKind.VBD
    override val name get() = "CD drive ${device.ifBlank { "?" }}"
    override val fence get() = listOf(vmUuid, empty, vdiUuid, busy, allowed.sorted())
    override val summary get() = if (empty) "$name empty" else "$name holds ${vdiName ?: "an ISO"}"
}

data class EvacuationBlocker(val vmName: String, val code: String)

/** A host as read for maintenance. [residentVms] excludes the control domain. [bootMarker] is other_config:boot_time. */
data class HostState(
    override val authority: String, override val uuid: String, override val name: String, val enabled: Boolean, val live: Boolean,
    val master: Boolean, val bootMarker: String?, val residentVms: List<String>, val allowed: Set<String>,
    override val busy: Boolean, override val observedAt: Long,
    val blockers: List<EvacuationBlocker>? = null, val blockersProblem: String? = null,
) : TargetState {
    override val kind get() = TargetKind.HOST
    override val fence get() = listOf(name, enabled, live, master, bootMarker, residentVms.sorted(), allowed.sorted(), busy)
    override val summary get() = "${if (enabled) "Enabled" else "Disabled"} · ${if (live) "live" else "not live"} · " +
        "${residentVms.size} VM${if (residentVms.size == 1) "" else "s"}"
}

/** One host's connection to one SR. [busy] reflects the SR's current operations. */
data class PbdState(
    override val authority: String, override val uuid: String, val srUuid: String, val srName: String,
    val hostUuid: String, val hostName: String, val hostLive: Boolean, val attached: Boolean,
    override val busy: Boolean, override val observedAt: Long,
) : TargetState {
    override val kind get() = TargetKind.PBD
    override val name get() = "$srName on $hostName"
    override val fence get() = listOf(srUuid, hostUuid, hostLive, attached, busy)
    override val summary get() = if (attached) "Attached" else "Not attached"
}

/** The server answered UUID_INVALID: the object does not exist (any more). */
data class MissingTarget(override val authority: String, override val kind: TargetKind, override val uuid: String,
    override val observedAt: Long) : TargetState {
    override val name get() = ""
    override val busy get() = false
    override val fence get() = listOf("missing")
    override val summary get() = "No longer exists"
}

data class IsoChoice(val uuid: String, val name: String, val srName: String)
data class IsoCatalog(val isos: List<IsoChoice>, val truncated: Boolean)
data class IsoChoiceRequest(val drive: CdDrive, val catalog: IsoCatalog, val observedAt: Long)

/** A B2 confirmation, bound to the connection identity and the fresh target read it was built from. */
data class TargetConfirmation(
    val profileId: String, val origin: String, val action: TargetAction, val state: TargetState,
    val argument: IsoChoice? = null, val snapshotName: String? = null, val typedName: String? = null,
    /** For snapshot delete: the sub-steps shown to the user, recomputed and compared before sending. */
    val plan: List<OperationStep> = emptyList(),
)
data class TargetReview(val operation: PowerOperation, val profileId: String, val origin: String, val state: TargetState)

/** Sends the documented first arity and, only on an explicit parameter-count rejection, the other one once. */
object XapiArity {
    const val MISMATCH = "MESSAGE_PARAMETER_COUNT_MISMATCH"
    fun <T> send(primary: List<Any?>, alternate: List<Any?>?, call: (List<Any?>) -> T): T = try { call(primary) }
        catch (error: RpcFailure) {
            // XAPI checks the parameter count before dispatch, so no task or side effect exists yet.
            if (alternate == null || error.code != MISMATCH || !error.explicitRejection) throw error
            call(alternate)
        }
}

object TargetRules {
    const val MAX_SNAPSHOT_NAME = 200
    const val CONFIRMATION_MILLIS = 30_000L

    fun eligible(state: TargetState, action: TargetAction): Boolean = unavailableReason(state, action) == null

    /** One gate per kind: a control is enabled exactly when there is no reason to disable it. */
    fun unavailableReason(state: TargetState, action: TargetAction): String? {
        if (state is MissingTarget) return "This ${state.kind.label} no longer exists. Refresh."
        if (state.kind != action.targetKind) return "This action does not apply to a ${state.kind.label}."
        if (state.busy) return "Another operation is active on this ${state.kind.label}."
        val specific = when (state) {
            is VmTarget -> null
            is SnapshotTarget -> if (state.liveBusy) "Another operation is active on ${state.vmName}." else null
            is CdDrive -> when {
                action == TargetAction.CD_INSERT && !state.empty -> "Eject the current ISO first."
                action == TargetAction.CD_EJECT && state.empty -> "The drive is empty."
                else -> null
            }
            is HostState -> hostReason(state, action)
            is PbdState -> when {
                state.attached -> "Already attached."
                !state.hostLive -> "${state.hostName} is not reporting as live."
                else -> null
            }
            is MissingTarget -> null
        }
        if (specific != null) return specific
        val key = action.allowedKey ?: return null
        val allowed = when (state) {
            is VmTarget -> state.allowed; is SnapshotTarget -> state.allowed; is CdDrive -> state.allowed
            is HostState -> state.allowed; else -> emptySet()
        }
        return if (key in allowed) null else notAllowedHint(action)
    }

    private fun hostReason(host: HostState, action: TargetAction): String? = when (action) {
        TargetAction.HOST_DISABLE -> when {
            !host.enabled -> "The host is already disabled."
            !host.live -> "The host is not reporting as live."
            else -> null
        }
        TargetAction.HOST_ENABLE -> when {
            host.enabled -> "The host is already enabled."
            !host.live -> "The host is not reporting as live."
            else -> null
        }
        TargetAction.HOST_EVACUATE -> when {
            !host.live -> "The host is not reporting as live."
            host.enabled -> "Disable the host first so that no new VMs start on it."
            host.residentVms.isEmpty() -> "No VMs are running on this host."
            !host.blockers.isNullOrEmpty() -> "${host.blockers.size} VM${if (host.blockers.size == 1) " prevents" else "s prevent"} evacuation. Resolve the listed reasons first."
            else -> null
        }
        TargetAction.HOST_REBOOT, TargetAction.HOST_SHUTDOWN -> when {
            host.enabled -> "Disable the host first."
            host.residentVms.isNotEmpty() -> "Move or stop the VMs on this host first (${host.residentVms.size} remain${if (host.residentVms.size == 1) "s" else ""})."
            action == TargetAction.HOST_REBOOT && !validHostBoot(host.bootMarker) ->
                "The host does not report a boot time (other_config:boot_time), so Meteor could not verify a reboot."
            else -> null
        }
        else -> null
    }

    private fun notAllowedHint(action: TargetAction): String = when (action) {
        TargetAction.SNAPSHOT_TAKE -> "The server does not currently allow a snapshot of this VM. XAPI omits it while another operation runs, or for VMs with passthrough devices or storage that cannot snapshot."
        TargetAction.SNAPSHOT_REVERT -> "The server does not currently allow reverting to this snapshot."
        TargetAction.SNAPSHOT_DELETE -> "The server does not currently allow deleting this snapshot."
        else -> "The server does not currently allow this action."
    }

    /** Reasons for the snapshot list, before the snapshot itself is read again for a confirmation. */
    fun listReason(vm: VmTarget, snapshot: SnapshotInfo, action: TargetAction): String? = when {
        vm.busy || snapshot.busy -> "Another operation is active."
        action.allowedKey != null && action.allowedKey !in snapshot.allowed -> notAllowedHint(action)
        else -> null
    }

    /** The exact, case-sensitive text a typed confirmation needs, or null for a single confirmation. */
    fun confirmationPhrase(action: TargetAction, state: TargetState): String? {
        if (action.confirmation != ConfirmationStrength.TYPED_NAME) return null
        return when (state) {
            is SnapshotTarget -> if (action == TargetAction.SNAPSHOT_REVERT) state.vmName.ifBlank { state.vmUuid } else state.name.ifBlank { state.uuid }
            else -> state.name.ifBlank { state.uuid }
        }
    }
    fun phraseSubject(action: TargetAction): String = when (action) {
        TargetAction.SNAPSHOT_REVERT -> "VM name"
        TargetAction.SNAPSHOT_DELETE -> "snapshot name"
        else -> "host name"
    }
    fun typedNameMatches(confirmation: TargetConfirmation): Boolean =
        confirmationPhrase(confirmation.action, confirmation.state)?.let { it == confirmation.typedName } ?: true

    fun snapshotNameProblem(name: String?): String? = when {
        name.isNullOrBlank() -> "Enter a snapshot name."
        name.length > MAX_SNAPSHOT_NAME -> "Use at most $MAX_SNAPSHOT_NAME characters."
        name.any { it.isISOControl() } -> "Remove line breaks and control characters."
        else -> null
    }
    fun defaultSnapshotName(vmName: String, now: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        "${vmName.ifBlank { "VM" }} ${DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(zone).format(Instant.ofEpochMilli(now))}"
            .take(MAX_SNAPSHOT_NAME)

    /**
     * The only disks snapshot delete may destroy: non-empty Disk slots whose VDI is a snapshot VDI,
     * is referenced only by this snapshot and is not referenced by the live VM. CD drives, empty
     * slots, non-snapshot VDIs and shared VDIs are never selected. The snapshot record is last.
     */
    fun deletePlan(snapshot: SnapshotTarget): List<OperationStep> =
        snapshot.disks.filter { keptReason(it) == null }.distinctBy { it.vdiUuid }
            .map { OperationStep(StepKind.VDI_DESTROY, requireNotNull(it.vdiUuid), it.vdiName.ifBlank { "Disk snapshot" }) } +
            OperationStep(StepKind.VM_DESTROY, snapshot.uuid, snapshot.name.ifBlank { "Snapshot" })
    /** Why a disk slot is kept, or null when delete destroys its VDI. */
    fun keptReason(disk: SnapshotDisk): String? = when {
        disk.type != "Disk" -> "CD or other drive"
        disk.empty || disk.vdiUuid.isNullOrBlank() -> "empty slot"
        !disk.vdiIsSnapshot -> "not a snapshot disk"
        disk.inLiveVm -> "used by the VM"
        !disk.onlyThisSnapshot -> "also used elsewhere"
        else -> null
    }

    fun validHostBoot(value: String?): Boolean = value?.trim()?.toDoubleOrNull()?.let { it.isFinite() && it > 0 } == true
    /** A host reboot is proven only by a valid boot marker that differs from the preflight one. */
    fun hostBootChanged(before: String?, after: String?): Boolean {
        val old = before?.trim()?.toDoubleOrNull()?.takeIf { validHostBoot(before) } ?: return false
        val new = after?.trim()?.toDoubleOrNull()?.takeIf { validHostBoot(after) } ?: return false
        return new != old
    }

    /** Expected VM power state after a revert: a checkpoint restores a suspended VM, a disk snapshot a halted one. */
    fun revertedPower(snapshot: SnapshotTarget): String = if (snapshot.withMemory) "Suspended" else "Halted"

    /** Preflight facts the proof needs. Keys are stable journal keys. */
    fun marker(action: TargetAction, state: TargetState): Map<String, String>? = when {
        action == TargetAction.SNAPSHOT_TAKE && state is VmTarget -> mapOf("snapshots" to state.snapshots.map { it.uuid }.sorted().joinToString(","))
        action == TargetAction.SNAPSHOT_REVERT && state is SnapshotTarget -> mapOf("parent" to state.liveParentUuid.orEmpty(),
            "disks" to state.liveDisks.sorted().joinToString(","), "power" to state.livePower, "expect" to revertedPower(state))
        state is HostState -> mapOf("master" to state.master.toString())
        else -> null
    }
    private fun list(value: String?): Set<String> = value.orEmpty().split(',').filter { it.isNotBlank() }.toSet()
    fun wasMaster(operation: PowerOperation) = operation.marker?.get("master") == "true"

    /** The UUID [TargetAction.proofKind] is read with. */
    fun proofUuid(operation: PowerOperation): String = when (operation.targetAction?.proofKind) {
        TargetKind.VM -> operation.vmUuid
        else -> operation.targetUuid.orEmpty()
    }

    fun confirm(profile: Profile, confirmation: TargetConfirmation, current: TargetState, now: Long) {
        val original = confirmation.state
        require(profile.id == confirmation.profileId && profile.origin() == confirmation.origin) { "Connection changed. Review the action again." }
        require(original.kind == confirmation.action.targetKind) { "This action does not apply here." }
        require(current.authority == original.authority && current.kind == original.kind && current.uuid == original.uuid) { "Object identity changed." }
        require(now - original.observedAt in 0..CONFIRMATION_MILLIS) { "Confirmation expired. Review the action again." }
        unavailableReason(current, confirmation.action)?.let { throw IllegalArgumentException("This action is no longer available: $it") }
        require(current.fence == original.fence) { "The ${current.kind.label} changed. Review the action again." }
        require(typedNameMatches(confirmation) && confirmationPhrase(confirmation.action, current) == confirmationPhrase(confirmation.action, original)) {
            "The typed name does not match. Review the action again."
        }
        when (confirmation.action) {
            TargetAction.SNAPSHOT_TAKE -> snapshotNameProblem(confirmation.snapshotName)?.let { throw IllegalArgumentException(it) }
            TargetAction.CD_INSERT -> require(confirmation.argument != null && confirmation.argument.uuid.isNotBlank()) { "Choose an ISO." }
            TargetAction.SNAPSHOT_DELETE -> require(current is SnapshotTarget && confirmation.plan == deletePlan(current)) { "The snapshot changed. Review the action again." }
            else -> Unit
        }
        require(confirmation.action == TargetAction.CD_INSERT || confirmation.argument == null) { "This action does not take an ISO." }
    }

    /** Post-task proof for single-step kinds. Snapshot delete is proven step by step. */
    fun verified(operation: PowerOperation, state: TargetState): Boolean {
        val action = operation.targetAction ?: return false
        if (operation.foreign != null || state.authority != operation.authority) return false
        if (state.kind != action.proofKind || state.uuid != proofUuid(operation)) return false
        return when (action) {
            TargetAction.SNAPSHOT_TAKE -> newSnapshot(operation, state) != null
            TargetAction.SNAPSHOT_REVERT -> {
                val vm = state as? VmTarget ?: return false
                val marker = operation.marker ?: return false
                val disksChanged = list(marker["disks"]).isNotEmpty() && vm.diskVdis.toSet() != list(marker["disks"])
                val parentChanged = marker["parent"] != operation.targetUuid
                vm.parentUuid == operation.targetUuid && vm.power == marker["expect"] && (disksChanged || parentChanged)
            }
            TargetAction.SNAPSHOT_DELETE -> state is MissingTarget &&
                operation.steps.orEmpty().let { steps -> steps.isNotEmpty() && steps.all { it.state == StepState.DONE || it.state == StepState.ABSENT } }
            TargetAction.CD_INSERT -> state is CdDrive && !state.empty && state.vdiUuid != null && state.vdiUuid == operation.argumentUuid
            TargetAction.CD_EJECT -> state is CdDrive && state.empty
            TargetAction.HOST_DISABLE -> state is HostState && !state.enabled
            TargetAction.HOST_ENABLE -> state is HostState && state.enabled
            TargetAction.HOST_EVACUATE -> state is HostState && state.residentVms.isEmpty()
            TargetAction.HOST_REBOOT -> state is HostState && state.live && hostBootChanged(operation.beforeBoot, state.bootMarker)
            TargetAction.HOST_SHUTDOWN -> state is HostState && !state.live
            TargetAction.PBD_PLUG -> state is PbdState && state.attached
        }
    }
    /** The snapshot a take created: the requested name, and not present before the request. */
    fun newSnapshot(operation: PowerOperation, state: TargetState): SnapshotInfo? {
        if (operation.targetAction != TargetAction.SNAPSHOT_TAKE || state !is VmTarget || state.uuid != operation.vmUuid) return null
        val before = list(operation.marker?.get("snapshots") ?: return null)
        return state.snapshots.singleOrNull { it.name == operation.argumentName && it.uuid !in before }
    }

    fun verifiedMessage(operation: PowerOperation, state: TargetState): String = when (operation.targetAction) {
        TargetAction.SNAPSHOT_TAKE -> "Snapshot verified: “${operation.argumentName}” exists."
        TargetAction.SNAPSHOT_REVERT -> "Revert verified: ${operation.vmName} now derives from “${operation.targetName}” (${(state as? VmTarget)?.power})."
        TargetAction.SNAPSHOT_DELETE -> "Delete verified: the snapshot and ${operation.steps.orEmpty().count { it.kind == StepKind.VDI_DESTROY }} disk snapshot(s) are gone."
        TargetAction.CD_INSERT -> "Insert verified: ${operation.argumentName ?: "the ISO"} is in ${operation.targetName}."
        TargetAction.CD_EJECT -> "Eject verified: ${operation.targetName} is empty."
        TargetAction.HOST_DISABLE -> "Disable verified: the host is disabled."
        TargetAction.HOST_ENABLE -> "Enable verified: the host is enabled."
        TargetAction.HOST_EVACUATE -> "Evacuate verified: no VM remains on the host."
        TargetAction.HOST_REBOOT -> "Host reboot verified: the host is live with a changed boot time."
        TargetAction.HOST_SHUTDOWN -> "Shutdown verified: the host is no longer live."
        TargetAction.PBD_PLUG -> "Plug verified: the storage connection is attached."
        null -> ""
    }
    fun unverifiedMessage(operation: PowerOperation): String = when {
        operation.targetAction in setOf(TargetAction.HOST_REBOOT, TargetAction.HOST_SHUTDOWN) && wasMaster(operation) ->
            "The pool coordinator itself was ${if (operation.targetAction == TargetAction.HOST_REBOOT) "rebooted" else "shut down"}, so the connection dropped. " +
                "Meteor cannot verify this from the API. Check the host directly, then use Check status or Review once the coordinator answers again. The action will not be resent."
        operation.targetAction == TargetAction.HOST_REBOOT -> "Host reboot not verified yet: waiting for the host to be live with a changed boot time. Check status later; the action will not be resent."
        operation.targetAction == TargetAction.HOST_SHUTDOWN -> "Shutdown not verified yet: the host still reports as live. Check status later; the action will not be resent."
        else -> "Still unverified. Check status to continue observing; the action will not be resent."
    }

    /** What a snapshot delete did and did not destroy, for the card, history and review. */
    fun stepSummary(steps: List<OperationStep>): String {
        fun names(vararg states: StepState) = steps.filter { it.state in states }.joinToString { "“${it.name}”" }
        return listOf("Destroyed" to names(StepState.DONE), "Already absent" to names(StepState.ABSENT),
            "Uncertain" to names(StepState.SUBMITTING, StepState.TRACKING, StepState.UNKNOWN),
            "Failed" to steps.filter { it.state == StepState.FAILED }.joinToString { "“${it.name}”${it.code?.let { c -> " ($c)" }.orEmpty()}" },
            "Not sent" to names(StepState.PENDING))
            .filter { it.second.isNotBlank() }.joinToString(". ") { "${it.first}: ${it.second}" }.ifBlank { "No sub-steps." } + "."
    }

    /**
     * A ticket kept in memory after a failed journal write may carry a sub-step task the disk lacks.
     * Only a step the disk still records as SUBMITTING without a task adopts the remembered task.
     */
    fun mergeTicketSteps(persisted: List<OperationStep>?, ticket: List<OperationStep>?): List<OperationStep>? {
        if (persisted == null || ticket == null || persisted.size != ticket.size) return persisted
        return persisted.zip(ticket).map { (disk, memory) ->
            if (disk.kind == memory.kind && disk.uuid == memory.uuid && disk.state == StepState.SUBMITTING && disk.taskRef == null && memory.taskRef != null) memory
            else disk
        }
    }

    /** Advisory only, like [RoleRules.explanation]: the server decides every request. */
    fun roleExplanation(permissions: SessionPermissions?, action: TargetAction): String? =
        RoleRules.explanation(permissions, action.minimumRole)
}
