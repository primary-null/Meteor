package com.meteor.app.data

// --- A4 alerts/events ---
// Live-update plumbing for XAPI `event.from`. Events are used only as a trigger to re-run the existing reads;
// state is never rebuilt from event payloads. The only payload fields read are operation, class and ref.

data class ServerEvent(val operation: String, val cls: String, val ref: String)
data class EventBatch(val token: String, val events: List<ServerEvent>)

/**
 * One long-lived XAPI session dedicated to `event.from`. It is separate from the per-call sessions, so a
 * blocked long-poll never sits in front of an inventory read or a power action.
 */
interface EventSession {
    /**
     * Returns the current event token. It subscribes to the small `pool` class only, so the first call does not
     * download a snapshot of every VM, template, VBD and VIF. The token is accepted for the full class list afterwards,
     * but a real server answers the first follow-up with a backlog of the newly named classes (34 events, up to about
     * 137 KB on a small pool; METEOR-LIVE-FINDINGS E3), so the first refresh is bigger than a later one.
     */
    suspend fun begin(): String
    /** Long-polls for [timeoutSeconds]; returns early when something changes. Cancelling aborts the HTTP call. */
    suspend fun next(token: String, timeoutSeconds: Double): EventBatch
    /** Idempotent, best-effort logout. */
    suspend fun close()
}

enum class EventStatus { OFF, CONNECTING, LIVE, RETRYING }

/** What the loop is allowed to refresh, and when. All values are documented in METEOR-A4-ALERTS-EVENTS.md. */
object EventPolicy {
    val CLASSES = listOf("VM", "host", "pool", "SR", "PBD", "VBD", "VIF", "task", "message")
    const val POLL_TIMEOUT_SECONDS = 30.0
    /** The HTTP read timeout is the server-side timeout plus this margin, so a quiet poll is never cut short. */
    const val READ_MARGIN_MILLIS = 20_000L
    fun readTimeoutMillis(timeoutSeconds: Double, marginMillis: Long = READ_MARGIN_MILLIS): Long =
        (timeoutSeconds * 1000).toLong() + marginMillis
    /** Events arriving within this window are merged into one refresh. */
    const val DEBOUNCE_MILLIS = 1_000L
    /** Even a busy pool triggers at most one coalesced refresh this often. */
    const val MIN_REFRESH_GAP_MILLIS = 5_000L
    /** Fallback inventory polling: the pre-existing 30 s, or this when live events are healthy. */
    const val POLLING_STALE_MILLIS = 30_000L
    const val HEALTHY_STALE_MILLIS = 90_000L
    /** How long a snapshot is called "Connected" on Overview. Longer when live events keep it honest. */
    const val FRESH_WINDOW_MILLIS = 45_000L
    const val HEALTHY_FRESH_WINDOW_MILLIS = 150_000L
    val BACKOFF_MILLIS = listOf(2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 60_000L)
}

/** Test-friendly copy of the policy numbers. */
data class EventTiming(
    val pollTimeoutSeconds: Double = EventPolicy.POLL_TIMEOUT_SECONDS,
    val debounceMillis: Long = EventPolicy.DEBOUNCE_MILLIS,
    val minRefreshGapMillis: Long = EventPolicy.MIN_REFRESH_GAP_MILLIS,
    val backoffMillis: List<Long> = EventPolicy.BACKOFF_MILLIS,
)

class Backoff(private val steps: List<Long>) {
    init { require(steps.isNotEmpty()) }
    private var index = 0
    fun next(): Long = steps[minOf(index++, steps.lastIndex)]
    fun longest(): Long { index = steps.size; return steps.last() }
    fun reset() { index = 0 }
}

object EventParser {
    fun batch(value: Any?): EventBatch {
        val map = value as? Map<*, *> ?: throw GatewayError("invalid-events")
        val token = map["token"] as? String ?: throw GatewayError("invalid-events")
        if (token.isBlank() || token.length > 200) throw GatewayError("invalid-events")
        val list = map["events"] as? List<*> ?: throw GatewayError("invalid-events")
        return EventBatch(token, list.mapNotNull { item ->
            val event = item as? Map<*, *> ?: return@mapNotNull null
            ServerEvent((event["operation"] as? String).orEmpty().lowercase().take(10),
                (event["class"] as? String).orEmpty().lowercase().take(40), (event["ref"] as? String).orEmpty().take(200))
        })
    }
}

/** Which of the existing refreshes a batch asks for. Deleted message references are the one payload use. */
data class EventTriggers(
    val inventory: Boolean = false, val details: Boolean = false, val alerts: Boolean = false,
    val activity: Boolean = false, val full: Boolean = false, val deletedMessages: Set<String> = emptySet(),
) {
    val any: Boolean get() = inventory || details || alerts || activity || full || deletedMessages.isNotEmpty()
    operator fun plus(other: EventTriggers) = EventTriggers(inventory || other.inventory, details || other.details,
        alerts || other.alerts, activity || other.activity, full || other.full, deletedMessages + other.deletedMessages)
    companion object {
        val NONE = EventTriggers()
        /** After (re)connecting the event session: events may have been missed, so everything is re-read incrementally. */
        val START = EventTriggers(inventory = true, details = true, alerts = true, activity = true)
        /** After EVENTS_LOST: the server dropped events, so alerts are re-read in full (that is the only way to see deletions). */
        val FULL = START.copy(full = true)
        fun classify(events: List<ServerEvent>): EventTriggers = events.fold(NONE) { total, event ->
            total + when (event.cls) {
                "vm" -> EventTriggers(inventory = true, details = true)
                "host", "pool", "sr", "pbd" -> EventTriggers(inventory = true)
                "vbd", "vif" -> EventTriggers(details = true)
                "task" -> EventTriggers(details = true, activity = true)
                "message" -> EventTriggers(alerts = true, activity = true,
                    deletedMessages = if (event.operation == "del" && event.ref.isNotBlank()) setOf(event.ref) else emptySet())
                else -> NONE
            }
        }
    }
}

enum class EventFailureKind {
    /**
     * The server dropped events, rejected the token (`EVENT_FROM_TOKEN_PARSE_FAILURE`, what a real XCP-ng 8.3 answers for
     * a malformed token) or the answer was too large: take a fresh token and re-read everything.
     */
    EVENTS_LOST,
    /** The session is gone: log in again and re-read everything. */
    SESSION_INVALID,
    /** Retrying quickly cannot help (wrong password, pool member, no permission): wait the longest backoff. */
    SLOW,
    TRANSIENT,
}

object EventFailures {
    private val slow = setOf("HOST_IS_SLAVE", "SESSION_AUTHENTICATION_FAILED", "RBAC_PERMISSION_DENIED", "PERMISSION_DENIED",
        "MESSAGE_METHOD_UNKNOWN", "NOT_IMPLEMENTED")
    private val lost = setOf("EVENTS_LOST", "EVENT_FROM_TOKEN_PARSE_FAILURE")
    fun classify(error: Throwable): EventFailureKind = when {
        error is RpcFailure && error.code in lost -> EventFailureKind.EVENTS_LOST
        error is RpcFailure && error.code == "SESSION_INVALID" -> EventFailureKind.SESSION_INVALID
        error is RpcFailure && error.code in slow -> EventFailureKind.SLOW
        error is UnsupportedOperationException -> EventFailureKind.SLOW
        error is GatewayError && error.reason == "response-limit" -> EventFailureKind.EVENTS_LOST
        error is IllegalArgumentException && error.message.orEmpty().startsWith("Response ") -> EventFailureKind.EVENTS_LOST
        else -> EventFailureKind.TRANSIENT
    }
}
// --- end A4 ---
