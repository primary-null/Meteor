package com.meteor.app.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

/**
 * Journaled operation hardening: a VM deleted elsewhere, records this build cannot interpret, failures
 * before the write call, and per-kind tracking budgets with bounded tolerance for transient failures.
 */
class OperationHardeningTest {
    private val profile = Profile("p", "Lab", "https://test.invalid/", "tester", "secret", "a".repeat(64))
    private fun vm(power: String = "Running", boot: String? = "20260101T10:00:00Z") = VmDetails("pool", "vm", "Guest", "", power, null, 1, 1024,
        emptyList(), emptyList(), false, null, emptyList(), boot,
        setOf("start", "clean_shutdown", "clean_reboot", "pause", "suspend", "pool_migrate"), false, 1000, residentHostUuid = "host-a")

    /** Every write is encoded and every read decoded, as OperationJournal does. */
    private class Store(initial: List<Map<String, Any?>> = emptyList()) : OperationStore {
        val rows = linkedMapOf<String, Map<String, Any?>>().apply { initial.forEach { put(it["id"] as String, it) } }
        val written = mutableListOf<PowerOperation>()
        override suspend fun load() = rows.values.map(JournalCodec::decode)
        override suspend fun put(operation: PowerOperation) { written += operation; rows[operation.id] = JournalCodec.encode(operation) }
    }

    /** [state] is what the server returns; null means the VM is gone (UUID_INVALID after the pool check). */
    private class Backend(var state: VmDetails?) : PowerBackend, PowerConnection {
        var pool = "pool"
        var sends = 0; var detailReads = 0; var stateReads = 0; var poolChecks = 0; var taskLookups = 0; var observations = 0
        var stateFailure: Exception? = null
        var submitFailure: Exception? = null
        var after: VmDetails? = null
        /** Scripted task observations; the last one repeats. A thrown entry is an observation failure. */
        var tasks: List<Any> = listOf(TaskObservation("success", 1.0))
        override suspend fun <T> powerConnection(profile: Profile, action: suspend PowerConnection.() -> T): T = action(this)
        override suspend fun details(authority: String, uuid: String): VmDetails {
            detailReads++
            require(authority == pool) { "Pool identity changed." }
            return state ?: throw RpcFailure("UUID_INVALID")
        }
        override suspend fun vmState(authority: String, uuid: String): VmDetails? {
            stateReads++
            require(authority == pool) { "Pool identity changed." }
            stateFailure?.let { throw it }
            return if (sends > 0 && after != null) after else state
        }
        override suspend fun verifyPoolIdentity(authority: String) { poolChecks++; require(authority == pool) { "Pool identity changed." } }
        override suspend fun submit(authority: String, uuid: String, action: PowerAction): String {
            sends++
            submitFailure?.let { throw it }
            return "task-ref"
        }
        override suspend fun submitOperation(authority: String, uuid: String, action: PowerAction, targetHost: String?) = submit(authority, uuid, action)
        override suspend fun host(authority: String, uuid: String) = HostChoice(uuid, "Host B", enabled = true, live = true)
        override suspend fun taskUuid(reference: String) = "task-uuid"
        override suspend fun taskReference(uuid: String): String { taskLookups++; return "task-ref" }
        override suspend fun observeTask(reference: String): TaskObservation {
            val next = tasks[minOf(observations++, tasks.lastIndex)]
            if (next is Exception) throw next
            return next as TaskObservation
        }
    }
    private fun pending(phase: PowerPhase = PowerPhase.UNKNOWN) = PowerOperation("op", profile.id, profile.origin(), "pool", "vm", "Guest",
        PowerAction.SHUTDOWN, null, phase, 100, 100, "task-ref", "task-uuid")
    private fun storeWith(operation: PowerOperation) = Store(listOf(JournalCodec.encode(operation)))
    private fun fixed(backend: Backend, store: Store, now: () -> Long = { 1000 }) = PowerService(backend, store, now, {}, 2)
    private fun confirmation(action: PowerAction, current: VmDetails = vm(), host: HostChoice? = null) =
        PowerConfirmation(profile.id, profile.origin(), current, action, host, current.name)

    // --- 1. The VM was deleted on the server ---

