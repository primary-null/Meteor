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
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

/** B2 storage/host through PowerService: journal barrier, uncertainty, sub-steps, reconciliation and manual review. */
class TargetServiceTest {
    private class Store : OperationStore {
        val records = linkedMapOf<String, PowerOperation>()
        val phases = mutableListOf<PowerPhase>()
        var failPutWhen: ((PowerOperation) -> Boolean)? = null
        override suspend fun load() = records.values.map { JournalCodec.decode(JournalCodec.encode(it)) }
        override suspend fun put(operation: PowerOperation) {
            if (failPutWhen?.invoke(operation) == true) { failPutWhen = null; throw IOException("disk full") }
            records[operation.id] = operation; phases += operation.phase
        }
    }

    /** Server model: [states] is what a read returns; a send applies [after] unless [sendFailure] is set. */
    private class Backend(val store: Store) : PowerBackend, PowerConnection {
        val states = mutableMapOf<Pair<TargetKind, String>, TargetState>()
        val after = mutableMapOf<Pair<TargetKind, String>, TargetState>()
        val sent = mutableListOf<String>()
        val absentUuids = mutableSetOf<String>()
        var sendFailure: Exception? = null
        var stepFailures = mutableMapOf<String, Exception>()
        var task = TaskObservation("success", 1.0)
        var stepTask: (OperationStep) -> TaskObservation = { TaskObservation("success", 1.0) }
        var readFailure: Exception? = null
        var taskLookupFailure: Exception? = null
        var vmReads = 0
        fun put(state: TargetState) { states[state.kind to state.uuid] = state }
        override suspend fun <T> powerConnection(profile: Profile, action: suspend PowerConnection.() -> T): T = action(this)
        override suspend fun details(authority: String, uuid: String): VmDetails { vmReads++; throw IllegalStateException("VM path must not be used") }
        override suspend fun submit(authority: String, uuid: String, action: PowerAction): String = error("VM sends must not be used")
        override suspend fun taskUuid(reference: String) = "uuid-$reference"
        override suspend fun taskReference(uuid: String): String { taskLookupFailure?.let { throw it }; return uuid.removePrefix("uuid-") }
        override suspend fun observeTask(reference: String): TaskObservation {
            readFailure?.let { throw it }
            return if (reference.startsWith("step:")) stepTask(stepByRef.getValue(reference)) else task
        }
        override suspend fun target(authority: String, kind: TargetKind, uuid: String): TargetState {
            readFailure?.let { throw it }
            if (uuid in absentUuids) return MissingTarget(authority, kind, uuid, B2Fixtures.NOW)
            return states[kind to uuid] ?: MissingTarget(authority, kind, uuid, B2Fixtures.NOW)
        }
        override suspend fun iso(authority: String, uuid: String) = B2Fixtures.iso.also { require(uuid == it.uuid) }
        override suspend fun submitTarget(authority: String, action: TargetAction, target: String, argument: String?): String {
            assertEquals(PowerPhase.SUBMITTING, store.records.values.last().phase) // write-ahead barrier
            sent += "${action.name}:$target:${argument.orEmpty()}"
            sendFailure?.let { throw it }
            states.putAll(after)
            return "task-${sent.size}"
        }
        val stepByRef = mutableMapOf<String, OperationStep>()
        override suspend fun absent(authority: String, step: OperationStep): Boolean { readFailure?.let { throw it }; return step.uuid in absentUuids }
        override suspend fun submitStep(authority: String, step: OperationStep): String {
            val stored = store.records.values.last().steps!!.single { it.uuid == step.uuid }
            assertEquals(StepState.SUBMITTING, stored.state) // each sub-step is journaled before it is sent
            sent += "${step.kind.name}:${step.uuid}"
            stepFailures[step.uuid]?.let { throw it }
            absentUuids += step.uuid
            return "step:${step.uuid}".also { stepByRef[it] = step }
        }
    }
    private fun service(backend: Backend, store: Store, attempts: Int = 2) = PowerService(backend, store, { B2Fixtures.NOW }, {}, attempts)
    private fun setup(state: TargetState, after: TargetState? = null): Triple<Store, Backend, PowerService> {
        val store = Store(); val backend = Backend(store)
        backend.put(state); after?.let { backend.after[it.kind to it.uuid] = it }
        return Triple(store, backend, service(backend, store))
    }
    /** The state each kind should produce, read with the kind's proof object. */
    private fun proofAfter(action: TargetAction): TargetState = when (action) {
        TargetAction.SNAPSHOT_TAKE -> vm(snapshots = vm().snapshots + SnapshotInfo("snap-new", "Snap", "20260101T11:00:00Z", false))
        TargetAction.SNAPSHOT_REVERT -> vm(power = "Halted", parent = "snap-1", disks = listOf("vdi-clone"))
        TargetAction.CD_INSERT -> drive(empty = false)
        TargetAction.CD_EJECT -> drive(empty = true)
        TargetAction.HOST_DISABLE -> host(enabled = false)
        TargetAction.HOST_ENABLE -> host(enabled = true)
        TargetAction.HOST_EVACUATE -> host(enabled = false)
        TargetAction.HOST_REBOOT -> host(enabled = false, boot = "1700000900.0")
        TargetAction.HOST_SHUTDOWN -> host(enabled = false, live = false)
        TargetAction.PBD_PLUG -> pbd(attached = true)
        TargetAction.SNAPSHOT_DELETE -> error("multi-step")
    }
    private val singleStep = TargetAction.entries - TargetAction.SNAPSHOT_DELETE

