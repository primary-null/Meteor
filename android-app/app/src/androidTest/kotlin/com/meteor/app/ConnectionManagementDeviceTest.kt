package com.meteor.app

import android.app.Application
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.meteor.app.data.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import java.util.concurrent.atomic.AtomicInteger

/** Offline profile lifecycle checks with real encrypted storage and a delayed fake gateway. */
@RunWith(AndroidJUnit4::class)
class ConnectionManagementDeviceTest {
    private val application get() = ApplicationProvider.getApplicationContext<Application>()
    private val profile = Profile("fixture", "Fixture", "https://test.invalid/", "tester", "fixture", "a".repeat(64))
    private val vaultName = "instrumentation-connections.secured"
    private val journalName = "instrumentation-connections.json"
    private val alias = "meteor.instrumentation.connections"
    private val backend = Backend()
    private lateinit var model: MeteorViewModel
    private lateinit var vault: ProfileVault
    private lateinit var journal: OperationJournal
    private val models = mutableListOf<MeteorViewModel>()

    private class Backend : MeteorGateway {
        val reads = AtomicInteger()
        var delayed: CompletableDeferred<Unit>? = null
        val started = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        var lastProfile: Profile? = null
        override suspend fun inventory(profile: Profile): Inventory {
            lastProfile = profile
            reads.incrementAndGet()
            delayed?.let { barrier ->
                // Model a blocking network operation which completes after Disconnect was selected.
                withContext(NonCancellable) { started.complete(Unit); barrier.await(); finished.complete(Unit) }
            }
            return Inventory("pool", "Fixture pool", emptyList(), emptyList(), 0, 0, System.currentTimeMillis())
        }
        override suspend fun details(profile: Profile, authority: String, uuid: String): VmDetails = error("No detail reads expected")
        override suspend fun <T> powerConnection(profile: Profile, action: suspend PowerConnection.() -> T): T = error("Profile changes must not submit power")
    }