    @Test fun deletedVmIsClosedOnlyByManualReviewAsAnUnknownOutcome() = runTest {
        val store = storeWith(pending()); val backend = Backend(null); val service = fixed(backend, store)
        val checked = service.reconcile(profile, store.load().single())
        assertTrue(checked.unresolved); assertEquals(PowerPhase.UNKNOWN, checked.phase)
        assertTrue(checked.message, checked.message.contains("no longer exists"))
        assertEquals(0, backend.taskLookups) // nothing is observed for a VM that cannot be verified

        val review = service.review(profile, store.load().single())
        assertEquals(ReviewSubject.VM_MISSING, review.subject); assertNull(review.details)
        assertEquals("The VM no longer exists on this pool", ReviewText.currentState(review))
        assertTrue(runCatching { service.resolveAfterReview(profile, review, false) }.isFailure)
        assertTrue(store.load().single().unresolved)

        val closed = service.resolveAfterReview(profile, review, true)
        assertFalse(closed.unresolved); assertEquals(PowerPhase.UNKNOWN, closed.phase); assertNotEquals(PowerPhase.SUCCEEDED, closed.phase)
        assertTrue(closed.message, closed.message.contains("no longer exists") && closed.message.contains("remains unverified"))
        assertEquals("VM no longer exists", closed.manualResolution?.power)
        assertEquals("Closed · Outcome unverified", OperationHistory.status(closed))
        val reloaded = store.load().single()
        assertFalse(reloaded.unresolved); assertEquals("task-uuid", reloaded.taskUuid); assertEquals(PowerAction.SHUTDOWN, reloaded.action)
        assertEquals(0, backend.sends)
        // The profile can be forgotten again.
        val document = ProfileDocument(listOf(profile), profile.id)
        assertTrue(ConnectionProfiles.forget(document, profile.id, store.load()).profiles.isEmpty())
    }

    @Test fun missingVmReviewStillRequiresTheOriginalPoolAndAStableAnswer() = runTest {
        val store = storeWith(pending()); val backend = Backend(null).apply { pool = "other-pool" }
        val service = fixed(backend, store)
        assertTrue(runCatching { service.review(profile, store.load().single()) }.isFailure)
        assertEquals(PowerPhase.UNKNOWN, service.reconcile(profile, store.load().single()).phase)
        assertTrue(store.load().single().message.contains("Could not reconcile securely"))
        backend.pool = "pool"
        val review = service.review(profile, store.load().single())
        backend.state = vm() // the VM is visible again: the missing-VM review no longer holds
        assertTrue(runCatching { service.resolveAfterReview(profile, review, true) }.isFailure)
        // A normal VM review cannot be closed by a later "missing" answer either.
        val present = service.review(profile, store.load().single())
        assertEquals(ReviewSubject.VM, present.subject)
        backend.state = null
        assertTrue(runCatching { service.resolveAfterReview(profile, present, true) }.isFailure)
        assertTrue(store.load().single().unresolved); assertEquals(0, backend.sends)
    }

    @Test fun otherReadFailuresKeepTodaysLockedBehaviour() = runTest {
        val store = storeWith(pending()); val backend = Backend(vm()).apply { stateFailure = RpcFailure("RBAC_PERMISSION_DENIED") }
        val service = fixed(backend, store)
        assertTrue(runCatching { service.review(profile, store.load().single()) }.isFailure)
        assertTrue(service.reconcile(profile, store.load().single()).message.contains("Could not reconcile securely"))
        backend.stateFailure = IOException("offline")
        assertTrue(runCatching { service.review(profile, store.load().single()) }.isFailure)
        assertTrue(store.load().single().unresolved); assertEquals(0, backend.sends)
    }

    @Test fun vmDeletedWhileTrackingIsUncertainAndNeverSuccess() = runTest {
        val store = Store(); val backend = Backend(vm("Halted")); val service = fixed(backend, store)
        val result = service.execute(profile, confirmation(PowerAction.START, vm("Halted"))) {
            if (it.phase == PowerPhase.TRACKING) backend.state = null // deleted from another tool while the task runs
        }
        assertEquals(PowerPhase.UNKNOWN, result.phase); assertTrue(result.unresolved)
        assertTrue(result.message.contains("no longer exists")); assertEquals(1, backend.sends)
    }

    // --- 2. Records this build cannot interpret ---

    private fun foreignHostRecord(extra: Map<String, Any?> = emptyMap()) = linkedMapOf<String, Any?>("id" to "future", "profile" to profile.id,
        "origin" to profile.origin(), "authority" to "pool", "vm" to "", "name" to "Host C", "action" to "HOST_FIRMWARE_UPDATE",
        "phase" to "TRACKING", "createdAt" to 100, "updatedAt" to 100, "taskRef" to "OpaqueRef:t", "taskUuid" to "t-uuid",
        "message" to "Updating firmware", "target" to "host-c", "firmware" to mapOf("version" to "2.1")) + extra

