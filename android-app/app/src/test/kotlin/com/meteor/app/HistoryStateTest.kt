package com.meteor.app

import com.meteor.app.data.*
import org.junit.Assert.*
import org.junit.Test

class HistoryStateTest {
    private val pending = PowerOperation("op", "profile", "a".repeat(64), "pool", "vm", "VM", PowerAction.START,
        null, PowerPhase.UNKNOWN, 1, 2)
    @Test fun delayedHistoryCannotEraseANewerUncertainAction() {
        val before = MeteorState(journalProblem = false)
        val after = before.withOperation(pending)
        assertSame(after, after.withJournal(emptyList(), before.historyRevision))
        assertEquals(pending, after.operation)
        assertTrue(after.operation!!.unresolved)
    }
    @Test fun historyReadStartedBeforeSubmissionCannotReplaceItsState() {
        val before = MeteorState(journalProblem = false)
        val sending = before.copy(operationBusy = true)
        assertSame(sending, sending.withJournal(emptyList(), before.historyRevision))
    }
    @Test fun successfulReadKeepsPendingAheadOfNewerClosedHistory() {
        val newer = pending.copy(id = "closed", phase = PowerPhase.SUCCEEDED, updatedAt = 100)
        val state = MeteorState().withJournal(listOf(pending, newer))
        assertFalse(state.journalProblem)
        assertEquals(pending, state.operation)
        assertEquals(listOf(newer, pending), state.history)
    }
}