    @Test fun everySingleStepKindIsJournaledBeforeTheSocketAndProvenByAFreshRead() = runTest {
        for (action in singleStep) {
            val (store, backend, service) = setup(eligibleState(action), proofAfter(action))
            if (action.proofKind != action.targetKind) backend.put(vm()) // revert is proven on the VM
            val result = service.executeTarget(profile, confirm(action, eligibleState(action)))
            assertEquals("$action: ${result.message}", PowerPhase.SUCCEEDED, result.phase)
            assertEquals(listOf(PowerPhase.PREPARED, PowerPhase.SUBMITTING, PowerPhase.TRACKING, PowerPhase.TRACKING, PowerPhase.VERIFYING, PowerPhase.SUCCEEDED), store.phases)
            assertEquals("$action", 1, backend.sent.size)
            val saved = store.load().single()
            assertEquals(action, saved.targetAction); assertEquals(PowerAction.TARGET, saved.action)
            assertEquals(eligibleState(action).uuid, saved.targetUuid); assertEquals("task-1", saved.taskRef); assertEquals("uuid-task-1", saved.taskUuid)
            assertEquals(0, backend.vmReads)
        }
    }
    @Test fun recordIdentityCarriesOwningVmSubjectAndArgument() = runTest {
        val (store, backend, service) = setup(drive(), drive(empty = false))
        service.executeTarget(profile, confirm(TargetAction.CD_INSERT, drive()))
        val op = store.load().single()
        assertEquals("vm", op.vmUuid); assertEquals("Guest", op.vmName); assertEquals("CD drive 3", op.targetName)
        assertEquals("iso-1", op.argumentUuid); assertEquals("debian.iso", op.argumentName)
        assertEquals(listOf("CD_INSERT:vbd-cd:iso-1"), backend.sent)
        assertEquals("Guest · CD drive 3 · Insert ISO", OperationHistory.title(op))
        val (hostStore, _, hostService) = setup(host(), host(enabled = false))
        hostService.executeTarget(profile, confirm(TargetAction.HOST_DISABLE, host()))
        val hostOp = hostStore.load().single()
        assertEquals("", hostOp.vmUuid); assertEquals("Host B", hostOp.vmName); assertEquals("Host B · Disable host", OperationHistory.title(hostOp))
        val (takeStore, takeBackend, take) = setup(vm(), proofAfter(TargetAction.SNAPSHOT_TAKE))
        val taken = take.executeTarget(profile, confirm(TargetAction.SNAPSHOT_TAKE, vm(), name = "Snap"))
        assertEquals("snap-new", taken.argumentUuid); assertEquals("Snap", taken.argumentName)
        assertEquals("snap-1", takeStore.load().single().marker?.get("snapshots"))
        assertEquals(listOf("SNAPSHOT_TAKE:vm:Snap"), takeBackend.sent)
    }
    @Test fun unchangedStateAfterTaskSuccessIsUnknownAndReconcileNeverResends() = runTest {
        for (action in singleStep) {
            val (store, backend, service) = setup(eligibleState(action))
            if (action.proofKind != action.targetKind) backend.put(vm())
            val pending = service.executeTarget(profile, confirm(action, eligibleState(action)))
            assertEquals("$action", PowerPhase.UNKNOWN, pending.phase); assertTrue(pending.unresolved)
            assertEquals("$action", PowerPhase.UNKNOWN, service.reconcile(profile, pending).phase)
            backend.put(proofAfter(action))
            assertEquals("$action", PowerPhase.SUCCEEDED, service.reconcile(profile, store.load().single()).phase)
            assertEquals("$action", 1, backend.sent.size)
        }
    }
    @Test fun explicitRejectionAndSendTimeChecksFailButTransportLossIsUnknown() = runTest {
        val cases = listOf(RpcFailure("HOST_IN_USE") to PowerPhase.FAILED, NotSent("The host no longer exists.") to PowerPhase.FAILED,
            RpcFailure("RPC_FAULT", explicitRejection = false) to PowerPhase.UNKNOWN, IOException("reset") to PowerPhase.UNKNOWN)
        for ((error, expected) in cases) {
            val (store, backend, service) = setup(host(), host(enabled = false))
            backend.sendFailure = error
            val result = service.executeTarget(profile, confirm(TargetAction.HOST_DISABLE, host()))
            assertEquals("$error", expected, result.phase)
            if (error is RpcFailure && error.explicitRejection) assertTrue(result.message.contains("HOST_IN_USE"))
            if (error is NotSent) assertTrue(result.message.startsWith("Not sent"))
            if (expected == PowerPhase.UNKNOWN) {
                assertEquals(PowerPhase.UNKNOWN, service.reconcile(profile, result).phase)
                assertTrue(store.load().single().message.contains("No task ticket"))
            }
            assertEquals(1, backend.sent.size)
        }
    }
    @Test fun failedTaskSurfacesItsCode() = runTest {
        val (_, backend, service) = setup(host(enabled = false, resident = listOf("Guest")))
        backend.task = TaskObservation("failure", 1.0, "VM_REQUIRES_SR")
        val result = service.executeTarget(profile, confirm(TargetAction.HOST_EVACUATE, host(enabled = false, resident = listOf("Guest"))))
        assertEquals(PowerPhase.FAILED, result.phase); assertTrue(result.message.contains("VM_REQUIRES_SR"))
    }
    @Test fun confirmationFencesStopBeforeAnythingIsJournaled() = runTest {
        val original = host(enabled = false, resident = listOf("Guest"))
        for (current in listOf(original.copy(residentVms = listOf("Guest", "New")), original.copy(name = "Renamed"), original.copy(enabled = true),
            original.copy(blockers = listOf(EvacuationBlocker("Guest", "VM_REQUIRES_SR"))), MissingTarget("pool", TargetKind.HOST, "host-b", 1))) {
            val (store, backend, service) = setup(current)
            assertTrue(runCatching { service.executeTarget(profile, confirm(TargetAction.HOST_EVACUATE, original)) }.isFailure)
            assertTrue(store.records.isEmpty()); assertTrue(backend.sent.isEmpty())
        }
        val (store, backend, service) = setup(original)
        for (typed in listOf("host b", "Host B ", "")) assertTrue(runCatching { service.executeTarget(profile, confirm(TargetAction.HOST_EVACUATE, original, typed = typed)) }.isFailure)
        assertTrue(runCatching { service.executeTarget(profile, confirm(TargetAction.HOST_EVACUATE, original.copy(observedAt = B2Fixtures.NOW - 30_001))) }.isFailure)
        assertTrue(runCatching { service.executeTarget(profile.copy(username = "x"), confirm(TargetAction.HOST_EVACUATE, original)) }.isFailure)
        assertTrue(store.records.isEmpty()); assertTrue(backend.sent.isEmpty())
    }
    @Test fun oneUnresolvedOperationBlocksEveryKindInBothFrameworks() = runTest {
        val (store, backend, service) = setup(host(), host(enabled = false))
        backend.sendFailure = IOException("lost")
        assertEquals(PowerPhase.UNKNOWN, service.executeTarget(profile, confirm(TargetAction.HOST_DISABLE, host())).phase)
        backend.sendFailure = null
        backend.put(pbd())
        assertTrue(runCatching { service.executeTarget(profile, confirm(TargetAction.PBD_PLUG, pbd())) }.isFailure)
        val vmDetails = VmDetails("pool", "vm", "Guest", "", "Halted", null, 1, 1, emptyList(), emptyList(), false, null, emptyList(), null, setOf("start"), false, B2Fixtures.NOW)
        assertTrue(runCatching { service.execute(profile, PowerConfirmation(profile.id, profile.origin(), vmDetails, PowerAction.START)) }.isFailure)
        assertEquals(1, backend.sent.size)
        // And an unresolved VM operation blocks storage/host kinds.
        val vmStore = Store(); val vmBackend = Backend(vmStore).apply { put(host()) }
        vmStore.records["a2"] = PowerOperation("a2", "p", profile.origin(), "pool", "vm", "Guest", PowerAction.REBOOT, "20260101T10:00:00Z",
            PowerPhase.TRACKING, 1, 1, "ref", "uuid", "Waiting")
        assertTrue(runCatching { service(vmBackend, vmStore).executeTarget(profile, confirm(TargetAction.HOST_DISABLE, host())) }.isFailure)
        assertTrue(vmBackend.sent.isEmpty())
    }
    @Test fun vmPathsRefuseStorageHostRecords() = runTest {
        val (store, backend, service) = setup(host())
        backend.sendFailure = IOException("lost")
        val pending = service.executeTarget(profile, confirm(TargetAction.HOST_DISABLE, host()))
        assertFalse(PowerAction.TARGET.requestable)
        assertTrue(runCatching { PowerAction.TARGET.xapiArguments("vm", null) }.isFailure)
        assertFalse(PowerRules.verified(pending, VmDetails("pool", "", "Host B", "", "", null, 1, 1, emptyList(), emptyList(), false, null,
            emptyList(), null, emptySet(), false, 1)))
        assertTrue(runCatching { service.review(profile, pending) }.isFailure)
        assertEquals(0, backend.vmReads)
        assertTrue(runCatching { PowerOperation("x", "p", "o", "pool", "", "n", PowerAction.TARGET, null, PowerPhase.PREPARED, 1, 1) }.isFailure)
        assertTrue(runCatching { PowerOperation("x", "p", "o", "pool", "", "n", PowerAction.START, null, PowerPhase.PREPARED, 1, 1,
            targetAction = TargetAction.HOST_DISABLE, targetUuid = "h") }.isFailure)
        assertTrue(runCatching { PowerOperation("x", "p", "o", "pool", "", "n", PowerAction.TARGET, null, PowerPhase.PREPARED, 1, 1,
            targetAction = TargetAction.HOST_DISABLE, targetUuid = "") }.isFailure)
        assertEquals(1, store.records.size)
    }
    @Test fun appStoppedBeforeTheBarrierIsFailedWithoutSending() = runTest {
        val (store, backend, service) = setup(host())
        store.records["p"] = PowerOperation("p", "p", profile.origin(), "pool", "", "Host B", PowerAction.TARGET, null, PowerPhase.PREPARED, 1, 1,
            targetAction = TargetAction.HOST_DISABLE, targetUuid = "host-b", targetName = "Host B")
        val result = service.reconcile(profile, store.load().single())
        assertEquals(PowerPhase.FAILED, result.phase); assertTrue(result.message.contains("No request was sent"))
        assertTrue(backend.sent.isEmpty())
    }
    @Test fun aTaskTicketKeptAfterAFailedJournalWriteIsRecoveredWithoutResending() = runTest {
        val (store, backend, service) = setup(host())
        store.failPutWhen = { it.phase == PowerPhase.TRACKING }
        // The ticket write fails after the send: the outcome is uncertain and the disk has no ticket.
        assertEquals(PowerPhase.UNKNOWN, service.executeTarget(profile, confirm(TargetAction.HOST_DISABLE, host())).phase)
        assertNull(store.load().single().taskRef)
        backend.put(host(enabled = false))
        val recovered = service.reconcile(profile, store.load().single())
        assertEquals(PowerPhase.SUCCEEDED, recovered.phase); assertEquals("task-1", recovered.taskRef)
        assertEquals(1, backend.sent.size)
    }

