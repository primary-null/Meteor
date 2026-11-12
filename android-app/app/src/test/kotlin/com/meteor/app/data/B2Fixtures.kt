package com.meteor.app.data

/** B2 storage/host: target states for JVM tests. Defaults are eligible for the obvious action. */
internal object B2Fixtures {
    val profile = Profile("p", "Lab", "https://test.invalid/", "tester", "secret", "a".repeat(64))
    const val NOW = 1000L

    fun vm(power: String = "Running", allowed: Set<String> = setOf("snapshot", "clean_shutdown"), busy: Boolean = false,
        snapshots: List<SnapshotInfo> = listOf(snapInfo()), parent: String? = "snap-1", disks: List<String> = listOf("vdi-live"),
        drives: List<CdDrive> = listOf(drive()), name: String = "Guest", observedAt: Long = NOW) =
        VmTarget("pool", "vm", name, power, allowed, busy, observedAt, snapshots, false, parent, disks, drives)

    fun snapInfo(uuid: String = "snap-1", name: String = "Before upgrade") =
        SnapshotInfo(uuid, name, "20260101T10:00:00Z", false, setOf("revert", "destroy"))

    fun ownDisk(vdi: String = "vdi-snap", vbd: String = "vbd-1") = SnapshotDisk(vbd, "Disk", false, vdi, "disk snapshot", true, true, false)

    fun snapshot(name: String = "Before upgrade", vmName: String = "Guest", withMemory: Boolean = false,
        allowed: Set<String> = setOf("revert", "destroy"), busy: Boolean = false, liveBusy: Boolean = false,
        disks: List<SnapshotDisk> = listOf(ownDisk()), livePower: String = "Running", liveParent: String? = "snap-0",
        liveDisks: List<String> = listOf("vdi-live"), observedAt: Long = NOW) =
        SnapshotTarget("pool", "snap-1", name, "vm", vmName, "20260101T10:00:00Z", withMemory, if (withMemory) "Suspended" else "Halted",
            allowed, busy, observedAt, disks, livePower, liveParent, liveDisks, liveBusy)

    fun drive(empty: Boolean = true, vdi: String? = if (empty) null else "iso-1", allowed: Set<String> = if (empty) setOf("insert") else setOf("eject"),
        busy: Boolean = false, observedAt: Long = NOW) =
        CdDrive("pool", "vbd-cd", "3", "vm", "Guest", "Running", empty, vdi, vdi?.let { "debian.iso" }, allowed, busy, observedAt)

    fun host(enabled: Boolean = true, live: Boolean = true, master: Boolean = false, boot: String? = "1700000000.5",
        resident: List<String> = emptyList(), allowed: Set<String> = setOf("evacuate", "reboot", "shutdown"), busy: Boolean = false,
        blockers: List<EvacuationBlocker>? = emptyList(), name: String = "Host B", observedAt: Long = NOW) =
        HostState("pool", "host-b", name, enabled, live, master, boot, resident, allowed, busy, observedAt, blockers)

    fun pbd(attached: Boolean = false, live: Boolean = true, busy: Boolean = false, observedAt: Long = NOW) =
        PbdState("pool", "pbd-b", "sr-1", "Shared NFS", "host-b", "Host B", live, attached, busy, observedAt)

    val iso = IsoChoice("iso-1", "debian.iso", "ISO library")

    fun confirm(action: TargetAction, state: TargetState, typed: String? = null, name: String? = "Snap", argument: IsoChoice? = null) =
        TargetConfirmation(profile.id, profile.origin(), action, state,
            argument = argument ?: iso.takeIf { action == TargetAction.CD_INSERT },
            snapshotName = name.takeIf { action == TargetAction.SNAPSHOT_TAKE },
            typedName = typed ?: TargetRules.confirmationPhrase(action, state),
            plan = if (action == TargetAction.SNAPSHOT_DELETE && state is SnapshotTarget) TargetRules.deletePlan(state) else emptyList())

    /** A state each kind may start from, so every kind can be exercised generically. */
    fun eligibleState(action: TargetAction): TargetState = when (action) {
        TargetAction.SNAPSHOT_TAKE -> vm()
        TargetAction.SNAPSHOT_REVERT, TargetAction.SNAPSHOT_DELETE -> snapshot()
        TargetAction.CD_INSERT -> drive(empty = true)
        TargetAction.CD_EJECT -> drive(empty = false)
        TargetAction.HOST_DISABLE -> host()
        TargetAction.HOST_ENABLE -> host(enabled = false)
        TargetAction.HOST_EVACUATE -> host(enabled = false, resident = listOf("Guest"))
        TargetAction.HOST_REBOOT, TargetAction.HOST_SHUTDOWN -> host(enabled = false)
        TargetAction.PBD_PLUG -> pbd()
    }
}
