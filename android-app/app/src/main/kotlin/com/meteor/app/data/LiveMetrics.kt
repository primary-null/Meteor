package com.meteor.app.data

/**
 * What the live view reads. A real XCP-ng answers `get_data_sources` with a value of 0.0 for every source, so that
 * call is only a catalogue (names, units, limits, enabled). The current value of one source comes from
 * `VM.query_data_source` / `host.query_data_source`, one call per source. [LiveMetrics.plan] therefore picks a small
 * fixed set of enabled sources once, and each tick queries exactly those.
 */
data class PlannedMetric(val name: String, val label: String, val unit: String, val group: MetricGroup,
    /** The source's finite, positive maximum. An infinite maximum means "no limit" and is stored as null. */
    val limit: Double? = null,
    /** Computed from other readings, never queried: host "Used memory" is total minus free. */
    val derived: Boolean = false)

data class MetricPlan(val items: List<PlannedMetric>, val advertisedCount: Int) {
    val queryNames: List<String> get() = items.filter { !it.derived }.map { it.name }
}

object LiveMetrics {
    /** Per-core CPU readings only for objects with 2 to this many cores; more cores are summarised by the average. */
    const val MAX_PER_CORE = 4
    const val MAX_DISKS = 2
    const val MAX_NICS = 2
    private const val MAX_SOURCES = 4096
    private val core = Regex("cpu([0-9]{1,4})")
    private val vbd = Regex("vbd_([A-Za-z0-9]{1,16})_(?:read|write)")
    private val vif = Regex("vif_([0-9]{1,3})_(?:rx|tx)")
    private val pif = Regex("pif_(.{1,48})_(?:rx|tx)")

    /** `(fraction)` is the server's spelling; the app's unit names have no brackets. */
    fun unit(raw: Any?): String = (raw as? String).orEmpty().trim().removeSurrounding("(", ")").take(40)

    private fun enabled(source: Map<String, Any?>) = source["enabled"] == true || source["enabled"] == "true" || source["enabled"] == "1"

