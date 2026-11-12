package com.meteor.app.data

import com.meteor.app.data.B2Fixtures.confirm
import com.meteor.app.data.B2Fixtures.drive
import com.meteor.app.data.B2Fixtures.eligibleState
import com.meteor.app.data.B2Fixtures.host
import com.meteor.app.data.B2Fixtures.ownDisk
import com.meteor.app.data.B2Fixtures.pbd
import com.meteor.app.data.B2Fixtures.profile
import com.meteor.app.data.B2Fixtures.snapshot
import com.meteor.app.data.B2Fixtures.vm
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.time.ZoneOffset

/** B2 storage/host: gates, disabled reasons, confirmation fences, delete planning, proofs and arity fallback. */
class TargetRulesTest {
    private val allStates: List<TargetState> = listOf(
        vm(), vm(allowed = emptySet()), vm(busy = true), vm(power = "Halted"),
        snapshot(), snapshot(allowed = emptySet()), snapshot(busy = true), snapshot(liveBusy = true), snapshot(withMemory = true),
        drive(empty = true), drive(empty = false), drive(empty = true, allowed = emptySet()), drive(busy = true),
        host(), host(enabled = false), host(live = false), host(enabled = false, resident = listOf("Guest")),
        host(enabled = false, resident = listOf("Guest"), blockers = listOf(EvacuationBlocker("Guest", "VM_REQUIRES_SR"))),
        host(enabled = false, boot = null), host(enabled = false, allowed = emptySet()), host(busy = true), host(master = true, enabled = false),
        pbd(), pbd(attached = true), pbd(live = false), pbd(busy = true),
        MissingTarget("pool", TargetKind.HOST, "host-b", 1000), MissingTarget("pool", TargetKind.SNAPSHOT, "snap-1", 1000))

    // Eligibility and disabled reasons