    @Test fun foreignRecordWithoutAVmStaysLockedButCanBeClosedAfterAPoolOnlyReview() = runTest {
        val raw = foreignHostRecord()
        val store = Store(listOf(raw)); val backend = Backend(vm()); val service = fixed(backend, store)
        val locked = store.load().single()
        assertTrue(locked.unresolved); assertNotNull(locked.foreign)
        assertTrue(runCatching { service.execute(profile, confirmation(PowerAction.SHUTDOWN)) }.isFailure)
        assertTrue(runCatching { service.reconcile(profile, locked) }.exceptionOrNull() is IllegalArgumentException)
        assertEquals(raw, store.rows.getValue("future")) // never guessed at, never rewritten

        val review = service.review(profile, locked)
        assertEquals(ReviewSubject.POOL_ONLY, review.subject); assertNull(review.details)
        assertEquals(1, backend.poolChecks); assertEquals(0, backend.detailReads + backend.stateReads)
        assertEquals("HOST_FIRMWARE_UPDATE (not recognized by this version)", ReviewText.originalAction(review.operation))
        assertTrue(ReviewText.explanation(review).joinToString().contains("only that this connection reaches the original pool"))

        val closed = service.resolveAfterReview(profile, review, true)
        assertFalse(closed.unresolved); assertEquals(PowerPhase.UNKNOWN, closed.phase)
        assertTrue(closed.message, closed.message.contains("unknown outcome"))
        assertEquals(2, backend.poolChecks); assertEquals(0, backend.sends + backend.taskLookups + backend.observations)
        val written = store.rows.getValue("future")
        for (key in raw.keys - setOf("phase", "updatedAt", "message")) assertEquals(key, raw[key], written[key])
        assertEquals("UNKNOWN", written["phase"]); assertTrue(written.containsKey("manualResolution"))
        assertFalse(store.load().single().unresolved)
    }

    @Test fun poolOnlyReviewRefusesAnotherPoolOrAMissingAuthority() = runTest {
        for (raw in listOf(foreignHostRecord(), foreignHostRecord(mapOf("authority" to "")))) {
            val store = Store(listOf(raw)); val backend = Backend(vm()).apply { if (raw["authority"] == "pool") pool = "other-pool" }
            val service = fixed(backend, store)
            assertTrue(runCatching { service.review(profile, store.load().single()) }.isFailure)
            assertTrue(store.load().single().unresolved)
        }
        // A stale pool-only review cannot close the record.
        var now = 1000L
        val store = Store(listOf(foreignHostRecord())); val backend = Backend(vm()); val service = fixed(backend, store) { now }
        val review = service.review(profile, store.load().single())
        now = 31_001
        assertTrue(runCatching { service.resolveAfterReview(profile, review, true) }.isFailure)
        assertTrue(store.load().single().unresolved)
    }

    @Test fun foreignClosurePersistsEvenWhenTheRawRecordHasAnUnacceptedResolution() = runTest {
        val raw = foreignHostRecord(mapOf("manualResolution" to mapOf("reviewedAt" to 5, "origin" to "not-a-digest")))
        val store = Store(listOf(raw)); val service = fixed(Backend(vm()), store)
        assertTrue(store.load().single().unresolved)
        service.resolveAfterReview(profile, service.review(profile, store.load().single()), true)
        assertFalse(store.load().single().unresolved)
        // A record already closed by an accepted resolution is still written back verbatim.
        val closedRaw = store.rows.getValue("future")
        assertEquals(closedRaw, JournalCodec.encode(JournalCodec.decode(closedRaw)))
    }

    @Test fun foreignRecordNamingAVmUsesTheVmReview() = runTest {
        val raw = foreignHostRecord(mapOf("vm" to "vm", "action" to "VM_CLONE"))
        val store = Store(listOf(raw)); val backend = Backend(vm()); val service = fixed(backend, store)
        assertEquals(ReviewSubject.VM, service.review(profile, store.load().single()).subject)
        backend.state = null
        val review = service.review(profile, store.load().single())
        assertEquals(ReviewSubject.VM_MISSING, review.subject)
        assertFalse(service.resolveAfterReview(profile, review, true).unresolved)
        assertEquals(0, backend.poolChecks + backend.sends)
    }

    // --- 3. Failures that provably happen before the write call ---

