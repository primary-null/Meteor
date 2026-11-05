package com.meteor.app

import android.app.Application
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.meteor.app.data.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import java.io.IOException

/** Drives the actual ViewModel with delayed responses and isolated durable stores. */
@RunWith(AndroidJUnit4::class)
class DetailRaceDeviceTest {
    private val application get() = ApplicationProvider.getApplicationContext<Application>()
    private val profile = Profile("fixture", "Fixture", "https://test.invalid/", "tester", "fixture", "a".repeat(64))
    private val vaultName = "instrumentation-detail-race.secured"
    private val journalName = "instrumentation-detail-race.json"
    private val alias = "meteor.instrumentation.detail-race"
    private val backend = Backend()
    private lateinit var model: MeteorViewModel

    private data class Request(val uuid: String, val result: CompletableDeferred<VmDetails> = CompletableDeferred())
    private class Backend : MeteorGateway {
        var current = Inventory("pool", "Fixture", emptyList(), emptyList(), 0, 0, 1)
        var inventoryResult: CompletableDeferred<Inventory>? = null
        val requests = Channel<Request>(Channel.UNLIMITED)
        override suspend fun inventory(profile: Profile) = inventoryResult?.await() ?: current
        override suspend fun details(profile: Profile, authority: String, uuid: String): VmDetails {
            val request = Request(uuid)
            requests.send(request)
            return request.result.await()
        }
        override suspend fun <T> powerConnection(profile: Profile, action: suspend PowerConnection.() -> T): T =
            error("A details read must not submit power or access a server.")
    }
    private fun guest(uuid: String) = Guest(uuid, uuid, "Halted", "pool", null, 1, 1024)
    private fun detail(uuid: String) = VmDetails("pool", uuid, uuid, "", "Halted", null, 1, 1024,
        emptyList(), emptyList(), false, null, emptyList(), null, setOf("start"), false, System.currentTimeMillis())
    private fun main(action: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(action)
    private suspend fun awaitState(predicate: (MeteorState) -> Boolean) = withTimeout(15_000) { model.state.first(predicate) }
    private suspend fun request() = withTimeout(15_000) { backend.requests.receive() }

    @Before fun prepare() = runBlocking {
        val vault = ProfileVault(application, vaultName, alias)
        vault.save(ProfileDocument(listOf(profile), profile.id))
        main {
            model = MeteorViewModel(application, backend, vault, OperationJournal(application, journalName))
        }
        awaitState { it.connected }
        Unit
    }
    @After fun cleanUp() {
        if (::model.isInitialized) main { model.viewModelScope.cancel() }
        for (name in listOf(vaultName, journalName)) {
            for (suffix in listOf("", ".bak", ".new")) File(application.filesDir, name + suffix).delete()
        }
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
    }

    @Test fun aLaterVmSelectionWinsWhenResponsesArriveOutOfOrder() = runBlocking {
        main { model.openDetails(guest("a")) }
        val first = request()
        main { model.openDetails(guest("b")) }
        val second = request()
        assertEquals("a", first.uuid); assertEquals("b", second.uuid)
        second.result.complete(detail("b"))
        awaitState { it.details?.uuid == "b" && !it.detailsBusy }
        first.result.complete(detail("a"))
        main { }
        assertEquals("b", model.state.value.details?.uuid)
    }

    @Test fun changedPinAndChangedPoolDiscardOldDetailsAndConfirmations() = runBlocking {
        main { model.openDetails(guest("a")) }
        val oldPinRequest = request()
        val changedPin = profile.copy(certificateSha256 = "b".repeat(64))
        main { model.switchProfile(changedPin) }
        awaitState { it.connected && it.selectedProfile == changedPin }
        oldPinRequest.result.complete(detail("a"))
        main { }
        assertNull(model.state.value.details)
        assertNull(model.state.value.pendingPower)

        main { model.openDetails(guest("a")) }
        val oldPoolRequest = request()
        backend.current = backend.current.copy(authority = "different-pool")
        main { model.switchProfile(changedPin) }
        awaitState { it.connected && it.inventory?.authority == "different-pool" }
        oldPoolRequest.result.complete(detail("a"))
        main { }
        assertNull(model.state.value.details)
        assertNull(model.state.value.pendingPower)
        assertFalse(model.state.value.detailsBusy)
    }

    @Test fun wrongVmIdentityIsHandledAsAFailedRead() = runBlocking {
        main { model.openDetails(guest("a")) }
        request().result.complete(detail("other-vm"))
        awaitState { !it.detailsBusy }
        assertNull(model.state.value.details)
        assertNull(model.state.value.pendingPower)
        assertNotNull(model.state.value.message)
    }

    @Test fun openingDuringInventoryRefreshLoadsWhenConnectionIsReady() = runBlocking<Unit> {
        val inventory = CompletableDeferred<Inventory>()
        backend.inventoryResult = inventory
        main { model.refresh() }
        awaitState { it.loading }
        main { model.openDetails(guest("a")) }
        assertEquals("a", model.state.value.selectedGuest?.uuid)
        assertFalse(backend.requests.tryReceive().isSuccess)
        inventory.complete(backend.current)
        request().result.complete(detail("a"))
        awaitState { it.details?.uuid == "a" && !it.detailsBusy }
    }

    @Test fun failedDetailsCanRetryAndClosingStopsFurtherRequests() = runBlocking {
        main { model.openDetails(guest("a")) }
        request().result.completeExceptionally(IOException("temporary read failure"))
        awaitState { !it.detailsBusy && it.message != null }
        assertNull(model.state.value.details)
        assertEquals("a", model.state.value.selectedGuest?.uuid)
        main { model.openDetails(guest("a")) }
        request().result.complete(detail("a"))
        awaitState { it.details?.uuid == "a" }
        main { model.closeDetails(); model.refreshDetails() }
        assertNull(model.state.value.selectedGuest)
        assertFalse(backend.requests.tryReceive().isSuccess)
    }

    @Test fun unresolvedActionAllowsProfileRepairAndAddingConnections() = runBlocking {
        main { model.viewModelScope.cancel() }
        val journal = OperationJournal(application, journalName)
        val pending = PowerOperation("pending", profile.id, profile.origin(), "pool", "a", "A", PowerAction.START,
            null, PowerPhase.UNKNOWN, 1, 1)
        journal.put(pending)
        main { model = MeteorViewModel(application, backend, ProfileVault(application, vaultName, alias), journal) }
        awaitState { it.connected && !it.operationBusy && it.operation?.unresolved == true }
        val repaired = profile.copy(certificateSha256 = "b".repeat(64))
        main { model.switchProfile(repaired) }
        awaitState { it.connected && it.selectedProfile == repaired }
        assertTrue(model.state.value.operation?.unresolved == true)
        assertEquals(profile.origin(), model.state.value.operation?.origin)
        val another = profile.copy(id = "another", endpoint = "https://other.invalid/")
        main { model.switchProfile(another) }
        awaitState { it.connected && it.selectedProfile == another }
        assertEquals(2, model.state.value.profiles.size)
        main { model.inspect("Fixture", "http://invalid.invalid/", "tester", "fixture") }
        assertEquals("Enter an HTTPS server address without a path or credentials.", model.state.value.message)
        assertTrue(journal.load().single().unresolved)
    }

    @Test fun profileLimitIsReportedWithoutDisablingEncryptedStorage() = runBlocking {
        main { model.viewModelScope.cancel() }
        val vault = ProfileVault(application, vaultName, alias)
        val profiles = listOf(profile) + (1..49).map { profile.copy(id = "fixture-$it", endpoint = "https://fixture-$it.invalid/") }
        vault.save(ProfileDocument(profiles, profile.id))
        main { model = MeteorViewModel(application, backend, vault, OperationJournal(application, journalName)) }
        awaitState { it.connected }
        main { model.inspect("Extra", "https://extra.invalid/", "tester", "fixture") }
        assertTrue(model.state.value.message?.contains("Up to 50 connections") == true)
        assertFalse(model.state.value.loading)
        assertFalse(model.state.value.vaultProblem)
        assertEquals(50, vault.load().profiles.size)
        val repaired = profile.copy(certificateSha256 = "b".repeat(64))
        main { model.switchProfile(repaired) }
        awaitState { it.connected && it.selectedProfile == repaired }
        assertEquals(50, vault.load().profiles.size)
        assertEquals(repaired, vault.load().profiles.single { it.id == profile.id })
    }
}
