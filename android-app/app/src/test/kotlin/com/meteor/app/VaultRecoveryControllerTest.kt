package com.meteor.app

import com.meteor.app.data.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import javax.crypto.AEADBadTagException

@OptIn(ExperimentalCoroutinesApi::class)
class VaultRecoveryControllerTest {
    private val saved = Profile("saved", "Saved", "https://pool-a.invalid/", "tester", "fixture", "a".repeat(64))
    private val typed = Profile("typed", "Typed", "https://pool-b.invalid/", "tester", "fixture", "b".repeat(64))

    /** Records every call; [loads] decides what each load does. */
    private class Vault : ProfileStorage {
        var stored = ProfileDocument()
        val loads = ArrayDeque<() -> ProfileDocument>()
        var loadCount = 0
        var startOvers = 0
        var startOverFails = false
        var saveFails = false
        val saves = mutableListOf<ProfileDocument>()
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun load(): ProfileDocument { loadCount++; gate?.await(); return (loads.removeFirstOrNull() ?: { stored })() }
        override suspend fun save(document: ProfileDocument) { if (saveFails) throw IOException("fixture"); saves += document; stored = document }
        override suspend fun startOver(): Boolean {
            startOvers++
            if (startOverFails) throw IOException("fixture rename failure")
            stored = ProfileDocument(); return true
        }
    }

    private inner class Fixture(scope: CoroutineScope, failure: Throwable = AEADBadTagException("tag"), state: MeteorState = MeteorState()) {
        val vault = Vault()
        val state = MutableStateFlow(state.copy(journalProblem = false))
        val retained = mutableListOf<Set<String>>()
        val opened = mutableListOf<Profile?>()
        val busyWhenOpened = mutableListOf<Boolean>()
        var allowed = true
        val controller = VaultRecoveryController(scope, vault, this.state, { it() }, { allowed },
            open = { vault.load() }, retain = { retained += it }, opened = { opened += it; busyWhenOpened += this.state.value.profileBusy })
        init { controller.loadFailed(failure) }
    }

    private fun pending(profileId: String = saved.id) = PowerOperation("pending", profileId, saved.origin(), "pool", "vm", "Fixture VM",
        PowerAction.START, null, PowerPhase.UNKNOWN, 1, 1)

    // --- the failure ---

    @Test fun aFailedLoadMarksTheVaultAndKeepsTheCause() = runTest {
        val f = Fixture(backgroundScope, AEADBadTagException("tag"))
        assertTrue(f.state.value.vaultProblem)
        assertEquals(VaultRecovery(VaultFailure.CORRUPT, attempts = 1), f.state.value.vaultRecovery)
        assertEquals(VaultFailure.KEY_UNAVAILABLE, Fixture(backgroundScope, VaultException(VaultFailure.KEY_UNAVAILABLE, "key")).state.value.vaultRecovery.failure)
        assertEquals(VaultFailure.TEMPORARY, Fixture(backgroundScope, IOException("disk")).state.value.vaultRecovery.failure)
    }

    // --- Try again ---

    @Test fun aTransientFailureRecoversWithoutRestartingTheApp() = runTest {
        val f = Fixture(backgroundScope, IOException("disk"))
        f.vault.stored = ProfileDocument(listOf(saved), saved.id)
        f.controller.retry(); runCurrent()
        assertFalse(f.state.value.vaultProblem)
        assertEquals(VaultRecovery(), f.state.value.vaultRecovery)
        assertEquals(listOf(saved), f.state.value.profiles)
        assertEquals(saved.id, f.state.value.selectedId)
        assertFalse(f.state.value.profileBusy)
        assertEquals(NoticeKind.INFO, f.state.value.notice?.kind)
        assertEquals(listOf(saved), f.opened) // start-up's steps run: it connects the saved selection
        assertEquals(listOf(false), f.busyWhenOpened) // and only once the busy flag is clear, so a pending-action recheck is allowed
        assertEquals(0, f.vault.startOvers)
        assertTrue(f.vault.saves.isEmpty()) // nothing to write back
    }

    @Test fun aRetryThatFailsAgainCountsTheAttemptAndUpdatesTheCause() = runTest {
        val f = Fixture(backgroundScope, IOException("disk"))
        f.vault.loads += { throw AEADBadTagException("tag") }
        f.controller.retry(); runCurrent()
        assertTrue(f.state.value.vaultProblem)
        assertEquals(VaultRecovery(VaultFailure.CORRUPT, attempts = 2), f.state.value.vaultRecovery)
        assertFalse(f.state.value.profileBusy)
        assertTrue(f.opened.isEmpty())
        assertEquals(0, f.vault.startOvers)
    }

