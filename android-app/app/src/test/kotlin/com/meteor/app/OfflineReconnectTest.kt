package com.meteor.app

import com.meteor.app.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.security.cert.CertificateExpiredException
import javax.net.ssl.SSLHandshakeException

/**
 * Automatic reconnects: bounded backoff, a stop after a rejected password / changed certificate / pool member,
 * no repeated notices, and awareness of the phone's default network. Time and the network are injected.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OfflineReconnectTest {
    private val profile = Profile("profile", "Fixture", "https://test.invalid/", "tester", "fixture", "a".repeat(64))

    private class Vault(var document: ProfileDocument) : ProfileStorage {
        var writes = 0
        override suspend fun load() = document
        override suspend fun save(document: ProfileDocument) { writes++; this.document = document }
    }
    private class Backend : MeteorGateway {
        var reads = 0
        val logins = mutableListOf<Profile>()
        var result: suspend () -> Inventory = { inventory("pool", 1) }
        override suspend fun inventory(profile: Profile): Inventory { reads++; logins += profile; return result() }
        override suspend fun details(profile: Profile, authority: String, uuid: String): VmDetails = error("Unexpected details")
        override suspend fun <T> powerConnection(profile: Profile, action: suspend PowerConnection.() -> T): T = error("Unexpected power access")
    }
    private class Journal : OperationStore {
        override suspend fun load() = emptyList<PowerOperation>()
        override suspend fun put(operation: PowerOperation) = error("Connections must not change operation history")
    }
    private class Network : NetworkMonitor {
        val flow = MutableStateFlow(NetworkStatus())
        override val status: StateFlow<NetworkStatus> = flow
        var starts = 0; var stops = 0
        override fun start() { starts++ }
        override fun stop() { stops++ }
        fun lose() { flow.update { it.copy(online = false) } }
        fun regain() { flow.update { it.copy(online = true, changes = it.changes + 1) } }
        fun switchNetwork() { flow.update { it.copy(changes = it.changes + 1) } }
    }

    private inner class Fixture(scope: CoroutineScope, connected: Boolean = false) {
        val backend = Backend()
        val vault = Vault(ProfileDocument(listOf(profile), profile.id))
        val network = Network()
        var now = 0L
        val state = MutableStateFlow(MeteorState(profiles = listOf(profile), selectedId = profile.id, journalProblem = false,
            inventory = inventory("pool", 0), connected = connected))
        val controller = ConnectionController(scope, backend, vault, Journal(), CertificateInspector { ServerCertificate("a".repeat(64), "Fixture", 1, 2) },
            state, {}, {}, {}, { "Fixture failure (${it.javaClass.simpleName})." }, {}, network, { now }).apply { initialized = true }

        /** Lets the controller take its first network reading (that reading never triggers an attempt). */
        fun TestScope.begin() { runCurrent() }
        fun TestScope.automatic() { controller.refresh(automatic = true); runCurrent() }
        fun TestScope.advance(millis: Long) { now += millis }
        fun fail(error: Throwable = IOException("fixture outage")) { backend.result = { throw error } }
    }

    // --- backoff ---

    @Test fun automaticReconnectsBackOffFromThirtySecondsToFiveMinutesAndStayThere() = runTest {
        val f = Fixture(backgroundScope)
        with(f) {
            begin(); fail()
            automatic(); assertEquals(1, backend.reads)
            // 30 s, 60 s, 2 min, then 5 min between attempts, counted from the start of the failed attempt.
            val gaps = listOf(30_000L, 60_000L, 120_000L, 300_000L, 300_000L)
            gaps.forEachIndexed { index, gap ->
                advance(gap - 1); automatic()
                assertEquals("still waiting $gap ms after attempt ${index + 1}", index + 1, backend.reads)
                advance(1); automatic()
                assertEquals("attempt ${index + 2} is due", index + 2, backend.reads)
            }
        }
    }

    @Test fun thePollingEntryPointIsGatedByTheSameBackoff() = runTest {
        val f = Fixture(backgroundScope)
        with(f) {
            begin(); fail()
            controller.refreshIfStale(); runCurrent()          // the snapshot is from 1970, so it is stale
            controller.refreshIfStale(); runCurrent()          // a destination change restarts the poll loop and asks again
            controller.refreshIfStale(); runCurrent()
            assertEquals(1, backend.reads)
            advance(30_000); controller.refreshIfStale(); runCurrent()
            assertEquals(2, backend.reads)
        }
    }

    @Test fun aManualRefreshAlwaysTriesAndRestartsTheBackoff() = runTest {
        val f = Fixture(backgroundScope)
        with(f) {
            begin(); fail()
            automatic(); advance(30_000); automatic(); advance(60_000); automatic()
            assertEquals(3, backend.reads)                       // the next automatic attempt is now 120 s away
            controller.refresh(); runCurrent()
            assertEquals("the Refresh button is never gated", 4, backend.reads)
            advance(29_999); automatic(); assertEquals(4, backend.reads)
            advance(1); automatic(); assertEquals("the schedule restarted at 30 s", 5, backend.reads)
        }
    }

    @Test fun connectingOrSwitchingProfileRestartsTheBackoff() = runTest {
        val f = Fixture(backgroundScope)
        with(f) {
            begin(); fail()
            automatic(); advance(30_000); automatic(); advance(60_000); automatic()
            assertEquals(3, backend.reads)
            controller.switchProfile(profile); runCurrent()
            assertEquals(4, backend.reads)
            advance(30_000); automatic()
            assertEquals(5, backend.reads)
        }
    }

    @Test fun aSuccessfulConnectionClearsTheBackoffForTheNextFailure() = runTest {
        val f = Fixture(backgroundScope)
        with(f) {
            begin(); fail()
            automatic(); advance(30_000); automatic(); advance(60_000); automatic()        // two failures deep
            backend.result = { inventory("pool", 5) }
            advance(120_000); automatic()
            assertTrue(state.value.connected)
            fail(); advance(1_000)
            controller.refresh(automatic = true); runCurrent()                              // same-connection read fails
            assertFalse(state.value.connected)
            val reads = backend.reads
            advance(29_999); automatic(); assertEquals(reads, backend.reads)
            advance(1); automatic(); assertEquals("a fresh 30 s step, not the 5 min cap", reads + 1, backend.reads)
        }
    }

    @Test fun aFailedAttemptIsScheduledFromItsStartNotItsEnd() = runTest {
        val f = Fixture(backgroundScope)
        with(f) {
            begin()
            backend.result = { f.now += 20_000; throw IOException("slow timeout") }         // the attempt itself takes 20 s
            automatic()
            advance(10_000)                                                                // 30 s after the attempt started
            automatic()
            assertEquals(2, backend.reads)
        }
    }

    // --- authentication, certificate, pool member ---

    @Test fun aRejectedPasswordStopsAutomaticRetriesWithAPersistentExplanationAndNoNotice() = runTest {
        val f = Fixture(backgroundScope)
        with(f) {
            begin(); fail(RpcFailure("SESSION_AUTHENTICATION_FAILED"))
            automatic()
            assertEquals(RetryStop.AUTHENTICATION, state.value.retryStop)
            assertNull("the persistent banner is the explanation; the retry loop posts no notice", state.value.notice)
            val banner = ConnectionStatus.banner(state.value)!!
            assertEquals(NoticeKind.ERROR, banner.kind)
            assertTrue(banner.text.contains("rejected the saved username or password") && banner.text.contains("Automatic reconnecting is paused"))
            repeat(20) { advance(600_000); automatic(); controller.refreshIfStale(); runCurrent() }
            assertEquals("not one more login in 200 minutes", 1, backend.reads)
            assertEquals(RetryStop.AUTHENTICATION, state.value.retryStop)
        }
    }

    @Test fun refreshOrEditingTheCredentialsLiftsTheStop() = runTest {
        val f = Fixture(backgroundScope)
        with(f) {
            begin(); fail(RpcFailure("SESSION_AUTHENTICATION_FAILED"))
            automatic()
            val gate = CompletableDeferred<Inventory>()
            backend.result = { gate.await() }
            controller.refresh(); runCurrent()
            assertEquals(2, backend.reads)
            assertNull("the stop is lifted while the explicit attempt runs", state.value.retryStop)
            gate.completeExceptionally(RpcFailure("SESSION_AUTHENTICATION_FAILED")); runCurrent()
            assertEquals("a manual attempt that fails again shows the server's answer", NoticeKind.ERROR, state.value.notice?.kind)
            assertEquals(RetryStop.AUTHENTICATION, state.value.retryStop)
            // Editing the password is an explicit connection with the new credentials.
            backend.result = { inventory("pool", 9) }
            controller.saveConnection("Fixture", profile.endpoint, profile.username, "new-password", profile.id); runCurrent()
            assertEquals("new-password", backend.logins.last().password)
            assertTrue(state.value.connected); assertNull(state.value.retryStop)
            assertNull(ConnectionStatus.banner(state.value))
        }
    }

    @Test fun aChangedOrInvalidCertificateStopsAutomaticRetriesToo() = runTest {
        listOf<Throwable>(CertificateChanged(), SSLHandshakeException("fixture").apply { initCause(CertificateChanged()) },
            CertificateExpiredException("fixture")).forEach { error ->
            val f = Fixture(backgroundScope)
            with(f) {
                begin(); fail(error)
                automatic()
                assertEquals(RetryStop.CERTIFICATE, state.value.retryStop)
                assertEquals(NoticeKind.ERROR, state.value.notice?.kind)
                repeat(5) { advance(600_000); automatic() }
                assertEquals(1, backend.reads)
                controller.refresh(); runCurrent()
                assertEquals("an explicit Refresh may retry", 2, backend.reads)
            }
        }
    }

    @Test fun aPoolMemberAnswerKeepsTheMasterOfferOpenAndIsNotRetriedAutomatically() = runTest {
        val f = Fixture(backgroundScope)
        with(f) {
            begin(); fail(RpcFailure("HOST_IS_SLAVE", details = listOf("192.0.2.10")))
            automatic()
            val offer = state.value.masterOffer
            assertEquals("https://192.0.2.10/", offer?.masterEndpoint)
            assertEquals(RetryStop.POOL_MEMBER, state.value.retryStop)
            repeat(5) { advance(600_000); automatic() }
            assertEquals("the saved password went to the member once", 1, backend.reads)
            assertEquals("the open offer stays", offer, state.value.masterOffer)
            controller.refresh(); runCurrent()
            assertEquals(2, backend.reads)
            assertEquals(offer, state.value.masterOffer)
        }
    }

    @Test fun otherServerErrorsStayOnTheBackoffAndDoNotStop() = runTest {
        val f = Fixture(backgroundScope)
        with(f) {
            begin(); fail(RpcFailure("HOST_STILL_BOOTING"))
            automatic()
            assertNull(state.value.retryStop)
            advance(30_000); automatic()
            assertEquals(2, backend.reads)
        }
    }

    @Test fun noAutomaticReconnectWhileAPowerConfirmationOrReviewIsPending() = runTest {
        val f = Fixture(backgroundScope)
        with(f) {
            begin()
            state.update { it.copy(pendingPower = PowerConfirmation(profile.id, profile.origin(), detail, PowerAction.START, typedName = "Fixture")) }
            automatic(); advance(600_000); automatic()
            assertEquals(0, backend.reads)
        }
    }

    // --- notices ---

    @Test fun theRetryLoopNeverClearsAndRepostsTheSameErrorNotice() = runTest {
        val f = Fixture(backgroundScope)
        val seen = mutableListOf<UiNotice?>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { f.state.collect { seen += it.notice } }
        with(f) {
            begin(); fail()
            automatic()
            val posted = state.value.notice
            assertNotNull(posted); assertEquals(NoticeKind.ERROR, posted?.kind)
            val afterFirst = seen.size
            repeat(4) { advance(300_000); automatic() }
            assertEquals(5, backend.reads)
            assertTrue("the notice stayed in place through four more cycles: ${seen.drop(afterFirst - 1)}",
                seen.drop(afterFirst - 1).all { it == posted })
            // A success removes the failure it explained.
            backend.result = { inventory("pool", 7) }
            advance(300_000); automatic()
            assertTrue(state.value.connected); assertNull(state.value.notice)
        }
    }

    // --- network ---

    @Test fun offlineShowsAPlainStateInsteadOfAServerErrorAndMakesNoAutomaticLogins() = runTest {
        val f = Fixture(backgroundScope, connected = true)
        with(f) {
            begin()
            network.lose(); runCurrent()
            assertTrue(state.value.offline)
            assertEquals(UiNotice.info(ConnectionStatus.OFFLINE), ConnectionStatus.banner(state.value))
            assertEquals(NoticeKind.INFO, ConnectionStatus.banner(state.value)?.kind)
            repeat(5) { advance(600_000); automatic(); controller.refreshIfStale(); runCurrent() }
            assertEquals("no polling logins while offline", 0, backend.reads)
            assertTrue("going offline does not tear the connection down", state.value.connected)
            // An explicit Refresh still tries; its failure is the network's, so no server error and no backoff.
            fail()
            controller.refresh(); runCurrent()
            assertEquals(1, backend.reads)
            assertNull(state.value.notice); assertNull(state.value.retryStop)
        }
    }

    @Test fun regainingTheNetworkMakesOneImmediateAttemptWithAFreshBackoff() = runTest {
        val f = Fixture(backgroundScope)
        with(f) {
            begin(); fail()
            automatic(); advance(30_000); automatic(); advance(60_000); automatic()        // backed off to 2 min
            assertEquals(3, backend.reads)
            network.lose(); runCurrent()
            assertEquals(3, backend.reads)
            advance(5_000)                                                                  // well inside the backoff window
            network.regain(); runCurrent()
            assertEquals("one immediate automatic attempt", 4, backend.reads)
            assertFalse(state.value.offline)
            advance(29_999); automatic(); assertEquals(4, backend.reads)
            advance(1); automatic(); assertEquals("and the schedule restarted at 30 s", 5, backend.reads)
        }
    }

    @Test fun regainingTheNetworkDoesNotLiftAnAuthenticationStop() = runTest {
        val f = Fixture(backgroundScope)
        with(f) {
            begin(); fail(RpcFailure("SESSION_AUTHENTICATION_FAILED"))
            automatic()
            network.lose(); runCurrent(); network.regain(); runCurrent()
            assertEquals(1, backend.reads)
            assertEquals(RetryStop.AUTHENTICATION, state.value.retryStop)
            assertNotNull("the stop is shown again as soon as the network is back", ConnectionStatus.banner(state.value))
            assertEquals(ConnectionStatus.banner(state.value)?.kind, NoticeKind.ERROR)
        }
    }

    @Test fun theFirstReadingAndAnUnchangedOnlineStatusStartNothing() = runTest {
        val f = Fixture(backgroundScope)
        with(f) {
            begin()
            assertEquals(0, backend.reads)
            network.flow.update { it.copy() }; runCurrent()
            assertEquals(0, backend.reads)
        }
    }

    @Test fun aNetworkSwitchWhileConnectedIsOneOrdinaryRefreshNotATeardown() = runTest {
        val f = Fixture(backgroundScope)
        with(f) {
            begin()
            controller.switchProfile(profile); runCurrent()
            assertTrue(state.value.connected)
            state.update { it.copy(pendingPower = PowerConfirmation(profile.id, profile.origin(), detail, PowerAction.START, typedName = "Fixture")) }
            val generation = controller.generation; val writes = vault.writes; val reads = backend.reads
            val loadingSeen = mutableListOf<Boolean>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { state.collect { loadingSeen += it.loading } }
            backend.result = { inventory("pool", 40) }
            network.switchNetwork(); runCurrent()
            assertEquals("exactly one read", reads + 1, backend.reads)
            assertEquals(40L, state.value.inventory?.observedAtMillis)
            assertTrue(state.value.connected); assertFalse(loadingSeen.any { it })
            assertNotNull("an open confirmation is kept", state.value.pendingPower)
            assertEquals(generation, controller.generation); assertEquals(writes, vault.writes)
        }
    }

    @Test fun aNetworkSwitchDoesNotLiftAStop() = runTest {
        val f = Fixture(backgroundScope)
        with(f) {
            begin(); fail(RpcFailure("SESSION_AUTHENTICATION_FAILED"))
            automatic()
            network.switchNetwork(); runCurrent()
            assertEquals(1, backend.reads)
        }
    }

    @Test fun anAutomaticReconnectWorksAgainAfterTheNetworkReturnsEvenWhenTheFirstAttemptWasOffline() = runTest {
        val f = Fixture(backgroundScope)
        with(f) {
            begin(); network.lose(); runCurrent()
            fail()
            controller.refresh(); runCurrent()                  // offline: fails without advancing the backoff
            backend.result = { inventory("pool", 3) }
            network.regain(); runCurrent()
            assertTrue(state.value.connected)
            assertEquals(2, backend.reads)
        }
    }

    // --- pure pieces ---

    @Test fun backoffStepsAreBoundedAndReset() {
        val backoff = ReconnectBackoff()
        assertTrue(backoff.due(0))
        val expected = listOf(30_000L, 60_000L, 120_000L, 300_000L, 300_000L, 300_000L)
        expected.forEach { gap ->
            backoff.failed(1_000_000)
            assertFalse(backoff.due(1_000_000 + gap - 1)); assertTrue(backoff.due(1_000_000 + gap))
        }
        backoff.reset()
        assertTrue(backoff.due(0))
        backoff.failed(0); assertFalse(backoff.due(29_999))
    }

    @Test fun failuresAreClassifiedThroughWrappedCauses() {
        assertEquals(RetryStop.AUTHENTICATION, ConnectionFailures.stopFor(RpcFailure("SESSION_AUTHENTICATION_FAILED")))
        assertEquals(RetryStop.AUTHENTICATION, ConnectionFailures.stopFor(IOException("wrapped", RpcFailure("SESSION_AUTHENTICATION_FAILED"))))
        assertEquals(RetryStop.CERTIFICATE, ConnectionFailures.stopFor(SSLHandshakeException("x").apply { initCause(CertificateChanged()) }))
        assertEquals(RetryStop.POOL_MEMBER, ConnectionFailures.stopFor(RpcFailure("HOST_IS_SLAVE", details = listOf("192.0.2.10"))))
        listOf<Throwable>(RpcFailure("HOST_STILL_BOOTING"), RpcFailure("SESSION_INVALID"), IOException("down"), java.net.SocketTimeoutException())
            .forEach { assertNull(it.toString(), ConnectionFailures.stopFor(it)) }
    }

    @Test fun theStatusBannerIsDerivedFromStateAndNeverShownWhileConnecting() {
        val base = MeteorState(profiles = listOf(profile), selectedId = profile.id)
        assertNull(ConnectionStatus.banner(base))
        assertNull(ConnectionStatus.banner(base.copy(retryStop = RetryStop.AUTHENTICATION, loading = true)))
        assertEquals(ConnectionStatus.OFFLINE, ConnectionStatus.banner(base.copy(offline = true, retryStop = RetryStop.AUTHENTICATION))?.text)
        assertTrue(ConnectionStatus.banner(base.copy(retryStop = RetryStop.CERTIFICATE))!!.text.contains("certificate"))
        assertTrue(ConnectionStatus.banner(base.copy(retryStop = RetryStop.POOL_MEMBER))!!.text.contains("pool member"))
    }

    @Test fun aPreferenceFailureShowsTheValidationSentenceOnly() {
        assertEquals("Choose an image under 3 MB.", ErrorReasons.preference(IllegalArgumentException("Choose an image under 3 MB.")))
        assertEquals("That preference could not be saved. Try again.", ErrorReasons.preference(IllegalArgumentException()))
        assertEquals("That preference could not be saved. Try again.", ErrorReasons.preference(IllegalArgumentException("Failed requirement.")))
        assertEquals("That preference could not be saved. Try again.", ErrorReasons.preference(IllegalArgumentException("x".repeat(500))))
        assertEquals("That preference could not be saved. Try again.", ErrorReasons.preference(IOException("/data/user/0/secret/path")))
    }

    private val detail = VmDetails("pool", "vm", "Fixture VM", "", "Running", "host", 1, 1024, emptyList(), emptyList(),
        false, null, emptyList(), null, setOf("hard_shutdown"), false, 1)

    private companion object {
        fun inventory(authority: String, observedAt: Long) = Inventory(authority, "Pool", emptyList(), emptyList(), 0, 0, observedAt)
    }
}
