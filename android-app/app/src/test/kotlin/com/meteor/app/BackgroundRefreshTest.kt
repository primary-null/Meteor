package com.meteor.app

import com.meteor.app.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

/**
 * A same-connection background refresh (events, fallback poll, header Refresh) re-reads the inventory without
 * tearing the connection down: `connected`, open confirmations, the selected VM and the vault are left alone.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BackgroundRefreshTest {
    private val profile = Profile("profile", "Fixture", "https://test.invalid/", "tester", "fixture", "a".repeat(64))

    private class Vault(var document: ProfileDocument) : ProfileStorage {
        var writes = 0
        override suspend fun load() = document
        override suspend fun save(document: ProfileDocument) { writes++; this.document = document }
    }
    private class Backend : MeteorGateway {
        var reads = 0
        var result: suspend () -> Inventory = { inventory("pool", 1) }
        override suspend fun inventory(profile: Profile): Inventory { reads++; return result() }
        override suspend fun details(profile: Profile, authority: String, uuid: String): VmDetails = error("Unexpected details")
        override suspend fun <T> powerConnection(profile: Profile, action: suspend PowerConnection.() -> T): T = error("Unexpected power access")
    }
    private class Journal : OperationStore {
        override suspend fun load() = emptyList<PowerOperation>()
        override suspend fun put(operation: PowerOperation) = error("Connections must not change operation history")
    }
    private inner class Fixture(scope: CoroutineScope) {
        val vault = Vault(ProfileDocument(listOf(profile), profile.id))
        val backend = Backend()
        val state = MutableStateFlow(MeteorState(profiles = listOf(profile), selectedId = profile.id, journalProblem = false))
        var closed = 0; var invalidated = 0; var opened = 0; var changed = 0; var inspections = 0
        /** Every value `profileBusy` took, to prove the background path never raises it. */
        val profileBusySeen = mutableListOf<Boolean>()
        val controller = ConnectionController(scope, backend, vault, Journal(), CertificateInspector {
            inspections++; ServerCertificate("a".repeat(64), "Fixture", 1, 2)
        }, state,
            closeDetails = { closed++; state.update { it.copy(selectedGuest = null, details = null, detailsBusy = false, pendingPower = null,
                pendingHostChoice = null, hostChoiceBusy = false) } },
            invalidateDetails = { invalidated++ }, openDetails = { opened++ }, friendly = { "Fixture refresh failed." },
            connectionChanged = { changed++ }).apply { initialized = true }
        init { scope.launch { state.collect { profileBusySeen += it.profileBusy } } }

        /** Connects explicitly, then opens a VM with a typed confirmation, a review, a host choice and a B2 view. */
        fun connectAndOpenDialogs(testScope: TestScope) {
            controller.switchProfile(profile); testScope.runCurrent()
            assertTrue(state.value.connected)
            state.update { it.copy(selectedGuest = guest, details = detail, detailsBusy = true, pendingPower = confirmation,
                pendingReview = review, pendingHostChoice = hostChoice, storageHost = storage) }
            closed = 0; invalidated = 0; opened = 0; changed = 0; profileBusySeen.clear()
        }
    }

    private val guest = Guest("vm", "Fixture VM", "Running", "pool", "host", 1, 1024)
    private val detail = VmDetails("pool", "vm", "Fixture VM", "", "Running", "host", 1, 1024, emptyList(), emptyList(),
        false, null, emptyList(), null, setOf("hard_shutdown"), false, 1)
    private val confirmation = PowerConfirmation(profile.id, profile.origin(), detail, PowerAction.START, typedName = "Fixture")
    private val review = PowerReview(PowerOperation("op", profile.id, profile.origin(), "pool", "vm", "Fixture VM", PowerAction.START,
        null, PowerPhase.UNKNOWN, 1, 1), profile.id, profile.origin(), detail)
    private val hostChoice = HostChoiceRequest(PowerAction.START, "pool", "vm", "host", listOf(HostChoice("host", "Host", true, true, true)), 1)
    private val storage = StorageHostView(profileId = profile.id, origin = profile.origin(), isoBusy = true)

    @Test fun backgroundRefreshKeepsTheConnectionDialogsAndSelectedVmAndNeverWritesTheVault() = runTest {
        val f = Fixture(backgroundScope)
        f.connectAndOpenDialogs(this)
        val writes = f.vault.writes; val manualEvents = f.state.value.manualConnectionEvent
        val response = CompletableDeferred<Inventory>()
        f.backend.result = { response.await() }

        f.controller.refresh(); runCurrent()
        // In flight: still connected, not "loading", every open dialog intact.
        f.state.value.let {
            assertTrue(it.connected); assertFalse(it.loading); assertTrue(it.refreshing)
            assertEquals(confirmation, it.pendingPower); assertEquals(review, it.pendingReview); assertEquals(hostChoice, it.pendingHostChoice)
            assertEquals(guest, it.selectedGuest); assertEquals(detail, it.details); assertTrue(it.detailsBusy); assertEquals(storage, it.storageHost)
            assertNull(it.notice)
        }
        val generation = f.controller.generation

        response.complete(inventory("pool", 2)); runCurrent()
        f.state.value.let {
            assertEquals(2L, it.inventory?.observedAtMillis)
            assertTrue(it.connected); assertFalse(it.loading); assertFalse(it.refreshing)
            assertEquals(confirmation, it.pendingPower); assertEquals(review, it.pendingReview); assertEquals(hostChoice, it.pendingHostChoice)
            assertEquals(guest, it.selectedGuest); assertEquals(detail, it.details); assertTrue(it.detailsBusy); assertEquals(storage, it.storageHost)
            assertNull("no \"Connection refreshed\" notice", it.notice)
            assertEquals(manualEvents, it.manualConnectionEvent)
        }
        assertEquals("the profile did not change, so the vault is not rewritten", writes, f.vault.writes)
        assertFalse(f.profileBusySeen.any { it })
        assertEquals("detail, host-choice and B2 requests of this connection stay valid", generation, f.controller.generation)
        assertEquals(0, f.closed); assertEquals(0, f.invalidated); assertEquals(0, f.changed); assertEquals(0, f.opened)
        assertEquals(0, f.inspections)
    }

    @Test fun aStaleSnapshotRefreshUsesTheBackgroundPath() = runTest {
        val f = Fixture(backgroundScope)
        f.connectAndOpenDialogs(this)
        val writes = f.vault.writes
        f.state.update { it.copy(inventory = inventory("pool", 0)) }   // old enough for every threshold
        f.controller.refreshIfStale(EventPolicy.POLLING_STALE_MILLIS); runCurrent()
        assertEquals(2, f.backend.reads)
        assertEquals(confirmation, f.state.value.pendingPower); assertTrue(f.state.value.connected)
        assertEquals(writes, f.vault.writes)
    }

    @Test fun aSupersededRefreshIsDiscarded() = runTest {
        val f = Fixture(backgroundScope)
        f.connectAndOpenDialogs(this)
        val first = CompletableDeferred<Inventory>(); val second = CompletableDeferred<Inventory>()
        val responses = ArrayDeque(listOf(first, second))
        f.backend.result = { withContext(NonCancellable) { responses.removeFirst().await() } }
        f.controller.refresh(); runCurrent()
        f.controller.refresh(); runCurrent()
        second.complete(inventory("pool", 20)); runCurrent()
        assertEquals(20L, f.state.value.inventory?.observedAtMillis); assertFalse(f.state.value.refreshing)
        first.complete(inventory("other-pool", 10)); runCurrent()
        assertEquals("a late, superseded answer never lands", 20L, f.state.value.inventory?.observedAtMillis)
        assertEquals("pool", f.state.value.inventory?.authority)
        assertEquals(confirmation, f.state.value.pendingPower); assertEquals(0, f.closed)
    }

    @Test fun anExplicitConnectionOrDisconnectSupersedesARefreshInFlight() = runTest {
        val f = Fixture(backgroundScope)
        f.connectAndOpenDialogs(this)
        val late = CompletableDeferred<Inventory>()
        f.backend.result = { withContext(NonCancellable) { late.await() } }
        f.controller.refresh(); runCurrent()
        f.controller.disconnect(); runCurrent()
        assertFalse(f.state.value.refreshing)
        late.complete(inventory("pool", 30)); runCurrent()
        assertFalse(f.state.value.connected); assertNull(f.state.value.inventory); assertNull(f.state.value.selectedId)
    }

    @Test fun aChangedPoolAuthorityStillClosesDetailsAndResetsPoolScopedState() = runTest {
        val f = Fixture(backgroundScope)
        f.connectAndOpenDialogs(this)
        f.backend.result = { inventory("other-pool", 3) }
        f.controller.refresh(); runCurrent()
        f.state.value.let {
            assertEquals("other-pool", it.inventory?.authority); assertTrue(it.connected)
            assertNull(it.selectedGuest); assertNull(it.details); assertNull(it.pendingPower); assertNull(it.pendingHostChoice)
            assertNull(it.pendingReview); assertFalse(it.detailsBusy)
        }
        assertEquals(1, f.closed); assertEquals(1, f.changed); assertEquals(1, f.invalidated)
    }

    @Test fun aFailedRefreshDisconnectsWithTheErrorAndTheNextRefreshIsAFullReconnect() = runTest {
        val f = Fixture(backgroundScope)
        f.connectAndOpenDialogs(this)
        val writes = f.vault.writes
        f.backend.result = { throw IOException("fixture outage") }
        f.controller.refresh(); runCurrent()
        f.state.value.let {
            assertFalse(it.connected); assertFalse(it.refreshing); assertFalse(it.loading)
            assertEquals(NoticeKind.ERROR, it.notice?.kind); assertEquals("Fixture refresh failed.", it.notice?.text)
            assertNull("no VM confirmation is kept against a connection that just failed", it.pendingPower)
            assertNull(it.pendingHostChoice)
        }
        assertEquals(writes, f.vault.writes)
        // Not connected any more: the next refresh is the full reconnect, which saves the profile as before.
        f.state.update { it.copy(pendingReview = null) }
        f.backend.result = { inventory("pool", 4) }
        f.controller.refresh(); runCurrent()
        assertTrue(f.state.value.connected); assertEquals(writes + 1, f.vault.writes)
    }

    @Test fun aFailedRefreshStillOffersThePoolMaster() = runTest {
        val f = Fixture(backgroundScope)
        f.connectAndOpenDialogs(this)
        f.backend.result = { throw RpcFailure("HOST_IS_SLAVE", details = listOf("192.0.2.10")) }
        f.controller.refresh(); runCurrent()
        assertFalse(f.state.value.connected)
        assertEquals("https://192.0.2.10/", f.state.value.masterOffer?.masterEndpoint)
        assertEquals(0, f.inspections)
    }

    @Test fun aChangedIdentityUsesTheFullConnectionPath() = runTest {
        val f = Fixture(backgroundScope)
        f.controller.switchProfile(profile); runCurrent()
        val writes = f.vault.writes; val invalidated = f.invalidated
        // The selected profile's pin no longer matches the connection that was opened.
        val repinned = profile.copy(certificateSha256 = "b".repeat(64))
        f.state.update { it.copy(profiles = listOf(repinned)) }
        f.controller.refresh(); runCurrent()
        assertEquals(writes + 1, f.vault.writes)
        assertEquals(invalidated + 1, f.invalidated)
    }

    @Test fun aRenameDuringTheConnectionKeepsTheBackgroundPath() = runTest {
        val f = Fixture(backgroundScope)
        f.controller.switchProfile(profile); runCurrent()
        f.controller.saveConnection("Renamed", profile.endpoint, profile.username, "", profile.id); runCurrent()
        val writes = f.vault.writes; val invalidated = f.invalidated
        f.controller.refresh(); runCurrent()
        assertEquals(writes, f.vault.writes); assertTrue(f.state.value.connected); assertEquals(invalidated, f.invalidated)
        assertEquals("Renamed", f.state.value.selectedProfile?.label)
    }

    @Test fun anExplicitConnectStillResetsAndSavesAsBefore() = runTest {
        val f = Fixture(backgroundScope)
        f.connectAndOpenDialogs(this)
        val writes = f.vault.writes; val manualEvents = f.state.value.manualConnectionEvent
        val response = CompletableDeferred<Inventory>()
        f.backend.result = { response.await() }
        f.controller.switchProfile(profile); runCurrent()
        f.state.value.let {
            assertFalse(it.connected); assertTrue(it.loading)
            assertNull(it.pendingPower); assertNull(it.pendingReview); assertFalse(it.detailsBusy)
            assertEquals(NoticeKind.INFO, it.notice?.kind)                // "Connection refreshed. Review VM details..."
        }
        response.complete(inventory("pool", 5)); runCurrent()
        assertTrue(f.state.value.connected); assertEquals(writes + 1, f.vault.writes)
        assertEquals(manualEvents + 1, f.state.value.manualConnectionEvent)
        assertEquals(1, f.invalidated)
    }

    @Test fun trustApprovalUsesTheFullPathWithReview() = runTest {
        val f = Fixture(backgroundScope)
        f.controller.switchProfile(profile); runCurrent()
        f.controller.inspect("Fixture", "https://renewed.invalid/", profile.username, "", profile.id); runCurrent()
        assertEquals(1, f.inspections); assertNotNull(f.state.value.pendingTrust)
        val writes = f.vault.writes
        f.controller.refresh(); runCurrent()
        assertEquals("no refresh while a certificate review is open", 1, f.backend.reads)
        f.controller.acceptTrust(); runCurrent()
        assertEquals(writes + 1, f.vault.writes)
        assertEquals("https://renewed.invalid/", f.vault.document.profiles.single().endpoint)
        assertTrue(f.state.value.connected)
    }

    private companion object {
        fun inventory(authority: String, observedAt: Long) = Inventory(authority, "Pool", emptyList(), emptyList(), 0, 0, observedAt)
    }
}
