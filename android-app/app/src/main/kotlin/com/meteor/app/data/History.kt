package com.meteor.app.data

import java.util.Locale

/** The three RRD archives that suit a phone: 5 s steps for 10 minutes, 1 min for 2 hours, 1 h for 1 week. */
enum class HistoryRange(val label: String, val spanSeconds: Long, val stepSeconds: Long, val pollSeconds: Long) {
    TEN_MINUTES("10 minutes", 600, 5, 10),
    TWO_HOURS("2 hours", 7_200, 60, 60),
    ONE_WEEK("1 week", 604_800, 3_600, 600);

    /**
     * `start` and `interval` for `/rrd_updates`. The first request covers the span; a later one only re-reads from two
     * steps before the newest stored row so that a still-settling last row is replaced.
     *
     * The server picks the archive from `start`, not from `interval`: it answers from the finest archive whose oldest
     * row is not newer than `start`, otherwise from the next coarser one. The archives hold exactly the span (120 rows
     * of 5 s, 120 of 60 s, 168 of 1 h), so a `start` aligned down to the step lies one row before the finest
     * archive's oldest row and gets the coarser one (10, 2 and 7 rows on XCP-ng 8.3; METEOR-LIVE-FINDINGS D6). The first
     * request therefore starts [INSIDE_STEPS] steps inside the span, which the server answered with the requested step
     * every time, for every phase of the step. The window is two steps shorter than the span.
     */
    fun query(serverNowSeconds: Long, lastKnownSeconds: Long? = null): HistoryQuery {
        val full = (alignDown(serverNowSeconds) - spanSeconds + INSIDE_STEPS * stepSeconds).coerceAtLeast(0)
        val start = if (lastKnownSeconds == null) full else maxOf(full, alignDown((lastKnownSeconds - 2 * stepSeconds).coerceAtLeast(0)))
        return HistoryQuery(start, stepSeconds)
    }
    private fun alignDown(seconds: Long) = seconds / stepSeconds * stepSeconds
    companion object { const val INSIDE_STEPS = 2L }
}
data class HistoryQuery(val startSeconds: Long, val intervalSeconds: Long)

enum class HistoryGroup(val title: String) { CPU("CPU"), LOAD("Load"), MEMORY("Memory"), NETWORK("Network"), DISK("Disk") }
enum class HistoryUnit { FRACTION, BYTES, BYTES_PER_SECOND, LOAD }

/** [card] groups series that share a chart. [declaredUnit] is the unit the RRD metric is documented in. */
data class HistorySeries(val card: String, val id: String, val label: String, val group: HistoryGroup,
    val unit: HistoryUnit, val declaredUnit: String, val values: List<Double>)
data class HistorySnapshot(
    val target: MetricTarget, val range: HistoryRange, val stepSeconds: Long, val timestamps: List<Long>,
    val series: List<HistorySeries>, val observedAt: Long, val serverTimeSeconds: Long? = null,
    val notes: List<String> = emptyList(),
)

/** The read cannot be served from this connection's RRD endpoint; the live source stays available. */
class HistoryUnavailable(val reason: String) : Exception(reason)

object HistoryRouting {
    /** RRD data lives on each host. Only the pool coordinator is reachable under the saved certificate pin. */
    fun hostReason(hostName: String, isCoordinator: Boolean): String? =
        if (isCoordinator) null else "$hostName is a pool member. History is read over this connection's pinned certificate, " +
            "so it is only available for the pool coordinator. Showing live values instead."

    fun vmReason(power: String, residentRef: String?, coordinatorRef: String, residentName: String?): String? = when {
        power != "Running" -> "History needs a running VM. Showing the live view instead."
        residentRef.isNullOrBlank() || residentRef == "OpaqueRef:NULL" -> "The host running this VM is unknown. Showing live values instead."
        residentRef != coordinatorRef -> "This VM runs on ${residentName?.takeIf { it.isNotBlank() } ?: "a pool member"}, not the pool coordinator. " +
            "History is read over this connection's pinned certificate, so it is only available for VMs on the coordinator. Showing live values instead."
        else -> null
    }
}