    @Test fun aTemporaryFailureOffersStartOverOnlyAfterTryAgainFailedToo() = runTest {
        val f = Fixture(backgroundScope, IOException("disk"))
        assertFalse(VaultRecoveryRules.startOverOffered(f.state.value))
        f.controller.startOver(); runCurrent() // not offered yet, so it does nothing
        assertEquals(0, f.vault.startOvers)
        f.vault.loads += { throw IOException("disk again") }
        f.controller.retry(); runCurrent()
        assertTrue(VaultRecoveryRules.startOverOffered(f.state.value))
    }

    @Test fun connectionsAddedMeanwhileStayAndAreSavedWithTheOpenedVault() = runTest {
        val session = MeteorState(profiles = listOf(typed), selectedId = typed.id, connected = true)
        val f = Fixture(backgroundScope, IOException("disk"), session)
        f.vault.stored = ProfileDocument(listOf(saved), saved.id)
        f.controller.retry(); runCurrent()
        assertEquals(listOf(saved.id, typed.id), f.state.value.profiles.map { it.id })
        assertEquals(typed.id, f.state.value.selectedId) // the open connection stays selected
        assertEquals(listOf(saved.id, typed.id), f.vault.stored.profiles.map { it.id })
        assertTrue(f.state.value.notice!!.text.contains("saved too"))
        assertEquals(listOf<Profile?>(null), f.opened) // already connected: nothing to connect
    }

    @Test fun aSessionDuplicateOfASavedServerIsDroppedWithANotice() = runTest {
        val duplicate = typed.copy(id = "dup", endpoint = saved.endpoint)
        val f = Fixture(backgroundScope, IOException("disk"), MeteorState(profiles = listOf(duplicate)))
        f.vault.stored = ProfileDocument(listOf(saved), null)
        f.controller.retry(); runCurrent()
        assertEquals(listOf(saved), f.state.value.profiles)
        assertTrue(f.state.value.notice!!.text.contains("not kept"))
        assertTrue(f.vault.saves.isEmpty())
    }

    @Test fun ifSavingTheSessionConnectionsFailsTheVaultIsStillOpenAndTheyStayListed() = runTest {
        val f = Fixture(backgroundScope, IOException("disk"), MeteorState(profiles = listOf(typed), selectedId = typed.id, connected = true))
        f.vault.stored = ProfileDocument(listOf(saved), saved.id)
        f.vault.saveFails = true
        f.controller.retry(); runCurrent()
        assertFalse(f.state.value.vaultProblem)
        assertEquals(listOf(saved.id, typed.id), f.state.value.profiles.map { it.id })
        assertTrue(f.state.value.notice!!.text.contains("could not be saved yet"))
        assertEquals(listOf(saved), f.vault.stored.profiles) // the readable vault was not damaged
    }

    @Test fun nothingStartsWhileAnythingElseChangesProfilesOrConnects() = runTest {
        listOf<(MeteorState) -> MeteorState>({ it.copy(loading = true) }, { it.copy(profileBusy = true) }, { it.copy(operationBusy = true) },
            { it.copy(pendingTrust = PendingTrust(saved, ServerCertificate("c".repeat(64), "Fixture", 1, 2))) }).forEach { change ->
            val f = Fixture(backgroundScope, AEADBadTagException("tag"))
            f.state.value = change(f.state.value)
            f.controller.retry(); f.controller.startOver(); runCurrent()
            assertEquals(0, f.vault.loadCount); assertEquals(0, f.vault.startOvers)
        }
        val blocked = Fixture(backgroundScope, AEADBadTagException("tag")).apply { allowed = false }
        blocked.controller.retry(); blocked.controller.startOver(); runCurrent()
        assertEquals(0, blocked.vault.loadCount); assertEquals(0, blocked.vault.startOvers)
    }

    @Test fun aSecondTapWhileRunningDoesNothingAndTheBusyFlagsClearAfterwards() = runTest {
        val f = Fixture(backgroundScope, IOException("disk"))
        f.vault.gate = CompletableDeferred()
        f.controller.retry(); runCurrent()
        assertTrue(f.state.value.profileBusy); assertTrue(f.state.value.vaultRecovery.busy)
        f.controller.retry(); f.controller.startOver(); runCurrent()
        assertEquals(1, f.vault.loadCount)
        f.vault.gate!!.complete(Unit); runCurrent()
        assertFalse(f.state.value.profileBusy); assertFalse(f.state.value.vaultRecovery.busy)
    }