    // Snapshot delete: recorded sub-steps

    private val liveDisk = SnapshotDisk("vbd-live", "Disk", false, "vdi-live", "live", vdiIsSnapshot = false, onlyThisSnapshot = false, inLiveVm = true)
    private val cd = SnapshotDisk("vbd-cd", "CD", false, "iso-1", "debian.iso", false, false, true)
    private val target = snapshot(disks = listOf(ownDisk("vdi-a", "vbd-a"), liveDisk, cd, ownDisk("vdi-b", "vbd-b")))
    private fun deleteSetup(): Triple<Store, Backend, PowerService> = setup(target).also { (_, backend, _) -> backend.put(vm()) }

    @Test fun deleteDestroysOnlyTheSnapshotDisksThenTheRecordAndProvesThemGone() = runTest {
        val (store, backend, service) = deleteSetup()
        val result = service.executeTarget(profile, confirm(TargetAction.SNAPSHOT_DELETE, target))
        assertEquals(result.message, PowerPhase.SUCCEEDED, result.phase)
        assertEquals(listOf("VDI_DESTROY:vdi-a", "VDI_DESTROY:vdi-b", "VM_DESTROY:snap-1"), backend.sent)
        assertTrue(backend.sent.none { it.contains("vdi-live") || it.contains("iso-1") || it.endsWith(":vm") })
        assertEquals(listOf(StepState.DONE, StepState.DONE, StepState.DONE), store.load().single().steps!!.map { it.state })
        assertEquals("Delete verified: the snapshot and 2 disk snapshot(s) are gone.", result.message)
        assertEquals("vm", result.vmUuid); assertEquals("Before upgrade", result.targetName)
    }
    @Test fun interruptedDeleteIsUnknownWithAListAndTheRestIsNeverSent() = runTest {
        val (store, backend, service) = deleteSetup()
        backend.stepFailures["vdi-b"] = IOException("connection reset")
        val result = service.executeTarget(profile, confirm(TargetAction.SNAPSHOT_DELETE, target))
        assertEquals(PowerPhase.UNKNOWN, result.phase); assertTrue(result.unresolved)
        assertTrue(result.message, result.message.contains("Destroyed: “disk snapshot”") && result.message.contains("Uncertain: “disk snapshot”") &&
            result.message.contains("Not sent: “Before upgrade”"))
        assertEquals(listOf(StepState.DONE, StepState.UNKNOWN, StepState.PENDING), store.load().single().steps!!.map { it.state })
        // Reconcile observes only: the uncertain step has no ticket, and the record step is never sent.
        backend.stepFailures.clear()
        val again = service.reconcile(profile, store.load().single())
        assertEquals(PowerPhase.UNKNOWN, again.phase)
        assertTrue(again.message.contains("No task ticket was saved for “disk snapshot”"))
        assertEquals(listOf("VDI_DESTROY:vdi-a", "VDI_DESTROY:vdi-b"), backend.sent)
        // Every other write stays locked until the record is reviewed.
        assertTrue(runCatching { service.executeTarget(profile, confirm(TargetAction.SNAPSHOT_DELETE, target)) }.isFailure)
        assertEquals(2, backend.sent.size)
    }
    @Test fun aLostStepWithATicketIsObservedButLaterStepsStayUnsent() = runTest {
        val (store, backend, service) = deleteSetup()
        backend.stepTask = { step -> if (step.uuid == "vdi-a") TaskObservation("pending", 0.5) else TaskObservation("success", 1.0) }
        val first = service.executeTarget(profile, confirm(TargetAction.SNAPSHOT_DELETE, target))
        assertEquals(PowerPhase.UNKNOWN, first.phase)
        assertEquals(listOf(StepState.UNKNOWN, StepState.PENDING, StepState.PENDING), store.load().single().steps!!.map { it.state })
        assertEquals("step:vdi-a", store.load().single().steps!![0].taskRef)
        backend.stepTask = { TaskObservation("success", 1.0) }
        val observed = service.reconcile(profile, store.load().single())
        assertEquals(PowerPhase.UNKNOWN, observed.phase)
        assertEquals(listOf(StepState.DONE, StepState.PENDING, StepState.PENDING), store.load().single().steps!!.map { it.state })
        assertTrue(observed.message.contains("Not sent: “disk snapshot”, “Before upgrade”"))
        assertEquals(listOf("VDI_DESTROY:vdi-a"), backend.sent)
    }
    @Test fun interruptionBetweenStepsSendsNothingOnReconcile() = runTest {
        val (store, backend, service) = deleteSetup()
        val plan = TargetRules.deletePlan(target)
        store.records["d"] = PowerOperation("d", "p", profile.origin(), "pool", "vm", "Guest", PowerAction.TARGET, null, PowerPhase.VERIFYING, 1, 1,
            "step:vdi-a", "uuid-step:vdi-a", targetAction = TargetAction.SNAPSHOT_DELETE, targetUuid = "snap-1", targetName = "Before upgrade",
            steps = listOf(plan[0].copy(state = StepState.DONE, taskRef = "step:vdi-a"), plan[1], plan[2]))
        val result = service.reconcile(profile, store.load().single())
        assertEquals(PowerPhase.UNKNOWN, result.phase); assertTrue(result.message.contains("interrupted"))
        assertTrue(backend.sent.isEmpty())
    }
    @Test fun aServerRejectedStepStopsTheDeleteAsFailedWithAList() = runTest {
        val (store, backend, service) = deleteSetup()
        backend.stepFailures["vdi-b"] = RpcFailure("VDI_IN_USE")
        val result = service.executeTarget(profile, confirm(TargetAction.SNAPSHOT_DELETE, target))
        assertEquals(PowerPhase.FAILED, result.phase); assertFalse(result.unresolved)
        assertTrue(result.message, result.message.contains("Destroyed: “disk snapshot”") && result.message.contains("Failed: “disk snapshot” (VDI_IN_USE)") &&
            result.message.contains("Not sent: “Before upgrade”"))
        assertEquals(2, backend.sent.size)
        // A failed sub-step task is definitive too.
        val (_, other, otherService) = deleteSetup()
        other.stepTask = { step -> if (step.kind == StepKind.VM_DESTROY) TaskObservation("failure", 1.0, "OPERATION_NOT_ALLOWED") else TaskObservation("success", 1.0) }
        val failed = otherService.executeTarget(profile, confirm(TargetAction.SNAPSHOT_DELETE, target))
        assertEquals(PowerPhase.FAILED, failed.phase); assertTrue(failed.message.contains("OPERATION_NOT_ALLOWED"))
        // A send-time check that refuses a non-snapshot object sends nothing for it.
        val (_, refused, refusedService) = deleteSetup()
        refused.stepFailures["vdi-a"] = NotSent("“disk snapshot” is used by a VM that is not a snapshot.")
        val stopped = refusedService.executeTarget(profile, confirm(TargetAction.SNAPSHOT_DELETE, target))
        assertEquals(PowerPhase.FAILED, stopped.phase); assertTrue(stopped.message.startsWith("Not sent:"))
        assertEquals(listOf("VDI_DESTROY:vdi-a"), refused.sent)
    }
    @Test fun anAlreadyAbsentDiskIsRecordedAndNothingIsSentForIt() = runTest {
        val (store, backend, service) = deleteSetup()
        backend.absentUuids += "vdi-a"
        backend.put(target) // the snapshot itself is still readable for confirmation
        val result = service.executeTarget(profile, confirm(TargetAction.SNAPSHOT_DELETE, target))
        assertEquals(result.message, PowerPhase.SUCCEEDED, result.phase)
        assertEquals(listOf("VDI_DESTROY:vdi-b", "VM_DESTROY:snap-1"), backend.sent)
        assertEquals(listOf(StepState.ABSENT, StepState.DONE, StepState.DONE), store.load().single().steps!!.map { it.state })
    }
    @Test fun aReadFailureBeforeTheFirstStepSendsNothing() = runTest {
        val (store, backend, service) = deleteSetup()
        val confirmation = confirm(TargetAction.SNAPSHOT_DELETE, target)
        // The confirmation read succeeds; the first absence check then fails.
        var reads = 0
        val failing = object : PowerBackend {
            override suspend fun <T> powerConnection(profile: Profile, action: suspend PowerConnection.() -> T): T = action(object : PowerConnection by backend {
                override suspend fun absent(authority: String, step: OperationStep): Boolean { reads++; throw IOException("reset") }
            })
        }
        val result = PowerService(failing, store, { B2Fixtures.NOW }, {}, 2).executeTarget(profile, confirmation)
        assertEquals(PowerPhase.FAILED, result.phase); assertTrue(result.message.contains("No request was sent"))
        assertEquals(1, reads); assertTrue(backend.sent.isEmpty())
        assertFalse(store.load().single().unresolved)
    }

