package com.meteor.app

import com.meteor.app.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class EventControllerTest {
    private val profile = Profile("p1", "Lab", "https://lab.invalid/", "root", "fixture", "a".repeat(64))
    private val other = Profile("p2", "Other", "https://other.invalid/", "root", "fixture", "b".repeat(64))
    private fun inventory(authority: String = "pool") = Inventory(authority, "Pool", emptyList(), emptyList(), 0, 0, 1)
    private fun ready(foreground: Boolean = true) = MeteorState(profiles = listOf(profile, other), selectedId = profile.id,
        connected = true, inventory = inventory(), foreground = foreground)

    /** A scripted event session: begin() hands out tokens, next() waits for the test to supply a batch or a failure. */
    private class Session(val id: Int) : EventSession {
        val polls = Channel<suspend () -> EventBatch>(Channel.UNLIMITED)
        val tokensSeen = mutableListOf<String>()
        var begins = 0
        var closes = 0
        var onBegin: suspend () -> String = { "t$id.${begins}" }
        override suspend fun begin(): String { begins++; return onBegin() }
        override suspend fun next(token: String, timeoutSeconds: Double): EventBatch {
            tokensSeen += token
            return polls.receive()()
        }
        override suspend fun close() { closes++ }
        fun batch(token: String, vararg events: ServerEvent) { polls.trySend { EventBatch(token, events.toList()) } }
        fun fail(error: Throwable) { polls.trySend { throw error } }
    }
    private class Backend(val clock: () -> Long) : MeteorGateway {
        val opened = mutableListOf<Triple<Profile, String, Long>>()
        val sessions = ArrayDeque<Session>()
        val openFailures = ArrayDeque<Throwable>()
        override suspend fun openEvents(profile: Profile, authority: String): EventSession {
            opened += Triple(profile, authority, clock())
            openFailures.removeFirstOrNull()?.let { throw it }
            return sessions.removeFirst()
        }
        override suspend fun inventory(profile: Profile): Inventory = error("Unexpected inventory")
        override suspend fun details(profile: Profile, authority: String, uuid: String): VmDetails = error("Unexpected details")
        override suspend fun <T> powerConnection(profile: Profile, action: suspend PowerConnection.() -> T): T = error("Events never use the power path")
    }
    private class Actions : EventActions {
        val calls = mutableListOf<String>()
        var deleted = mutableListOf<Set<String>>()
        override fun refreshInventory() { calls += "inventory" }
        override fun refreshDetails() { calls += "details" }
        override fun refreshAlerts(deletedMessages: Set<String>, full: Boolean) { calls += if (full) "alerts-full" else "alerts"; deleted += deletedMessages }
        override fun refreshActivity() { calls += "activity" }
        fun take() = calls.toList().also { calls.clear(); deleted.clear() }
    }
    private fun vm(op: String = "mod") = ServerEvent(op, "vm", "OpaqueRef:vm")
    private class Fixture(val scope: CoroutineScope, val state: MutableStateFlow<MeteorState>, clock: () -> Long) {
        val backend = Backend(clock)
        val actions = Actions()
        val controller = EventController(scope, backend, state, actions, EventTiming(), clock)
    }
    private fun TestScope.fixture(state: MutableStateFlow<MeteorState>) = Fixture(backgroundScope, state) { testScheduler.currentTime }
    private fun TestScope.session(f: Fixture, id: Int = f.backend.sessions.size + 1) = Session(id).also { f.backend.sessions += it }

    @Test fun startsOnlyInTheForegroundWithAConnectedPoolAndStopsCleanlyInTheBackground() = runTest {
        val state = MutableStateFlow(ready(foreground = false))
        val f = fixture(state); val s = session(f)
        f.controller.start(); runCurrent()
        assertTrue(f.backend.opened.isEmpty()); assertEquals(EventStatus.OFF, state.value.events)
        state.value = state.value.copy(foreground = true); runCurrent()
        assertEquals(listOf(Triple(profile, "pool", 0L)), f.backend.opened)
        assertEquals(1, s.begins); assertEquals(EventStatus.LIVE, state.value.events); assertTrue(state.value.eventsHealthy)
        state.value = state.value.copy(foreground = false); runCurrent()
        assertEquals(1, s.closes); assertEquals(EventStatus.OFF, state.value.events); assertFalse(state.value.eventsHealthy)
        // Disconnecting has the same effect as backgrounding.
        s.fail(IOException())            // nothing is listening any more
        state.value = state.value.copy(foreground = true, selectedId = null); runCurrent()
        assertEquals(1, f.backend.opened.size)
    }
    @Test fun noPoolIdentityMeansNoSession() = runTest {
        val state = MutableStateFlow(ready().copy(inventory = null))
        val f = fixture(state); session(f)
        f.controller.start(); runCurrent()
        assertTrue(f.backend.opened.isEmpty())
        state.value = state.value.copy(inventory = inventory()); runCurrent()
        assertEquals(1, f.backend.opened.size)
    }
    @Test fun tokensProgressFromTheFirstCallAndEveryBatch() = runTest {
        val state = MutableStateFlow(ready()); val f = fixture(state); val s = session(f)
        f.controller.start(); runCurrent()
        s.batch("tok-1"); s.batch("tok-2"); s.batch("tok-3"); runCurrent()
        assertEquals(listOf("t1.1", "tok-1", "tok-2", "tok-3"), s.tokensSeen)       // each call carries the previous answer's token
        assertEquals(1, s.begins)
        assertEquals(1, f.backend.opened.size)                                       // one extra session, kept for the whole foreground
    }

    // ---- debounce, coalescing, rate limit ----
    @Test fun aBurstBecomesOneRefreshAndTheStartResyncIsOneToo() = runTest {
        val state = MutableStateFlow(ready()); val f = fixture(state); val s = session(f)
        f.controller.start(); runCurrent()
        assertEquals(emptyList<String>(), f.actions.take())                          // nothing before the debounce
        advanceTimeBy(999); runCurrent(); assertEquals(emptyList<String>(), f.actions.calls)
        advanceTimeBy(1); runCurrent()
        assertEquals(listOf("inventory", "details", "alerts", "activity"), f.actions.take())       // (re)connect re-reads once, not in full
        // Ten batches within the same second: one flush, after the debounce and the 5 s minimum gap.
        repeat(10) { s.batch("t$it", vm()) }; runCurrent()
        advanceTimeBy(4_999); runCurrent(); assertEquals(emptyList<String>(), f.actions.calls)
        advanceTimeBy(1); runCurrent()
        assertEquals(listOf("inventory", "details"), f.actions.take())
        advanceTimeBy(60_000); runCurrent(); assertEquals(emptyList<String>(), f.actions.calls)
    }
    @Test fun aQuietPoolRefreshesAfterTheDebounceAndAConstantStreamStillRefreshesOnSchedule() = runTest {
        val state = MutableStateFlow(ready()); val f = fixture(state); val s = session(f)
        f.controller.start(); runCurrent(); advanceTimeBy(1000); runCurrent(); f.actions.take()
        advanceTimeBy(20_000); runCurrent()                                          // long quiet period
        s.batch("a", ServerEvent("add", "message", "OpaqueRef:m")); runCurrent()
        advanceTimeBy(999); runCurrent(); assertEquals(emptyList<String>(), f.actions.calls)
        advanceTimeBy(1); runCurrent(); assertEquals(listOf("alerts", "activity"), f.actions.take())    // 1 s debounce only: gap already elapsed
        // A flush keeps happening every 5 s while events never stop: the first event of a burst fixes the deadline.
        var flushes = 0
        repeat(30) { second -> s.batch("x$second", vm()); advanceTimeBy(1000); runCurrent(); if (f.actions.take().isNotEmpty()) flushes++ }
        assertEquals(6, flushes)
    }
    @Test fun eventClassesPickTheRefreshesAndDeletedMessageReferencesAreHandedOver() = runTest {
        val state = MutableStateFlow(ready()); val f = fixture(state); val s = session(f)
        f.controller.start(); runCurrent(); advanceTimeBy(1000); runCurrent(); f.actions.take()
        advanceTimeBy(10_000)
        s.batch("a", ServerEvent("mod", "vbd", "r"), ServerEvent("mod", "session", "r")); runCurrent()
        advanceTimeBy(1000); runCurrent(); assertEquals(listOf("details"), f.actions.take())
        advanceTimeBy(10_000)
        s.batch("b", ServerEvent("del", "message", "OpaqueRef:gone"), ServerEvent("add", "message", "OpaqueRef:new")); runCurrent()
        advanceTimeBy(1000); runCurrent()
        assertEquals(listOf("alerts", "activity"), f.actions.calls); assertEquals(listOf(setOf("OpaqueRef:gone")), f.actions.deleted)
        f.actions.take(); advanceTimeBy(10_000)
        s.batch("c", ServerEvent("mod", "session", "r")); runCurrent(); advanceTimeBy(5_000); runCurrent()
        assertEquals(emptyList<String>(), f.actions.take())                          // unknown classes trigger nothing
    }

    // ---- losing events, losing the session ----
    @Test fun eventsLostTakesAFreshTokenOnTheSameSessionAndReadsEverythingInFull() = runTest {
        val state = MutableStateFlow(ready()); val f = fixture(state); val s = session(f)
        f.controller.start(); runCurrent(); advanceTimeBy(1000); runCurrent(); f.actions.take()
        s.batch("tok-1"); runCurrent()
        s.onBegin = { "fresh-token" }
        s.fail(RpcFailure("EVENTS_LOST")); runCurrent()
        assertEquals(2, s.begins); assertEquals(1, f.backend.opened.size); assertEquals(0, s.closes)
        s.batch("tok-after"); runCurrent()
        assertEquals(listOf("t1.1", "tok-1", "fresh-token", "tok-after"), s.tokensSeen)   // polling resumes from the fresh token
        advanceTimeBy(10_000); runCurrent()
        assertEquals(listOf("inventory", "details", "alerts-full", "activity"), f.actions.take())
        assertEquals(EventStatus.LIVE, state.value.events)
    }
    @Test fun aTokenTheServerCannotParseTakesAFreshTokenLikeLostEventsAndDoesNotBackOff() = runTest {
        val state = MutableStateFlow(ready()); val f = fixture(state); val s = session(f)
        f.controller.start(); runCurrent(); advanceTimeBy(1000); runCurrent(); f.actions.take()
        s.batch("tok-1"); runCurrent()
        s.onBegin = { "fresh-token" }
        s.fail(RpcFailure("EVENT_FROM_TOKEN_PARSE_FAILURE", details = listOf("tok-1"))); runCurrent()
        assertEquals(2, s.begins); assertEquals(1, f.backend.opened.size); assertEquals(0, s.closes)   // same session, no reconnect, no delay
        assertEquals(EventStatus.LIVE, state.value.events)
        s.batch("tok-after"); runCurrent()
        assertEquals(listOf("t1.1", "tok-1", "fresh-token", "tok-after"), s.tokensSeen)
        advanceTimeBy(10_000); runCurrent()
        assertEquals(listOf("inventory", "details", "alerts-full", "activity"), f.actions.take())
    }
    @Test fun anOversizedAnswerIsTreatedLikeLostEvents() = runTest {
        val state = MutableStateFlow(ready()); val f = fixture(state); val s = session(f)
        f.controller.start(); runCurrent(); advanceTimeBy(1000); runCurrent(); f.actions.take()
        s.fail(GatewayError("response-limit")); runCurrent()
        assertEquals(2, s.begins)
        s.fail(IllegalArgumentException("Response is too complex.")); runCurrent()
        assertEquals(3, s.begins)
        advanceTimeBy(10_000); runCurrent(); assertTrue(f.actions.take().contains("alerts-full"))
    }
    @Test fun sessionInvalidLogsInAgainAtOnceThenBacksOffIfItKeepsHappening() = runTest {
        val state = MutableStateFlow(ready()); val f = fixture(state); val first = session(f); val second = session(f); val third = session(f)
        f.controller.start(); runCurrent(); advanceTimeBy(1000); runCurrent(); f.actions.take()
        first.fail(RpcFailure("SESSION_INVALID")); runCurrent()
        assertEquals(1, first.closes)
        assertEquals(2, f.backend.opened.size); assertEquals(1000L, f.backend.opened.last().third)       // immediately, no backoff
        second.fail(RpcFailure("SESSION_INVALID")); runCurrent()
        assertEquals(2, f.backend.opened.size)                                         // a repeat without progress waits
        advanceTimeBy(1999); runCurrent(); assertEquals(2, f.backend.opened.size)
        advanceTimeBy(1); runCurrent(); assertEquals(3, f.backend.opened.size)
        assertEquals(1, second.closes); assertEquals(EventStatus.LIVE, state.value.events)
        advanceTimeBy(10_000); runCurrent()
        assertTrue(f.actions.take().contains("inventory"))                             // everything is re-read after a re-login
        third.batch("ok"); runCurrent()
    }

    // ---- errors and backoff ----
    @Test fun errorsBackOffAndAnySuccessResetsTheSchedule() = runTest {
        val state = MutableStateFlow(ready()); val f = fixture(state)
        repeat(3) { f.backend.openFailures += IOException("offline") }
        val s = session(f)
        f.controller.start(); runCurrent()
        assertEquals(listOf(0L), f.backend.opened.map { it.third }); assertEquals(EventStatus.RETRYING, state.value.events)
        advanceTimeBy(2000); runCurrent(); advanceTimeBy(4000); runCurrent()
        assertEquals(listOf(0L, 2000L, 6000L), f.backend.opened.map { it.third })
        advanceTimeBy(8000); runCurrent()
        assertEquals(listOf(0L, 2000L, 6000L, 14000L), f.backend.opened.map { it.third })      // the fourth attempt succeeds
        assertEquals(EventStatus.LIVE, state.value.events)
        s.fail(IOException("reset")); runCurrent()
        assertEquals(1, s.closes); assertEquals(EventStatus.RETRYING, state.value.events)
        val next = session(f)
        advanceTimeBy(2000); runCurrent()                                                  // back at the first step, not the fifth
        assertEquals(5, f.backend.opened.size); assertEquals(1, next.begins)
    }
    @Test fun retryingCannotHelpForAuthenticationOrAPoolMemberSoItWaitsTheLongest() = runTest {
        val state = MutableStateFlow(ready()); val f = fixture(state)
        f.backend.openFailures += RpcFailure("HOST_IS_SLAVE", details = listOf("192.0.2.10"))
        val s = session(f)
        f.controller.start(); runCurrent()
        advanceTimeBy(59_999); runCurrent(); assertEquals(1, f.backend.opened.size)
        advanceTimeBy(1); runCurrent(); assertEquals(2, f.backend.opened.size); assertEquals(1, s.begins)
        // A gateway that has no event support is not hammered either.
        val unsupported = MutableStateFlow(ready()); val g = fixture(unsupported)
        g.backend.openFailures += UnsupportedOperationException()
        g.controller.start(); runCurrent(); advanceTimeBy(30_000); runCurrent()
        assertEquals(1, g.backend.opened.size)
    }

    // ---- fencing ----
    @Test fun aProfileChangeClosesTheOldSessionAndALateBatchCannotRefreshTheNewConnection() = runTest {
        val state = MutableStateFlow(ready()); val f = fixture(state); val old = session(f); val replacement = session(f)
        f.controller.start(); runCurrent(); advanceTimeBy(1000); runCurrent(); f.actions.take()
        val late = CompletableDeferred<Unit>()
        old.polls.trySend { withContext(NonCancellable) { late.await() }; EventBatch("late", listOf(vm())) }   // ignores cancellation
        runCurrent()
        val generation = f.controller.loopGeneration
        // Switching connections passes through "no inventory" (the old snapshot is dropped), then a new pool snapshot arrives.
        state.value = state.value.copy(selectedId = other.id, inventory = null); runCurrent()
        late.complete(Unit); runCurrent()
        assertEquals(1, old.closes)
        state.value = state.value.copy(inventory = inventory("other-pool")); runCurrent()
        assertEquals(Triple(other, "other-pool", 1000L), f.backend.opened.last())
        assertTrue(f.controller.loopGeneration > generation)
        advanceTimeBy(20_000); runCurrent()
        // Only the new connection's own START resync ran; the stale vm event did not add a refresh of its own.
        assertEquals(listOf("inventory", "details", "alerts", "activity"), f.actions.take())
        assertEquals(1, replacement.begins)
    }
    @Test fun aPoolChangeUnderTheSameProfileRestartsTheLoop() = runTest {
        val state = MutableStateFlow(ready()); val f = fixture(state); val a = session(f); val b = session(f)
        f.controller.start(); runCurrent()
        state.value = state.value.copy(inventory = inventory("new-pool")); runCurrent()
        assertEquals(1, a.closes); assertEquals(listOf("pool", "new-pool"), f.backend.opened.map { it.second })
        assertEquals(1, b.begins)
    }
    @Test fun renamingOrRefreshingTheSameConnectionDoesNotRestartTheLoop() = runTest {
        val state = MutableStateFlow(ready()); val f = fixture(state); val s = session(f)
        f.controller.start(); runCurrent()
        // A refresh flips connected/loading and replaces the snapshot, but profile and pool are unchanged.
        state.value = state.value.copy(connected = false, loading = true); runCurrent()
        state.value = state.value.copy(connected = true, loading = false, inventory = inventory().copy(observedAtMillis = 99)); runCurrent()
        assertEquals(1, f.backend.opened.size); assertEquals(0, s.closes)
    }
    @Test fun aPendingFlushIsDroppedWhenTheLoopStops() = runTest {
        val state = MutableStateFlow(ready()); val f = fixture(state); val s = session(f)
        f.controller.start(); runCurrent(); advanceTimeBy(1000); runCurrent(); f.actions.take()
        advanceTimeBy(10_000)
        s.batch("a", vm()); runCurrent()
        advanceTimeBy(500); runCurrent()
        state.value = state.value.copy(foreground = false); runCurrent()
        advanceTimeBy(60_000); runCurrent()
        assertEquals(emptyList<String>(), f.actions.take())
        assertEquals(1, s.closes)
    }
    @Test fun stoppingTheControllerClosesTheSession() = runTest {
        val state = MutableStateFlow(ready()); val f = fixture(state); val s = session(f)
        f.controller.start(); runCurrent()
        f.controller.stop(); runCurrent()
        assertEquals(1, s.closes)
    }
    // --- offline and reconnect ---
    @Test fun noSessionIsOpenedWhileOfflineOrWhileAutomaticReconnectingIsStopped() = runTest {
        val state = MutableStateFlow(ready().copy(offline = true)); val f = fixture(state); val s = session(f)
        f.controller.start(); runCurrent()
        assertTrue("no events login without a network", f.backend.opened.isEmpty()); assertEquals(EventStatus.OFF, state.value.events)
        state.value = state.value.copy(offline = false, retryStop = RetryStop.AUTHENTICATION); runCurrent()
        assertTrue("no second session sends the rejected password again", f.backend.opened.isEmpty())
        state.value = state.value.copy(retryStop = null); runCurrent()
        assertEquals(1, f.backend.opened.size); assertEquals(1, s.begins)
        state.value = state.value.copy(offline = true); runCurrent()
        assertEquals("going offline closes the session cleanly", 1, s.closes); assertEquals(EventStatus.OFF, state.value.events)
    }
    // --- end offline and reconnect ---
}
