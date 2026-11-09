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
}
