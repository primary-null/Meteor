package com.meteor.app.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class PowerReviewTest {
    private val profile = Profile("original", "Fixture", "https://test.invalid/", "tester", "fixture", "a".repeat(64))
    private val repaired = profile.copy(certificateSha256 = "b".repeat(64))
    private fun vm() = VmDetails("pool", "vm", "Guest", "", "Running", null, 1, 1024,
        emptyList(), emptyList(), false, null, emptyList(), "20260101T10:00:00Z",
        setOf("clean_shutdown", "clean_reboot"), false, 1000)
    private fun operation(phase: PowerPhase = PowerPhase.UNKNOWN) = PowerOperation("pending", profile.id, profile.origin(),
        "pool", "vm", "Guest", PowerAction.START, null, phase, 100, 100, "task-ref", "task-uuid")
    private class Store(var operation: PowerOperation) : OperationStore {
        var failWrites = false
        override suspend fun load() = listOf(operation)
        override suspend fun put(operation: PowerOperation) {
            if (failWrites) throw IOException("disk unavailable")
            this.operation = operation
        }
    }
    private class Backend(var current: VmDetails) : PowerBackend, PowerConnection {
        var sends = 0; var reads = 0; var taskMissing = false
        override suspend fun <T> powerConnection(profile: Profile, action: suspend PowerConnection.() -> T) = action(this)
        override suspend fun details(authority: String, uuid: String): VmDetails { reads++; return current }
        override suspend fun submit(authority: String, uuid: String, action: PowerAction): String { sends++; error("Review must never submit") }
        override suspend fun taskUuid(reference: String) = "task-uuid"
        override suspend fun taskReference(uuid: String): String {
            if (taskMissing) throw RpcFailure("UUID_INVALID")
            return "task-ref"
        }
        override suspend fun observeTask(reference: String) = TaskObservation("success", 1.0)
    }
    private fun service(backend: Backend, store: Store, now: () -> Long = { 1000 }) = PowerService(backend, store, now, {}, 1)

    @Test fun missingTaskStaysUnknownUntilExplicitReviewAndNeverReplays() = runTest {
        val original = operation(); val store = Store(original)
        val backend = Backend(vm()).apply { taskMissing = true }; val service = service(backend, store)
        val unknown = service.reconcile(profile, original)
        assertTrue(unknown.unresolved); assertEquals(PowerPhase.UNKNOWN, unknown.phase)
        val review = service.review(profile, unknown)
        assertTrue(runCatching { service.resolveAfterReview(profile, review, false) }.isFailure)
        assertTrue(store.operation.unresolved)
        val closed = service.resolveAfterReview(profile, review, true)
        assertFalse(closed.unresolved); assertEquals(PowerPhase.UNKNOWN, closed.phase)
        assertEquals(original.origin, closed.origin); assertEquals(original.taskRef, closed.taskRef)
        assertEquals(original.taskUuid, closed.taskUuid); assertNotNull(closed.manualResolution)
        assertEquals(0, backend.sends)
        assertEquals(closed, service.reconcile(profile, closed))
    }
    @Test fun repairedPinCanReviewOriginalVmWithoutRebindingOriginalTaskIdentity() = runTest {
        for (phase in listOf(PowerPhase.SUBMITTING, PowerPhase.TRACKING, PowerPhase.UNKNOWN)) {
            val original = operation(phase); val store = Store(original); val backend = Backend(vm())
            val service = service(backend, store)
            assertTrue(runCatching { service.reconcile(repaired, original) }.isFailure)
            val review = service.review(repaired, original)
            val closed = service.resolveAfterReview(repaired, review, true)
            assertEquals(original.origin, closed.origin)
            assertEquals(repaired.origin(), closed.manualResolution?.origin)
            assertEquals(2, backend.reads); assertEquals(0, backend.sends)
        }
    }
    @Test fun wrongVmPoolOrActiveTaskCannotBeReviewedOrClosed() = runTest {
        val valid = vm()
        for (invalid in listOf(valid.copy(uuid = "other"), valid.copy(authority = "other"), valid.copy(currentOperations = true))) {
            val store = Store(operation()); val backend = Backend(invalid); val service = service(backend, store)
            assertTrue(runCatching { service.review(profile, store.operation) }.isFailure)
            backend.current = valid
            val review = service.review(profile, store.operation)
            backend.current = invalid
            assertTrue(runCatching { service.resolveAfterReview(profile, review, true) }.isFailure)
            assertTrue(store.operation.unresolved); assertEquals(0, backend.sends)
        }
    }
    @Test fun staleChangedOrWrongConnectionReviewCannotCloseRecord() = runTest {
        val store = Store(operation()); val backend = Backend(vm()); var now = 1000L
        val service = service(backend, store) { now }
        val review = service.review(profile, store.operation)
        assertTrue(runCatching { service.resolveAfterReview(repaired, review, true) }.isFailure)
        now = 31_001
        assertTrue(runCatching { service.resolveAfterReview(profile, review, true) }.isFailure)
        now = 1000
        backend.current = vm().copy(bootTime = "20260101T11:00:00Z")
        assertTrue(runCatching { service.resolveAfterReview(profile, review, true) }.isFailure)
        backend.current = vm()
        store.operation = store.operation.copy(message = "Another observer changed this record")
        assertTrue(runCatching { service.resolveAfterReview(profile, review, true) }.isFailure)
        assertTrue(store.operation.unresolved); assertEquals(0, backend.sends)
    }
    @Test fun failedResolutionWriteLeavesTheRecordPending() = runTest {
        val store = Store(operation()); val backend = Backend(vm()); val service = service(backend, store)
        val review = service.review(profile, store.operation)
        store.failWrites = true
        assertTrue(runCatching { service.resolveAfterReview(profile, review, true) }.isFailure)
        assertTrue(store.operation.unresolved); assertEquals(0, backend.sends)
    }
}