object HistoryMapper {
    const val MAX_NIC_CARDS = 6
    const val MAX_VIF_CARDS = 4
    const val MAX_DISK_CARDS = 4
    /** XAPI's name for the sum over all physical NICs (`pif_aggr_rx`, `pif_aggr_tx`). */
    const val AGGREGATE_NIC = "aggr"
    private val pif = Regex("pif_(.{1,48})_(rx|tx)")
    private val vif = Regex("vif_([0-9]{1,3})_(rx|tx)")
    private val vbd = Regex("vbd_([A-Za-z0-9]{1,16})_(read|write)")
    private val vcpu = Regex("cpu([0-9]{1,4})")

    /**
     * [update] must already be limited to the target's AVERAGE columns. Missing values stay [Double.NaN].
     * [diskDevices] are a VM's non-CD disk devices; CD drives report `vbd_*` series too but are not disks. Null keeps every device.
     */
    fun build(target: MetricTarget, range: HistoryRange, update: RrdUpdates, observedAt: Long, serverTimeSeconds: Long?,
        diskDevices: Set<String>? = null): HistorySnapshot {
        val names = update.columns.map { it.legend.name }
        require(names.distinct().size == names.size) { "Ambiguous metric identity." }
        val byName = update.columns.associate { it.legend.name to it.values }
        val rows = update.timestamps.size
        val series = ArrayList<HistorySeries>()
        val notes = ArrayList<String>()
        fun kibToBytes(value: Double) = value * 1024
        fun scaled(values: List<Double>) = values.map { if (it.isFinite()) kibToBytes(it) else Double.NaN }
        fun add(card: String, id: String, label: String, group: HistoryGroup, unit: HistoryUnit, declared: String, values: List<Double>) {
            series += HistorySeries(card, id, label, group, unit, declared, values)
        }
        fun rateCards(prefix: String, pattern: Regex, cap: Int, title: (String) -> String, first: String, second: String,
            group: HistoryGroup, labels: Pair<String, String>, wanted: (String) -> Boolean = { true }) {
            val devices = byName.keys.mapNotNull { pattern.matchEntire(it)?.groupValues?.get(1) }.distinct().filter(wanted)
                .sortedWith(compareBy({ it.length }, { it })).take(cap)
            for (device in devices) for ((direction, label) in listOf(first to labels.first, second to labels.second)) {
                byName["${prefix}_${device}_$direction"]?.let {
                    add(title(device), "${prefix}_${device}_$direction", label, group, HistoryUnit.BYTES_PER_SECOND, "bytes/s", it)
                }
            }
        }
        if (target.kind == MetricKind.HOST) {
            byName["cpu_avg"]?.let { add("CPU", "cpu_avg", "Average of physical CPUs", HistoryGroup.CPU, HistoryUnit.FRACTION, "fraction", it) }
            byName["loadavg"]?.let { add("Load average", "loadavg", "dom0 load average", HistoryGroup.LOAD, HistoryUnit.LOAD, "load", it) }
            val total = byName["memory_total_kib"]
            val free = byName["memory_free_kib"]
            if (total != null && free != null) {
                add("Memory", "memory_used", "Used (total − free)", HistoryGroup.MEMORY, HistoryUnit.BYTES, "KiB",
                    List(rows) { i -> (total[i] - free[i]).takeIf { it.isFinite() && it >= 0 }?.let(::kibToBytes) ?: Double.NaN })
                add("Memory", "memory_total", "Total", HistoryGroup.MEMORY, HistoryUnit.BYTES, "KiB", scaled(total))
            }
            // pif_aggr_* is the sum of all NICs, not a NIC. One clearly named card for it, and only when there are two or
            // more NICs to add up; with a single NIC it would repeat that NIC's card.
            val nics = byName.keys.mapNotNull { pif.matchEntire(it)?.groupValues?.get(1) }.distinct().filter { it != AGGREGATE_NIC }
            if (nics.size >= 2) rateCards("pif", pif, 1, { "All NICs" }, "rx", "tx", HistoryGroup.NETWORK, "Receive" to "Transmit") { it == AGGREGATE_NIC }
            rateCards("pif", pif, MAX_NIC_CARDS, { "NIC $it" }, "rx", "tx", HistoryGroup.NETWORK, "Receive" to "Transmit") { it != AGGREGATE_NIC }
        } else {
            val cpus = names.filter { vcpu.matches(it) }.sortedWith(compareBy { vcpu.matchEntire(it)!!.groupValues[1].toInt() })
            if (cpus.isNotEmpty()) {
                val columns = cpus.map { byName.getValue(it) }
                add("CPU", "cpu_avg", "Average of ${cpus.size} vCPU${if (cpus.size == 1) "" else "s"}", HistoryGroup.CPU, HistoryUnit.FRACTION, "fraction",
                    List(rows) { i -> columns.map { it[i] }.filter { it.isFinite() }.let { values -> if (values.isEmpty()) Double.NaN else values.average() } })
            }
            byName["memory"]?.let { add("Memory", "memory", "Allocated", HistoryGroup.MEMORY, HistoryUnit.BYTES, "bytes", it) }
            val internalFree = byName["memory_internal_free"]
            if (internalFree != null) add("Memory", "memory_internal_free", "Guest-reported free", HistoryGroup.MEMORY, HistoryUnit.BYTES, "KiB", scaled(internalFree))
            else if (byName.containsKey("memory")) notes += "Guest-reported free memory is not available. It needs working guest tools."
            rateCards("vif", vif, MAX_VIF_CARDS, { "Network VIF $it" }, "rx", "tx", HistoryGroup.NETWORK, "Receive" to "Transmit")
            rateCards("vbd", vbd, MAX_DISK_CARDS, { "Disk $it" }, "read", "write", HistoryGroup.DISK, "Read" to "Write") { diskDevices == null || it in diskDevices }
        }
        if (series.isEmpty()) notes += "The server returned no history for this ${if (target.kind == MetricKind.HOST) "host" else "VM"}."
        return HistorySnapshot(target, range, update.step, update.timestamps, series, observedAt, serverTimeSeconds, notes)
    }
}

