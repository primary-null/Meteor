package com.meteor.app

import com.meteor.app.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.CoroutineContext

// --- B3 background/summary ---

/** Result of a call that is waited for at most a fixed time. */
internal sealed interface Bounded<out T> {
    data class Done<T>(val value: T) : Bounded<T>
    data class Failed(val error: Throwable) : Bounded<Nothing>
    data object TimedOut : Bounded<Nothing>
}

/**
 * Runs [block] detached on [context] and waits at most [timeoutMillis]. Blocking HTTP reads do not observe coroutine
 * cancellation, so a plain `withTimeout` around them would still wait for the socket timeouts. Here the caller stops
 * waiting at the deadline; the abandoned call is cancelled and finishes on its own socket timeouts, and its result is
 * discarded (it can only log out its own session). Cancelling the caller cancels the call too.
 */
internal suspend fun <T> bounded(timeoutMillis: Long, context: CoroutineContext, block: suspend () -> T): Bounded<T> {
    val call = CoroutineScope(context + SupervisorJob()).async { block() }
    try {
        withTimeoutOrNull(timeoutMillis) { call.join() } ?: return Bounded.TimedOut
        return try { Bounded.Done(call.await()) } catch (error: Throwable) {
            currentCoroutineContext().ensureActive()
            Bounded.Failed(error)
        }
    } finally { if (!call.isCompleted) call.cancel() }
}

/** Posts notifications. Implemented with NotificationManager on Android and by a recorder in tests. */
interface AlertNotifier {
    /** False when the system blocks Meteor's notifications; a run then does nothing (no login, no watermark change). */
    val canPost: Boolean
    fun post(batch: AlertNotificationBatch)
    fun attention(profileId: String, text: AttentionText)
    fun clearAttention(profileId: String)
}

/** Removes alert notifications that no longer apply. The in-app Alerts inbox uses it. */
interface AlertNotificationCleaner {
    /** Cancels every alert notification of this profile and its group summary (not the "needs attention" one). */
    fun clearProfile(profileId: String)
    /** Cancels the notifications of these alerts (message references) and counts the group summary again from what is left. */
    fun clearAlerts(profileId: String, refs: Collection<String>)
}

data class BackgroundRunReport(
    val outcomes: Map<String, CheckOutcome> = emptyMap(), val notified: Int = 0,
    val vaultUnavailable: Boolean = false, val blocked: Boolean = false, val removedProfiles: Set<String> = emptySet(),
    /** The WorkManager period still needed afterwards, or null when no profile is opted in. */
    val periodMinutes: Int? = null,
    /** True when a transport failure or timeout is waiting for a quick retry, so the worker asks WorkManager to run again after a backoff. */
    val retryWanted: Boolean = false,
)

/**
 * One background alert check. Pure apart from its injected parts, so it is tested on the JVM.
 *
 * - Reads the existing encrypted profile vault; never writes it, never changes a certificate pin.
 * - For each opted-in, due profile (at most [BackgroundAlerts.MAX_PROFILES_PER_RUN], least recently checked first):
 *   exactly one gateway call, which is one login, `pool.get_all_records`, A4's `message.get_since`, a few name
 *   reads and one logout. No retry in the same run, no server write of any kind, no power journal access.
 * - Each profile is waited for at most [profileBudgetMillis]; the run stops starting profiles once fewer than that
 *   remain of [runBudgetMillis]. Skipped profiles stay due for the next run.
 * - The watermark is saved before notifications are posted, so a crash can lose a notification but never repeat one.
 * - Alerts the in-app Alerts inbox has already shown (`seen`) are handled but not notified.
 * - Every attempt that ends is recorded per profile: its time and outcome, the last successful time and the failures in a
 *   row, so Settings can say what happened. A transport failure or timeout is retried sooner (see [BackgroundAlerts.due]);
 *   a changed certificate, refused credentials, a pool-member answer and server errors wait for the normal interval.
 * - A pin mismatch, an authentication failure or a pool-member answer posts one "needs attention" notification per
 *   distinct problem; so do [BackgroundAlerts.FAILING_AFTER] failed checks in a row. Nothing is changed on the device or the server.
 */