    @Test fun notSentIsAClosedFailureAndReleasesTheFence() = runTest {
        val store = Store(); val backend = Backend(vm()).apply { submitFailure = NotSent("Could not read the VM again just before sending (network error).") }
        val service = fixed(backend, store)
        val result = service.execute(profile, confirmation(PowerAction.SHUTDOWN))
        assertEquals(PowerPhase.FAILED, result.phase); assertFalse(result.unresolved)
        assertEquals("Not sent: Could not read the VM again just before sending (network error).", result.message)
        assertEquals(listOf(PowerPhase.PREPARED, PowerPhase.SUBMITTING, PowerPhase.FAILED), store.written.map { it.phase })
        // Nothing is pending, so the same action can be confirmed and sent again.
        backend.submitFailure = null; backend.after = vm("Halted")
        assertEquals(PowerPhase.SUCCEEDED, service.execute(profile, confirmation(PowerAction.SHUTDOWN)).phase)
        assertEquals(2, backend.sends)
    }

    @Test fun anythingAfterTheWriteMayHaveLeftStaysUncertain() = runTest {
        for (failure in listOf(IOException("reset"), GatewayError("http-error"), RpcFailure("RPC_FAULT", explicitRejection = false))) {
            val store = Store(); val backend = Backend(vm()).apply { submitFailure = failure }
            val result = fixed(backend, store).execute(profile, confirmation(PowerAction.SHUTDOWN))
            assertEquals("$failure", PowerPhase.UNKNOWN, result.phase); assertTrue(result.unresolved)
        }
    }

    // --- 4. Tracking budgets, transient failures and progress ---

    @Test fun budgetsAreShortForInstantKindsAndGenerousForSlowOnes() {
        val short = TrackingPolicy.SHORT_MILLIS
        for (action in listOf(PowerAction.PAUSE, PowerAction.UNPAUSE)) assertEquals(short, TrackingPolicy.budgetMillis(action))
        for (action in listOf(TargetAction.HOST_DISABLE, TargetAction.HOST_ENABLE, TargetAction.CD_INSERT, TargetAction.CD_EJECT))
            assertEquals(short, TrackingPolicy.budgetMillis(action))
        for (action in listOf(PowerAction.SHUTDOWN, PowerAction.REBOOT, PowerAction.SUSPEND, PowerAction.POOL_MIGRATE))
            assertTrue("$action", TrackingPolicy.budgetMillis(action) >= 20 * 60_000L)
        for (action in listOf(TargetAction.HOST_REBOOT, TargetAction.HOST_SHUTDOWN, TargetAction.HOST_EVACUATE))
            assertTrue("$action", TrackingPolicy.budgetMillis(action) >= 20 * 60_000L)
        assertTrue(TrackingPolicy.budgetMillis(TargetAction.HOST_EVACUATE) > TrackingPolicy.budgetMillis(PowerAction.POOL_MIGRATE))
        for (action in PowerAction.entries) assertTrue(TrackingPolicy.budgetMillis(action) >= short)
        // The record's own kind decides: a storage/host record uses its target kind.
        val host = PowerOperation("h", "p", "o", "pool", "", "Host", PowerAction.TARGET, null, PowerPhase.TRACKING, 1, 1,
            targetAction = TargetAction.HOST_REBOOT, targetUuid = "host-b")
        assertEquals(TrackingPolicy.budgetMillis(TargetAction.HOST_REBOOT), TrackingPolicy.budgetMillis(host))
    }

    private fun timed(backend: Backend, store: Store, waits: MutableList<Long>) = PowerService(backend, store, { 1000 }, { waits += it })

    @Test fun pendingTaskIsObservedForTheKindsWholeBudgetThenStaysUncertain() = runTest {
        for (action in listOf(PowerAction.PAUSE, PowerAction.SHUTDOWN, PowerAction.POOL_MIGRATE)) {
            val store = Store(); val backend = Backend(vm()).apply { tasks = listOf(TaskObservation("pending", 0.1)) }
            val waits = mutableListOf<Long>()
            val host = if (action == PowerAction.POOL_MIGRATE) HostChoice("host-b", "Host B", enabled = true, live = true) else null
            val result = timed(backend, store, waits).execute(profile, confirmation(action, host = host))
            assertEquals(PowerPhase.UNKNOWN, result.phase); assertTrue(result.unresolved); assertEquals(1, backend.sends)
            val budget = TrackingPolicy.budgetMillis(action)
            assertTrue("$action ${waits.sum()}", waits.sum() in (budget - TrackingPolicy.SLOW_INTERVAL_MILLIS)..budget)
            assertEquals(waits.size + 1, backend.observations)
        }
        // The old fixed two minutes now applies only to instant kinds.
        assertTrue(TrackingPolicy.budgetMillis(PowerAction.POOL_MIGRATE) / TrackingPolicy.FAST_INTERVAL_MILLIS > 80)
    }