    private fun main(action: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(action)
    private suspend fun awaitState(predicate: (MeteorState) -> Boolean) = withTimeout(15_000) { model.state.first(predicate) }
    private fun createModel(storage: ProfileStorage = vault): MeteorViewModel = MeteorViewModel(application, backend, storage, journal).also { models += it }

    @Before fun prepare() = runBlocking {
        vault = ProfileVault(application, vaultName, alias)
        journal = OperationJournal(application, journalName)
        vault.save(ProfileDocument(listOf(profile), profile.id))
        main { model = createModel() }
        awaitState { it.connected }
        Unit
    }

    @After fun cleanUp() {
        backend.delayed?.complete(Unit)
        main { models.forEach { it.viewModelScope.cancel() } }
        for (name in listOf(vaultName, journalName)) {
            for (suffix in listOf("", ".bak", ".new")) File(application.filesDir, name + suffix).delete()
        }
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
    }

    @Test fun renameWorksOfflineAndPreservesSelectionCredentialsAndPin() = runBlocking {
        val reads = backend.reads.get()
        main {
            model.editProfile(profile.id)
            model.saveConnection("Renamed", profile.endpoint, profile.username, "", profile.id)
        }
        awaitState { !it.profileBusy && it.profiles.single().label == "Renamed" }
        assertEquals(reads, backend.reads.get())
        assertEquals(profile.copy(label = "Renamed"), vault.load().profiles.single())
        assertEquals(profile.id, vault.load().selectedId)
        Unit
    }

    @Test fun replacingCredentialsUsesTheSavedPinAndPreservesTheProfileId() = runBlocking {
        main {
            model.editProfile(profile.id)
            model.saveConnection(profile.label, profile.endpoint, profile.username, "replacement", profile.id)
        }
        awaitState { it.connected && !it.loading && it.profiles.single().password == "replacement" }
        assertEquals(profile.copy(password = "replacement"), backend.lastProfile)
        assertEquals(profile.id, vault.load().profiles.single().id)
        Unit
    }

    @Test fun disconnectDiscardsALateReconnectAndPersistsAcrossModelRecreation() = runBlocking {
        val barrier = CompletableDeferred<Unit>()
        backend.delayed = barrier
        main { model.switchProfile(profile) }
        withTimeout(15_000) { backend.started.await() }
        main { model.disconnect() }
        awaitState { !it.profileBusy && it.selectedId == null }
        barrier.complete(Unit)
        withTimeout(15_000) { backend.finished.await() }
        assertNull(vault.load().selectedId)
        assertEquals(listOf(profile), vault.load().profiles)
        assertFalse(model.state.value.connected)
        val reads = backend.reads.get()
        main { model.refreshIfStale(); model = createModel() }
        awaitState { !it.journalProblem && it.profiles.isNotEmpty() }
        assertNull(model.state.value.selectedId)
        assertEquals(reads, backend.reads.get())
        Unit
    }

    @Test fun forgetChecksTheWholeJournalAndPreservesAnUnresolvedRecord() = runBlocking {
        val pending = PowerOperation("pending", profile.id, profile.origin(), "pool", "vm", "Fixture VM",
            PowerAction.START, null, PowerPhase.UNKNOWN, 1, 1)
        journal.put(pending)
        journal.put(pending.copy(id = "closed", phase = PowerPhase.SUCCEEDED, updatedAt = 2))
        main { model.forgetProfile(profile.id) }
        awaitState { !it.profileBusy && it.message?.startsWith("Resolve this connection's pending action") == true }
        assertEquals(listOf(profile), vault.load().profiles)
        assertEquals(2, journal.load().size)
        assertTrue(journal.load().single { it.id == pending.id }.unresolved)
        Unit
    }

    @Test fun forgettingAnActiveProfileDisconnectsAndKeepsClosedHistory() = runBlocking {
        val closed = PowerOperation("closed", profile.id, profile.origin(), "pool", "vm", "Fixture VM",
            PowerAction.START, null, PowerPhase.SUCCEEDED, 1, 1)
        journal.put(closed)
        main { model.forgetProfile(profile.id) }
        awaitState { !it.profileBusy && it.profiles.isEmpty() }
        assertNull(model.state.value.inventory)
        assertFalse(model.state.value.connected)
        assertTrue(vault.load().profiles.isEmpty())
        assertNull(vault.load().selectedId)
        assertEquals(listOf(closed), journal.load())
        Unit
    }

    @Test fun failedRenameDoesNotReplaceTheExistingEncryptedProfile() = runBlocking {
        main {
            model.editProfile(profile.id)
            model.saveConnection("x".repeat(250_000), profile.endpoint, profile.username, "", profile.id)
        }
        awaitState { !it.profileBusy && it.message?.startsWith("Connection could not be saved") == true }
        assertEquals(listOf(profile), model.state.value.profiles)
        assertEquals(listOf(profile), vault.load().profiles)
        assertEquals(profile.id, model.state.value.editingProfileId)
        Unit
    }

    @Test fun cancellationDuringAProfileCommitCannotSplitMemoryAndDisk() = runBlocking {
        val writeStarted = CompletableDeferred<Unit>()
        val releaseWrite = CompletableDeferred<Unit>()
        val storage = object : ProfileStorage {
            override suspend fun load() = vault.load()
            override suspend fun save(document: ProfileDocument) {
                if (document.profiles.single().password == "replacement") {
                    writeStarted.complete(Unit)
                    releaseWrite.await()
                }
                vault.save(document)
            }
        }
        main { model.viewModelScope.cancel(); model = createModel(storage) }
        awaitState { it.connected }
        try {
            main {
                model.editProfile(profile.id)
                model.saveConnection(profile.label, profile.endpoint, profile.username, "replacement", profile.id)
            }
            withTimeout(15_000) { writeStarted.await() }
            assertTrue(model.state.value.profileBusy)
            main { model.cancelEdit(); model.disconnect() }
            assertTrue(model.state.value.profileBusy)
            assertEquals(profile.id, model.state.value.editingProfileId)
            // Even lifecycle cancellation must finish publishing a write which has already begun.
            main { model.viewModelScope.cancel() }
            releaseWrite.complete(Unit)
            awaitState { !it.profileBusy && it.profiles.single().password == "replacement" }
            assertEquals(vault.load().profiles, model.state.value.profiles)
            assertEquals(vault.load().selectedId, model.state.value.selectedId)
        } finally { releaseWrite.complete(Unit) }
        Unit
    }
}
