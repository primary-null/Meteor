package com.meteor.app.data

// --- A4 alerts/events ---
// Pure alert-inbox logic: no Android, no network. The gateway reads XAPI `message` records, the controller
// merges them here, and the screen filters and resolves them here. Bodies stay in memory; only a per-profile
// "last seen" server timestamp is ever persisted.

/** XAPI message priority 1..5. Anything else is [UNRATED] and is never counted as urgent. */
enum class AlertSeverity(val priority: Int, val label: String) {
    CRITICAL(1, "Critical"), HIGH(2, "High"), WARNING(3, "Warning"), LOW(4, "Low"), INFO(5, "Info"), UNRATED(0, "Unrated");

    /** Priorities 1-3 are the ones that feed the unread badge. */
    val urgent: Boolean get() = this == CRITICAL || this == HIGH || this == WARNING

    companion object {
        fun of(priority: Int?): AlertSeverity = entries.firstOrNull { it != UNRATED && it.priority == priority } ?: UNRATED
    }
}

enum class AlertObjectKind(val label: String) { VM("VM"), HOST("Host"), STORAGE("Storage"), POOL("Pool"), OTHER("Other") }

/** Identity is the XAPI message reference; the same message keeps the same reference on every read. */
data class AlertMessage(
    val ref: String, val uuid: String, val name: String, val priority: Int?, val objectClass: String,
    val objectUuid: String, val timestamp: Long?, val body: String,
) {
    val severity: AlertSeverity get() = AlertSeverity.of(priority)
    val objectKind: AlertObjectKind get() = AlertObjects.kind(objectClass)
    override fun toString() = "AlertMessage(details=redacted)"
}

object AlertMapper {
    /** Newest first. A record that is not a map is a protocol error; field problems degrade to blanks. */
    fun parse(records: Map<String, Map<String, Any?>>): List<AlertMessage> = records.map { (ref, record) ->
        require(ref.isNotBlank() && ref.length <= 200) { "Invalid alert reference." }
        AlertMessage(ref, (record["uuid"] as? String).orEmpty().take(64),
            (record["name"] as? String).orEmpty().take(160),
            record["priority"]?.toString()?.toLongOrNull()?.takeIf { it in 1..5 }?.toInt(),
            (record["cls"] as? String).orEmpty().take(40), (record["obj_uuid"] as? String).orEmpty().take(160),
            ActivityMapper.time(record["timestamp"]), (record["body"] as? String).orEmpty().take(4096))
    }.sortedWith(NEWEST_FIRST)

    val NEWEST_FIRST: Comparator<AlertMessage> =
        compareByDescending<AlertMessage> { it.timestamp ?: Long.MIN_VALUE }.thenBy { it.ref }
}