    @Test fun aBoundedRunOfTransientFailuresIsToleratedAndOnlyTheReadBackProvesSuccess() = runTest {
        val store = Store(); val backend = Backend(vm())
        backend.after = vm("Halted")
        backend.tasks = List(TrackingPolicy.TRANSIENT_FAILURES) { IOException("unreachable") } +
            listOf(TaskObservation("pending", 0.5), GatewayError("http-error"), TaskObservation("success", 1.0))
        val result = timed(backend, store, mutableListOf()).execute(profile, confirmation(PowerAction.SHUTDOWN))
        assertEquals(result.message, PowerPhase.SUCCEEDED, result.phase); assertEquals(1, backend.sends)
        assertEquals(1, backend.detailReads) // the confirmation read; tracking uses the light state read
        assertTrue(backend.stateReads >= 1)
    }

    @Test fun tooManyTransientFailuresOrAnyExplicitErrorEndsTrackingUncertain() = runTest {
        val tolerance = TrackingPolicy.TRANSIENT_FAILURES
        val cases = listOf<Pair<Exception, Int>>(IOException("down") to tolerance + 1, RpcFailure("RPC_FAULT", explicitRejection = false) to tolerance + 1,
            RpcFailure("HANDLE_INVALID") to 1, IllegalArgumentException("Pool identity changed.") to 1)
        for ((failure, observations) in cases) {
            val store = Store(); val backend = Backend(vm()).apply { tasks = listOf(failure) }
            val result = timed(backend, store, mutableListOf()).execute(profile, confirmation(PowerAction.SHUTDOWN))
            assertEquals("$failure", PowerPhase.UNKNOWN, result.phase); assertTrue(result.unresolved)
            assertEquals("$failure", observations, backend.observations); assertEquals(1, backend.sends)
        }
    }

    @Test fun taskSuccessWithoutTheProvenStateStopsAfterTheVerificationWindow() = runTest {
        val store = Store(); val backend = Backend(vm()).apply { after = vm() } // still Running after a "successful" shutdown
        val waits = mutableListOf<Long>()
        val result = timed(backend, store, waits).execute(profile, confirmation(PowerAction.SHUTDOWN))
        assertEquals(PowerPhase.UNKNOWN, result.phase); assertTrue(result.unresolved)
        assertTrue(waits.sum() <= TrackingPolicy.VERIFY_MILLIS + TrackingPolicy.SLOW_INTERVAL_MILLIS)
        assertTrue(waits.sum() < TrackingPolicy.budgetMillis(PowerAction.SHUTDOWN))
    }

    @Test fun longTrackingIsCancellableAndResumesByTaskUuidAfterRestart() = runTest {
        val store = Store(); val backend = Backend(vm()).apply { tasks = listOf(TaskObservation("pending", 0.2)); after = vm("Halted") }
        var waited = 0
        val cancelling = PowerService(backend, store, { 1000 }, { if (++waited == 3) throw CancellationException("app stopped") })
        assertTrue(runCatching { cancelling.execute(profile, confirmation(PowerAction.SHUTDOWN)) }.exceptionOrNull() is CancellationException)
        val saved = store.load().single()
        assertEquals(PowerPhase.TRACKING, saved.phase); assertEquals("task-uuid", saved.taskUuid); assertNull(saved.progress)
        backend.tasks = listOf(TaskObservation("success", 1.0))
        val resumed = PowerService(backend, store, { 1000 }, {}).reconcile(profile, saved)
        assertEquals(PowerPhase.SUCCEEDED, resumed.phase); assertEquals(1, backend.taskLookups); assertEquals(1, backend.sends)
    }

    @Test fun progressIsPublishedWhileTrackingButNeverJournaled() = runTest {
        val store = Store(); val backend = Backend(vm()).apply {
            after = vm("Halted")
            tasks = listOf(TaskObservation("pending", 0.25), TaskObservation("pending", 0.251), TaskObservation("pending", 0.5),
                TaskObservation("pending", Double.NaN), TaskObservation("success", 1.0))
        }
        val published = mutableListOf<PowerOperation>()
        val result = timed(backend, store, mutableListOf()).execute(profile, confirmation(PowerAction.SHUTDOWN)) { published += it }
        assertEquals(PowerPhase.SUCCEEDED, result.phase)
        assertEquals(listOf(0.25, 0.5), published.mapNotNull { it.progress })
        assertTrue(store.written.all { it.progress == null })
        assertTrue(store.rows.values.none { it.containsKey("progress") })
        assertNull(TrackingPolicy.shownProgress(1.5)); assertNull(TrackingPolicy.shownProgress(null)); assertEquals(0.0, TrackingPolicy.shownProgress(0.0)!!, 0.0)
    }
}
