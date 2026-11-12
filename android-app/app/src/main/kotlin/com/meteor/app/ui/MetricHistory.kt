package com.meteor.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.meteor.app.MeteorState
import com.meteor.app.data.*

/**
 * Wraps the live performance page with a range selector. Live stays the default and is rendered by [live]; choosing a
 * range swaps in server history. Polling for either view is driven by the shell's existing visible-page tick.
 */
@Composable
fun PerformanceWithHistory(state: MeteorState, now: Long, back: () -> Unit, live: @Composable () -> Unit) {
    val depth = LocalDepthUi.current
    if (depth == null) { live(); return }
    val target = state.metricTarget
    val history = state.depth.history
    val mine = history.target == target && target != null
    val range = history.range.takeIf { mine }
    Column(Modifier.fillMaxSize()) {
        FlowRow(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = range == null, onClick = { depth.actions.selectRange(null) }, enabled = target != null && depth.connected,
                label = { Text("Live") })
            HistoryRange.entries.forEach { choice ->
                FilterChip(selected = range == choice, onClick = { depth.actions.selectRange(choice) }, enabled = target != null && depth.connected,
                    label = { Text(choice.label) })
            }
        }
        if (range == null) history.unavailable?.takeIf { mine }?.let { reason ->
            Text(reason, Modifier.padding(horizontal = 20.dp, vertical = 6.dp), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary)
        }
        Box(Modifier.weight(1f)) {
            if (range == null) live() else HistoryScreen(state, depth, range, now, back)
        }
    }
}

@Composable
private fun HistoryScreen(state: MeteorState, depth: DepthUi, range: HistoryRange, now: Long, back: () -> Unit) {
    val history = state.depth.history
    val snapshot = history.snapshot?.takeIf { it.target == state.metricTarget && it.range == range }
    LazyColumn(Modifier.fillMaxSize().testTag("history-list"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Text("History · ${range.label}", style = MaterialTheme.typography.headlineMedium)
            Text(state.metricTarget?.name.orEmpty(), style = MaterialTheme.typography.titleMedium)
            Text("Averages from the server's round-robin archives, one point every ${stepText(range.stepSeconds)}. Gaps mean the server had no sample; " +
                "values are never filled in. Units are the metric's documented server units.", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = back) { Text("Back") }
                OutlinedButton(onClick = depth.actions.refreshHistory, enabled = depth.canRead && !history.busy) { Text("Refresh history") }
            }
        }
        if (history.busy && snapshot == null) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        history.problem?.let { problem -> item { Text(problem) } }
        if (snapshot != null) {
            item {
                val fresh = depth.connected && history.problem == null && now - snapshot.observedAt in 0..(range.pollSeconds * 3000)
                Text("${if (fresh) "Updated" else "Last-known data from"} ${time(snapshot.observedAt)} · ${snapshot.timestamps.size} points",
                    style = MaterialTheme.typography.labelMedium)
                snapshot.notes.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            val cards = snapshot.series.groupBy { it.card }
            items(cards.entries.toList(), key = { it.key }) { (title, series) ->
                HistoryChartCard(title, series, snapshot.timestamps, snapshot.stepSeconds, range)
            }
        } else if (!history.busy && history.problem == null) item { Text("Reading history…") }
    }
}

private fun stepText(seconds: Long) = when {
    seconds % 3600 == 0L -> "${seconds / 3600} hour"
    seconds % 60 == 0L -> "${seconds / 60} minute"
    else -> "$seconds seconds"
}
