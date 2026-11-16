package com.meteor.app

import com.meteor.app.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// --- A4 alerts/events ---

/** The existing refreshes an event batch may request. Implemented by the view model; each one keeps its own gates. */
internal interface EventActions {
    fun refreshInventory()
    fun refreshDetails()
    fun refreshAlerts(deletedMessages: Set<String>, full: Boolean)
    fun refreshActivity()
}

/**
 * Live updates. While the activity is STARTED and a pool is connected, one separate XAPI session long-polls
 * `event.from`. Events are only a trigger: they are coalesced and then ask the existing reads to run again; no
 * state is rebuilt from event payloads.
 *
 * The loop is keyed by (profile, pool). Any change of that key, backgrounding, disconnecting or forgetting the
 * profile cancels it; cancellation aborts the in-flight HTTP call and logs the session out. Every effect is checked
 * against the current key and a loop generation first, so a late batch can never refresh a different connection.
 */
internal class EventController(
    private val scope: CoroutineScope,
    private val gateway: MeteorGateway,
    private val state: MutableStateFlow<MeteorState>,
    private val actions: EventActions,
    private val timing: EventTiming = EventTiming(),
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private data class Key(val profile: Profile, val authority: String)

    var loopGeneration = 0L
        private set
    private var runner: Job? = null

    private fun key(current: MeteorState): Key? {
        if (!current.foreground) return null
        // offline and reconnect: a second session would send the saved password again behind the stopped reconnect, or retry with no network.
        if (current.offline || current.retryStop != null) return null
        val profile = current.selectedProfile ?: return null
        val authority = current.inventory?.authority ?: return null
        return Key(profile, authority)
    }
    private fun publish(status: EventStatus) { state.update { if (it.events == status) it else it.copy(events = status) } }

    fun start() {
        if (runner != null) return
        runner = scope.launch {
            state.map(::key).distinctUntilChanged().collectLatest { key ->
                if (key == null) publish(EventStatus.OFF) else run(key)
            }
        }
    }
    fun stop() { runner?.cancel(); runner = null }

    private suspend fun run(key: Key) {
        val generation = ++loopGeneration
        fun current() = generation == loopGeneration && key(state.value) == key
        val backoff = Backoff(timing.backoffMillis)
        var session: EventSession? = null
        var token = ""
        var consecutiveInvalid = 0
        publish(EventStatus.CONNECTING)
        try {
            coroutineScope {
                val flusher = Flusher(this, ::current)
                while (true) {
                    if (session == null) {
                        try {
                            val opened = gateway.openEvents(key.profile, key.authority)
                            session = opened
                            token = opened.begin()
                            if (!current()) return@coroutineScope
                            publish(EventStatus.LIVE)
                            backoff.reset()
                            // Events may have been missed before this session existed.
                            flusher.add(EventTriggers.START)
                        } catch (error: Exception) {
                            if (error is CancellationException) throw error
                            closeQuietly(session); session = null
                            if (!current()) return@coroutineScope
                            publish(EventStatus.RETRYING)
                            delay(if (EventFailures.classify(error) == EventFailureKind.SLOW) backoff.longest() else backoff.next())
                            continue
                        }
                    }
                    try {
                        val batch = requireNotNull(session).next(token, timing.pollTimeoutSeconds)
                        if (!current()) return@coroutineScope
                        token = batch.token
                        consecutiveInvalid = 0
                        publish(EventStatus.LIVE)
                        backoff.reset()
                        EventTriggers.classify(batch.events).takeIf { it.any }?.let(flusher::add)
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        if (!current()) return@coroutineScope
                        when (EventFailures.classify(error)) {
                            EventFailureKind.EVENTS_LOST -> try {
                                // Take a fresh token on the same session and re-read everything.
                                token = requireNotNull(session).begin()
                                flusher.add(EventTriggers.FULL)
                            } catch (second: Exception) {
                                if (second is CancellationException) throw second
                                closeQuietly(session); session = null
                                publish(EventStatus.RETRYING)
                                delay(backoff.next())
                            }
                            EventFailureKind.SESSION_INVALID -> {
                                closeQuietly(session); session = null
                                // The first expiry logs in again at once; a repeat without progress backs off.
                                if (++consecutiveInvalid > 1) { publish(EventStatus.RETRYING); delay(backoff.next()) }
                            }
                            EventFailureKind.SLOW -> {
                                closeQuietly(session); session = null
                                publish(EventStatus.RETRYING); delay(backoff.longest())
                            }
                            EventFailureKind.TRANSIENT -> {
                                closeQuietly(session); session = null
                                publish(EventStatus.RETRYING); delay(backoff.next())
                            }
                        }
                    }
                }
            }
        } finally {
            withContext(NonCancellable) {
                closeQuietly(session)
                // Only the loop that still owns the status clears it; a newer loop publishes its own.
                if (generation == loopGeneration) publish(EventStatus.OFF)
            }
        }
    }

    private suspend fun closeQuietly(session: EventSession?) {
        if (session == null) return
        withContext(NonCancellable) { runCatching { session.close() } }
    }

    /**
     * Debounce and coalesce. The first event of a burst schedules one flush after [EventTiming.debounceMillis];
     * later events only merge into it, so a constant stream cannot postpone the refresh forever. Flushes are
     * at least [EventTiming.minRefreshGapMillis] apart.
     */
    private inner class Flusher(private val loop: CoroutineScope, private val current: () -> Boolean) {
        private var pending = EventTriggers.NONE
        private var job: Job? = null
        private var lastFlush: Long? = null
        fun add(triggers: EventTriggers) {
            pending += triggers
            if (job?.isActive == true) return
            job = loop.launch {
                val earliest = lastFlush?.let { it + timing.minRefreshGapMillis - clock() } ?: 0L
                delay(maxOf(timing.debounceMillis, earliest))
                val due = pending
                pending = EventTriggers.NONE
                lastFlush = clock()
                if (current()) apply(due)
            }
        }
    }

    private fun apply(triggers: EventTriggers) {
        if (triggers.inventory) actions.refreshInventory()
        if (triggers.details) actions.refreshDetails()
        if (triggers.alerts || triggers.deletedMessages.isNotEmpty()) actions.refreshAlerts(triggers.deletedMessages, triggers.full)
        if (triggers.activity) actions.refreshActivity()
    }
}
// --- end A4 ---
