package com.meteor.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.meteor.app.data.*
import java.text.DateFormat
import java.util.Date

@Composable
internal fun PoolActivityScreen(state: MeteorState, now: Long, refresh: () -> Unit, back: () -> Unit) {
    var alerts by rememberSaveable { mutableStateOf(false) }
    var activeOnly by rememberSaveable { mutableStateOf(true) }
    val activity = state.poolActivity
    val scopeKey = state.selectedProfile?.let { it.id + ":" + it.origin() } + ":" + activity?.authority
    LazyColumn(Modifier.fillMaxSize().testTag("activity-list"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Text("Pool activity", style = MaterialTheme.typography.headlineMedium)
            Text("Tasks and alerts currently retained by the server, including other clients. This is a read-only snapshot, not a complete audit log.",
                style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = back) { Text("Back to overview") }
                OutlinedButton(onClick = refresh, enabled = state.connected && !state.activityBusy && !state.loading && !ControlGates.operationExclusive(state)) { Text("Refresh activity") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !alerts, onClick = { alerts = false }, label = { Text("Tasks") })
                FilterChip(selected = alerts, onClick = { alerts = true }, label = { Text("Alerts") })
            }
        }
        if (state.activityBusy && activity == null) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        state.activityProblem?.let { problem -> item { Text(problem) } }
        if (activity != null) {
            item {
                val fresh = state.connected && state.activityProblem == null && now - activity.observedAt in 0..30_000
                Text("${if (fresh) "Server snapshot" else "Last-known snapshot"} · ${activityTime(activity.observedAt)}", style = MaterialTheme.typography.labelMedium)
            }
            if (alerts) {
                val section = activity.alerts
                if (section.issue != null) item { Text(section.issue.text) }
                else {
                    item { Text(if (section.total == MessageWindow.TOTAL_UNKNOWN) // inventory scale: the shared large-store fallback
                        "Showing the newest ${section.items.size} alerts of the last ${AlertMerge.FALLBACK_WINDOW_MILLIS / 86_400_000} days. The server's message store is larger than Meteor reads at once, so older alerts are not loaded."
                        else "Showing ${section.items.size} of ${section.total} retained alerts.", style = MaterialTheme.typography.bodySmall) }
                    if (section.items.isEmpty()) item { Text("No alerts reported.") }
                    items(section.items, key = { "alert:$scopeKey:${it.uuid}" }) { alert ->
                        var details by rememberSaveable { mutableStateOf(false) }
                        ElevatedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(alert.name, style = MaterialTheme.typography.titleMedium)
                                Text("${alert.objectClass.ifBlank { "Object" }} · ${alert.priority?.let { "Priority $it" } ?: "Priority unknown"}", style = MaterialTheme.typography.labelMedium)
                                Text(activityTime(alert.createdAt), style = MaterialTheme.typography.bodySmall)
                                TextButton(onClick = { details = !details }) { Text(if (details) "Hide alert details" else "Show alert details") }
                                if (details) {
                                    if (alert.objectUuid.isNotBlank()) Text("Object: ${alert.objectUuid}", style = MaterialTheme.typography.bodySmall)
                                    Text(alert.body.ifBlank { "No detail text reported." }, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            } else {
                val section = activity.tasks
                if (section.issue != null) item { Text(section.issue.text) }
                else {
                    val tasks = section.items.filter { !activeOnly || it.status in setOf("pending", "cancelling") }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = activeOnly, onClick = { activeOnly = true }, label = { Text("Active") })
                            FilterChip(selected = !activeOnly, onClick = { activeOnly = false }, label = { Text("All retained") })
                        }
                        Text("Loaded ${section.items.size} of ${section.total} tasks. Server task status does not verify a VM power outcome.", style = MaterialTheme.typography.bodySmall)
                    }
                    if (tasks.isEmpty()) item { Text(if (activeOnly) "No active tasks reported." else "No tasks reported.") }
                    items(tasks, key = { "task:$scopeKey:${it.uuid}" }) { task ->
                        ElevatedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(task.name, style = MaterialTheme.typography.titleMedium)
                                Text(when (task.status) {
                                    "pending" -> "In progress"
                                    "cancelling" -> "Cancellation in progress"
                                    "success" -> "Server reported success"
                                    "failure" -> "Server reported failure"
                                    "cancelled" -> "Server reported cancellation"
                                    else -> "Task status unknown"
                                }, style = MaterialTheme.typography.labelLarge)
                                Text("Created ${activityTime(task.createdAt)}", style = MaterialTheme.typography.bodySmall)
                                if (task.status in setOf("pending", "cancelling")) task.progress?.takeIf { it.isFinite() && it in 0.0..1.0 }?.let {
                                    LinearProgressIndicator(progress = { it.toFloat() }, modifier = Modifier.fillMaxWidth())
                                    Text("Estimated progress: ${(it * 100).toInt()}% · completion is determined by task status", style = MaterialTheme.typography.bodySmall)
                                }
                                task.errorCode?.let { Text("Error code: $it", style = MaterialTheme.typography.bodySmall) }
                            }
                        }
                    }
                }
            }
        } else if (!state.activityBusy && state.activityProblem == null) item { Text("Connect and refresh to read pool activity.") }
    }
}

private fun activityTime(value: Long?): String = value?.let {
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it))
} ?: "Time not reported"
