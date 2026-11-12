package com.meteor.app.data

// --- B3 background/summary ---
// The all-pools summary (one bounded read per saved profile) and the manual, read-only host update check.
// Pure: no Android, no network.

/** Counts from one short-lived session. [unreadUrgent] is null when the alert read was refused or failed. */
data class PoolSummaryRead(
    val authority: String, val poolName: String, val hostsLive: Int, val hostsTotal: Int,
    val vmsRunning: Int, val vmsTotal: Int, val unreadUrgent: Int?, val observedAt: Long,
    val alertsPartial: Boolean = false,
)

enum class PoolReachability(val label: String) {
    NOT_CHECKED("Not checked"), REACHABLE("Reachable"), UNREACHABLE("Unreachable"), CERTIFICATE_CHANGED("Certificate changed"),
}

/** One profile's last summary. It belongs to the profile's trusted [origin]; a row for an older origin is ignored. */
data class PoolSummaryRow(
    val profileId: String, val origin: String, val reachability: PoolReachability,
    val read: PoolSummaryRead? = null, val problem: String? = null, val checkedAt: Long? = null,
)

data class AllPoolsSummary(
    val rows: Map<String, PoolSummaryRow> = emptyMap(), val refreshedAt: Long? = null,
    val busy: Boolean = false, val checking: Set<String> = emptySet(),
)

object PoolSummaries {
    const val STALE_MILLIS = 5 * 60_000L
    const val MAX_PARALLEL = 3
    const val TIMEOUT_MILLIS = 20_000L
    /** The VM read excludes templates, snapshots and control domains on the server; the client filters again. */
    const val VM_FILTER = "field \"is_a_template\"=\"false\" and field \"is_control_domain\"=\"false\" and field \"is_a_snapshot\"=\"false\""

    private fun Map<String, Any?>.text(key: String) = this[key]?.toString().orEmpty()
    private fun Map<String, Any?>.flag(key: String) = this[key] == true || text(key) == "true" || text(key) == "1"

    fun counts(
        pools: Map<String, Map<String, Any?>>, hosts: Map<String, Map<String, Any?>>, hostMetrics: Map<String, Map<String, Any?>>,
        vms: Map<String, Map<String, Any?>>, unreadUrgent: Int?, alertsPartial: Boolean, now: Long,
    ): PoolSummaryRead {
        require(pools.size == 1) { "Direct XAPI must identify exactly one pool." }
        val pool = pools.values.single()
        val authority = pool.text("uuid").also { require(it.isNotBlank()) { "Missing pool identity." } }
        val guests = vms.values.filterNot { it.flag("is_a_template") || it.flag("is_control_domain") || it.flag("is_a_snapshot") }
        return PoolSummaryRead(authority, pool.text("name_label").ifBlank { "XCP-ng pool" },
            hosts.values.count { hostMetrics[it.text("metrics")]?.flag("live") == true }, hosts.size,
            guests.count { it.text("power_state") == "Running" }, guests.size, unreadUrgent, now, alertsPartial)
    }

    fun unread(messages: List<AlertMessage>, lastSeen: Long?): Int =
        messages.count { it.severity.urgent && AlertSeen.isNew(it, lastSeen) }

    fun certificateChanged(error: Throwable): Boolean = generateSequence(error) { it.cause }.take(8).any { it is CertificateChanged }

    fun failed(profile: Profile, error: Throwable, now: Long): PoolSummaryRow =
        if (certificateChanged(error)) PoolSummaryRow(profile.id, profile.origin(), PoolReachability.CERTIFICATE_CHANGED,
            problem = "The server certificate no longer matches. Open the connection to review it.", checkedAt = now)
        else PoolSummaryRow(profile.id, profile.origin(), PoolReachability.UNREACHABLE, problem = ErrorReasons.reason(error), checkedAt = now)

    fun timedOut(profile: Profile, now: Long): PoolSummaryRow = PoolSummaryRow(profile.id, profile.origin(), PoolReachability.UNREACHABLE,
        problem = "No answer within ${TIMEOUT_MILLIS / 1000} s.", checkedAt = now)

