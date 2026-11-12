package com.meteor.app.data

import org.junit.Assert.*
import org.junit.Test

class OperationHistoryTest {
    private fun op(id: String, phase: PowerPhase = PowerPhase.SUCCEEDED, time: Long = 1) =
        PowerOperation(id, "profile", "a".repeat(64), "pool", "vm", "VM", PowerAction.START, null, phase, 1, time)
    @Test fun allUnresolvedActionsSurviveTheClosedHistoryLimit() {
        val pending = op("pending", PowerPhase.UNKNOWN)
        val records = (1..80).map { op("closed-$it", time = it.toLong()) } + pending
        val result = OperationHistory.include(records, op("new", time = 100))
        assertEquals(51, result.size)
        assertTrue(pending in result)
        assertEquals(pending, OperationHistory.current(result))
        assertFalse(result.any { it.id == "closed-1" })
    }
    @Test fun updatedRecordsReplaceInsteadOfDuplicatingAndRemainReadOnly() {
        val original = op("one", PowerPhase.TRACKING)
        val records = listOf(original)
        val updated = original.copy(phase = PowerPhase.UNKNOWN, updatedAt = 2)
        assertEquals(listOf(updated), OperationHistory.include(records, updated))
        assertEquals(listOf(original), records)
    }
    @Test fun manualClosureNeverLooksLikeVerifiedTaskSuccess() {
        val closed = op("reviewed", PowerPhase.UNKNOWN).copy(manualResolution = ManualResolution(2, "b".repeat(64), "Halted", null))
        assertFalse(closed.unresolved)
        assertEquals("Closed · Outcome unverified", OperationHistory.status(closed))
        assertEquals("Verified", OperationHistory.status(op("verified")))
        assertEquals("Outcome uncertain", OperationHistory.status(op("unknown", PowerPhase.UNKNOWN)))
    }

    // ---- operation card visibility ----

    private val linger = OperationHistory.CARD_LINGER_MILLIS
    private val reviewed = ManualResolution(2, "b".repeat(64), "Halted", null)

    @Test fun anUnresolvedOperationIsAlwaysShownHoweverOldItIs() {
        val longAgo = 1_000L
        val now = longAgo + 30L * 24 * 3600 * 1000
        for (phase in listOf(PowerPhase.PREPARED, PowerPhase.SUBMITTING, PowerPhase.TRACKING, PowerPhase.VERIFYING, PowerPhase.UNKNOWN)) {
            assertTrue("$phase", OperationHistory.cardVisible(op("pending", phase, time = longAgo), now))
        }
    }
    @Test fun anUncertainOutcomeStaysVisibleUntilItIsReviewedAndClosed() {
        val uncertain = op("unknown", PowerPhase.UNKNOWN, time = 10)
        assertTrue(uncertain.unresolved)
        assertTrue(OperationHistory.cardVisible(uncertain, 10 + 5 * linger))
        // Closing it by manual review makes it an ordinary closed record, shown briefly after the review.
        val closed = uncertain.copy(manualResolution = reviewed, updatedAt = 50_000)
        assertFalse(closed.unresolved)
        assertTrue(OperationHistory.cardVisible(closed, 50_000 + linger))
        assertFalse(OperationHistory.cardVisible(closed, 50_000 + linger + 1))
    }
    @Test fun aClosedOperationIsShownBrieflyThenLeavesTheScreen() {
        for (phase in listOf(PowerPhase.SUCCEEDED, PowerPhase.FAILED)) {
            val done = op("done", phase, time = 1_000_000)
            assertTrue("$phase just now", OperationHistory.cardVisible(done, 1_000_000))
            assertTrue("$phase one minute later", OperationHistory.cardVisible(done, 1_000_000 + 60_000))
            assertTrue("$phase at the limit", OperationHistory.cardVisible(done, 1_000_000 + linger))
            assertFalse("$phase just past the limit", OperationHistory.cardVisible(done, 1_000_000 + linger + 1))
            assertFalse("$phase days later", OperationHistory.cardVisible(done, 1_000_000 + 3L * 24 * 3600 * 1000))
        }
        assertEquals("two minutes", 120_000L, linger)
    }
    @Test fun aRecordFromTheFutureIsNotPinnedAndNothingMeansNoCard() {
        // The phone clock was set back: the age of the record cannot be trusted, so it is not pinned. History still lists it.
        assertFalse(OperationHistory.cardVisible(op("future", time = 2_000_000), 1_000_000))
        assertFalse(OperationHistory.cardVisible(null, 1_000_000))
    }
    @Test fun theCardRuleOnlyHidesTheCardNeverTheRecord() {
        val old = op("old", time = 1)
        val records = listOf(old)
        assertFalse(OperationHistory.cardVisible(OperationHistory.current(records), 10L * linger))
        assertEquals("the record stays the current one and stays in the history list", old, OperationHistory.current(records))
        assertEquals(records, OperationHistory.include(emptyList(), old))
    }
    @Test fun aPendingRecordIsNeverHiddenBehindANewerClosedOne() {
        // current() prefers an unresolved record, so a pending one is never hidden behind a newer closed one.
        val pending = op("pending", PowerPhase.UNKNOWN, time = 1)
        val newerClosed = op("closed", PowerPhase.SUCCEEDED, time = 900_000)
        val shown = OperationHistory.current(listOf(newerClosed, pending))
        assertEquals(pending, shown)
        assertTrue(OperationHistory.cardVisible(shown, 900_000 + 10 * linger))
    }
}
