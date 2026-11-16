package com.meteor.app

import com.meteor.app.data.*
import org.junit.Assert.*
import org.junit.Test

/** What the person sees for an unreadable vault: the panel, the confirmation, and the banners. */
class VaultRecoveryTextTest {
    private val saved = Profile("saved", "Saved", "https://pool-a.invalid/", "tester", "fixture", "a".repeat(64))
    private fun problem(failure: VaultFailure, attempts: Int = 1, extra: (MeteorState) -> MeteorState = { it }) =
        extra(MeteorState(vaultProblem = true, journalProblem = false, vaultRecovery = VaultRecovery(failure, attempts)))
    private fun pending(profileId: String = saved.id) = PowerOperation("pending", profileId, saved.origin(), "pool", "vm", "Fixture VM",
        PowerAction.START, null, PowerPhase.UNKNOWN, 1, 1)

    @Test fun nothingIsShownWhileTheSavedConnectionsOpened() {
        assertNull(VaultRecoveryText.panel(MeteorState()))
        assertTrue(VaultRecoveryText.banners(MeteorState(), onConnections = false).isEmpty())
    }

    @Test fun theMainSentenceIsPlainAndSaysWhatItMeansForThisSession() {
        VaultFailure.entries.forEach { failure ->
            val panel = VaultRecoveryText.panel(problem(failure))!!
            assertTrue(panel.title.contains("could not be opened"))
            assertFalse("jargon in the main sentence", listOf("keystore", "gcm", "aes", "decrypt", "vault").any { panel.body.contains(it, ignoreCase = true) })
            assertTrue(panel.body.contains("aren't saved"))
            assertTrue(panel.body.contains("enter them again"))
            assertTrue(panel.detail.startsWith("Detail:"))
        }
    }

    @Test fun theCauseGoesInTheSecondaryLine() {
        assertTrue(VaultRecoveryText.panel(problem(VaultFailure.KEY_UNAVAILABLE))!!.detail.contains("Keystore"))
        assertTrue(VaultRecoveryText.panel(problem(VaultFailure.CORRUPT))!!.detail.contains("damaged"))
        assertTrue(VaultRecoveryText.panel(problem(VaultFailure.UNKNOWN_FORMAT))!!.detail.contains("version of Meteor"))
        assertTrue(VaultRecoveryText.panel(problem(VaultFailure.TEMPORARY))!!.detail.contains("temporary"))
    }

    @Test fun permanentCausesOfferBothButtonsAndATemporaryOneOffersStartOverOnlyAfterAFailedRetry() {
        listOf(VaultFailure.KEY_UNAVAILABLE, VaultFailure.CORRUPT, VaultFailure.UNKNOWN_FORMAT).forEach {
            val panel = VaultRecoveryText.panel(problem(it))!!
            assertTrue(panel.retryEnabled); assertTrue(panel.startOverShown); assertTrue(panel.startOverEnabled)
            assertFalse(panel.stillFailing)
        }
        val first = VaultRecoveryText.panel(problem(VaultFailure.TEMPORARY))!!
        assertTrue(first.retryEnabled); assertFalse(first.startOverShown); assertFalse(first.startOverEnabled)
        val again = VaultRecoveryText.panel(problem(VaultFailure.TEMPORARY, attempts = 2))!!
        assertTrue(again.startOverShown); assertTrue(again.stillFailing)
    }

    @Test fun buttonsAreDisabledWhileBusyOrWhileSomethingElseChangesProfiles() {
        val busy = VaultRecoveryText.panel(problem(VaultFailure.CORRUPT) { it.copy(vaultRecovery = it.vaultRecovery.copy(busy = true), profileBusy = true) })!!
        assertTrue(busy.busy); assertFalse(busy.retryEnabled); assertFalse(busy.startOverEnabled); assertTrue(busy.startOverShown)
        val connecting = VaultRecoveryText.panel(problem(VaultFailure.CORRUPT) { it.copy(loading = true) })!!
        assertFalse(connecting.retryEnabled); assertFalse(connecting.startOverEnabled)
    }