    fun reachable(profile: Profile, read: PoolSummaryRead, now: Long) =
        PoolSummaryRow(profile.id, profile.origin(), PoolReachability.REACHABLE, read, checkedAt = now)
}

// ---- read-only update availability (host.call_plugin updater.py check_update) ---------------------------------

data class HostUpdate(val name: String, val version: String?)

/**
 * [recognized] is false when the plugin answered in a shape Meteor does not know; nothing is then claimed about
 * whether updates exist. The response format is not verified against a live XCP-ng host.
 */
data class HostUpdateResult(val updates: List<HostUpdate>, val total: Int, val recognized: Boolean)

object HostUpdates {
    const val PLUGIN = "updater.py"
    const val FUNCTION = "check_update"
    const val MAX_SHOWN = 200
    const val READ_TIMEOUT_MILLIS = 90_000
    private val packageName = Regex("[A-Za-z0-9._+:~-]{1,100}")
    private val versionText = Regex("[A-Za-z0-9._+:~-]{1,60}")

    private fun name(value: Any?): String? = (value as? String)?.trim()?.takeIf { it.matches(packageName) }
    private fun version(item: Map<*, *>): String? {
        val parts = listOf(item["version"], item["release"]).mapNotNull { (it as? String)?.trim()?.takeIf { text -> text.matches(versionText) } }
        return parts.takeIf { it.isNotEmpty() }?.joinToString("-")
    }

    /**
     * The XCP-ng updater plugin is expected to return a JSON array encoded as a string, one object per package with at
     * least `name` (and usually `version`, `release`). Also accepted: an array of names, or an object keyed by name.
     * Anything else is "unrecognized". Names and versions outside a conservative character set are not shown.
     */
    fun parse(raw: Any?): HostUpdateResult {
        val unknown = HostUpdateResult(emptyList(), 0, recognized = false)
        val text = (raw as? String)?.trim()?.takeIf { it.isNotEmpty() && it.length <= 4 * 1024 * 1024 } ?: return unknown
        val json = runCatching { JsonLite.parse(text) }.getOrNull() ?: return unknown
        val entries: List<HostUpdate?> = when (json) {
            is List<*> -> json.map { item ->
                when (item) {
                    is Map<*, *> -> HostUpdate(name(item["name"]) ?: "Unnamed package", version(item))
                    is String -> HostUpdate(name(item) ?: "Unnamed package", null)
                    else -> null
                }
            }
            is Map<*, *> -> if (json.keys.any { it == "error" || it == "errors" }) return unknown
                else json.entries.map { (key, value) -> HostUpdate(name(key) ?: "Unnamed package", (value as? Map<*, *>)?.let(::version)) }
            else -> return unknown
        }
        if (entries.any { it == null }) return unknown
        val updates = entries.filterNotNull().sortedBy { it.name.lowercase() }
        return HostUpdateResult(updates.take(MAX_SHOWN), updates.size, recognized = true)
    }

    private val exceptionType = Regex("[A-Za-z][A-Za-z0-9_.]{0,59}")

    /** The server's reason, from closed vocabularies only. Plugin messages (which can contain mirror URLs) are not shown. */
    fun problem(error: Throwable): String = when {
        error is RpcFailure && error.code in setOf("RBAC_PERMISSION_DENIED", "PERMISSION_DENIED") ->
            "The server refused: ${ErrorReasons.rpc(error)} Running the updater plugin usually needs the pool-admin role."
        error is RpcFailure && error.code == "XENAPI_MISSING_PLUGIN" ->
            "The updater plugin is not installed on this host. (XENAPI_MISSING_PLUGIN)"
        error is RpcFailure && error.code == "XENAPI_PLUGIN_FAILURE" -> {
            val type = error.details.getOrNull(1)?.takeIf { it.matches(exceptionType) }?.let { " ($it)" }.orEmpty()
            "The host's updater plugin reported an error$type. Check the host's update configuration. (XENAPI_PLUGIN_FAILURE)"
        }
        error is RpcFailure && error.code == "UUID_INVALID" -> "This host is no longer in the pool. (UUID_INVALID)"
        else -> ErrorReasons.reason(error)
    }
}
// --- end B3 ---
