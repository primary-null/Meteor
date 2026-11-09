package com.meteor.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.meteor.app.data.*
import java.text.DateFormat
import java.util.Date
import java.util.Locale

internal fun metricText(value: Double?, unit: String): String {
    if (value == null || !value.isFinite()) return "Unavailable"
    fun number(number: Double) = String.format(Locale.getDefault(), "%.3g", number)
    fun memory(bytes: Double): String = when {
        !bytes.isFinite() -> number(value) + " " + unit
        bytes >= 1L.shl(30) -> number(bytes / 1L.shl(30)) + " GiB"
        bytes >= 1L.shl(20) -> number(bytes / 1L.shl(20)) + " MiB"
        bytes >= 1024 -> number(bytes / 1024) + " KiB"
        else -> number(bytes) + " B"
    }
    return when (unit.lowercase(Locale.ROOT)) {
        "fraction", "ratio" -> if ((value * 100).isFinite()) number(value * 100) + "%" else "Unavailable"
        "%", "percent" -> number(value) + "%"
        "b", "bytes" -> memory(value)
        "kib", "kibibytes" -> memory(value * 1024)
        "b/s", "bytes/s", "bytes per second" -> memory(value) + "/s"
        else -> (number(value) + " " + unit).trim()
    }
}

@Composable
internal fun PerformanceScreen(state: MeteorState, now: Long, refresh: () -> Unit, back: () -> Unit) {
    val target = state.metricTarget
    val latest = state.metricSamples.lastOrNull()
    LazyColumn(Modifier.fillMaxSize().testTag("performance-list"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Text("Live performance", style = MaterialTheme.typography.headlineMedium)
            Text(target?.name ?: "Choose a VM or host", style = MaterialTheme.typography.titleMedium)
            Text("Up to five minutes collected while this view is open. Gaps mean samples are unavailable; units come from the server.",
                style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = back) { Text("Back") }
                OutlinedButton(onClick = refresh, enabled = target != null && state.connected && !state.metricsBusy && !state.loading && !state.operationBusy) { Text("Refresh metrics") }
            }
        }
        if (state.metricsBusy && latest == null) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        state.metricsProblem?.let { problem -> item { Text(problem) } }
        if (latest != null) {
            item {
                val fresh = state.connected && state.metricsProblem == null && now - latest.observedAt in 0..15_000
                Text("${if (fresh) "Latest sample" else "Last-known sample"} · ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(latest.observedAt))}",
                    style = MaterialTheme.typography.labelMedium)
                latest.note?.let { Text(it) }
                if (latest.readings.isNotEmpty()) Text("Showing ${latest.readings.size} of ${latest.advertisedCount} advertised sources.", style = MaterialTheme.typography.bodySmall)
            }
            items(latest.readings, key = { it.name }) { reading ->
                MetricCard(reading, state.metricSamples)
            }
        } else if (!state.metricsBusy && state.metricsProblem == null) item { Text("Connect and refresh to collect metric samples.") }
    }
}

@Composable
private fun MetricCard(reading: MetricReading, snapshots: List<MetricSnapshot>) {
    val ink = MaterialTheme.colorScheme.primary
    val points = snapshots.map { snapshot ->
        snapshot.observedAt to snapshot.readings.firstOrNull { it.name == reading.name && it.unit == reading.unit }?.value
    }
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val group = if (reading.group == MetricGroup.CPU) "CPU" else reading.group.name.lowercase().replaceFirstChar { it.uppercase() }
            Text("$group · ${reading.label}", style = MaterialTheme.typography.labelLarge)
            Text(metricText(reading.value, reading.unit), style = MaterialTheme.typography.titleLarge)
            reading.unavailable?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            val values = points.mapNotNull { it.second?.takeIf { value -> value.isFinite() } }
            if (values.isNotEmpty()) Text("Chart range: ${metricText(values.min(), reading.unit)} – ${metricText(values.max(), reading.unit)}",
                style = MaterialTheme.typography.bodySmall)
            Canvas(Modifier.fillMaxWidth().height(60.dp).semantics { contentDescription = "Recent reported samples for ${reading.label}; missing values are gaps" }) {
                if (values.isNotEmpty()) {
                    val low = values.min(); val high = values.max()
                    val range = (high - low).takeIf { it > 0 && it.isFinite() } ?: 1.0
                    val start = points.first().first
                    val duration = (points.last().first - start).coerceAtLeast(1).toDouble()
                    fun position(point: Pair<Long, Double?>): Offset {
                        val level = if (high > low) (point.second!! - low) / range else 0.5
                        return Offset(((point.first - start) / duration * size.width).toFloat(),
                            (size.height - 4 - level * (size.height - 8)).toFloat())
                    }
                    points.zipWithNext().forEach { (a, b) ->
                        if (a.second?.isFinite() == true && b.second?.isFinite() == true && b.first - a.first in 1..15_000)
                            drawLine(ink, position(a), position(b), strokeWidth = 2.dp.toPx())
                    }
                    points.lastOrNull()?.takeIf { it.second?.isFinite() == true }?.let { drawCircle(ink, 3.dp.toPx(), position(it)) }
                }
            }
        }
    }
}