    // Host reboot and shutdown

    @Test fun memberRebootIsProvenOnlyByAChangedBootMarkerOnALiveHost() = runTest {
        val start = host(enabled = false)
        val (store, backend, service) = setup(start)
        val pending = service.executeTarget(profile, confirm(TargetAction.HOST_REBOOT, start))
        assertEquals(PowerPhase.UNKNOWN, pending.phase); assertEquals("1700000000.5", pending.beforeBoot)
        assertTrue(pending.message.contains("changed boot time"))
        backend.put(start.copy(live = false, bootMarker = "1700000900.0"))
        assertEquals(PowerPhase.UNKNOWN, service.reconcile(profile, store.load().single()).phase)
        backend.put(start.copy(bootMarker = "1700000900.0"))
        val verified = service.reconcile(profile, store.load().single())
        assertEquals(PowerPhase.SUCCEEDED, verified.phase); assertEquals("Host reboot verified: the host is live with a changed boot time.", verified.message)
        assertEquals(1, backend.sent.size)
    }
    @Test fun coordinatorRebootDropsTheConnectionAndStaysUnknownUntilReviewed() = runTest {
        val master = host(enabled = false, master = true, name = "Host A")
        val (store, backend, service) = setup(master)
        val dropping = object : PowerBackend {
            override suspend fun <T> powerConnection(profile: Profile, action: suspend PowerConnection.() -> T): T = action(object : PowerConnection by backend {
                override suspend fun submitTarget(authority: String, action: TargetAction, target: String, argument: String?): String =
                    backend.submitTarget(authority, action, target, argument).also { backend.readFailure = IOException("connection refused") }
            })
        }
        val pending = PowerService(dropping, store, { B2Fixtures.NOW }, {}, 2).executeTarget(profile, confirm(TargetAction.HOST_REBOOT, master))
        assertEquals(PowerPhase.UNKNOWN, pending.phase); assertTrue(pending.message, pending.message.contains("pool coordinator"))
        assertEquals("true", pending.marker?.get("master"))
        // While the coordinator is away, reconcile stays uncertain.
        assertEquals(PowerPhase.UNKNOWN, service.reconcile(profile, store.load().single()).phase)
        // Back with a changed boot marker, but the old task is gone: still never inferred from the marker alone.
        backend.readFailure = null
        backend.put(master.copy(bootMarker = "1700000900.0"))
        backend.taskLookupFailure = RpcFailure("HANDLE_INVALID")
        val after = service.reconcile(profile, store.load().single())
        assertEquals(PowerPhase.UNKNOWN, after.phase); assertTrue(after.message.contains("pool coordinator"))
        // Manual review keeps the UNKNOWN outcome and adds a separate resolution.
        val review = service.reviewTarget(profile, store.load().single())
        assertEquals("1700000900.0", (review.state as HostState).bootMarker)
        val closed = service.resolveTargetAfterReview(profile, review, true)
        assertEquals(PowerPhase.UNKNOWN, closed.phase); assertFalse(closed.unresolved)
        assertEquals("1700000900.0", closed.manualResolution?.bootTime)
        assertTrue(closed.message.contains("Original outcome remains unverified"))
        assertEquals("Closed · Outcome unverified", OperationHistory.status(closed))
        assertEquals(1, backend.sent.size)
    }
    @Test fun shutdownIsProvenByTheHostNoLongerBeingLive() = runTest {
        val start = host(enabled = false)
        val (_, backend, service) = setup(start, start.copy(live = false))
        assertEquals(PowerPhase.SUCCEEDED, service.executeTarget(profile, confirm(TargetAction.HOST_SHUTDOWN, start)).phase)
        val master = host(enabled = false, master = true)
        val (_, masterBackend, masterService) = setup(master)
        masterBackend.sendFailure = IOException("connection reset")
        val lost = masterService.executeTarget(profile, confirm(TargetAction.HOST_SHUTDOWN, master))
        assertEquals(PowerPhase.UNKNOWN, lost.phase); assertTrue(lost.message.contains("pool coordinator"))
        assertEquals(1, backend.sent.size); assertEquals(1, masterBackend.sent.size)
    }

