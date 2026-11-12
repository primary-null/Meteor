package com.meteor.app

import com.meteor.app.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// --- A4 alerts/events ---

/**
 * Owns the alert inbox: bounded first read, incremental `message.get_since`, last-seen tracking and confirmed
 * dismissal. Reads are fenced by profile, origin, pool and a request generation, and last-known alerts survive a
 * failed refresh. Dismissal is the only write; it is idempotent and deliberately not a VM power operation, so it
 * never touches the power journal, and it never reports success without a read-back.
 */
internal class AlertsController(
    private val scope: CoroutineScope,
    private val gateway: MeteorGateway,
    private val state: MutableStateFlow<MeteorState>,
    private val seen: AlertSeenStore,
    private val explain: (Throwable) -> String = ErrorReasons::reason,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Target(val profile: Profile, val authority: String, val scopeKey: String)

    private var generation = 0L
    private var job: Job? = null
    private var again = false
    private var againFull = false
    private var lastStart = Long.MIN_VALUE / 2

    /**
     * Reads need a selected profile with a known pool, not the `connected` flag: an explicit (re)connection clears that flag
     * for its whole duration, and the resync that follows lost events runs right after it. A write needs both.
     */
    private fun target(requireConnected: Boolean = false): Target? {
        val current = state.value
        val profile = current.selectedProfile ?: return null
        val authority = current.inventory?.authority ?: return null
        if (current.pendingTrust != null || requireConnected && !current.connected) return null
        return Target(profile, authority, AlertScope.key(profile, authority))
    }
    private fun stillCurrent(target: Target): Boolean = state.value.let { current ->
        current.pendingTrust == null && current.selectedProfile?.let { AlertScope.key(it, current.inventory?.authority) } == target.scopeKey
    }

    /** Disconnect or profile/pool change: drop the inbox and anything in flight. */
    fun invalidate() {
        generation++; job?.cancel(); job = null; again = false; againFull = false
        state.update { it.copy(alerts = null, alertsBusy = false, alertsProblem = null) }
    }

    /**
     * Reads alerts. The first read of a scope (and one every ten minutes) is a full bounded read; everything else is
     * `message.get_since` from the newest server timestamp seen, minus an overlap. A request that arrives while one
     * is running is remembered and runs once afterwards.
     */
    fun refresh(full: Boolean = false, minGapMillis: Long = 0) {
        val target = target() ?: return
        if (!full && minGapMillis > 0 && clock() - lastStart < minGapMillis) return
        if (job?.isActive == true) { again = true; againFull = againFull || full; return }
        val existing = state.value.alerts?.takeIf { it.scopeKey == target.scopeKey }
        val readFull = full || existing == null || clock() - existing.fullSyncAt >= AlertMerge.FULL_RECONCILE_MILLIS
        val request = ++generation
        lastStart = clock()
        state.update { it.copy(alertsBusy = true, alerts = existing) }
        job = scope.launch {
            try {
                val stored = existing?.lastSeen ?: runCatching { seen.lastSeen(target.profile.id) }.getOrNull()
                val since = existing?.takeUnless { readFull }?.let { AlertMerge.since(it.cursor) }
                val fetch = gateway.alerts(target.profile, target.authority, since)
                require(fetch.authority == target.authority) { "Pool identity changed." }
                if (request == generation && stillCurrent(target)) state.update { current ->
                    val base = current.alerts?.takeIf { it.scopeKey == target.scopeKey }
                    val merged = if (fetch.full || base == null) AlertMerge.full(target.scopeKey, fetch, AlertSeen.advance(base?.lastSeen, stored))
                        else AlertMerge.incremental(base, fetch)
                    current.copy(alerts = merged, alertsProblem = null)
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (request == generation && stillCurrent(target)) state.update { it.copy(alertsProblem = ErrorReasons.readIssue(error, "Alerts")) }
            } finally {
                if (request == generation) {
                    state.update { it.copy(alertsBusy = false) }
                    job = null
                    if (again) { val wantsFull = againFull; again = false; againFull = false; refresh(wantsFull) }
                }
            }
        }
    }

    /** Removes alerts that an event reported deleted, then re-reads incrementally. Deletions are not visible to `get_since`. */
    fun onMessageEvents(deleted: Set<String>, full: Boolean) {
        remove(deleted)
        refresh(full)
    }

    fun remove(refs: Set<String>) {
        if (refs.isEmpty()) return
        state.update { current -> current.copy(alerts = current.alerts?.let { AlertMerge.remove(it, refs) }) }
    }

    /** Called while the Alerts screen shows the list: records the newest server timestamp on screen as seen. */
    fun markSeen() {
        val current = state.value
        val profile = current.selectedProfile ?: return
        val inbox = current.currentAlerts ?: return
        val advanced = AlertSeen.advance(inbox.lastSeen, AlertSeen.newest(inbox)) ?: return
        if (advanced == inbox.lastSeen) return
        state.update { latest ->
            latest.copy(alerts = latest.alerts?.takeIf { it.scopeKey == inbox.scopeKey }?.copy(lastSeen = advanced) ?: latest.alerts)
        }
        scope.launch { runCatching { seen.save(profile.id, advanced) } }
    }

    /**
     * `message.destroy` for up to [AlertDismissal.MAX_BATCH] alerts. Only alerts the read-back shows as gone leave
     * the list. The outcome is reported, then the whole list is re-read from the server.
     */
    fun dismiss(messages: List<AlertMessage>) {
        val target = target(requireConnected = true) ?: return
        if (state.value.alertDismissBusy || messages.isEmpty()) return
        val chosen = messages.distinctBy { it.ref }.take(AlertDismissal.MAX_BATCH)
        state.update { it.copy(alertDismissBusy = true) }
        scope.launch {
            try {
                val outcome = gateway.dismissAlerts(target.profile, target.authority, chosen.map { AlertTarget(it.ref, it.uuid) })
                if (stillCurrent(target)) remove(outcome.removed)
                state.update { it.copy(notice = DismissNotices.of(outcome, chosen.size)) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                state.update { it.copy(notice = UiNotice.error("Alerts could not be dismissed. ${explain(error)} Nothing is assumed to have changed; the list will be re-read.")) }
            } finally {
                state.update { it.copy(alertDismissBusy = false) }
                refresh(full = true)
            }
        }
    }
}

internal object DismissNotices {
    fun of(outcome: DismissOutcome, requested: Int): UiNotice {
        val removed = outcome.removed.size
        val denied = outcome.denied
        if (denied == null && removed == requested && outcome.results.size == requested) {
            return UiNotice.info(if (requested == 1) "Alert dismissed. The server no longer lists it." else "$requested alerts dismissed. The server no longer lists them.")
        }
        val parts = mutableListOf("$removed of $requested confirmed removed by the server.")
        if (denied != null) {
            val reason = ErrorReasons.rpc(RpcFailure(requireNotNull(denied.code), details = listOfNotNull(denied.permission)))
            parts += "The server refused: $reason Dismissing alerts needs the pool-operator role or higher."
        }
        val others = outcome.results.filter { it.status == DismissStatus.REFUSED && it.code != denied?.code }.mapNotNull { it.code }.distinct()
        if (others.isNotEmpty()) parts += "The server refused some with ${others.joinToString(", ")}."
        if (outcome.unconfirmed > 0) parts += "${outcome.unconfirmed} could not be confirmed removed and stay listed until the server stops returning them."
        return if (denied != null) UiNotice.error(parts.joinToString(" ")) else UiNotice.warning(parts.joinToString(" "))
    }
}
// --- end A4 ---
