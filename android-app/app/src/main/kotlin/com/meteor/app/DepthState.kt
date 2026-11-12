package com.meteor.app

import com.meteor.app.data.*

/**
 * One on-demand read, keyed by the object it describes. A value that belongs to another key is never shown.
 * [stale] means the held value may no longer match the server and no read is in flight; the page reads again when it can.
 */
data class InfoSlot<T>(val key: String? = null, val value: T? = null, val busy: Boolean = false, val problem: String? = null,
    val stale: Boolean = false) {
    fun valueFor(expected: String): T? = value.takeIf { key == expected }
    fun busyFor(expected: String) = busy && key == expected
    fun problemFor(expected: String): String? = problem.takeIf { key == expected }
    fun staleFor(expected: String) = stale && key == expected
}

/**
 * Historical charts for [target]. A null [range] is the live view. [unavailable] explains why the live view is
 * shown instead of the history the user asked for. [requestedAt] is the phone clock of the latest attempt.
 */
data class HistoryState(
    val target: MetricTarget? = null, val range: HistoryRange? = null, val snapshot: HistorySnapshot? = null,
    val busy: Boolean = false, val problem: String? = null, val unavailable: String? = null, val requestedAt: Long = 0,
)

/** Everything the read-only observability depth feature keeps in memory. Nothing here is persisted. */
data class DepthState(
    val history: HistoryState = HistoryState(),
    val vm: InfoSlot<VmInfo> = InfoSlot(), val host: InfoSlot<HostInfo> = InfoSlot(),
    val networks: InfoSlot<NetworkOverview> = InfoSlot(), val storage: InfoSlot<StorageInfo> = InfoSlot(),
)