    @Test fun disabledReasonsMatchTheGateForEveryKindAndState() {
        for (state in allStates) for (action in TargetAction.entries)
            assertEquals("$action $state", TargetRules.eligible(state, action), TargetRules.unavailableReason(state, action) == null)
        for (action in TargetAction.entries) assertTrue("$action", TargetRules.eligible(eligibleState(action), action))
    }
    @Test fun aKindNeverAppliesToAnotherTargetKind() {
        for (action in TargetAction.entries) for (state in allStates.filter { it.kind != action.targetKind })
            assertFalse("$action ${state.kind}", TargetRules.eligible(state, action))
        assertTrue(TargetRules.unavailableReason(MissingTarget("pool", TargetKind.SNAPSHOT, "s", 1), TargetAction.SNAPSHOT_DELETE)!!.contains("no longer exists"))
    }
    @Test fun snapshotKindsFollowAllowedOperationsAndBusyObjects() {
        assertTrue(TargetRules.unavailableReason(vm(allowed = emptySet()), TargetAction.SNAPSHOT_TAKE)!!.contains("does not currently allow a snapshot"))
        assertTrue(TargetRules.unavailableReason(vm(busy = true), TargetAction.SNAPSHOT_TAKE)!!.contains("Another operation"))
        assertTrue(TargetRules.eligible(vm(power = "Halted"), TargetAction.SNAPSHOT_TAKE))
        assertFalse(TargetRules.eligible(snapshot(allowed = setOf("destroy")), TargetAction.SNAPSHOT_REVERT))
        assertTrue(TargetRules.eligible(snapshot(allowed = setOf("destroy")), TargetAction.SNAPSHOT_DELETE))
        assertTrue(TargetRules.unavailableReason(snapshot(liveBusy = true), TargetAction.SNAPSHOT_REVERT)!!.contains("Guest"))
        val listed = vm().snapshots.single()
        assertNull(TargetRules.listReason(vm(), listed, TargetAction.SNAPSHOT_REVERT))
        assertNotNull(TargetRules.listReason(vm(), listed.copy(allowed = setOf("destroy")), TargetAction.SNAPSHOT_REVERT))
        assertNotNull(TargetRules.listReason(vm(busy = true), listed, TargetAction.SNAPSHOT_DELETE))
    }
    @Test fun cdDriveInsertNeedsAnEmptyDriveAndEjectNeedsMedia() {
        assertEquals("Eject the current ISO first.", TargetRules.unavailableReason(drive(empty = false, allowed = setOf("insert", "eject")), TargetAction.CD_INSERT))
        assertEquals("The drive is empty.", TargetRules.unavailableReason(drive(empty = true, allowed = setOf("insert", "eject")), TargetAction.CD_EJECT))
        assertEquals("The server does not currently allow this action.", TargetRules.unavailableReason(drive(empty = true, allowed = emptySet()), TargetAction.CD_INSERT))
    }
    @Test fun hostMaintenanceIsSeparateStepsWithExplainedGates() {
        assertNull(TargetRules.unavailableReason(host(), TargetAction.HOST_DISABLE))
        assertEquals("The host is already disabled.", TargetRules.unavailableReason(host(enabled = false), TargetAction.HOST_DISABLE))
        assertEquals("The host is already enabled.", TargetRules.unavailableReason(host(), TargetAction.HOST_ENABLE))
        assertEquals("The host is not reporting as live.", TargetRules.unavailableReason(host(enabled = false, live = false), TargetAction.HOST_ENABLE))
        // Evacuate only after disable, only with VMs to move, and not while the server reports blockers.
        assertTrue(TargetRules.unavailableReason(host(resident = listOf("Guest")), TargetAction.HOST_EVACUATE)!!.contains("Disable the host first"))
        assertEquals("No VMs are running on this host.", TargetRules.unavailableReason(host(enabled = false), TargetAction.HOST_EVACUATE))
        assertTrue(TargetRules.unavailableReason(host(enabled = false, resident = listOf("Guest"),
            blockers = listOf(EvacuationBlocker("Guest", "VM_REQUIRES_SR"))), TargetAction.HOST_EVACUATE)!!.contains("prevents evacuation"))
        assertNull(TargetRules.unavailableReason(host(enabled = false, resident = listOf("Guest"), blockers = null), TargetAction.HOST_EVACUATE))
        assertNotNull(TargetRules.unavailableReason(host(enabled = false, resident = listOf("Guest"), allowed = setOf("reboot")), TargetAction.HOST_EVACUATE))
    }
    @Test fun hostRebootAndShutdownAreOfferedOnlyForADisabledEmptyHost() {
        for (action in listOf(TargetAction.HOST_REBOOT, TargetAction.HOST_SHUTDOWN)) {
            assertEquals("Disable the host first.", TargetRules.unavailableReason(host(), action))
            assertTrue(TargetRules.unavailableReason(host(enabled = false, resident = listOf("Guest")), action)!!.contains("1 remains"))
            assertTrue(TargetRules.eligible(host(enabled = false, master = true), action)) // allowed, with a strong warning in the UI
            assertFalse(TargetRules.eligible(host(enabled = false, allowed = setOf("evacuate")), action))
        }
        assertTrue(TargetRules.unavailableReason(host(enabled = false, boot = null), TargetAction.HOST_REBOOT)!!.contains("other_config:boot_time"))
        assertTrue(TargetRules.eligible(host(enabled = false, boot = null), TargetAction.HOST_SHUTDOWN))
    }
    @Test fun pbdPlugRepairsOnlyAnUnattachedConnectionOnALiveHost() {
        assertNull(TargetRules.unavailableReason(pbd(), TargetAction.PBD_PLUG))
        assertEquals("Already attached.", TargetRules.unavailableReason(pbd(attached = true), TargetAction.PBD_PLUG))
        assertEquals("Host B is not reporting as live.", TargetRules.unavailableReason(pbd(live = false), TargetAction.PBD_PLUG))
        assertNotNull(TargetRules.unavailableReason(pbd(busy = true), TargetAction.PBD_PLUG))
    }
    @Test fun rolesExplainButNeverDecide() {
        val operator = SessionPermissions(roles = listOf("vm-operator"))
        assertNull(TargetRules.roleExplanation(operator, TargetAction.CD_INSERT))
        assertTrue(TargetRules.roleExplanation(operator, TargetAction.SNAPSHOT_TAKE)!!.contains("vm-power-admin"))
        assertTrue(TargetRules.roleExplanation(SessionPermissions(roles = listOf("vm-power-admin")), TargetAction.HOST_DISABLE)!!.contains("pool-operator"))
        assertNull(TargetRules.roleExplanation(SessionPermissions(roles = listOf("pool-operator")), TargetAction.PBD_PLUG))
        assertNull(TargetRules.roleExplanation(SessionPermissions(superuser = true), TargetAction.HOST_REBOOT))
        assertNull(TargetRules.roleExplanation(SessionPermissions(roles = listOf("custom")), TargetAction.HOST_REBOOT))
        assertTrue(TargetRules.eligible(host(), TargetAction.HOST_DISABLE))
    }