    // Manual review

    @Test fun manualReviewFencesTheObjectAndKeepsTheOriginalOutcome() = runTest {
        val (store, backend, service) = setup(host())
        backend.sendFailure = IOException("lost")
        service.executeTarget(profile, confirm(TargetAction.HOST_DISABLE, host()))
        backend.put(host(busy = true))
        assertTrue(runCatching { service.reviewTarget(profile, store.load().single()) }.isFailure)
        backend.put(host(enabled = false))
        val review = service.reviewTarget(profile, store.load().single())
        assertTrue(runCatching { service.resolveTargetAfterReview(profile, review, false) }.isFailure)
        backend.put(host(enabled = true))
        assertTrue(runCatching { service.resolveTargetAfterReview(profile, review, true) }.isFailure)
        backend.put(host(enabled = false))
        assertTrue(runCatching { service.resolveTargetAfterReview(profile.copy(username = "x"), review, true) }.isFailure)
        assertTrue(runCatching { service.resolveTargetAfterReview(profile, review.copy(state = (review.state as HostState).copy(observedAt = B2Fixtures.NOW - 30_001)), true) }.isFailure)
        val closed = service.resolveTargetAfterReview(profile, review, true)
        assertEquals(PowerPhase.UNKNOWN, closed.phase); assertFalse(closed.unresolved)
        assertEquals("Disabled · live · 0 VMs", closed.manualResolution?.power)
        assertTrue(runCatching { service.resolveTargetAfterReview(profile, review, true) }.isFailure)
        assertEquals(1, backend.sent.size)
        // A repaired connection to the same pool may review (A2 rule); recovery still needs the original one.
        val repaired = profile.copy(certificateSha256 = "b".repeat(64))
        val (s2, b2, svc2) = setup(host())
        b2.sendFailure = IOException("lost")
        svc2.executeTarget(profile, confirm(TargetAction.HOST_DISABLE, host()))
        assertTrue(runCatching { svc2.reconcile(repaired, s2.load().single()) }.isFailure)
        assertNotNull(svc2.reviewTarget(repaired, s2.load().single()))
    }
    @Test fun reviewOfAnInterruptedDeleteListsItsSteps() = runTest {
        val (store, backend, service) = deleteSetup()
        backend.stepFailures["vdi-b"] = IOException("reset")
        service.executeTarget(profile, confirm(TargetAction.SNAPSHOT_DELETE, target))
        val review = service.reviewTarget(profile, store.load().single())
        assertTrue(review.state is SnapshotTarget)
        val closed = service.resolveTargetAfterReview(profile, review, true)
        assertTrue(closed.message, closed.message.contains("Destroyed: “disk snapshot”") && closed.message.contains("Not sent: “Before upgrade”"))
        assertFalse(store.load().single().unresolved)
        assertEquals(2, backend.sent.size)
    }
}