/**
 * Joins an incremental read onto the window already held, keeping at most the range's span. Rows are matched by time,
 * so a read with another step (the server answered from another archive) adds its rows to the earlier ones instead of
 * replacing them. While earlier rows from a coarser read are still held, the window's step is the coarser one, so the
 * chart does not break the line between rows that are one coarse step apart; once they have aged out it is the new step.
 * Only another object or another range starts a new window.
 */
object HistoryWindow {
    fun merge(previous: HistorySnapshot?, next: HistorySnapshot): HistorySnapshot {
        if (previous == null || previous.target != next.target || previous.range != next.range) return next
        if (next.timestamps.isEmpty()) return previous.copy(observedAt = next.observedAt, serverTimeSeconds = next.serverTimeSeconds, notes = next.notes)
        val times = (previous.timestamps + next.timestamps).distinct().sorted()
        val kept = times.filter { it >= times.last() - next.range.spanSeconds }
        val oldIndex = previous.timestamps.withIndex().associate { it.value to it.index }
        val newIndex = next.timestamps.withIndex().associate { it.value to it.index }
        val oldById = previous.series.associateBy { it.id }
        val newById = next.series.associateBy { it.id }
        val merged = (next.series.map { it.id } + previous.series.map { it.id }.filter { it !in newById }).map { id ->
            val newer = newById[id]
            val older = oldById[id]
            val template = newer ?: requireNotNull(older)
            template.copy(values = kept.map { time ->
                // A row the new read contains is authoritative, even where the series is now missing.
                newIndex[time]?.let { newer?.values?.get(it) ?: Double.NaN }
                    ?: oldIndex[time]?.let { older?.values?.get(it) } ?: Double.NaN
            })
        }
        val earlierRowsKept = kept.any { it in oldIndex && it !in newIndex }
        val step = if (earlierRowsKept) maxOf(previous.stepSeconds, next.stepSeconds) else next.stepSeconds
        return next.copy(stepSeconds = step, timestamps = kept, series = merged)
    }
}

