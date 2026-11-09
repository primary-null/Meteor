package com.meteor.app.data

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatterBuilder
import java.time.format.ResolverStyle
import java.time.temporal.ChronoField

enum class MetricKind { VM, HOST }
data class MetricTarget(val authority: String, val uuid: String, val kind: MetricKind, val name: String)
enum class MetricGroup { CPU, MEMORY, DISK, NETWORK }
data class MetricReading(val name: String, val label: String, val unit: String, val group: MetricGroup,
    val value: Double?, val unavailable: String? = null)
data class MetricSnapshot(val target: MetricTarget, val observedAt: Long, val readings: List<MetricReading>,
    val advertisedCount: Int, val note: String? = null)

object MetricMapper {
    private fun group(name: String): MetricGroup? = when {
        name.matches(Regex("cpu(?:[0-9]+|_avg|_usage)")) -> MetricGroup.CPU
        name in setOf("memory", "memory_internal_free", "memory_free_kib", "memory_total_kib", "memory_target") -> MetricGroup.MEMORY
        name.startsWith("vbd_") -> MetricGroup.DISK
        name.startsWith("vif_") || name.startsWith("pif_") -> MetricGroup.NETWORK
        else -> null
    }
    fun snapshot(target: MetricTarget, sources: List<Map<String, Any?>>, now: Long): MetricSnapshot {
        require(sources.size <= 4096) { "Too many metric sources." }
        val names = sources.map { it["name_label"] as? String ?: throw GatewayError("invalid-metrics") }
        require(names.all { it.isNotBlank() && it.length <= 160 } && names.distinct().size == names.size) { "Ambiguous metric identity." }
        val selected = sources.filter { group(it["name_label"] as String) != null }
            .groupBy { group(it["name_label"] as String)!! }.toSortedMap()
            .flatMap { (_, entries) -> entries.sortedWith(compareBy<Map<String, Any?>> {
                if (it["name_label"] == "cpu_avg") 0 else 1
            }.thenBy { it["name_label"] as String }).take(4) }
        val readings = selected.map { source ->
            val name = source["name_label"] as String
            val enabled = source["enabled"] == true || source["enabled"] == "true" || source["enabled"] == "1"
            val value = source["value"]?.toString()?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }
            val label = when (name) {
                "memory" -> "Allocated memory"
                "memory_internal_free" -> "Guest-reported free memory"
                "memory_free_kib" -> "Free memory"
                "memory_total_kib" -> "Total memory"
                "memory_target" -> "Target memory"
                else -> name.replace('_', ' ').replaceFirstChar { it.uppercase() }
            }
            MetricReading(name, label, (source["units"] as? String).orEmpty().take(40), group(name)!!,
                value.takeIf { enabled }, if (!enabled) "Source is not recorded" else if (value == null) "No valid sample" else null)
        }
        return MetricSnapshot(target, now, readings, sources.size,
            if (readings.isEmpty()) "No supported metric sources were reported." else null)
    }
}

/** In-memory samples, bounded by five minutes and sixty observations. Missing data stays missing. */
object MetricWindow {
    fun append(previous: List<MetricSnapshot>, next: MetricSnapshot): List<MetricSnapshot> {
        if (previous.lastOrNull()?.let { it.target != next.target || it.observedAt > next.observedAt } == true) return listOf(next)
        return (previous.filter { it.observedAt < next.observedAt && next.observedAt - it.observedAt <= 300_000 } + next).takeLast(60)
    }
}

enum class ReadIssue(val text: String) {
    UNSUPPORTED("Not supported by this server"), FORBIDDEN("Not available to this account"), TEMPORARY("Could not read this section securely")
}
data class ReadSection<T>(val items: List<T>, val total: Int = items.size, val issue: ReadIssue? = null)
data class PoolTask(val uuid: String, val name: String, val status: String, val progress: Double?,
    val createdAt: Long?, val finishedAt: Long?, val errorCode: String?)
data class PoolAlert(val uuid: String, val name: String, val priority: Int?, val objectClass: String,
    val objectUuid: String, val createdAt: Long?, val body: String) {
    override fun toString() = "PoolAlert(details=redacted)"
}
data class PoolActivity(val authority: String, val observedAt: Long, val tasks: ReadSection<PoolTask>, val alerts: ReadSection<PoolAlert>)

object ActivityMapper {
    private val compact = DateTimeFormatterBuilder().appendPattern("uuuuMMdd'T'HH:mm:ss")
        .optionalStart().appendFraction(ChronoField.NANO_OF_SECOND, 1, 9, true).optionalEnd()
        .toFormatter().withResolverStyle(ResolverStyle.STRICT)
    fun time(value: Any?): Long? {
        val text = value as? String ?: return null
        val parsed = runCatching { Instant.parse(text) }.getOrElse {
            runCatching { LocalDateTime.parse(text.removeSuffix("Z"), compact).toInstant(ZoneOffset.UTC) }.getOrNull()
        }
        return runCatching { parsed?.toEpochMilli() }.getOrNull()?.takeIf { it > 0 }
    }
    private fun identities(records: Map<String, Map<String, Any?>>) {
        require(records.size <= 5000) { "Too many activity records." }
        val ids = records.values.map { it["uuid"] as? String }
        require(ids.all { !it.isNullOrBlank() } && ids.distinct().size == ids.size) { "Ambiguous activity identity." }
    }
    fun tasks(records: Map<String, Map<String, Any?>>): ReadSection<PoolTask> {
        identities(records)
        val values = records.values.map { record ->
            val status = record["status"] as? String ?: "unknown"
            val code = (record["error_info"] as? List<*>)?.firstOrNull() as? String
            PoolTask(record["uuid"] as String, (record["name_label"] as? String).orEmpty().take(160).ifBlank { "Server task" },
                status.takeIf { it in setOf("pending", "cancelling", "cancelled", "failure", "success") } ?: "unknown",
                record["progress"]?.toString()?.toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.0..1.0 },
                time(record["created"]), time(record["finished"]).takeUnless { status in setOf("pending", "cancelling") },
                code?.takeIf { it.matches(Regex("[A-Z0-9_]{1,100}")) && status == "failure" })
        }.sortedWith(compareByDescending<PoolTask> { it.status in setOf("pending", "cancelling") }
            .thenByDescending { it.createdAt ?: 0 }.thenBy { it.uuid })
        return ReadSection(values.take(100), records.size)
    }
    fun alerts(records: Map<String, Map<String, Any?>>): ReadSection<PoolAlert> {
        identities(records)
        val values = records.values.map { record ->
            PoolAlert(record["uuid"] as String, (record["name"] as? String).orEmpty().take(160).ifBlank { "Server alert" },
                record["priority"]?.toString()?.toIntOrNull(), (record["cls"] as? String).orEmpty().take(40),
                (record["obj_uuid"] as? String).orEmpty().take(160), time(record["timestamp"]),
                (record["body"] as? String).orEmpty().take(4096))
        }.sortedWith(compareByDescending<PoolAlert> { it.createdAt ?: 0 }.thenBy { it.uuid })
        return ReadSection(values.take(100), records.size)
    }
}