    // Confirmation scale and fences

    @Test fun confirmationScaleMatchesThePlan() {
        val typed = setOf(TargetAction.SNAPSHOT_REVERT, TargetAction.SNAPSHOT_DELETE, TargetAction.HOST_EVACUATE, TargetAction.HOST_REBOOT, TargetAction.HOST_SHUTDOWN)
        for (action in TargetAction.entries)
            assertEquals("$action", if (action in typed) ConfirmationStrength.TYPED_NAME else ConfirmationStrength.SINGLE, action.confirmation)
        assertEquals("Guest", TargetRules.confirmationPhrase(TargetAction.SNAPSHOT_REVERT, snapshot()))
        assertEquals("Before upgrade", TargetRules.confirmationPhrase(TargetAction.SNAPSHOT_DELETE, snapshot()))
        assertEquals("snap-1", TargetRules.confirmationPhrase(TargetAction.SNAPSHOT_DELETE, snapshot(name = " ")))
        assertEquals("Host B", TargetRules.confirmationPhrase(TargetAction.HOST_EVACUATE, host()))
        assertNull(TargetRules.confirmationPhrase(TargetAction.HOST_DISABLE, host()))
        assertEquals("snapshot name", TargetRules.phraseSubject(TargetAction.SNAPSHOT_DELETE))
        assertEquals("VM name", TargetRules.phraseSubject(TargetAction.SNAPSHOT_REVERT))
    }
    @Test fun typedNameMustMatchExactly() {
        for (typed in listOf("", "guest", "Guest ", "GUEST", "Before upgrade")) {
            val c = confirm(TargetAction.SNAPSHOT_REVERT, snapshot(), typed = typed)
            assertFalse(typed, TargetRules.typedNameMatches(c))
            assertTrue(runCatching { TargetRules.confirm(profile, c, snapshot(), 1000) }.isFailure)
        }
        assertTrue(TargetRules.typedNameMatches(confirm(TargetAction.SNAPSHOT_REVERT, snapshot())))
        assertTrue(TargetRules.typedNameMatches(confirm(TargetAction.HOST_DISABLE, host()).copy(typedName = null)))
        assertFalse(TargetRules.typedNameMatches(confirm(TargetAction.HOST_REBOOT, host(enabled = false)).copy(typedName = null)))
    }
    @Test fun everyKindAcceptsAnUnchangedFreshReadWithinThirtySeconds() {
        for (action in TargetAction.entries) {
            val state = eligibleState(action)
            TargetRules.confirm(profile, confirm(action, state), state.withObservedAt(30_999), 1000 + 30_000)
        }
    }
    @Test fun expiredChangedOrForeignConfirmationsAreRefused() {
        val c = confirm(TargetAction.HOST_EVACUATE, host(enabled = false, resident = listOf("Guest")))
        val h = c.state as HostState
        val changes = listOf(h.copy(name = "Renamed"), h.copy(residentVms = listOf("Guest", "Other")), h.copy(master = true), h.copy(enabled = true),
            h.copy(live = false), h.copy(authority = "other"), h.copy(uuid = "host-a"), h.copy(busy = true), h.copy(bootMarker = "1700000999.0"),
            MissingTarget("pool", TargetKind.HOST, "host-b", 1000))
        for (current in changes) assertTrue("$current", runCatching { TargetRules.confirm(profile, c, current, 1000) }.isFailure)
        assertTrue(runCatching { TargetRules.confirm(profile, c, c.state, 1000 + 30_001) }.isFailure)
        assertTrue(runCatching { TargetRules.confirm(profile, c, c.state, 999) }.isFailure)
        assertTrue(runCatching { TargetRules.confirm(profile.copy(username = "other"), c, c.state, 1000) }.isFailure)
        assertTrue(runCatching { TargetRules.confirm(profile.copy(id = "q"), c, c.state, 1000) }.isFailure)
        TargetRules.confirm(profile, c, c.state, 1000)
        // A snapshot changed after review (another disk, or the live VM's disks) needs a new review.
        val delete = confirm(TargetAction.SNAPSHOT_DELETE, snapshot())
        assertTrue(runCatching { TargetRules.confirm(profile, delete, snapshot(disks = listOf(ownDisk(), ownDisk("vdi-2", "vbd-2"))), 1000) }.isFailure)
        assertTrue(runCatching { TargetRules.confirm(profile, delete, snapshot(liveDisks = listOf("vdi-new")), 1000) }.isFailure)
        assertTrue(runCatching { TargetRules.confirm(profile, delete.copy(plan = emptyList()), snapshot(), 1000) }.isFailure)
        // A CD drive whose media changed, and an ISO on the wrong kind, are refused.
        val insert = confirm(TargetAction.CD_INSERT, drive())
        assertTrue(runCatching { TargetRules.confirm(profile, insert, drive(empty = false, allowed = setOf("insert", "eject")), 1000) }.isFailure)
        assertTrue(runCatching { TargetRules.confirm(profile, insert.copy(argument = null), drive(), 1000) }.isFailure)
        assertTrue(runCatching { TargetRules.confirm(profile, confirm(TargetAction.CD_EJECT, drive(false)).copy(argument = B2Fixtures.iso), drive(false), 1000) }.isFailure)
    }
    @Test fun snapshotNamesAreValidatedAndDefaultToVmAndTime() {
        assertNull(TargetRules.snapshotNameProblem("Before upgrade"))
        for (bad in listOf(null, "", "   ", "a\nb", "tab\there", "x".repeat(201))) assertNotNull(bad, TargetRules.snapshotNameProblem(bad))
        assertEquals("Guest 2026-01-01 10:00", TargetRules.defaultSnapshotName("Guest", 1767261600000, ZoneOffset.UTC))
        assertEquals("VM 2026-01-01 10:00", TargetRules.defaultSnapshotName("", 1767261600000, ZoneOffset.UTC))
        val take = confirm(TargetAction.SNAPSHOT_TAKE, vm(), name = "bad\nname")
        assertTrue(runCatching { TargetRules.confirm(profile, take, vm(), 1000) }.isFailure)
    }

