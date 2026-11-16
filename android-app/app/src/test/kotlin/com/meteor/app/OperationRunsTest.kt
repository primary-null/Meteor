package com.meteor.app

import com.meteor.app.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

/**
 * Stop watching: the run that holds `operationBusy`, the `operationWatching` flag that opens reads and browsing, and
 * the card's switch. The caller keeps `operationBusy` (and so every write gate) for the whole run.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OperationRunsTest {
    private val profile = Profile("p", "Lab", "https://test.invalid/", "tester", "secret", "a".repeat(64))
    private fun op(phase: PowerPhase, taskUuid: String? = "task-uuid") = PowerOperation("op", profile.id, profile.origin(), "pool", "vm", "Guest",
        PowerAction.SHUTDOWN, null, phase, 1, 1, "task-ref".takeIf { taskUuid != null }, taskUuid)

    private class Fixture {
        val state = MutableStateFlow(MeteorState(journalProblem = false, operationBusy = true))
        val published = mutableListOf<PowerOperation>()
        val runs = OperationRuns(state) { op -> published += op; state.update { it.copy(operation = op) } }
    }

    @Test fun watchingStartsOnlyOnceTheTaskUuidIsSavedAndEndsWithTheRun() = runTest {
        val f = Fixture(); val seen = mutableListOf<Boolean>()
        val result = f.runs.run { changed ->
            for (step in listOf(op(PowerPhase.PREPARED, null), op(PowerPhase.SUBMITTING, null), op(PowerPhase.TRACKING, null),
                op(PowerPhase.TRACKING), op(PowerPhase.VERIFYING), op(PowerPhase.SUBMITTING, null), op(PowerPhase.TRACKING))) {
                changed(step); seen += f.state.value.operationWatching
            }
            "done"
        }
        assertEquals("done", result)
        assertEquals(listOf(false, false, false, true, true, false, true), seen)
        assertFalse(f.state.value.operationWatching); assertFalse(f.state.value.operationStopping)
        assertEquals(7, f.published.size)
    }

    @Test fun stopIsOfferedOnlyWhileWatchingAndEndsTheRunQuietly() = runTest {
        val f = Fixture()
        val gate = CompletableDeferred<Unit>()
        val run = async {
            f.runs.run { changed ->
                f.runs.stop() // not yet watching: ignored
                assertFalse(f.state.value.operationStopping)
                changed(op(PowerPhase.TRACKING))
                // What PowerService does once its watch is stopped: save the record as still unverified, then throw.
                gate.await()
                if (currentCoroutineContext()[TrackingWatch]!!.stopRequested) {
                    changed(op(PowerPhase.UNKNOWN).copy(message = OperationWatch.stoppedMessage(op(PowerPhase.UNKNOWN))))
                    throw StopWatching()
                }
                "finished"
            }
        }
        runCurrent()
        assertTrue(ControlGates.canStopWatching(f.state.value))
        f.runs.stop(); runCurrent()
        assertTrue(f.state.value.operationStopping); assertFalse(ControlGates.canStopWatching(f.state.value))
        gate.complete(Unit); runCurrent()
        assertNull("a stop is not an error and not a result", run.await())
        assertFalse(f.state.value.operationWatching); assertFalse(f.state.value.operationStopping)
        assertTrue(f.state.value.operation!!.unresolved); assertNull(f.state.value.notice)
    }

    @Test fun otherFailuresAndCancellationStillReachTheCaller() = runTest {
        val f = Fixture()
        val failure = runCatching { f.runs.run<Unit> { changed -> changed(op(PowerPhase.TRACKING)); throw IOException("journal unreadable") } }
        assertTrue(failure.exceptionOrNull() is IOException)
        assertFalse(f.state.value.operationWatching)
        val job = launch { f.runs.run { changed -> changed(op(PowerPhase.TRACKING)); awaitCancellation() } }
        runCurrent(); assertTrue(f.state.value.operationWatching)
        job.cancel(); runCurrent()
        assertTrue(job.isCancelled); assertFalse(f.state.value.operationWatching)
    }

    @Test fun eachRunGetsItsOwnWatch() = runTest {
        val f = Fixture()
        var first: TrackingWatch? = null
        f.runs.run { changed -> first = currentCoroutineContext()[TrackingWatch]; changed(op(PowerPhase.TRACKING)) }
        first!!.stop() // a late stop of a finished run
        f.runs.run { _ -> assertFalse(currentCoroutineContext()[TrackingWatch]!!.stopRequested); assertNotSame(first, currentCoroutineContext()[TrackingWatch]) }
    }

    // --- the gates ---

    private fun watching(operation: PowerOperation = op(PowerPhase.TRACKING)) =
        MeteorState(journalProblem = false, connected = true, operationBusy = true, operationWatching = true, operation = operation)

    @Test fun onlyTheSendOrReviewWindowIsExclusive() {
        assertTrue(ControlGates.operationExclusive(MeteorState(operationBusy = true)))
        assertFalse(ControlGates.operationExclusive(watching()))
        assertFalse(ControlGates.operationExclusive(MeteorState()))
    }

    @Test fun writesStayLockedWhileWatching() {
        val state = watching()
        assertFalse(ControlGates.vmActionsReady(state)); assertFalse(ControlGates.hostPickerEnabled(state))
        // Even if the record view were momentarily resolved, the run itself still holds every write.
        assertFalse(ControlGates.vmActionsReady(state.copy(operation = null)))
    }

    @Test fun onlyTheRunningOperationsConnectionIsHeld() {
        val state = watching()
        assertTrue(ControlGates.identityHeld(state, profile.id))
        assertFalse(ControlGates.identityHeld(state, "other"))
        assertFalse(ControlGates.identityHeld(state, null))
        assertFalse(ControlGates.identityHeld(state.copy(operationBusy = false, operationWatching = false), profile.id)) // nothing running
    }

    @Test fun stopNeedsAWatchedOperationWithASavedTaskUuid() {
        assertTrue(ControlGates.canStopWatching(watching()))
        assertFalse(ControlGates.canStopWatching(watching().copy(operationStopping = true)))
        assertFalse(ControlGates.canStopWatching(watching().copy(operationWatching = false)))
        assertFalse(ControlGates.canStopWatching(watching(op(PowerPhase.TRACKING, taskUuid = null))))
        assertFalse(ControlGates.canStopWatching(watching(op(PowerPhase.SUCCEEDED))))
        assertFalse(ControlGates.canStopWatching(watching().copy(operationBusy = false)))
    }
}
