package com.meteor.app.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.meteor.app.ControlGates
import com.meteor.app.MeteorState
import com.meteor.app.data.OperationWatch
import com.meteor.app.data.PowerOperation
import com.meteor.app.data.PowerPhase
import com.meteor.app.data.TrackingPolicy

/** Journaled operation hardening: the server task's estimate while Meteor waits for it. Never shown as proof. */
@Composable internal fun OperationProgress(operation: PowerOperation) {
    if (!operation.unresolved || operation.phase != PowerPhase.TRACKING) return
    val progress = TrackingPolicy.shownProgress(operation.progress) ?: return
    LinearProgressIndicator(progress = { progress.toFloat() }, modifier = Modifier.fillMaxWidth())
    Text("Server estimate: ${(progress * 100).toInt()}% · the result is verified by reading the state afterwards",
        style = MaterialTheme.typography.bodySmall)
}

/**
 * Stop watching: while a sent operation is observed by its saved task UUID, how long ago it was sent, what to expect
 * for a long kind, and the switch that ends only the observation. [now] is the shell's five-second tick.
 */
@Composable internal fun OperationWatching(operation: PowerOperation, state: MeteorState, now: Long, stop: () -> Unit) {
    if (!state.operationBusy || !state.operationWatching || !OperationWatch.watchable(operation)) return
    Text(OperationWatch.elapsed(operation, maxOf(now, operation.updatedAt)), style = MaterialTheme.typography.bodySmall)
    OperationWatch.longWait(operation)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    OutlinedButton(onClick = stop, enabled = ControlGates.canStopWatching(state)) {
        Text(if (state.operationStopping) "Stopping…" else "Stop watching")
    }
    Text(OperationWatch.STOP_HINT, style = MaterialTheme.typography.bodySmall)
}
