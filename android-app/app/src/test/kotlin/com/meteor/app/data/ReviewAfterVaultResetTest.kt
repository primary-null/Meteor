package com.meteor.app.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/**
 * After "Start over" the saved connection is gone and the same server is added again under a new profile id. The identity rules are
 * unchanged: automatic checking still needs the original profile id and origin, so it cannot run; a manual review reads the
 * original pool and VM through any connection to that pool and keeps the original record's identity.
 */
class ReviewAfterVaultResetTest {
    private val original = Profile("original", "Fixture", "https://test.invalid/", "tester", "old-password", "a".repeat(64))
    private val readded = Profile("added-after-reset", "Fixture again", "https://test.invalid/", "tester", "new-password", "a".repeat(64))
    private val otherServer = Profile("other", "Other", "https://other.invalid/", "tester", "fixture", "c".repeat(64))
    private fun vm() = VmDetails("pool", "vm", "Guest", "", "Running", null, 1, 1024,
        emptyList(), emptyList(), false, null, emptyList(), "20260101T10:00:00Z",
        setOf("clean_shutdown", "clean_reboot"), false, 1000)
    private fun operation() = PowerOperation("pending", original.id, original.origin(), "pool", "vm", "Guest", PowerAction.START, null,
        PowerPhase.UNKNOWN, 100, 100, "task-ref", "task-uuid")
    private class Store(var operation: PowerOperation) : OperationStore {
        var writes = 0
        override suspend fun load() = listOf(operation)
        override suspend fun put(operation: PowerOperation) { writes++; this.operation = operation }
    }
    private class Backend(var current: VmDetails) : PowerBackend, PowerConnection {
        var sends = 0
        override suspend fun <T> powerConnection(profile: Profile, action: suspend PowerConnection.() -> T) = action(this)
        override suspend fun details(authority: String, uuid: String): VmDetails = current
        override suspend fun submit(authority: String, uuid: String, action: PowerAction): String { sends++; error("Review must never submit") }
        override suspend fun taskUuid(reference: String) = "task-uuid"
        override suspend fun taskReference(uuid: String) = "task-ref"
        override suspend fun observeTask(reference: String) = TaskObservation("success", 1.0)
    }

    @Test fun theReaddedConnectionHasTheSameOriginButNotTheSameProfileId() {
        assertEquals(original.origin(), readded.origin()) // same server, username and certificate pin
        assertNotEquals(original.id, readded.id)
        assertNotEquals(original.origin(), otherServer.origin())
    }

    @Test fun automaticCheckingStillRequiresTheOriginalProfileAndStaysRefused() = runTest {
        val store = Store(operation()); val backend = Backend(vm())
        val service = PowerService(backend, store, { 1000 }, {}, 1)
        assertTrue(runCatching { service.reconcile(readded, store.operation) }.isFailure)
        assertEquals(0, store.writes); assertEquals(0, backend.sends)
        assertTrue(store.operation.unresolved)
    }

    @Test fun aManualReviewThroughTheReaddedConnectionClosesTheRecordWithoutRebindingItsIdentity() = runTest {
        val record = operation(); val store = Store(record); val backend = Backend(vm())
        val service = PowerService(backend, store, { 1000 }, {}, 1)
        val review = service.review(readded, record)
        assertEquals(readded.id, review.profileId)
        val closed = service.resolveAfterReview(readded, review, true)
        assertFalse(closed.unresolved); assertEquals(PowerPhase.UNKNOWN, closed.phase)
        assertEquals(record.profileId, closed.profileId) // the record keeps the profile that sent the action
        assertEquals(record.origin, closed.origin)
        assertEquals(record.taskRef, closed.taskRef); assertEquals(record.taskUuid, closed.taskUuid)
        assertEquals(readded.origin(), closed.manualResolution?.origin)
        assertEquals(0, backend.sends)
    }

    @Test fun aConnectionToAnotherPoolCannotReviewIt() = runTest {
        val store = Store(operation()); val backend = Backend(vm().copy(authority = "another-pool"))
        val service = PowerService(backend, store, { 1000 }, {}, 1)
        assertTrue(runCatching { service.review(otherServer, store.operation) }.isFailure)
        assertTrue(store.operation.unresolved); assertEquals(0, store.writes)
    }
}
