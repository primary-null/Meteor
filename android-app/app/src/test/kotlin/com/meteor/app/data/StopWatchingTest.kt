package com.meteor.app.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

/**
 * Stop watching: ending the observation of a sent operation at every point of the tracking loop. A stop never sends,
 * never cancels a server task and never records an outcome; the record stays unresolved as still unverified, and
 * "Check status" (reconcile) resumes it by the saved task UUID exactly as recovery after an app restart does.
 */
class StopWatchingTest {
    private val profile = Profile("p", "Lab", "https://test.invalid/", "tester", "secret", "a".repeat(64))
    private fun vm(power: String = "Running") = VmDetails("pool", "vm", "Guest", "", power, null, 1, 1024,
        emptyList(), emptyList(), false, null, emptyList(), "20260101T10:00:00Z", setOf("clean_shutdown", "start"), false, 1000)

    /** Every write is encoded and every read decoded, as OperationJournal does. */
    private class Store : OperationStore {
        val rows = linkedMapOf<String, Map<String, Any?>>()
        override suspend fun load() = rows.values.map(JournalCodec::decode)
        override suspend fun put(operation: PowerOperation) { rows[operation.id] = JournalCodec.encode(operation) }
        fun single() = rows.values.single().let(JournalCodec::decode)
    }

    private class Backend(var state: VmDetails) : PowerBackend, PowerConnection {
        var after: VmDetails? = null
        var sends = 0; var observations = 0; var stateReads = 0; var taskLookups = 0
        /** Scripted task observations; the last one repeats. */
        var tasks: List<TaskObservation> = listOf(TaskObservation("pending", 0.25))
        var onObserve: suspend (Int) -> Unit = {}
        var onState: suspend (Int) -> Unit = {}
        var onSubmit: () -> Unit = {}
        override suspend fun <T> powerConnection(profile: Profile, action: suspend PowerConnection.() -> T): T = action(this)
        override suspend fun details(authority: String, uuid: String) = state
        override suspend fun vmState(authority: String, uuid: String): VmDetails? {
            onState(++stateReads)
            return if (sends > 0) after ?: state else state
        }
        override suspend fun submit(authority: String, uuid: String, action: PowerAction): String { sends++; onSubmit(); return "task-ref" }
        override suspend fun taskUuid(reference: String) = "task-uuid"
        override suspend fun taskReference(uuid: String): String { taskLookups++; assertEquals("task-uuid", uuid); return "task-ref" }
        override suspend fun observeTask(reference: String): TaskObservation {
            val index = observations++
            onObserve(index + 1)
            return tasks[minOf(index, tasks.lastIndex)]
        }
    }

    /** The real per-kind budget and polling intervals, on the test's virtual clock. */
    private fun service(backend: Backend, store: Store) = PowerService(backend, store, { 1000 }, { delay(it) })
    private fun shutdown() = PowerConfirmation(profile.id, profile.origin(), vm(), PowerAction.SHUTDOWN)

    /** Runs [block] with [watch] in its context and reports "stopped" for [StopWatching], or the result's phase. */
    private fun TestScope.watched(watch: TrackingWatch, block: suspend () -> PowerOperation) =
        async { try { withContext(watch) { block() }.phase.name } catch (stopped: StopWatching) { "stopped" } }

    private fun assertStoppedAndPending(store: Store) {
        val saved = store.single()
        assertTrue(saved.unresolved); assertEquals(PowerPhase.UNKNOWN, saved.phase); assertNull(saved.manualResolution)
        assertEquals(OperationWatch.stoppedMessage(saved), saved.message)
        assertTrue(saved.message.contains("Nothing was cancelled on the server and nothing will be resent"))
        assertEquals("task-ref", saved.taskRef); assertEquals("task-uuid", saved.taskUuid)
        assertEquals(PowerAction.SHUTDOWN, saved.action); assertEquals(profile.origin(), saved.origin); assertEquals("vm", saved.vmUuid)
    }

    /** Check status after a stop: observation resumes by the saved task UUID, nothing is resent, and only the read-back proves it. */
    private suspend fun assertCheckStatusResumes(store: Store, backend: Backend) {
        backend.onObserve = {}; backend.onState = {}
        backend.tasks = listOf(TaskObservation("success", 1.0)); backend.after = vm("Halted")
        val resumed = service(backend, store).reconcile(profile, store.single())
        assertEquals(resumed.message, PowerPhase.SUCCEEDED, resumed.phase)
        assertEquals(1, backend.sends); assertEquals(1, backend.taskLookups)
    }

