package com.meteor.app

import com.meteor.app.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionControllerTest {
    private val original = Profile("profile", "Fixture", "https://test.invalid/", "tester", "fixture", "a".repeat(64))
    private class Vault(var document: ProfileDocument) : ProfileStorage {
        var fail = false
        var gate: CompletableDeferred<Unit>? = null
        var writes = 0
        override suspend fun load() = document
        override suspend fun save(document: ProfileDocument) {
            writes++
            gate?.await()
            if (fail) throw IOException("fixture write failure")
            this.document = document
        }
    }
    private class Backend : MeteorGateway {
        var reads = 0
        var result: suspend () -> Inventory = { Inventory("pool", "Pool", emptyList(), emptyList(), 0, 0, 1) }
        override suspend fun inventory(profile: Profile): Inventory { reads++; return result() }
        override suspend fun details(profile: Profile, authority: String, uuid: String): VmDetails = error("Unexpected details")
        override suspend fun <T> powerConnection(profile: Profile, action: suspend PowerConnection.() -> T): T = error("Unexpected power access")
    }
    private class Journal : OperationStore {
        var records = emptyList<PowerOperation>()
        override suspend fun load() = records
        override suspend fun put(operation: PowerOperation) = error("Connections must not change operation history")
    }
    private inner class Fixture(scope: CoroutineScope) {
        val vault = Vault(ProfileDocument(listOf(original)))
        val backend = Backend()
        val journal = Journal()
        val state = MutableStateFlow(MeteorState(profiles = listOf(original), journalProblem = false))
        var inspections = 0
        val controller = ConnectionController(scope, backend, vault, journal, CertificateInspector {
            inspections++; ServerCertificate("b".repeat(64), "Fixture", 1, 2)
        }, state, {}, {}, {}, { "Fixture connection failed." }).apply { initialized = true }
    }

    @Test fun renameIsOfflineAndPreservesCredentialsAndTrust() = runTest {
        val f = Fixture(backgroundScope)
        f.controller.saveConnection("Renamed", original.endpoint, original.username, "", original.id)
        runCurrent()
        assertEquals(original.copy(label = "Renamed"), f.vault.document.profiles.single())
        assertEquals(0, f.backend.reads)
        assertEquals(0, f.inspections)
        assertEquals(NoticeKind.INFO, f.state.value.notice?.kind)
    }
    @Test fun certificateInspectionIsInjectedAndNeedsExplicitAcceptance() = runTest {
        val f = Fixture(backgroundScope)
        f.controller.inspect("Changed", "https://other.invalid/", original.username, "", original.id)
        runCurrent()
        assertEquals(1, f.inspections)
        assertEquals(0, f.backend.reads)
        assertEquals(0, f.vault.writes)
        assertEquals(original, f.state.value.pendingTrust?.previous)
        f.controller.acceptTrust()
        runCurrent()
        assertTrue(f.state.value.connected)
        assertEquals("b".repeat(64), f.vault.document.profiles.single().certificateSha256)
        assertEquals(original.password, f.vault.document.profiles.single().password)
    }
    @Test fun cancellationCannotSplitTheDurableCommitAndVisibleProfile() = runTest {
        val scope = CoroutineScope(backgroundScope.coroutineContext)
        val f = Fixture(scope)
        val gate = CompletableDeferred<Unit>(); f.vault.gate = gate
        f.controller.switchProfile(original)
        runCurrent()
        assertTrue(f.state.value.profileBusy)
        f.controller.disconnect() // A commit in progress cannot be overtaken.
        scope.cancel()
        gate.complete(Unit)
        runCurrent()
        assertTrue(f.state.value.connected)
        assertFalse(f.state.value.profileBusy)
        assertEquals(f.vault.document.selectedId, f.state.value.selectedId)
        assertEquals(f.vault.document.profiles, f.state.value.profiles)
    }
    @Test fun lateNetworkResponseCannotUndoADisconnect() = runTest {
        val f = Fixture(backgroundScope)
        val response = CompletableDeferred<Inventory>()
        f.backend.result = { withContext(NonCancellable) { response.await() } }
        f.controller.switchProfile(original); runCurrent()
        f.controller.disconnect(); runCurrent()
        response.complete(Inventory("pool", "Pool", emptyList(), emptyList(), 0, 0, 1)); runCurrent()
        assertFalse(f.state.value.connected)
        assertNull(f.state.value.selectedId)
        assertNull(f.vault.document.selectedId)
        assertNull(f.state.value.inventory)
    }
    @Test fun failedSavePreservesThePreviousProfileAndShowsAnError() = runTest {
        val f = Fixture(backgroundScope); f.vault.fail = true
        f.controller.saveConnection("Renamed", original.endpoint, original.username, "", original.id); runCurrent()
        assertEquals(original, f.vault.document.profiles.single())
        assertEquals(original, f.state.value.profiles.single())
        assertEquals(NoticeKind.ERROR, f.state.value.notice?.kind)
        assertFalse(f.state.value.profileBusy)
    }
    @Test fun forgetReadsTheWholeJournalAndCannotRemoveAnUnresolvedOrigin() = runTest {
        val f = Fixture(backgroundScope)
        val pending = PowerOperation("pending", original.id, original.origin(), "pool", "vm", "VM", PowerAction.START,
            null, PowerPhase.UNKNOWN, 1, 1)
        f.journal.records = listOf(pending)
        f.controller.forgetProfile(original.id); runCurrent()
        assertEquals(listOf(original), f.state.value.profiles)
        assertEquals(listOf(original), f.vault.document.profiles)
        assertEquals(listOf(pending), f.journal.records)
        assertEquals(0, f.vault.writes)
    }
    @Test fun sessionOnlyRenameWarnsWithoutOverwritingTheUnreadableVault() = runTest {
        val f = Fixture(backgroundScope)
        f.state.value = f.state.value.copy(vaultProblem = true)
        f.controller.saveConnection("Session", original.endpoint, original.username, "", original.id); runCurrent()
        assertEquals("Session", f.state.value.profiles.single().label)
        assertEquals(original, f.vault.document.profiles.single())
        assertEquals(0, f.vault.writes)
        assertEquals(NoticeKind.WARNING, f.state.value.notice?.kind)
    }
    @Test fun invalidEndpointNeverReachesCertificateOrCredentialCalls() = runTest {
        val f = Fixture(backgroundScope)
        f.controller.inspect("Invalid", "http://test.invalid/path", "tester", "fixture"); runCurrent()
        assertEquals(0, f.inspections)
        assertEquals(0, f.backend.reads)
        assertEquals(0, f.vault.writes)
        assertEquals(NoticeKind.ERROR, f.state.value.notice?.kind)
    }
}