    private fun limit(source: Map<String, Any?>): Double? =
        source["max"]?.toString()?.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 }

    /**
     * The intended readings per object kind, in display order, from the sources the server lists as enabled:
     * VM: CPU average (and per-vCPU for 2 to 4 vCPUs), allocated memory and guest-reported free memory, read/write of
     * up to two disks, receive/transmit of up to two virtual NICs. Host: CPU average (and per-core for 2 to 4 cores),
     * load average, used/free/total memory, receive/transmit of up to two NICs (plus "All NICs" when there are two or more).
     * Queue sizes, latencies, error counters, C-states and per-SR names are deliberately not shown.
     * [diskDevices] are the VM's non-CD disk devices (for example `xvda`); null means unknown and keeps every device.
     */
    fun plan(target: MetricTarget, sources: List<Map<String, Any?>>, diskDevices: Set<String>?): MetricPlan {
        require(sources.size <= MAX_SOURCES) { "Too many metric sources." }
        val names = sources.map { it["name_label"] as? String ?: throw GatewayError("invalid-metrics") }
        require(names.all { it.isNotBlank() && it.length <= 160 } && names.distinct().size == names.size) { "Ambiguous metric identity." }
        val on = sources.filter(::enabled).associateBy { it["name_label"] as String }
        fun item(name: String, label: String, group: MetricGroup, unit: String? = null, derived: Boolean = false): PlannedMetric? =
            on[name]?.let {
                val shown = unit ?: unit(it["units"])
                // A fraction is drawn from 0 to 100% even when the server lists no finite maximum.
                PlannedMetric(name, label, shown, group, limit(it) ?: 1.0.takeIf { shown == "fraction" }, derived)
            }
        fun number(name: String, prefix: String) = name.removePrefix(prefix).toIntOrNull() ?: Int.MAX_VALUE
        val items = ArrayList<PlannedMetric>()
        val cores = on.keys.filter { core.matches(it) }.sortedBy { number(it, "cpu") }
        fun perCore(label: (Int) -> String) {
            if (cores.size in 2..MAX_PER_CORE) cores.forEach { name -> item(name, label(number(name, "cpu")), MetricGroup.CPU)?.let(items::add) }
        }
        fun pairs(devices: List<String>, prefix: String, first: String, second: String, label: (String, String) -> String, group: MetricGroup) {
            for (device in devices) for (direction in listOf(first, second))
                item("${prefix}_${device}_$direction", label(device, direction), group)?.let(items::add)
        }
        if (target.kind == MetricKind.VM) {
            item("cpu_usage", "Average of vCPUs", MetricGroup.CPU)?.let(items::add)
            perCore { "vCPU $it" }
            item("memory", "Allocated memory", MetricGroup.MEMORY)?.let(items::add)
            // The server describes this source as "memory used"; its values are free memory (747 MiB of 1 GiB on an idle guest).
            item("memory_internal_free", "Guest-reported free memory", MetricGroup.MEMORY)?.let(items::add)
            val disks = on.keys.mapNotNull { vbd.matchEntire(it)?.groupValues?.get(1) }.distinct()
                .filter { "vbd_${it}_read" in on && "vbd_${it}_write" in on && (diskDevices == null || it in diskDevices) }
                .sortedWith(compareBy({ it.length }, { it })).take(MAX_DISKS)
            pairs(disks, "vbd", "read", "write", { device, direction -> "$device $direction" }, MetricGroup.DISK)
            val nics = on.keys.mapNotNull { vif.matchEntire(it)?.groupValues?.get(1) }.distinct()
                .filter { "vif_${it}_rx" in on && "vif_${it}_tx" in on }.sortedBy { it.toInt() }.take(MAX_NICS)
            pairs(nics, "vif", "rx", "tx", { device, direction -> "Interface $device ${if (direction == "rx") "receive" else "transmit"}" }, MetricGroup.NETWORK)
        } else {
            item("cpu_avg", "Average of physical CPUs", MetricGroup.CPU)?.let(items::add)
            perCore { "CPU $it" }
            // The server types the load average as a "(fraction)"; it is a plain load figure and can exceed 1.
            item("loadavg", "dom0 load average", MetricGroup.LOAD, unit = "load")?.let(items::add)
            if ("memory_total_kib" in on && "memory_free_kib" in on)
                items += PlannedMetric("memory_used", "Used memory", unit(on.getValue("memory_total_kib")["units"]), MetricGroup.MEMORY, derived = true)
            item("memory_free_kib", "Free memory", MetricGroup.MEMORY)?.let(items::add)
            item("memory_total_kib", "Total memory", MetricGroup.MEMORY)?.let(items::add)
            val nics = on.keys.mapNotNull { pif.matchEntire(it)?.groupValues?.get(1) }.distinct()
                .filter { it != "aggr" && "pif_${it}_rx" in on && "pif_${it}_tx" in on }.sortedWith(compareBy({ it.length }, { it }))
            // pif_aggr is the sum of all NICs, not a NIC. With a single NIC it would only repeat that NIC.
            if (nics.size >= 2 && "pif_aggr_rx" in on && "pif_aggr_tx" in on) {
                item("pif_aggr_rx", "All NICs receive", MetricGroup.NETWORK)?.let(items::add)
                item("pif_aggr_tx", "All NICs transmit", MetricGroup.NETWORK)?.let(items::add)
            }
            pairs(nics.take(MAX_NICS), "pif", "rx", "tx", { device, direction -> "$device ${if (direction == "rx") "receive" else "transmit"}" }, MetricGroup.NETWORK)
        }
        return MetricPlan(items, sources.size)
    }

    /** A queried value as a number, or null when it is not a finite, non-negative sample. */
    fun sample(value: Any?): Double? = when (value) {
        is Double -> value
        is Long -> value.toDouble()
        is String -> value.trim().toDoubleOrNull()
        else -> null
    }?.takeIf { it.isFinite() && it >= 0 }

    /** [values] holds the queried samples by source name; a missing or non-finite sample is a gap, never zero. */
    fun snapshot(target: MetricTarget, plan: MetricPlan, values: Map<String, Double?>, now: Long): MetricSnapshot {
        val readings = plan.items.map { item ->
            val value = if (item.derived) used(plan, values) else sample(values[item.name])
            MetricReading(item.name, item.label, item.unit, item.group, value, if (value == null) "No valid sample" else null, item.limit)
        }
        return MetricSnapshot(target, now, readings, plan.advertisedCount,
            if (readings.isEmpty()) "No supported metric sources were reported." else null)
    }

    private fun used(plan: MetricPlan, values: Map<String, Double?>): Double? {
        if (plan.items.none { it.name == "memory_total_kib" }) return null
        val total = sample(values["memory_total_kib"]) ?: return null
        val free = sample(values["memory_free_kib"]) ?: return null
        return (total - free).takeIf { it >= 0 }
    }

    /**
     * The top of a live sparkline. Every live chart starts at zero, so a move from 1% to 2% looks like a doubling of a
     * small number, not a spike. A source with a finite limit (fractions: 1.0, drawn as 0 to 100%) scales to it; the others
     * scale to a round figure above the largest sample.
     */
    fun scaleTop(limit: Double?, values: List<Double>): Double {
        val high = values.filter { it.isFinite() }.maxOrNull() ?: 0.0
        return if (limit != null && limit.isFinite() && limit > 0) maxOf(limit, high) else HistoryChartMath.niceCeiling(high)
    }
}

/**
 * Short-lived memory of the choices made for one object so that a 5-second tick does not download the whole source
 * catalogue (36 KB for a guest, 62 KB for a host) and the VM's disk records every time. Keys hold no secret.
 */
internal class LiveCache(private val ttlMillis: Long = 60_000) {
    data class Key(val profileId: String, val endpoint: String, val kind: MetricKind, val uuid: String)
    private class Entry<T>(val value: T, val at: Long)
    private val plans = HashMap<Key, Entry<MetricPlan>>()
    private val disks = HashMap<Key, Entry<Set<String>>>()
    private fun <T> get(map: HashMap<Key, Entry<T>>, key: Key, now: Long): T? =
        map[key]?.takeIf { now - it.at in 0..ttlMillis }?.value
    private fun <T> put(map: HashMap<Key, Entry<T>>, key: Key, value: T, now: Long) {
        if (map.size >= 64) map.clear()
        map[key] = Entry(value, now)
    }
    @Synchronized fun plan(key: Key, now: Long) = get(plans, key, now)
    @Synchronized fun putPlan(key: Key, plan: MetricPlan, now: Long) = put(plans, key, plan, now)
    @Synchronized fun dropPlan(key: Key) { plans.remove(key) }
    @Synchronized fun disks(key: Key, now: Long) = get(disks, key, now)
    @Synchronized fun putDisks(key: Key, devices: Set<String>, now: Long) = put(disks, key, devices, now)
}