    @Test fun stopMidReadLeavesTheRecordPendingAndCheckStatusResumesByTaskUuid() = runTest {
        val store = Store(); val backend = Backend(vm()); val watch = TrackingWatch()
        val gate = CompletableDeferred<Unit>()
        backend.onObserve = { if (it == 3) gate.await() } // the third read is in flight
        val run = watched(watch) { service(backend, store).execute(profile, shutdown()) }
        advanceUntilIdle()
        assertEquals(3, backend.observations); assertEquals(PowerPhase.TRACKING, store.single().phase)
        watch.stop(); advanceUntilIdle()
        assertEquals("stopped", run.await())
        assertStoppedAndPending(store)
        assertEquals(3, backend.observations); assertEquals(1, backend.sends) // no further read, nothing resent
        assertCheckStatusResumes(store, backend)
    }

    @Test fun stopBetweenObservationsEndsAtTheWait() = runTest {
        val store = Store(); val backend = Backend(vm()); val watch = TrackingWatch()
        val published = mutableListOf<PowerOperation>()
        val run = watched(watch) {
            service(backend, store).execute(profile, shutdown()) { op -> published += op; if (op.progress != null) watch.stop() }
        }
        advanceUntilIdle()
        assertEquals("stopped", run.await())
        assertEquals(1, backend.observations)
        assertStoppedAndPending(store)
        assertEquals(store.single(), published.last().copy(updatedAt = store.single().updatedAt)) // the card shows the saved record
        assertNull(published.last().progress)
        assertCheckStatusResumes(store, backend)
    }

    @Test fun stopAfterTaskSuccessBeforeTheReadBackNeverReportsSuccess() = runTest {
        val store = Store(); val backend = Backend(vm()); val watch = TrackingWatch()
        backend.tasks = listOf(TaskObservation("success", 1.0))
        backend.onState = { watch.stop(); yield() } // the read-back is interrupted
        val run = watched(watch) { service(backend, store).execute(profile, shutdown()) }
        advanceUntilIdle()
        assertEquals("stopped", run.await())
        assertStoppedAndPending(store)
        assertCheckStatusResumes(store, backend)
    }

    @Test fun aResultProvenJustBeforeTheStopIsKept() = runTest {
        val store = Store(); val backend = Backend(vm()); val watch = TrackingWatch()
        backend.tasks = listOf(TaskObservation("success", 1.0)); backend.after = vm("Halted")
        backend.onState = { watch.stop() } // the read-back still completes and proves the result
        val run = watched(watch) { service(backend, store).execute(profile, shutdown()) }
        advanceUntilIdle()
        assertEquals("stopped", run.await())
        val saved = store.single()
        assertEquals(PowerPhase.SUCCEEDED, saved.phase); assertTrue(saved.message, saved.message.endsWith("verified: Halted."))
    }

    @Test fun aStopDuringTheSendNeverLosesTheTaskTicket() = runTest {
        val store = Store(); val backend = Backend(vm()); val watch = TrackingWatch()
        backend.onSubmit = { watch.stop() }
        val run = watched(watch) { service(backend, store).execute(profile, shutdown()) }
        advanceUntilIdle()
        assertEquals("stopped", run.await())
        assertEquals(0, backend.observations) // the send finished and its ticket was saved before observation ended
        assertStoppedAndPending(store)
        assertCheckStatusResumes(store, backend)
    }

    @Test fun cancellingTheRunIsNotAStop() = runTest {
        val store = Store(); val backend = Backend(vm()); val watch = TrackingWatch()
        backend.onObserve = { if (it == 2) CompletableDeferred<Unit>().await() }
        val run = watched(watch) { service(backend, store).execute(profile, shutdown()) }
        advanceUntilIdle()
        run.cancel(); advanceUntilIdle()
        assertTrue(run.isCancelled)
        val saved = store.single()
        assertEquals(PowerPhase.TRACKING, saved.phase); assertFalse(saved.message.contains("Stopped watching"))
    }

    @Test fun aWatchThatIsNeverStoppedChangesNothing() = runTest {
        val store = Store(); val backend = Backend(vm()); val watch = TrackingWatch()
        backend.tasks = listOf(TaskObservation("pending", 0.5), TaskObservation("success", 1.0)); backend.after = vm("Halted")
        val run = watched(watch) { service(backend, store).execute(profile, shutdown()) }
        advanceUntilIdle()
        assertEquals("SUCCEEDED", run.await())
        watch.stop() // after the run: nothing left to stop
        assertEquals(PowerPhase.SUCCEEDED, store.single().phase)
    }

