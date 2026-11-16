package com.meteor.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.meteor.app.data.*
import java.text.DateFormat
import java.util.Date

private val dashed = floatArrayOf(14f, 10f)

@Composable
private fun seriesColor(index: Int): Color = when (index) {
    0 -> MaterialTheme.colorScheme.primary
    1 -> MaterialTheme.colorScheme.onSurface.copy(alpha = .72f)
    else -> MaterialTheme.colorScheme.outline
}

/**
 * One chart card. Series share the axis, are told apart by label, line style and colour, and a hole in the data is a
 * gap in the line, never a zero. The y axis starts at zero.
 */
@Composable
internal fun HistoryChartCard(title: String, series: List<HistorySeries>, timestamps: List<Long>, stepSeconds: Long, range: HistoryRange) {
    val unit = series.first().unit
    val axisMax = HistoryChartMath.axisMax(unit, series.map { it.values })
    val colors = series.indices.map { seriesColor(it) }
    val grid = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f)
    val summary = series.joinToString("; ") { "${it.label} latest ${HistoryFormat.value(it.values.lastOrNull() ?: Double.NaN, it.unit)}" }
    DepthPanel {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Column(Modifier.height(112.dp).width(64.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Text(HistoryFormat.value(axisMax, unit), style = MaterialTheme.typography.labelSmall)
                Text(HistoryFormat.value(0.0, unit), style = MaterialTheme.typography.labelSmall)
            }
            Canvas(Modifier.weight(1f).height(112.dp).semantics {
                contentDescription = "$title history over ${range.label}. $summary. Gaps in a line mean the server had no data."
            }) {
                val inset = 4.dp.toPx()
                val top = inset
                val bottom = size.height - inset
                listOf(0f, .5f, 1f).forEach { fraction ->
                    val y = bottom - (bottom - top) * fraction
                    drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
                }
                if (timestamps.isEmpty()) return@Canvas
                val first = timestamps.first()
                val span = (timestamps.last() - first).coerceAtLeast(1).toDouble()
                fun point(index: Int, value: Double) = Offset(
                    ((timestamps[index] - first) / span * size.width).toFloat(),
                    (bottom - (value / axisMax).coerceIn(0.0, 1.0) * (bottom - top)).toFloat())
                series.forEachIndexed { slot, line ->
                    val stroke = Stroke(2.dp.toPx(), pathEffect = if (slot == 0) null else PathEffect.dashPathEffect(dashed))
                    HistoryChartMath.runs(timestamps, line.values, stepSeconds).forEach { run ->
                        if (run.first == run.last) drawCircle(colors[slot], 2.5.dp.toPx(), point(run.first, line.values[run.first]))
                        else {
                            val path = Path()
                            for (i in run) point(i, line.values[i]).let { if (i == run.first) path.moveTo(it.x, it.y) else path.lineTo(it.x, it.y) }
                            drawPath(path, colors[slot], style = stroke)
                        }
                    }
                }
            }
        }
        if (timestamps.isNotEmpty()) {
            val format = if (range == HistoryRange.ONE_WEEK) DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                else DateFormat.getTimeInstance(DateFormat.SHORT)
            Row(Modifier.fillMaxWidth().padding(start = 64.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(format.format(Date(timestamps.first() * 1000)), style = MaterialTheme.typography.labelSmall)
                Text(format.format(Date(timestamps.last() * 1000)), style = MaterialTheme.typography.labelSmall)
            }
            // The axis always spans the window; say so when the server recorded nothing for its first part.
            HistoryChartMath.dataStartsLate(timestamps, series.map { it.values }, stepSeconds)?.let { start ->
                Text("The server recorded nothing before ${format.format(Date(start * 1000))}, so the line starts there.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        series.forEachIndexed { slot, line ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Canvas(Modifier.width(28.dp).height(10.dp)) {
                    drawLine(colors[slot], Offset(0f, size.height / 2), Offset(size.width, size.height / 2), strokeWidth = 2.dp.toPx(),
                        pathEffect = if (slot == 0) null else PathEffect.dashPathEffect(dashed))
                }
                val bounds = HistoryChartMath.finiteBounds(line.values)
                Column {
                    Text("${line.label}: ${HistoryFormat.value(line.values.lastOrNull() ?: Double.NaN, line.unit)}", style = MaterialTheme.typography.bodySmall)
                    Text(if (bounds == null) "No data in this window"
                        else "Range ${HistoryFormat.value(bounds.first, line.unit)} – ${HistoryFormat.value(bounds.second, line.unit)} · server unit ${line.declaredUnit}",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