    // Snapshot delete plan: only the snapshot's own disk snapshots

    @Test fun deletePlanNeverSelectsLiveCdEmptySharedOrNonSnapshotDisks() {
        val disks = listOf(
            ownDisk("vdi-snap-a", "vbd-1"),
            SnapshotDisk("vbd-2", "Disk", false, "vdi-live", "live disk", vdiIsSnapshot = false, onlyThisSnapshot = false, inLiveVm = true),
            SnapshotDisk("vbd-3", "CD", false, "iso-1", "debian.iso", vdiIsSnapshot = false, onlyThisSnapshot = false, inLiveVm = true),
            SnapshotDisk("vbd-4", "Disk", true, null, "", false, false, false),
            SnapshotDisk("vbd-5", "Disk", false, "vdi-shared", "shared", vdiIsSnapshot = true, onlyThisSnapshot = false, inLiveVm = false),
            SnapshotDisk("vbd-6", "Disk", false, "vdi-odd", "snapshot also on VM", vdiIsSnapshot = true, onlyThisSnapshot = true, inLiveVm = true),
            SnapshotDisk("vbd-7", "Disk", false, "vdi-plain", "not a snapshot", vdiIsSnapshot = false, onlyThisSnapshot = true, inLiveVm = false),
            ownDisk("vdi-snap-b", "vbd-8"))
        val plan = TargetRules.deletePlan(snapshot(disks = disks))
        assertEquals(listOf(StepKind.VDI_DESTROY to "vdi-snap-a", StepKind.VDI_DESTROY to "vdi-snap-b", StepKind.VM_DESTROY to "snap-1"),
            plan.map { it.kind to it.uuid })
        assertTrue(plan.all { it.state == StepState.PENDING && it.taskRef == null })
        val live = setOf("vdi-live", "iso-1", "vdi-shared", "vdi-odd", "vdi-plain")
        assertTrue(plan.none { it.uuid in live })
        assertEquals(listOf("not a snapshot disk", "CD or other drive", "empty slot", "also used elsewhere", "used by the VM", "not a snapshot disk"),
            disks.mapNotNull(TargetRules::keptReason))
        // A snapshot without disks still deletes only its own record.
        assertEquals(listOf(StepKind.VM_DESTROY), TargetRules.deletePlan(snapshot(disks = emptyList())).map { it.kind })
    }
    @Test fun stepSummaryListsWhatWasAndWasNotDestroyed() {
        val steps = listOf(OperationStep(StepKind.VDI_DESTROY, "a", "disk A", StepState.DONE),
            OperationStep(StepKind.VDI_DESTROY, "b", "disk B", StepState.ABSENT), OperationStep(StepKind.VDI_DESTROY, "c", "disk C", StepState.UNKNOWN, "ref"),
            OperationStep(StepKind.VDI_DESTROY, "d", "disk D", StepState.FAILED, code = "VDI_IN_USE"), OperationStep(StepKind.VM_DESTROY, "s", "snap", StepState.PENDING))
        assertEquals("Destroyed: “disk A”. Already absent: “disk B”. Uncertain: “disk C”. Failed: “disk D” (VDI_IN_USE). Not sent: “snap”.",
            TargetRules.stepSummary(steps))
    }