    @Test fun theConfirmationSaysWhatIsLostWhatIsNotAndThatNothingIsDeleted() {
        val text = VaultRecoveryText.confirmation(problem(VaultFailure.CORRUPT)).paragraphs.joinToString("\n")
        assertTrue(text.contains("servers, passwords and approved certificates on this phone will be gone"))
        assertTrue(text.contains("enter them again"))
        assertTrue(text.contains("Nothing on any server is changed"))
        assertTrue(text.contains("set aside"))
        assertTrue(text.contains("not deleted"))
        assertEquals("Start over", VaultRecoveryText.confirmation(problem(VaultFailure.CORRUPT)).confirmLabel)
    }

    @Test fun aPendingActionIsNamedInTheConfirmationWithWhatToDoAfterwards() {
        val state = problem(VaultFailure.CORRUPT) { it.copy(operation = pending()) }
        val text = VaultRecoveryText.confirmation(state).paragraphs.joinToString("\n")
        assertTrue(text.contains("A power action is still pending"))
        assertTrue(text.contains("new actions stay blocked"))
        assertTrue(text.contains("add the same server again"))
        assertTrue(text.contains("Review pending action"))
        assertFalse(text.contains("cannot be read"))
    }

    @Test fun anUnreadableJournalIsSaidToStayUnchangedToo() {
        val text = VaultRecoveryText.confirmation(problem(VaultFailure.CORRUPT) { it.copy(journalProblem = true) }).paragraphs.joinToString("\n")
        assertTrue(text.contains("Operation history could not be read"))
        assertTrue(text.contains("not changed"))
    }

    @Test fun aClosedActionIsNotMentioned() {
        val closed = pending().copy(phase = PowerPhase.SUCCEEDED)
        val text = VaultRecoveryText.confirmation(problem(VaultFailure.CORRUPT) { it.copy(operation = closed) }).paragraphs.joinToString("\n")
        assertFalse(text.contains("pending"))
    }

    @Test fun theShellBannerPointsToConnectionsButOnlyOutsideThatScreen() {
        val state = problem(VaultFailure.CORRUPT)
        val outside = VaultRecoveryText.banners(state, onConnections = false).single()
        assertEquals(NoticeKind.WARNING, outside.kind)
        assertTrue(outside.text.contains("Open Connections"))
        assertTrue(VaultRecoveryText.banners(state, onConnections = true).isEmpty())
    }

    @Test fun aPendingActionWhoseConnectionIsNotSavedGetsAPersistentNoteUntilItsConnectionIsBack() {
        assertNotNull(VaultRecoveryText.pendingNote(MeteorState(operation = pending())))
        assertNull(VaultRecoveryText.pendingNote(MeteorState(operation = pending(), profiles = listOf(saved))))
        assertNull(VaultRecoveryText.pendingNote(MeteorState(operation = pending().copy(phase = PowerPhase.FAILED))))
        assertNull(VaultRecoveryText.pendingNote(MeteorState()))
        val note = VaultRecoveryText.pendingNote(MeteorState(operation = pending()))!!.text
        assertTrue(note.contains("Add the same server again") && note.contains("Review pending action"))
        // It is shown on Connections as well; the vault banner is not.
        assertEquals(1, VaultRecoveryText.banners(MeteorState(operation = pending()), onConnections = true).size)
    }

    @Test fun theFinishedNoticeSaysWhatHappenedToTheConnectionsTypedMeanwhile() {
        val none = VaultMerge(ProfileDocument(), 0, 0)
        assertEquals("Saved connections are open again.", VaultRecoveryText.finished(false, none, true, null).text)
        assertTrue(VaultRecoveryText.finished(true, none, true, null).text.endsWith("Add your servers again."))
        val two = VaultMerge(ProfileDocument(), 2, 0)
        assertTrue(VaultRecoveryText.finished(false, two, true, null).text.contains("The 2 connections you added this time were saved too."))
        assertTrue(VaultRecoveryText.finished(false, VaultMerge(ProfileDocument(), 1, 0), true, null).text.contains("The connection you added this time was saved too."))
        val notice = VaultRecoveryText.finished(true, none, true, pending())
        assertEquals(NoticeKind.INFO, notice.kind)
        assertTrue(notice.text.contains("add the same server again, then use Review pending action"))
    }
}