object HistoryChartMath {
    /** Runs of consecutive finite points. A hole of more than one and a half steps ends a run. */
    fun runs(timestamps: List<Long>, values: List<Double>, step: Long): List<IntRange> {
        require(timestamps.size == values.size) { "Series does not match its time axis." }
        val runs = ArrayList<IntRange>()
        var start = -1
        for (i in values.indices) {
            val finite = values[i].isFinite()
            val joined = start >= 0 && i > 0 && values[i - 1].isFinite() && (timestamps[i] - timestamps[i - 1]) * 2 <= step * 3
            if (start >= 0 && !(finite && joined)) { runs += start..(i - 1); start = -1 }
            if (finite && start < 0) start = i
        }
        if (start >= 0) runs += start..values.lastIndex
        return runs
    }

    /**
     * When no series has a value for the first part of the window, the time of the first value; otherwise null. The chart's
     * time axis always spans the whole window, so a server that was up for a few hours shows a one-week axis with a line over
     * the last few hours. That is correct (the server recorded nothing before), and this is what lets the chart say so.
     * "A first part" means more than two steps and more than one twentieth of the window.
     */
    fun dataStartsLate(timestamps: List<Long>, series: List<List<Double>>, step: Long): Long? {
        if (timestamps.size < 2) return null
        val firstIndex = timestamps.indices.firstOrNull { i -> series.any { it.getOrNull(i)?.isFinite() == true } } ?: return null
        val late = timestamps[firstIndex] - timestamps.first()
        val span = timestamps.last() - timestamps.first()
        return if (late > maxOf(2 * step, span / 20)) timestamps[firstIndex] else null
    }

    /** Rates and bytes start at zero. CPU stays on a 0–100% axis unless a series genuinely exceeds it. */
    fun axisMax(unit: HistoryUnit, series: List<List<Double>>): Double {
        val high = series.flatten().filter { it.isFinite() }.maxOrNull() ?: 0.0
        return when {
            unit == HistoryUnit.FRACTION && high <= 1.0 -> 1.0
            high <= 0.0 -> 1.0
            else -> niceCeiling(high)
        }
    }
    fun niceCeiling(value: Double): Double {
        if (!value.isFinite() || value <= 0) return 1.0
        val magnitude = Math.pow(10.0, Math.floor(Math.log10(value)))
        val scaled = value / magnitude
        val step = when { scaled <= 1.0 -> 1.0; scaled <= 2.0 -> 2.0; scaled <= 5.0 -> 5.0; else -> 10.0 }
        return step * magnitude
    }
    fun finiteBounds(values: List<Double>): Pair<Double, Double>? =
        values.filter { it.isFinite() }.let { if (it.isEmpty()) null else it.min() to it.max() }
}

/** History values use [MetricFormat], the same format as the live performance view. */
object HistoryFormat {
    fun value(value: Double, unit: HistoryUnit, locale: Locale = Locale.getDefault()): String {
        if (!value.isFinite()) return "No data"
        return when (unit) {
            HistoryUnit.FRACTION -> MetricFormat.percent(value * 100, locale)
            HistoryUnit.LOAD -> MetricFormat.load(value, locale)
            HistoryUnit.BYTES -> size(value, locale)
            HistoryUnit.BYTES_PER_SECOND -> MetricFormat.rate(value, locale)
        }
    }
    fun size(bytes: Double, locale: Locale = Locale.getDefault()): String = if (!bytes.isFinite()) "—" else MetricFormat.bytes(bytes, locale)
    fun size(bytes: Long, locale: Locale = Locale.getDefault()) = size(bytes.toDouble(), locale)
}
