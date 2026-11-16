package com.meteor.app

import com.meteor.app.data.OperationWatch
import com.meteor.app.data.PowerOperation
import com.meteor.app.data.StopWatching
import com.meteor.app.data.TrackingWatch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/**
 * Stop watching: the journaled run (send and observation, or a status check) that holds `operationBusy`, and its
 * "Stop watching" switch. No Android dependency, so the rules are tested on the JVM.
 *
 * [run] marks [MeteorState.operationWatching] from every published record, so reads and browsing open up only once
 * the operation is sent and known by its saved task UUID. [stop] ends only the observation (see [TrackingWatch]); the
 * record is left unresolved as still unverified, and "Check status" resumes it exactly as recovery after a restart does.
 * `operationBusy` itself is owned by the caller, which keeps every write gate unchanged.
 */
internal class OperationRuns(
    private val state: MutableStateFlow<MeteorState>,
    private val publish: suspend (PowerOperation) -> Unit,
) {
    @Volatile private var watch: TrackingWatch? = null

    /**
     * Runs [block] (a [com.meteor.app.data.PowerService] call) with a fresh watch and a publish callback that keeps
     * [MeteorState.operationWatching] in step. Returns null when the person stopped watching; every other failure,
     * including the run's own cancellation, propagates to the caller unchanged.
     */
    suspend fun <T> run(block: suspend (changed: suspend (PowerOperation) -> Unit) -> T): T? {
        val current = TrackingWatch()
        watch = current
        try {
            return withContext(current) {
                block { operation ->
                    publish(operation)
                    val watching = OperationWatch.watchable(operation)
                    state.update { if (it.operationWatching == watching) it else it.copy(operationWatching = watching) }
                }
            }
        } catch (stopped: StopWatching) {
            // The record was already saved and published as still unverified; there is nothing else to report.
            return null
        } finally {
            if (watch === current) watch = null
            state.update { it.copy(operationWatching = false, operationStopping = false) }
        }
    }

    /** The card's "Stop watching". Ignored unless the running operation is being observed by its saved task UUID. */
    fun stop() {
        if (!ControlGates.canStopWatching(state.value)) return
        val current = watch ?: return
        state.update { it.copy(operationStopping = true) }
        current.stop()
    }
}