    // Proof per kind

    private fun op(action: TargetAction, state: TargetState, argumentUuid: String? = null, argumentName: String? = null,
        beforeBoot: String? = null, steps: List<OperationStep>? = null) = PowerOperation("o", "p", profile.origin(), "pool", state.vmUuid.orEmpty(),
        state.vmName ?: state.name, PowerAction.TARGET, beforeBoot, PowerPhase.VERIFYING, 1, 1, "ref", "uuid",
        targetAction = action, targetUuid = state.uuid, targetName = state.name, argumentUuid = argumentUuid, argumentName = argumentName,
        marker = TargetRules.marker(action, state), steps = steps)

    @Test fun takeIsProvenByANewSnapshotWithTheRequestedName() {
        val take = op(TargetAction.SNAPSHOT_TAKE, vm(), argumentName = "Before upgrade") // an older snapshot already has this name
        assertFalse(TargetRules.verified(take, vm()))
        val added = vm(snapshots = vm().snapshots + B2Fixtures.snapInfo("snap-2", "Before upgrade"))
        assertTrue(TargetRules.verified(take, added))
        assertEquals("snap-2", TargetRules.newSnapshot(take, added)?.uuid)
        assertFalse(TargetRules.verified(take, vm(snapshots = vm().snapshots + B2Fixtures.snapInfo("snap-2", "Other"))))
        assertFalse(TargetRules.verified(take.copy(authority = "other"), added))
        assertFalse(TargetRules.verified(take.copy(marker = null), added))
    }
    @Test fun revertIsProvenByParentEndStateAndChangedDisks() {
        val revert = op(TargetAction.SNAPSHOT_REVERT, snapshot())
        assertEquals("Halted", revert.marker?.get("expect"))
        val reverted = vm(power = "Halted", parent = "snap-1", disks = listOf("vdi-new"))
        assertTrue(TargetRules.verified(revert, reverted))
        assertFalse(TargetRules.verified(revert, reverted.copy(power = "Running")))
        assertFalse(TargetRules.verified(revert, reverted.copy(parentUuid = "snap-0")))
        // Same parent as before and the same disks: nothing distinguishes a revert, so it stays unproven.
        val already = op(TargetAction.SNAPSHOT_REVERT, snapshot(liveParent = "snap-1", livePower = "Halted"))
        assertFalse(TargetRules.verified(already, vm(power = "Halted", parent = "snap-1", disks = listOf("vdi-live"))))
        assertTrue(TargetRules.verified(already, vm(power = "Halted", parent = "snap-1", disks = listOf("vdi-new"))))
        val checkpoint = op(TargetAction.SNAPSHOT_REVERT, snapshot(withMemory = true))
        assertTrue(TargetRules.verified(checkpoint, reverted.copy(power = "Suspended")))
        assertFalse(TargetRules.verified(checkpoint, reverted))
    }
    @Test fun singleStepKindsAreProvenByTheirReadBackState() {
        assertTrue(TargetRules.verified(op(TargetAction.CD_INSERT, drive(), argumentUuid = "iso-1"), drive(empty = false)))
        assertFalse(TargetRules.verified(op(TargetAction.CD_INSERT, drive(), argumentUuid = "iso-2"), drive(empty = false)))
        assertFalse(TargetRules.verified(op(TargetAction.CD_INSERT, drive(), argumentUuid = "iso-1"), drive()))
        assertTrue(TargetRules.verified(op(TargetAction.CD_EJECT, drive(false)), drive()))
        assertFalse(TargetRules.verified(op(TargetAction.CD_EJECT, drive(false)), drive(false)))
        assertTrue(TargetRules.verified(op(TargetAction.HOST_DISABLE, host()), host(enabled = false)))
        assertFalse(TargetRules.verified(op(TargetAction.HOST_DISABLE, host()), host()))
        assertTrue(TargetRules.verified(op(TargetAction.HOST_ENABLE, host(enabled = false)), host()))
        assertTrue(TargetRules.verified(op(TargetAction.HOST_EVACUATE, host(enabled = false, resident = listOf("Guest"))), host(enabled = false)))
        assertFalse(TargetRules.verified(op(TargetAction.HOST_EVACUATE, host(enabled = false, resident = listOf("Guest"))), host(enabled = false, resident = listOf("Guest"))))
        assertTrue(TargetRules.verified(op(TargetAction.HOST_SHUTDOWN, host(enabled = false)), host(enabled = false, live = false)))
        assertFalse(TargetRules.verified(op(TargetAction.HOST_SHUTDOWN, host(enabled = false)), host(enabled = false)))
        assertTrue(TargetRules.verified(op(TargetAction.PBD_PLUG, pbd()), pbd(attached = true)))
        assertFalse(TargetRules.verified(op(TargetAction.PBD_PLUG, pbd()), pbd()))
        // Proof must come from the same object and pool.
        assertFalse(TargetRules.verified(op(TargetAction.PBD_PLUG, pbd()), pbd(attached = true).copy(uuid = "pbd-other")))
        assertFalse(TargetRules.verified(op(TargetAction.HOST_DISABLE, host()), MissingTarget("pool", TargetKind.HOST, "host-b", 1)))
        assertFalse(TargetRules.verified(op(TargetAction.HOST_DISABLE, host()).copy(targetAction = TargetAction.HOST_DISABLE, foreign = null), pbd()))
    }
    @Test fun deleteIsProvenOnlyWhenEveryStepIsDoneAndTheSnapshotIsGone() {
        val gone = MissingTarget("pool", TargetKind.SNAPSHOT, "snap-1", 1)
        val plan = TargetRules.deletePlan(snapshot())
        assertFalse(TargetRules.verified(op(TargetAction.SNAPSHOT_DELETE, snapshot(), steps = plan), gone))
        val done = plan.map { it.copy(state = StepState.DONE) }
        assertTrue(TargetRules.verified(op(TargetAction.SNAPSHOT_DELETE, snapshot(), steps = done), gone))
        assertTrue(TargetRules.verified(op(TargetAction.SNAPSHOT_DELETE, snapshot(), steps = listOf(done[0].copy(state = StepState.ABSENT), done[1])), gone))
        assertFalse(TargetRules.verified(op(TargetAction.SNAPSHOT_DELETE, snapshot(), steps = done), snapshot()))
    }
    @Test fun hostRebootNeedsAValidChangedBootMarker() {
        assertTrue(TargetRules.validHostBoot("1700000000.5")); assertTrue(TargetRules.validHostBoot(" 1700000000 "))
        for (bad in listOf(null, "", "soon", "0", "-5", "NaN", "Infinity")) assertFalse(bad, TargetRules.validHostBoot(bad))
        assertFalse(TargetRules.hostBootChanged("1700000000.5", "1700000000.50"))
        assertTrue(TargetRules.hostBootChanged("1700000000.5", "1700000600.0"))
        assertFalse(TargetRules.hostBootChanged(null, "1700000600.0"))
        assertFalse(TargetRules.hostBootChanged("1700000000.5", "garbage"))
        val reboot = op(TargetAction.HOST_REBOOT, host(enabled = false), beforeBoot = "1700000000.5")
        assertTrue(TargetRules.verified(reboot, host(enabled = false, boot = "1700000600.0")))
        assertTrue(TargetRules.verified(reboot, host(enabled = true, boot = "1700000600.0"))) // xapi may re-enable the host after start
        assertFalse(TargetRules.verified(reboot, host(enabled = false, boot = "1700000600.0", live = false)))
        assertFalse(TargetRules.verified(reboot, host(enabled = false)))
        assertFalse(TargetRules.verified(reboot.copy(beforeBoot = null), host(enabled = false, boot = "1700000600.0")))
        assertEquals("true", TargetRules.marker(TargetAction.HOST_REBOOT, host(master = true))?.get("master"))
        assertTrue(TargetRules.unverifiedMessage(op(TargetAction.HOST_REBOOT, host(enabled = false, master = true))).contains("coordinator"))
    }