    @Test fun checkStatusCanBeStoppedAndResumedAgain() = runTest {
        val store = Store(); val backend = Backend(vm())
        store.put(PowerOperation("op", profile.id, profile.origin(), "pool", "vm", "Guest", PowerAction.SHUTDOWN, null,
            PowerPhase.TRACKING, 100, 100, "task-ref", "task-uuid"))
        backend.sends = 1 // sent before the app restarted
        val watch = TrackingWatch()
        backend.onObserve = { if (it == 2) watch.stop() }
        val run = watched(watch) { service(backend, store).reconcile(profile, store.single()) }
        advanceUntilIdle()
        assertEquals("stopped", run.await())
        assertStoppedAndPending(store)
        backend.taskLookups = 0
        assertCheckStatusResumes(store, backend)
    }

    // --- what the card says ---

    private fun op(phase: PowerPhase, taskUuid: String? = "task-uuid", action: PowerAction = PowerAction.SHUTDOWN, createdAt: Long = 0,
        target: TargetAction? = null, steps: List<OperationStep>? = null) =
        PowerOperation("op", profile.id, profile.origin(), "pool", "vm", "Guest", if (target != null) PowerAction.TARGET else action, null, phase,
            createdAt, createdAt, "task-ref".takeIf { taskUuid != null }, taskUuid, targetAction = target, targetUuid = target?.let { "target" }, steps = steps)

    @Test fun onlyASentTaskKnownByItsUuidCanBeStopped() {
        assertTrue(OperationWatch.watchable(op(PowerPhase.TRACKING)))
        assertTrue(OperationWatch.watchable(op(PowerPhase.VERIFYING)))
        assertFalse(OperationWatch.watchable(op(PowerPhase.TRACKING, taskUuid = null))) // ticket not yet saved
        for (phase in listOf(PowerPhase.PREPARED, PowerPhase.SUBMITTING, PowerPhase.UNKNOWN, PowerPhase.SUCCEEDED, PowerPhase.FAILED))
            assertFalse("$phase", OperationWatch.watchable(op(phase)))
        assertTrue(OperationWatch.stoppable(op(PowerPhase.UNKNOWN)))
        assertFalse(OperationWatch.stoppable(op(PowerPhase.SUBMITTING)))
        assertFalse(OperationWatch.stoppable(op(PowerPhase.PREPARED, taskUuid = null)))
        assertFalse(OperationWatch.stoppable(op(PowerPhase.SUCCEEDED)))
    }

    @Test fun longKindsSayTheyTakeAWhileAndHowLongMeteorWatches() {
        assertNull(OperationWatch.longWait(op(PowerPhase.TRACKING, action = PowerAction.PAUSE)))
        assertNull(OperationWatch.longWait(op(PowerPhase.TRACKING, action = PowerAction.HARD_REBOOT)))
        assertEquals("This can take a while; Meteor watches for up to 30 min. You can keep using the app meanwhile.",
            OperationWatch.longWait(op(PowerPhase.TRACKING, action = PowerAction.POOL_MIGRATE)))
        assertTrue(OperationWatch.longWait(op(PowerPhase.TRACKING, target = TargetAction.HOST_EVACUATE))!!.contains("up to 60 min"))
        assertNull(OperationWatch.longWait(op(PowerPhase.TRACKING, target = TargetAction.CD_INSERT)))
    }

    @Test fun elapsedTimeIsCountedFromTheJournaledRecord() {
        val sent = op(PowerPhase.TRACKING, createdAt = 1_000_000)
        assertEquals("Sent less than a minute ago.", OperationWatch.elapsed(sent, 1_030_000))
        assertEquals("Sent 14 min ago.", OperationWatch.elapsed(sent, 1_000_000 + 14 * 60_000 + 5_000))
        assertEquals("Sent 1 h 2 min ago.", OperationWatch.elapsed(sent, 1_000_000 + 62 * 60_000))
        assertEquals("Sent less than a minute ago.", OperationWatch.elapsed(sent, 0)) // a clock step back never goes negative
    }

    @Test fun aStoppedDeleteSaysItsRemainingSubStepsAreNotSent() {
        val step = OperationStep(StepKind.VDI_DESTROY, "vdi-a", "disk snapshot", StepState.TRACKING)
        val delete = op(PowerPhase.TRACKING, target = TargetAction.SNAPSHOT_DELETE,
            steps = listOf(step, step.copy(uuid = "snap-1", kind = StepKind.VM_DESTROY, state = StepState.PENDING)))
        assertTrue(OperationWatch.stoppedMessage(delete).contains("The remaining sub-steps will not be sent."))
        assertFalse(OperationWatch.stoppedMessage(op(PowerPhase.TRACKING)).contains("sub-steps"))
    }
}