    // --- Start over ---

    @Test fun startOverSetsTheVaultAsideAndReturnsToNormalSaving() = runTest {
        val f = Fixture(backgroundScope, VaultException(VaultFailure.KEY_UNAVAILABLE, "key"))
        f.controller.startOver(); runCurrent()
        assertEquals(1, f.vault.startOvers)
        assertFalse(f.state.value.vaultProblem)
        assertEquals(VaultRecovery(), f.state.value.vaultRecovery)
        assertTrue(f.state.value.profiles.isEmpty()); assertNull(f.state.value.selectedId)
        assertFalse(f.state.value.profileBusy)
        assertEquals(setOf<String>(), f.retained.single()) // other stores drop what belonged to the old profiles
        assertEquals(listOf<Profile?>(null), f.opened)
        assertTrue(f.state.value.notice!!.text.startsWith("Started over."))
    }

    @Test fun startOverNeverChangesTheJournalStateAndAnUnresolvedActionKeepsItsLock() = runTest {
        val record = pending()
        val f = Fixture(backgroundScope, VaultException(VaultFailure.KEY_UNAVAILABLE, "key"), MeteorState(operation = record))
        f.controller.startOver(); runCurrent()
        // The controller has no journal at all; the visible record and the write lock derived from it are exactly as before.
        assertSame(record, f.state.value.operation)
        assertTrue(f.state.value.operation!!.unresolved)
        assertFalse(f.state.value.journalProblem)
        assertTrue(f.state.value.notice!!.text.contains("Review pending action"))
        // The pending action's own connection is gone, and the app says what to do.
        assertNotNull(VaultRecoveryText.pendingNote(f.state.value))
    }

    @Test fun startOverStillWorksWhileTheJournalCannotBeRead() = runTest {
        val f = Fixture(backgroundScope, AEADBadTagException("tag"), MeteorState(journalProblem = true))
        f.state.value = f.state.value.copy(journalProblem = true)
        f.controller.startOver(); runCurrent()
        assertEquals(1, f.vault.startOvers)
        assertTrue(f.state.value.journalProblem) // untouched: power controls stay locked
        assertFalse(f.state.value.vaultProblem)
    }

    @Test fun startOverKeepsAndSavesTheConnectionsTypedThisSession() = runTest {
        val f = Fixture(backgroundScope, AEADBadTagException("tag"), MeteorState(profiles = listOf(typed), selectedId = typed.id, connected = true))
        f.controller.startOver(); runCurrent()
        assertEquals(listOf(typed), f.state.value.profiles)
        assertEquals(ProfileDocument(listOf(typed), typed.id), f.vault.stored)
        assertEquals(setOf(typed.id), f.retained.single())
        assertTrue(f.state.value.connected)
    }

    @Test fun aFailedStartOverChangesNothingAndSaysSo() = runTest {
        val f = Fixture(backgroundScope, AEADBadTagException("tag"), MeteorState(profiles = listOf(typed), selectedId = typed.id))
        f.vault.startOverFails = true
        f.controller.startOver(); runCurrent()
        assertTrue(f.state.value.vaultProblem)
        assertEquals(VaultFailure.CORRUPT, f.state.value.vaultRecovery.failure)
        assertEquals(listOf(typed), f.state.value.profiles)
        assertEquals(VaultRecoveryText.START_OVER_FAILED, f.state.value.notice?.text)
        assertEquals(NoticeKind.ERROR, f.state.value.notice?.kind)
        assertFalse(f.state.value.profileBusy)
        assertTrue(f.vault.saves.isEmpty()); assertTrue(f.retained.isEmpty()); assertTrue(f.opened.isEmpty())
    }

    @Test fun startOverIsNotAvailableOnceTheVaultIsOpen() = runTest {
        val f = Fixture(backgroundScope, AEADBadTagException("tag"))
        f.controller.startOver(); runCurrent()
        f.controller.startOver(); f.controller.retry(); runCurrent()
        assertEquals(1, f.vault.startOvers) // a second reset needs a new failure
        assertEquals(0, f.vault.loadCount)
    }
}