class BackgroundAlertRunner(
    private val vault: ProfileStorage,
    private val gateway: MeteorGateway,
    private val store: BackgroundAlertStore,
    private val notifier: AlertNotifier,
    /** The in-app "seen" value, read only. */
    private val seen: AlertSeenStore? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ioContext: CoroutineContext = Dispatchers.IO,
    private val runBudgetMillis: Long = RUN_BUDGET_MILLIS,
    private val profileBudgetMillis: Long = PROFILE_BUDGET_MILLIS,
) {
    companion object {
        const val RUN_BUDGET_MILLIS = 4 * 60_000L
        const val PROFILE_BUDGET_MILLIS = 45_000L
    }

    suspend fun run(): BackgroundRunReport {
        val started = clock()
        val document = try { vault.load() } catch (error: Exception) {
            if (error is CancellationException) throw error
            // An unreadable vault is preserved as is; nothing is checked and nothing is pruned.
            val period = runCatching { BackgroundAlerts.periodMinutes(store.allSettings().values) }.getOrNull()
            if (period != null) note(started, RunProblem.VAULT_UNAVAILABLE)
            return BackgroundRunReport(vaultUnavailable = true, periodMinutes = period)
        }
        val removed = store.retainOnly(document.profiles.map { it.id }.toSet())
        val settings = store.allSettings()
        val period = BackgroundAlerts.periodMinutes(settings.values)
        if (period == null) return BackgroundRunReport(removedProfiles = removed)
        if (!notifier.canPost) { note(started, RunProblem.NOTIFICATIONS_BLOCKED); return BackgroundRunReport(blocked = true, removedProfiles = removed, periodMinutes = period) }
        note(started, RunProblem.OK)
        val due = BackgroundAlerts.due(document.profiles, settings, store.statuses(), started)
        val outcomes = LinkedHashMap<String, CheckOutcome>()
        var notified = 0
        for (profile in due) {
            if (clock() - started > runBudgetMillis - profileBudgetMillis) { outcomes[profile.id] = CheckOutcome.SKIPPED_BUDGET; continue }
            val setting = settings.getValue(profile.id)
            val (outcome, count) = check(profile, setting)
            outcomes[profile.id] = outcome
            notified += count
        }
        // Any opted-in profile still waiting for a quick retry asks WorkManager to run again, even if this run was too early for it.
        val statuses = store.statuses()
        val retryWanted = settings.any { (id, setting) -> setting.enabled && BackgroundAlerts.retryPending(statuses[id]) }
        return BackgroundRunReport(outcomes, notified, removedProfiles = removed, periodMinutes = period, retryWanted = retryWanted)
    }

    /** Best effort: a note that cannot be saved must not stop the check. */
    private suspend fun note(at: Long, problem: RunProblem) {
        try { store.setRunNote(RunNote(at, problem)) } catch (error: Exception) { if (error is CancellationException) throw error }
    }

    private suspend fun check(profile: Profile, setting: AlertNotifySettings): Pair<CheckOutcome, Int> {
        val now = clock()
        val previous = store.watermark(profile.id)
        val before = store.statuses()[profile.id]
        val inAppSeen = try { seen?.lastSeen(profile.id) } catch (error: Exception) { if (error is CancellationException) throw error; null }
        // Only an attempt that ends is recorded (below), so a run that is cancelled or killed midway leaves the profile due.
        val since = BackgroundSince { authority, serverClock -> BackgroundAlerts.since(setting, previous, authority, serverClock) }
        var notifiedCount = 0
        val outcome: CheckOutcome = when (val result = bounded(profileBudgetMillis, ioContext) { gateway.backgroundAlerts(profile, since, BackgroundAlerts.wanted(setting)) }) {
            is Bounded.Done -> try {
                val fetch = result.value
                val decision = BackgroundAlerts.decide(profile, setting, previous, fetch, inAppSeen)
                store.setWatermark(profile.id, decision.watermark, rewind = decision.rewound)
                if (store.attention(profile.id) != null) { store.setAttention(profile.id, null); runCatching { notifier.clearAttention(profile.id) } }
                val batch = AlertNotificationContent.batch(profile, fetch, decision.notify)
                if (batch == null) CheckOutcome.NOTHING_NEW
                else if (runCatching { notifier.post(batch) }.isSuccess) CheckOutcome.NOTIFIED.also { notifiedCount = decision.notify.size }
                else CheckOutcome.NOTIFY_FAILED
            } catch (error: ServerTimeUnavailable) { CheckOutcome.SERVER_TIME_UNAVAILABLE }
            is Bounded.Failed -> {
                val kind = attentionKind(result.error)
                if (kind != null) postAttention(profile, kind)
                when {
                    kind == AttentionKind.CERTIFICATE_CHANGED -> CheckOutcome.CERTIFICATE_CHANGED
                    kind == AttentionKind.AUTHENTICATION_FAILED -> CheckOutcome.AUTHENTICATION_FAILED
                    kind == AttentionKind.POOL_MEMBER -> CheckOutcome.POOL_MEMBER
                    result.error is ServerTimeUnavailable -> CheckOutcome.SERVER_TIME_UNAVAILABLE
                    result.error is RpcFailure || result.error is GatewayError -> CheckOutcome.SERVER_ERROR
                    else -> CheckOutcome.FAILED
                }
            }
            Bounded.TimedOut -> CheckOutcome.TIMED_OUT
        }
        val status = CheckStatus.after(before, now, outcome)
        store.setStatus(profile.id, status)
        // Checks that keep failing without a reason that already has its own notification: say so once.
        if (!outcome.success && !outcome.needsUser && status.failures >= BackgroundAlerts.FAILING_AFTER) postAttention(profile, AttentionKind.CHECKS_FAILING)
        return outcome to notifiedCount
    }

    private suspend fun postAttention(profile: Profile, kind: AttentionKind) {
        val marker = BackgroundAlerts.attentionMarker(kind, profile)
        if (store.attention(profile.id) != marker && runCatching { notifier.attention(profile.id, AlertNotificationContent.attention(kind, profile)) }.isSuccess)
            store.setAttention(profile.id, marker)
    }

    private fun attentionKind(error: Throwable): AttentionKind? = when {
        PoolSummaries.certificateChanged(error) -> AttentionKind.CERTIFICATE_CHANGED
        error is RpcFailure && error.code == "SESSION_AUTHENTICATION_FAILED" -> AttentionKind.AUTHENTICATION_FAILED
        error is RpcFailure && error.code == "HOST_IS_SLAVE" -> AttentionKind.POOL_MEMBER
        else -> null
    }
}
// --- end B3 ---
