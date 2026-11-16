package com.meteor.app.data

import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/*
 * Journaled operation hardening: how long PowerService keeps observing one sent operation.
 *
 * Observation is read-only. A budget only decides when Meteor stops watching and records
 * "still unverified"; it never decides success. Success still needs the task to finish and the
 * post-task state read-back to match the kind's proof. Nothing here sends or resends a request.
 * "Check status" after the budget, or recovery after an app restart, resumes observation by the
 * saved task UUID with a fresh budget.
 */
object TrackingPolicy {
    const val FAST_INTERVAL_MILLIS = 1_500L
    const val SLOW_INTERVAL_MILLIS = 5_000L
    /** Observations at the fast interval before polling slows down. */
    const val FAST_POLLS = 40
    const val SHORT_MILLIS = 2 * 60_000L
    /** After the task reports success, the read-back has this long to show the proven end state. */
    const val VERIFY_MILLIS = 2 * 60_000L
    /** Consecutive transient observation failures tolerated before tracking stops as "still unverified". */
    const val TRANSIENT_FAILURES = 8

    /** Wait before observation number [poll] + 1 (zero-based [poll]). */
    fun interval(poll: Int): Long = if (poll < FAST_POLLS) FAST_INTERVAL_MILLIS else SLOW_INTERVAL_MILLIS

    fun budgetMillis(operation: PowerOperation): Long = operation.targetAction?.let(::budgetMillis) ?: budgetMillis(operation.action)

    /** Instant kinds keep the short budget; kinds that wait for a guest, copy memory or move VMs get longer ones. */
    fun budgetMillis(action: PowerAction): Long = when (action) {
        PowerAction.PAUSE, PowerAction.UNPAUSE, PowerAction.UNRECOGNIZED, PowerAction.TARGET -> SHORT_MILLIS
        PowerAction.START, PowerAction.START_ON, PowerAction.RESUME, PowerAction.HARD_SHUTDOWN, PowerAction.HARD_REBOOT -> 5 * 60_000L
        // A clean shutdown or reboot waits for the guest to shut itself down; suspend writes all of its memory.
        PowerAction.SHUTDOWN, PowerAction.REBOOT, PowerAction.SUSPEND -> 20 * 60_000L
        PowerAction.POOL_MIGRATE -> 30 * 60_000L
        // --- VM settings --- no task is observed; the budget only bounds a read-back.
        PowerAction.SETTINGS -> SHORT_MILLIS
        // --- end VM settings ---
    }

    fun budgetMillis(action: TargetAction): Long = when (action) {
        TargetAction.CD_INSERT, TargetAction.CD_EJECT, TargetAction.HOST_DISABLE, TargetAction.HOST_ENABLE -> SHORT_MILLIS
        TargetAction.PBD_PLUG -> 5 * 60_000L
        TargetAction.SNAPSHOT_TAKE, TargetAction.SNAPSHOT_REVERT, TargetAction.SNAPSHOT_DELETE -> 10 * 60_000L
        TargetAction.HOST_SHUTDOWN -> 20 * 60_000L
        TargetAction.HOST_REBOOT -> 30 * 60_000L
        // Evacuation live-migrates every VM on the host, one after another.
        TargetAction.HOST_EVACUATE -> 60 * 60_000L
    }

    /**
     * A failure worth observing again: a network or HTTP failure, or a reply that is not an explicit
     * XAPI answer. An explicit XAPI error, an identity mismatch or an unknown task state ends tracking.
     */
    fun transient(error: Throwable): Boolean =
        error is IOException || error is GatewayError || error is RpcFailure && !error.explicitRejection

    /** A server progress estimate worth showing: finite and within 0..1. Completion is still decided by the read-back. */
    fun shownProgress(value: Double?): Double? = value?.takeIf { it.isFinite() && it in 0.0..1.0 }
}

// --- stop watching ---
/**
 * Thrown out of [PowerService] when the person chose "Stop watching". It is a [CancellationException], so every
 * existing `catch (Exception)` in the operation path passes it through untouched and none of them records an outcome.
 * By the time it is thrown the record has been saved as still unverified (see [PowerService]).
 */
class StopWatching : CancellationException("Stopped watching the server task.")

/**
 * One run's "Stop watching" switch. [stop] cancels only the observation loop that is running (or makes the next one
 * end before its first read); it never interrupts a journal write or a send, never cancels a server task and never
 * closes the record. A new watch is made for every run, so a stop can never leak into a later operation.
 * The caller runs [PowerService] with it in the coroutine context: `withContext(watch) { power.reconcile(...) }`.
 */
class TrackingWatch : AbstractCoroutineContextElement(TrackingWatch) {
    companion object Key : CoroutineContext.Key<TrackingWatch>
    private val stopped = AtomicBoolean(false)
    private val observer = AtomicReference<Job?>(null)
    val stopRequested: Boolean get() = stopped.get()
    fun stop() { if (stopped.compareAndSet(false, true)) observer.get()?.cancel(StopWatching()) }
    internal fun attach(job: Job) { observer.set(job); if (stopped.get()) job.cancel(StopWatching()) }
    internal fun detach(job: Job) { observer.compareAndSet(job, null) }
}

/** What the operation card says about a long wait. Plain functions so the wording and the rules are tested. */
object OperationWatch {
    private val WATCHED = setOf(PowerPhase.TRACKING, PowerPhase.VERIFYING)

    /** A sent operation whose server task is known by a saved UUID, so stopping now and "Check status" later is exactly restart recovery. */
    fun watchable(operation: PowerOperation): Boolean =
        operation.unresolved && operation.phase in WATCHED && !operation.taskUuid.isNullOrBlank() && operation.foreign == null

    /** A record a stop may rewrite as still unverified: unresolved, interpretable and past its send. */
    fun stoppable(operation: PowerOperation): Boolean = operation.unresolved && operation.foreign == null &&
        operation.phase in setOf(PowerPhase.TRACKING, PowerPhase.VERIFYING, PowerPhase.UNKNOWN)

    /** Kinds whose budget is ten minutes or more get the "this can take a while" sentence. */
    fun long(operation: PowerOperation): Boolean = TrackingPolicy.budgetMillis(operation) >= 10 * 60_000L

    /** How long ago the record was journaled, just before its single send. A clock step back never shows a negative time. */
    fun elapsed(operation: PowerOperation, now: Long): String {
        val minutes = ((now - operation.createdAt).coerceAtLeast(0) / 60_000L)
        return when {
            minutes < 1 -> "Sent less than a minute ago."
            minutes < 60 -> "Sent $minutes min ago."
            else -> "Sent ${minutes / 60} h ${minutes % 60} min ago."
        }
    }

    fun longWait(operation: PowerOperation): String? = if (!long(operation)) null else
        "This can take a while; Meteor watches for up to ${TrackingPolicy.budgetMillis(operation) / 60_000L} min. You can keep using the app meanwhile."

    const val STOP_HINT = "Stops watching only. The server task keeps running and nothing is resent."

    /** The saved message after a stop. A delete's remaining sub-steps are never sent automatically, as after a restart. */
    fun stoppedMessage(operation: PowerOperation): String =
        "Stopped watching. Nothing was cancelled on the server and nothing will be resent." +
            (if (operation.steps.orEmpty().any { it.state == StepState.PENDING }) " The remaining sub-steps will not be sent." else "") +
            " Check status to watch the task again."
}
// --- end stop watching ---