/** "VM_STARTED" -> "VM started", "ha_host_failed" -> "HA host failed". Display only; the raw name is kept. */
object AlertNames {
    private val acronyms = setOf("vm", "vms", "ha", "sr", "pbd", "vdi", "vbd", "vif", "pif", "xapi", "cpu", "io", "ip", "rrd", "pvs", "ntp", "dns", "nfs", "iscsi")
    fun humanize(raw: String): String {
        val words = raw.trim().split('_', '-', ' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return "Server alert"
        return words.mapIndexed { index, word ->
            val lower = word.lowercase()
            when {
                lower in acronyms -> lower.uppercase()
                index == 0 -> lower.replaceFirstChar { it.uppercase() }
                else -> lower
            }
        }.joinToString(" ").take(160)
    }
}

/**
 * Perfmon `ALARM` bodies ("value: 0.97\nconfig:\n<variable><name value="cpu_usage"/><alarm_trigger_level value="0.9"/>…")
 * as one readable line. Display only: the raw body stays available, and nothing here is used for decisions.
 */
object AlarmText {
    private val value = Regex("""(?m)^\s*value:\s*([-+0-9.eE]+)\s*$""")
    private val metric = Regex("""<name\s+value="([^"]{1,80})"""")
    private val level = Regex("""<alarm_trigger_level\s+value="([-+0-9.eE]+)"""")
    private val fractions = setOf("cpu_usage", "mem_usage", "fs_usage", "log_fs_usage")

    fun summary(name: String, body: String): String? {
        if (!name.equals("ALARM", ignoreCase = true)) return null
        val current = value.find(body)?.groupValues?.get(1)?.toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null
        val key = metric.find(body)?.groupValues?.get(1)
        val trigger = level.find(body)?.groupValues?.get(1)?.toDoubleOrNull()?.takeIf { it.isFinite() }
        val (label, format) = describe(key)
        val threshold = trigger?.let { if (key == "memory_free_kib") " (alarm below ${format(it)})" else " (alarm level ${format(it)})" }.orEmpty()
        return "$label ${format(current)}$threshold"
    }

    private fun describe(key: String?): Pair<String, (Double) -> String> = when {
        key == null -> "Monitored value" to ::plain
        key in fractions -> when (key) {
            "cpu_usage" -> "CPU usage"
            "mem_usage" -> "Control-domain memory usage"
            "fs_usage" -> "Control-domain disk usage"
            else -> "Control-domain log disk usage"
        } to { v -> "%.0f%%".format(java.util.Locale.ROOT, v * 100) }
        key == "memory_free_kib" -> "Free memory" to { v -> bytes(v * 1024) }
        key == "network_usage" -> "Network throughput" to { v -> bytes(v) + "/s" }
        key == "disk_usage" -> "Disk throughput" to { v -> bytes(v) + "/s" }
        key.startsWith("sr_io_throughput_total") -> "Storage throughput" to { v -> bytes(v * 1024 * 1024) + "/s" }
        else -> key to ::plain
    }

    private fun plain(v: Double) = if (v == Math.floor(v) && kotlin.math.abs(v) < 1e12) "%.0f".format(java.util.Locale.ROOT, v)
        else "%.4f".format(java.util.Locale.ROOT, v).trimEnd('0').trimEnd('.')

    private fun bytes(v: Double): String {
        val units = listOf("B", "KiB", "MiB", "GiB", "TiB")
        var size = kotlin.math.abs(v); var unit = 0
        while (size >= 1024 && unit < units.lastIndex) { size /= 1024; unit++ }
        return "%.1f %s".format(java.util.Locale.ROOT, if (v < 0) -size else size, units[unit])
    }
}

data class ResolvedAlertObject(val kind: AlertObjectKind, val uuid: String, val name: String?, val guest: Guest? = null) {
    /** True when the object is in the current inventory, so the UI can offer a tap-through. */
    val inInventory: Boolean get() = name != null
    val display: String get() = when {
        name != null -> "${kind.label} · $name"
        uuid.isBlank() -> kind.label
        else -> "${kind.label} · not in the current inventory (${uuid.take(8)}…)"
    }
}

object AlertObjects {
    fun kind(cls: String): AlertObjectKind = when (cls.trim().lowercase()) {
        "vm" -> AlertObjectKind.VM
        "host" -> AlertObjectKind.HOST
        "sr" -> AlertObjectKind.STORAGE
        "pool" -> AlertObjectKind.POOL
        else -> AlertObjectKind.OTHER
    }

    /** Resolves `cls` + `obj_uuid` against the current inventory only. Templates and snapshots are not in it. */
    fun resolve(message: AlertMessage, inventory: Inventory?): ResolvedAlertObject {
        val kind = message.objectKind
        val uuid = message.objectUuid
        if (inventory == null || uuid.isBlank()) return ResolvedAlertObject(kind, uuid, null)
        return when (kind) {
            AlertObjectKind.VM -> inventory.guests.firstOrNull { it.uuid == uuid }?.let { ResolvedAlertObject(kind, uuid, it.name, it) }
            AlertObjectKind.HOST -> inventory.hosts.firstOrNull { it.uuid == uuid }?.let { ResolvedAlertObject(kind, uuid, it.name) }
            AlertObjectKind.STORAGE -> inventory.repositories.firstOrNull { it.uuid == uuid }?.let { ResolvedAlertObject(kind, uuid, it.name) }
            AlertObjectKind.POOL -> if (uuid == inventory.authority) ResolvedAlertObject(kind, uuid, inventory.poolName) else null
            AlertObjectKind.OTHER -> null
        } ?: ResolvedAlertObject(kind, uuid, null)
    }
}

/**
 * The in-memory inbox for one connection scope (profile, origin, pool). [cursor] is the newest *server* message
 * timestamp seen; the phone clock never feeds it. [lastSeen] is the only persisted value.
 */
data class AlertInbox(
    val scopeKey: String, val messages: List<AlertMessage> = emptyList(), val cursor: Long? = null,
    val syncedAt: Long = 0, val fullSyncAt: Long = 0, val truncated: Boolean = false, val lastSeen: Long? = null,
) {
    val unreadUrgent: Int get() = messages.count { it.severity.urgent && AlertSeen.isNew(it, lastSeen) }
}

data class AlertFetch(val authority: String, val observedAt: Long, val messages: List<AlertMessage>,
    val full: Boolean, val truncated: Boolean = false)

/** An inbox belongs to one profile, one trusted origin (endpoint, pin, username) and one pool. */
object AlertScope {
    fun key(profile: Profile, authority: String?): String = "${profile.id}:${profile.origin()}:${authority.orEmpty()}"
}

object AlertMerge {
    const val MAX_KEPT = 1000
    /** `get_since` re-reads this much before the cursor, so slightly out-of-order server timestamps are not lost. */
    const val OVERLAP_MILLIS = 300_000L
    /** A full re-read this often catches deletions that the incremental path cannot see. */
    const val FULL_RECONCILE_MILLIS = 600_000L
    /** If the first full read is too large to parse, only this much history is requested instead. */
    const val FALLBACK_WINDOW_MILLIS = 3 * 24 * 3_600_000L

    fun since(cursor: Long?): Long? = cursor?.let { (it - OVERLAP_MILLIS).coerceAtLeast(1) }

    fun full(scopeKey: String, fetch: AlertFetch, lastSeen: Long?): AlertInbox {
        val deduped = fetch.messages.associateBy { it.ref }.values.sortedWith(AlertMapper.NEWEST_FIRST)
        return AlertInbox(scopeKey, deduped.take(MAX_KEPT), deduped.mapNotNull { it.timestamp }.maxOrNull(), fetch.observedAt,
            fetch.observedAt, fetch.truncated || deduped.size > MAX_KEPT, lastSeen)
    }

    /** Fetched records replace same-reference records; the cursor never moves backwards. */
    fun incremental(previous: AlertInbox, fetch: AlertFetch): AlertInbox {
        val merged = (previous.messages.associateBy { it.ref } + fetch.messages.associateBy { it.ref })
            .values.sortedWith(AlertMapper.NEWEST_FIRST)
        val cursor = listOfNotNull(previous.cursor, fetch.messages.mapNotNull { it.timestamp }.maxOrNull()).maxOrNull()
        return previous.copy(messages = merged.take(MAX_KEPT), cursor = cursor, syncedAt = fetch.observedAt,
            truncated = previous.truncated || merged.size > MAX_KEPT)
    }

    fun remove(inbox: AlertInbox, refs: Set<String>): AlertInbox =
        if (refs.isEmpty()) inbox else inbox.copy(messages = inbox.messages.filterNot { it.ref in refs })
}

object AlertSeen {
    /** Before any recorded view everything is new. A message without a timestamp cannot be placed, so it is not. */
    fun isNew(message: AlertMessage, lastSeen: Long?): Boolean =
        lastSeen == null || (message.timestamp?.let { it > lastSeen } ?: false)
    fun newest(inbox: AlertInbox): Long? = inbox.messages.mapNotNull { it.timestamp }.maxOrNull()
    /** Last-seen only moves forward. */
    fun advance(previous: Long?, newest: Long?): Long? = listOfNotNull(previous, newest).maxOrNull()
}

enum class AlertSort(val title: String) { PRIORITY("Priority"), NEWEST("Newest") }

/** Empty sets mean "all". Search is case-insensitive over the raw and readable name, body and resolved object. */
data class AlertFilter(
    val severities: Set<AlertSeverity> = emptySet(), val kinds: Set<AlertObjectKind> = emptySet(),
    val query: String = "", val newOnly: Boolean = false, val sort: AlertSort = AlertSort.PRIORITY,
) {
    fun apply(messages: List<AlertMessage>, inventory: Inventory?, lastSeen: Long?): List<AlertMessage> {
        val search = query.trim()
        val matched = messages.filter { message ->
            (severities.isEmpty() || message.severity in severities) &&
                (kinds.isEmpty() || message.objectKind in kinds) &&
                (!newOnly || AlertSeen.isNew(message, lastSeen)) &&
                (search.isEmpty() || matches(message, AlertObjects.resolve(message, inventory), search))
        }
        return when (sort) {
            AlertSort.NEWEST -> matched.sortedWith(AlertMapper.NEWEST_FIRST)
            AlertSort.PRIORITY -> matched.sortedWith(compareBy<AlertMessage> { if (it.severity == AlertSeverity.UNRATED) 99 else it.severity.priority }
                .thenComparing(AlertMapper.NEWEST_FIRST))
        }
    }
    private fun matches(message: AlertMessage, resolved: ResolvedAlertObject, search: String): Boolean =
        listOf(message.name, AlertNames.humanize(message.name), message.body, resolved.name.orEmpty(), message.objectUuid,
            message.severity.label, message.objectKind.label).any { it.contains(search, ignoreCase = true) }
}

// ---- dismissing (message.destroy) -------------------------------------------------------------------------

data class AlertTarget(val ref: String, val uuid: String)

/**
 * REMOVED = the read-back shows the message is gone. STILL_PRESENT = the server still returns it.
 * REFUSED = XAPI explicitly rejected the destroy. NOT_SENT = skipped after an earlier permission refusal.
 * UNKNOWN = neither the destroy nor the read-back could be confirmed.
 */
enum class DismissStatus { REMOVED, STILL_PRESENT, REFUSED, NOT_SENT, UNKNOWN }

data class DismissResult(val ref: String, val status: DismissStatus, val code: String? = null, val permission: String? = null)

data class DismissOutcome(val results: List<DismissResult>) {
    val removed: Set<String> get() = results.filter { it.status == DismissStatus.REMOVED }.map { it.ref }.toSet()
    /** The first explicit permission refusal, which carries the server's reason. */
    val denied: DismissResult? get() = results.firstOrNull { it.code == "RBAC_PERMISSION_DENIED" || it.code == "PERMISSION_DENIED" }
    val unconfirmed: Int get() = results.count { it.status == DismissStatus.UNKNOWN || it.status == DismissStatus.STILL_PRESENT }
}

object AlertDismissal {
    const val MAX_BATCH = 100
}
// --- end A4 ---