    // Arity fallback

    @Test fun arityFallbackHappensOnceAndOnlyOnAnExplicitParameterCountMismatch() {
        val sent = mutableListOf<List<Any?>>()
        fun call(failures: List<Exception?>): (List<Any?>) -> String = { args -> sent += args; failures.getOrNull(sent.size - 1)?.let { throw it }; "task" }
        val primary = listOf("vm", "name"); val alternate = listOf("vm", "name", emptyList<String>())
        assertEquals("task", XapiArity.send(primary, alternate, call(listOf(null)))); assertEquals(listOf(primary), sent)
        sent.clear()
        assertEquals("task", XapiArity.send(primary, alternate, call(listOf(RpcFailure(XapiArity.MISMATCH), null)))); assertEquals(listOf(primary, alternate), sent)
        val refusals = listOf(RpcFailure("SR_OPERATION_NOT_SUPPORTED"), RpcFailure(XapiArity.MISMATCH, explicitRejection = false),
            RpcFailure("RPC_FAULT", explicitRejection = false), IOException("reset"))
        for (error in refusals) {
            sent.clear()
            assertSame(error, runCatching { XapiArity.send(primary, alternate, call(listOf(error, null))) }.exceptionOrNull())
            assertEquals("$error", listOf(primary), sent)
        }
        sent.clear()
        assertTrue(runCatching { XapiArity.send(primary, null, call(listOf(RpcFailure(XapiArity.MISMATCH), null))) }.isFailure); assertEquals(1, sent.size)
        sent.clear()
        assertTrue(runCatching { XapiArity.send(primary, alternate, call(listOf(RpcFailure(XapiArity.MISMATCH), RpcFailure(XapiArity.MISMATCH), null))) }.isFailure)
        assertEquals(2, sent.size)
    }
    @Test fun xapiCallsAndArgumentsAreDeclaredPerKind() {
        assertEquals(listOf("vm", "Snap"), TargetAction.SNAPSHOT_TAKE.xapiArguments("vm", "Snap"))
        assertEquals(listOf("vm", "Snap", emptyList<String>()), TargetAction.SNAPSHOT_TAKE.alternateArguments("vm", "Snap"))
        assertEquals(listOf("host", true), TargetAction.HOST_DISABLE.alternateArguments("host", null))
        assertEquals(listOf("host", "OpaqueRef:NULL", "0"), TargetAction.HOST_EVACUATE.alternateArguments("host", null))
        assertEquals(listOf("vbd", "vdi"), TargetAction.CD_INSERT.xapiArguments("vbd", "vdi"))
        assertEquals(listOf("pbd"), TargetAction.PBD_PLUG.xapiArguments("pbd", null))
        assertNull(TargetAction.HOST_REBOOT.alternateArguments("host", null))
        assertTrue(runCatching { TargetAction.SNAPSHOT_DELETE.xapiArguments("snap", null) }.isFailure)
        assertTrue(runCatching { TargetAction.CD_INSERT.xapiArguments("vbd", null) }.isFailure)
        assertEquals("Async.VDI.destroy", StepKind.VDI_DESTROY.xapiMethod); assertEquals("Async.VM.destroy", StepKind.VM_DESTROY.xapiMethod)
        assertTrue(TargetAction.entries.filter { it != TargetAction.SNAPSHOT_DELETE }.all { it.xapiMethod.startsWith("Async.") })
        // No VM destroy, VDI destroy, SR create/destroy or network call is a single-step kind.
        assertTrue(TargetAction.entries.none { it.xapiMethod.contains("destroy") || it.xapiMethod.startsWith("Async.SR.") || it.xapiMethod.contains("network") })
    }
}

private fun TargetState.withObservedAt(at: Long): TargetState = when (this) {
    is VmTarget -> copy(observedAt = at); is SnapshotTarget -> copy(observedAt = at); is CdDrive -> copy(observedAt = at)
    is HostState -> copy(observedAt = at); is PbdState -> copy(observedAt = at); is MissingTarget -> copy(observedAt = at)
}
