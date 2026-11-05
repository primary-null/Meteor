package com.meteor.app.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class PowerTest {
    private val profile = Profile("p", "Lab", "https://test.invalid/", "tester", "secret", "a".repeat(64))
    private fun vm(power: String = "Halted", boot: String? = "20260101T10:00:00Z") = VmDetails("pool", "vm", "Guest", "", power, null, 1, 1024,
        emptyList(), emptyList(), false, null, emptyList(), boot, setOf("start", "clean_reboot", "clean_shutdown"), false, 1000)
    private class Store : OperationStore {
        val records = linkedMapOf<String, PowerOperation>()
        val phases = mutableListOf<PowerPhase>()
        override suspend fun load() = records.values.toList()
        override suspend fun put(operation: PowerOperation) { records[operation.id] = operation; phases += operation.phase }
    }
    private class Backend(var current: VmDetails, val store: Store) : PowerBackend, PowerConnection {
        var sends = 0; var networkFailure = false; var status = "success"; var after: VmDetails = current.copy(power = "Running")
        var submitted = false
        override suspend fun <T> powerConnection(profile: Profile, action: suspend PowerConnection.() -> T): T = action(this)
        override suspend fun details(authority: String, uuid: String) = if (submitted) after else current
        override suspend fun submit(authority: String, uuid: String, action: PowerAction): String {
            assertEquals(PowerPhase.SUBMITTING, store.records.values.last().phase)
            sends++; submitted = true
            if (networkFailure) throw IOException("lost response")
            return "task-ref"
        }
        override suspend fun taskUuid(reference: String) = "task-uuid"
        override suspend fun taskReference(uuid: String) = "task-ref"
        override suspend fun observeTask(reference: String) = TaskObservation(status, 1.0)
    }
    private fun confirmation(current: VmDetails, action: PowerAction = PowerAction.START) = PowerConfirmation(profile.id, profile.origin(), current, action)
    private fun service(backend: Backend, store: Store) = PowerService(backend, store, { 1000 }, {}, 2)

    @Test fun successRequiresTaskThenAuthoritativeReadback() = runTest {
        val store = Store(); val backend = Backend(vm(), store)
        val result = service(backend, store).execute(profile, confirmation(backend.current))
        assertEquals(PowerPhase.SUCCEEDED, result.phase); assertEquals(1, backend.sends)
        assertTrue(store.phases.indexOf(PowerPhase.SUBMITTING) < store.phases.indexOf(PowerPhase.TRACKING))
        assertTrue(store.phases.contains(PowerPhase.VERIFYING)); assertEquals("task-uuid", result.taskUuid)
    }
    @Test fun submissionTimeoutBlocksDuplicatesAndNeverReplays() = runTest {
        val store = Store(); val backend = Backend(vm(), store).apply { networkFailure = true }
        val first = service(backend, store).execute(profile, confirmation(backend.current))
        assertEquals(PowerPhase.UNKNOWN, first.phase)
        val restored = service(backend, store)
        assertEquals(PowerPhase.UNKNOWN, restored.reconcile(profile, first).phase)
        assertTrue(runCatching { restored.execute(profile, confirmation(backend.current)) }.isFailure)
        assertEquals(1, backend.sends)
    }
    @Test fun persistedTaskCanRecoverAfterProcessLikeCancellation() = runTest {
        val store = Store(); val backend = Backend(vm(), store)
        try {
            service(backend, store).execute(profile, confirmation(backend.current)) { if (it.phase == PowerPhase.TRACKING) throw CancellationException("simulated process stop") }
            fail("Cancellation expected")
        } catch (_: CancellationException) { }
        val saved = store.load().single()
        assertEquals("task-ref", saved.taskRef)
        assertEquals(PowerPhase.SUCCEEDED, service(backend, store).reconcile(profile, saved).phase)
        assertEquals(1, backend.sends)
    }
    @Test fun rebootDoesNotSucceedJustBecauseVmIsRunning() = runTest {
        val store = Store(); val backend = Backend(vm("Running"), store)
        backend.after = backend.current
        val op = service(backend, store).execute(profile, confirmation(backend.current, PowerAction.REBOOT))
        assertEquals(PowerPhase.UNKNOWN, op.phase)
        backend.after = backend.current.copy(bootTime = "20260101T10:01:00Z")
        assertEquals(PowerPhase.SUCCEEDED, service(backend, store).reconcile(profile, op).phase)
        assertEquals(1, backend.sends)
    }
    @Test fun staleOrDifferentOriginConfirmationNeverSends() = runTest {
        val store = Store(); val backend = Backend(vm(), store)
        val stale = confirmation(backend.current.copy(observedAt = -30_001))
        assertTrue(runCatching { service(backend, store).execute(profile, stale) }.isFailure)
        assertTrue(runCatching { service(backend, store).execute(profile.copy(certificateSha256 = "b".repeat(64)), confirmation(backend.current)) }.isFailure)
        assertEquals(0, backend.sends); assertTrue(store.load().isEmpty())
    }
    @Test fun changedPoolVmOrBootInvalidatesConfirmation() = runTest {
        val original = vm()
        listOf(original.copy(authority = "other"), original.copy(uuid = "other"), original.copy(bootTime = "20260102T10:00:00Z")).forEach { changed ->
            val store = Store(); val backend = Backend(changed, store)
            assertTrue(runCatching { service(backend, store).execute(profile, confirmation(original)) }.isFailure)
            assertEquals(0, backend.sends)
        }
    }
    @Test fun availabilityAndConcurrentOperationGateActions() {
        assertFalse(PowerRules.eligible(vm().copy(allowed = emptySet()), PowerAction.START))
        assertFalse(PowerRules.eligible(vm().copy(currentOperations = true), PowerAction.START))
        assertFalse(PowerRules.eligible(vm("Running", null), PowerAction.REBOOT))
        assertFalse(PowerRules.eligible(vm("Running"), PowerAction.START))
        assertFalse(PowerRules.validBoot("not-a-timestamp"))
        assertFalse(PowerRules.validBoot("19700101T00:00:00Z"))
        assertTrue(PowerRules.validBoot("20260101T10:00:00Z"))
        assertTrue(PowerRules.validBoot("2026-01-01T10:00:00Z"))
    }
    @Test fun pendingOrFailedTaskIsNeverReportedSuccessful() = runTest {
        listOf("pending", "failure", "cancelled").forEach { status ->
            val store = Store(); val backend = Backend(vm(), store).apply { this.status = status }
            val result = service(backend, store).execute(profile, confirmation(backend.current))
            assertEquals(if (status == "pending") PowerPhase.UNKNOWN else PowerPhase.FAILED, result.phase)
        }
    }
    @Test fun taskSuccessWithWrongVmStateRemainsUnverified() = runTest {
        val store = Store(); val backend = Backend(vm(), store).apply { after = current }
        assertEquals(PowerPhase.UNKNOWN, service(backend, store).execute(profile, confirmation(backend.current)).phase)
    }
    @Test fun preparedRecoveryDoesNotSendAndWrongOriginCannotObserve() = runTest {
        val store = Store(); val backend = Backend(vm(), store)
        val op = PowerOperation("id", profile.id, profile.origin(), "pool", "vm", "Guest", PowerAction.START,
            null, PowerPhase.PREPARED, 1, 1)
        store.put(op)
        assertTrue(runCatching { service(backend, store).reconcile(profile.copy(username = "other"), op) }.isFailure)
        assertEquals(PowerPhase.FAILED, service(backend, store).reconcile(profile, op).phase)
        assertEquals(0, backend.sends)
    }
    @Test fun failedWriteAheadBarrierPreventsNetworkSubmission() = runTest {
        val store = Store(); val backend = Backend(vm(), store)
        val failing = object : OperationStore {
            override suspend fun load() = store.load()
            override suspend fun put(operation: PowerOperation) {
                if (operation.phase == PowerPhase.SUBMITTING) throw IOException("storage unavailable")
                store.put(operation)
            }
        }
        val service = PowerService(backend, failing, { 1000 }, {}, 1)
        assertTrue(runCatching { service.execute(profile, confirmation(backend.current)) }.isFailure)
        assertEquals(0, backend.sends)
        assertEquals(PowerPhase.PREPARED, store.load().single().phase)
    }
    @Test fun postSubmissionJournalFailureRetainsHandleWithoutResending() = runTest {
        for (failAfterUuid in listOf(false, true)) {
            val store = Store(); val backend = Backend(vm(), store)
            var failedOnce = false
            val failing = object : OperationStore {
                override suspend fun load() = store.load()
                override suspend fun put(operation: PowerOperation) {
                    if (!failedOnce && operation.phase == PowerPhase.TRACKING &&
                        (operation.taskUuid != null) == failAfterUuid) {
                        failedOnce = true
                        throw IOException("One transient post-submit write failure")
                    }
                    store.put(operation)
                }
            }
            val service = PowerService(backend, failing, { 1000 }, {}, 1)
            val pending = service.execute(profile, confirmation(backend.current))
            assertTrue(failedOnce)
            assertEquals(PowerPhase.UNKNOWN, pending.phase)
            assertEquals("task-ref", store.load().single().taskRef)
            if (failAfterUuid) assertEquals("task-uuid", pending.taskUuid)
            assertEquals(PowerPhase.SUCCEEDED, service.reconcile(profile, pending).phase)
            assertEquals(1, backend.sends)
        }
    }
    @Test fun repeatedPostSubmitWriteFailuresKeepTicketForRecoveryInSameProcess() = runTest {
        val store = Store(); val backend = Backend(vm(), store)
        var writesAvailable = false
        val failing = object : OperationStore {
            override suspend fun load() = store.load()
            override suspend fun put(operation: PowerOperation) {
                if (operation.taskRef != null && !writesAvailable) throw IOException("disk unavailable twice")
                store.put(operation)
            }
        }
        val service = PowerService(backend, failing, { 1000 }, {}, 1)
        assertTrue(runCatching { service.execute(profile, confirmation(backend.current)) }.isFailure)
        val diskRecord = store.load().single()
        assertEquals(PowerPhase.SUBMITTING, diskRecord.phase); assertNull(diskRecord.taskRef)
        writesAvailable = true
        assertEquals(PowerPhase.SUCCEEDED, service.reconcile(profile, diskRecord).phase)
        assertEquals("task-ref", store.load().single().taskRef)
        assertEquals(1, backend.sends)
    }
}
