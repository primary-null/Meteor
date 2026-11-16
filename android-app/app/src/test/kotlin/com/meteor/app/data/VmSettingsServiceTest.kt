package com.meteor.app.data

import com.meteor.app.data.VmSettingsFixtures.GIB
import com.meteor.app.data.VmSettingsFixtures.NOW
import com.meteor.app.data.VmSettingsFixtures.applied
import com.meteor.app.data.VmSettingsFixtures.confirm
import com.meteor.app.data.VmSettingsFixtures.draft
import com.meteor.app.data.VmSettingsFixtures.halted
import com.meteor.app.data.VmSettingsFixtures.profile
import com.meteor.app.data.VmSettingsFixtures.running
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

/**
 * The synchronous journaled write through PowerService: write-ahead record per setter, proof by read-back, every
 * outcome of a reply (clean, refused, ambiguous, lost), cancellation, a multi-field edit failing midway, and Check status
 * after a stop, never resending anything.
 */
class VmSettingsServiceTest {
    private class Store : OperationStore {
        val records = linkedMapOf<String, PowerOperation>()
        override suspend fun load() = records.values.map { JournalCodec.decode(JournalCodec.encode(it)) }
        override suspend fun put(operation: PowerOperation) { records[operation.id] = operation }
        val last get() = records.values.last()
    }

    /** Server model. [answer] decides what each send does; by default the setter applies and replies cleanly. */
    private class Backend(val store: Store, var state: VmSettings) : PowerBackend, PowerConnection {
        val sent = mutableListOf<SettingKind>()
        var reads = 0
        var readFailure: Exception? = null
        var missing = false
        /** Returns the exception to throw after optionally applying; null for a clean reply. */
        var answer: (SettingStep, Int) -> Pair<Boolean, Exception?> = { _, _ -> true to null }
        /** Applied after each send, for example a concurrent change by someone else. */
        var afterSend: (VmSettings) -> VmSettings = { it }
        override suspend fun <T> powerConnection(profile: Profile, action: suspend PowerConnection.() -> T): T = action(this)
        override suspend fun details(authority: String, uuid: String): VmDetails =
            VmDetails(authority, uuid, state.name, state.description, state.power, null, 1, 1, emptyList(), emptyList(), false, null, emptyList(),
                null, emptySet(), state.busy, NOW)
        override suspend fun submit(authority: String, uuid: String, action: PowerAction): String = error("VM power sends must not be used")
        override suspend fun taskUuid(reference: String): String = error("no task")
        override suspend fun taskReference(uuid: String): String = error("no task")
        override suspend fun observeTask(reference: String): TaskObservation = error("no task")
        override suspend fun settings(authority: String, uuid: String): VmSettings? {
            reads++
            readFailure?.let { throw it }
            return if (missing) null else state
        }
        override suspend fun submitSetting(authority: String, uuid: String, step: SettingStep) {
            val stored = store.last.settings!!
            val index = stored.indexOfFirst { it.state == SettingState.SUBMITTING }
            // Write-ahead barrier: this exact step is journaled SUBMITTING, with its send time, before the call.
            assertEquals(step, stored[index]); assertNotNull(stored[index].sentAt); assertEquals(PowerPhase.SUBMITTING, store.last.phase)
            sent += step.kind
            val (apply, error) = answer(step, sent.size)
            if (apply) state = state.applied(step)
            state = afterSend(state)
            error?.let { throw it }
        }
    }

    private var now = NOW
    private fun setup(s: VmSettings = running()): Triple<Store, Backend, PowerService> {
        val store = Store(); val backend = Backend(store, s)
        return Triple(store, backend, PowerService(backend, store, { now }, {}, 2))
    }

    @Test fun aCleanMultiFieldEditIsSentInOrderAndProvenStepByStep() = runTest {
        val (store, backend, service) = setup(halted())
        val d = draft(halted()).copy(name = "Web", vcpusMax = "8", vcpusAtStartup = "6", memoryDynamicMax = "2048", autoPowerOn = true)
        val result = service.executeSettings(profile, confirm(halted(), d))
        assertEquals(PowerPhase.SUCCEEDED, result.phase)
        assertEquals(listOf(SettingKind.NAME, SettingKind.VCPUS_MAX, SettingKind.VCPUS_AT_STARTUP, SettingKind.MEMORY_LIMITS, SettingKind.AUTO_POWERON_ADD), backend.sent)
        assertTrue(result.settings!!.all { it.state == SettingState.DONE && it.observed == it.requested })
        assertTrue(result.message, result.message.startsWith("Saved and verified."))
        assertEquals(PowerAction.SETTINGS, result.action); assertNull(result.taskRef); assertEquals("vm", result.vmUuid); assertEquals("pool", result.authority)
        assertEquals(profile.origin(), result.origin)
        assertEquals("Web", backend.state.name); assertEquals(6, backend.state.vcpusAtStartup); assertEquals(2 * GIB, backend.state.memoryDynamicMax)
        assertFalse(store.last.unresolved)
    }

    @Test fun oneUnresolvedOperationBlocksTheNextWrite() = runTest {
        val (store, backend, service) = setup()
        backend.answer = { _, _ -> false to IOException("reset") }
        assertEquals(PowerPhase.UNKNOWN, service.executeSettings(profile, confirm(running(), draft(running()).copy(name = "x"))).phase)
        assertTrue(runCatching { service.executeSettings(profile, confirm(running(), draft(running()).copy(name = "y"))) }.exceptionOrNull() is IllegalArgumentException)
        assertEquals(1, backend.sent.size)
        assertEquals(1, store.records.size)
    }

    @Test fun aSuccessfulReplyWithTheOldValueReadBackStaysUncertain() = runTest {
        val (_, backend, service) = setup()
        backend.answer = { _, _ -> false to null } // the server says OK, yet nothing changes
        val result = service.executeSettings(profile, confirm(running(), draft(running()).copy(name = "Web", description = "x")))
        assertEquals(PowerPhase.UNKNOWN, result.phase)
        assertEquals(SettingState.UNKNOWN, result.settings!![0].state); assertEquals("Guest", result.settings!![0].observed)
        assertEquals(SettingState.PENDING, result.settings!![1].state)
        assertEquals(listOf(SettingKind.NAME), backend.sent)
    }

    @Test fun aThirdValueAfterACleanReplyIsReportedAsChangedElsewhereNotAsSuccess() = runTest {
        val (_, backend, service) = setup()
        backend.afterSend = { it.copy(name = "Someone else") }
        val result = service.executeSettings(profile, confirm(running(), draft(running()).copy(name = "Web", description = "x")))
        assertEquals(PowerPhase.FAILED, result.phase)
        assertEquals(SettingState.OVERRIDDEN, result.settings!![0].state)
        assertTrue(result.message, result.message.contains("now reads “Someone else”") && result.message.contains("Not sent: Description"))
        assertEquals(1, backend.sent.size)
    }

    @Test fun aLostReplyIsNeverResentAndStaysUncertainUntilProven() = runTest {
        val (store, backend, service) = setup()
        backend.answer = { _, _ -> false to IOException("connection reset") }
        val result = service.executeSettings(profile, confirm(running(), draft(running()).copy(memoryDynamicMax = "2048")))
        assertEquals(PowerPhase.UNKNOWN, result.phase)
        assertEquals(SettingState.UNKNOWN, result.settings!!.single().state)
        assertTrue(result.message, result.message.contains("The reply was lost"))
        // Check status at once: the old value cannot prove anything yet.
        now += 60_000
        var checked = service.reconcile(profile, store.last)
        assertEquals(PowerPhase.UNKNOWN, checked.phase); assertTrue(checked.message, checked.message.contains("Check status again then"))
        // After the quiet period the old value proves the call was not applied. Still nothing was resent.
        now += SettingsRules.QUIET_MILLIS
        checked = service.reconcile(profile, store.last)
        assertEquals(PowerPhase.FAILED, checked.phase); assertEquals(SettingState.NOT_APPLIED, checked.settings!!.single().state)
        assertEquals(1, backend.sent.size)
    }

    @Test fun aLostReplyWhoseValueReadsAsRequestedIsProvenByTheReadBack() = runTest {
        val (_, backend, service) = setup()
        backend.answer = { _, _ -> true to IOException("read timed out") }
        val result = service.executeSettings(profile, confirm(running(), draft(running()).copy(name = "Web", description = "x")))
        // Proven, but the run still stops after an unclean reply: the description is not sent automatically.
        assertEquals(SettingState.DONE, result.settings!![0].state); assertEquals(SettingState.PENDING, result.settings!![1].state)
        assertEquals(PowerPhase.FAILED, result.phase)
        assertTrue(result.message, result.message.contains("Edit again to send what was not sent."))
        assertEquals(listOf(SettingKind.NAME), backend.sent)
    }

    @Test fun anExplicitRejectionClosesTheRecordWithItsCode() = runTest {
        val (_, backend, service) = setup()
        backend.answer = { _, _ -> false to RpcFailure("MEMORY_CONSTRAINT_VIOLATION_ORDER") }
        val result = service.executeSettings(profile, confirm(running(), draft(running()).copy(memoryDynamicMin = "768"), typed = "Guest"))
        assertEquals(PowerPhase.FAILED, result.phase)
        assertEquals(SettingState.REJECTED, result.settings!!.single().state); assertEquals("MEMORY_CONSTRAINT_VIOLATION_ORDER", result.settings!!.single().code)
        assertTrue(result.message.contains("Refused by the server"))
    }

    @Test fun aRejectionContradictedByTheReadBackStaysUncertain() = runTest {
        val (_, backend, service) = setup()
        backend.answer = { _, _ -> true to RpcFailure("INVALID_VALUE") }
        val result = service.executeSettings(profile, confirm(running(), draft(running()).copy(vcpusLive = "3")))
        assertEquals(PowerPhase.UNKNOWN, result.phase); assertEquals(SettingState.UNKNOWN, result.settings!!.single().state)
    }

    @Test fun anAmbiguousErrorIsDecidedOnlyByTheReadBack() = runTest {
        // INTERNAL_ERROR (the lab's answer to a malformed number) with the old value: the call is over, so not applied.
        var (_, backend, service) = setup()
        backend.answer = { _, _ -> false to RpcFailure("INTERNAL_ERROR") }
        var result = service.executeSettings(profile, confirm(running(), draft(running()).copy(vcpusLive = "3")))
        assertEquals(PowerPhase.FAILED, result.phase); assertEquals(SettingState.NOT_APPLIED, result.settings!!.single().state)
        // With the requested value: proven.
        setup().let { (_, b, s) -> backend = b; service = s }
        backend.answer = { _, _ -> true to RpcFailure("INTERNAL_ERROR") }
        result = service.executeSettings(profile, confirm(running(), draft(running()).copy(vcpusLive = "3")))
        assertEquals(PowerPhase.SUCCEEDED, result.phase)
        // A partial or third value: uncertain, kept for review.
        setup().let { (_, b, s) -> backend = b; service = s }
        backend.answer = { _, _ -> false to RpcFailure("INTERNAL_ERROR") }
        backend.afterSend = { it.copy(memoryDynamicMin = 900L * VmSettingsFixtures.MIB) }
        result = service.executeSettings(profile, confirm(running(), draft(running()).copy(memoryDynamicMin = "768"), typed = "Guest"))
        assertEquals(PowerPhase.UNKNOWN, result.phase); assertEquals(SettingState.UNKNOWN, result.settings!!.single().state)
        // An unreadable answer after an ambiguous error: uncertain.
        setup().let { (_, b, s) -> backend = b; service = s }
        backend.answer = { _, _ -> backend.readFailure = IOException("down"); false to RpcFailure("INTERNAL_ERROR") }
        assertEquals(PowerPhase.UNKNOWN, service.executeSettings(profile, confirm(running(), draft(running()).copy(name = "x"))).phase)
    }

    @Test fun aMultiFieldEditFailingMidwayListsWhatWasSavedRefusedAndNotSent() = runTest {
        val (_, backend, service) = setup()
        backend.answer = { _, n -> if (n == 2) false to RpcFailure("INVALID_VALUE") else true to null }
        val result = service.executeSettings(profile, confirm(running(), draft(running()).copy(name = "Web", vcpusLive = "3", bootOrder = "dc")))
        assertEquals(listOf(SettingKind.NAME, SettingKind.VCPUS_LIVE), backend.sent)
        assertEquals(listOf(SettingState.DONE, SettingState.REJECTED, SettingState.PENDING), result.settings!!.map { it.state })
        assertEquals(PowerPhase.FAILED, result.phase)
        assertEquals("Partly saved", OperationHistory.status(result))
        assertTrue(result.message, result.message.contains("Saved: Name") && result.message.contains("Refused by the server: vCPUs") &&
            result.message.contains("Not sent: Boot order"))
    }

    @Test fun aFieldChangedOnTheServerBeforeItsTurnIsNotSent() = runTest {
        val (_, backend, service) = setup()
        backend.afterSend = { it.copy(description = "edited elsewhere") } // after the first send
        val result = service.executeSettings(profile, confirm(running(), draft(running()).copy(name = "Web", description = "mine")))
        assertEquals(listOf(SettingKind.NAME), backend.sent)
        assertEquals(PowerPhase.FAILED, result.phase)
        assertTrue(result.message, result.message.startsWith("Description changed on the server before it was sent."))
    }

    @Test fun aChangedVmOrAnExpiredConfirmationIsRefusedBeforeAnythingIsJournaled() = runTest {
        val (store, backend, service) = setup()
        backend.state = running().copy(name = "Renamed elsewhere")
        assertTrue(runCatching { service.executeSettings(profile, confirm(running(), draft(running()).copy(description = "x"))) }.isFailure)
        backend.state = running(); now = NOW + 31_000
        assertTrue(runCatching { service.executeSettings(profile, confirm(running(), draft(running()).copy(description = "x"))) }.isFailure)
        assertTrue(store.records.isEmpty()); assertTrue(backend.sent.isEmpty())
    }

    @Test fun cancellationBeforeTheSendSendsNothingAndSaysSo() = runTest {
        // The gateway refuses a write for a caller that is already cancelled (NotSent, SessionReuse); nothing reached the server.
        val (store, backend, service) = setup()
        backend.answer = { _, _ -> throw NotSent("The request was cancelled before sending.") }
        val result = service.executeSettings(profile, confirm(running(), draft(running()).copy(name = "x")))
        assertEquals(PowerPhase.FAILED, result.phase); assertEquals(SettingState.PENDING, result.settings!!.single().state)
        assertNull(result.settings!!.single().sentAt)
        assertTrue(result.message, result.message.startsWith("Not sent: The request was cancelled before sending.") && result.message.contains("Nothing was sent."))
        assertFalse(store.last.unresolved)
        // A run cancelled between steps journals what was sent and sends nothing more.
        val (store2, backend2, service2) = setup()
        val job = Job()
        backend2.answer = { _, n -> if (n == 1) job.cancel(); true to null }
        val error = runCatching { withContext(job) { service2.executeSettings(profile, confirm(running(), draft(running()).copy(name = "a", description = "b"))) } }.exceptionOrNull()
        assertTrue("$error", error is CancellationException)
        assertEquals(listOf(SettingKind.NAME), backend2.sent)
        val saved = store2.last
        assertEquals(listOf(SettingState.DONE, SettingState.PENDING), saved.settings!!.map { it.state })
        assertEquals(PowerPhase.FAILED, saved.phase); assertTrue(saved.message, saved.message.startsWith("Stopped before sending."))
    }

    @Test fun stopAndResumeAfterARestartReconcilesBySavedSendTimeWithoutResending() = runTest {
        // The app stopped right after journaling SUBMITTING (the send may or may not have happened).
        val (store, backend, service) = setup()
        val step = SettingsRules.plan(running(), draft(running()).copy(name = "Web")).single()
        val record = PowerOperation("op", profile.id, profile.origin(), "pool", "vm", "Guest", PowerAction.SETTINGS, null, PowerPhase.SUBMITTING,
            NOW, NOW, message = "Sending once", settings = listOf(step.copy(state = SettingState.SUBMITTING, sentAt = NOW)))
        store.put(record)
        // Recovery after a restart: the requested value proves it applied.
        backend.state = running().copy(name = "Web")
        now = NOW + 5_000
        val resumed = service.reconcile(profile, store.load().single())
        assertEquals(PowerPhase.SUCCEEDED, resumed.phase); assertEquals(SettingState.DONE, resumed.settings!!.single().state)
        assertTrue(backend.sent.isEmpty())
        // A PREPARED record with nothing sent closes as not sent.
        val (store2, _, service2) = setup()
        store2.put(record.copy(id = "op2", phase = PowerPhase.PREPARED, settings = listOf(step)))
        val closed = service2.reconcile(profile, store2.load().single())
        assertEquals(PowerPhase.FAILED, closed.phase); assertTrue(closed.message.startsWith("App stopped before submission"))
        // Another connection cannot run automatic recovery.
        val (store3, _, service3) = setup()
        store3.put(record.copy(id = "op3"))
        assertTrue(runCatching { service3.reconcile(profile.copy(id = "other"), store3.load().single()) }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test fun checkStatusOnAVmThatDisappearedNeedsManualReview() = runTest {
        val (store, backend, service) = setup()
        backend.answer = { _, _ -> false to IOException("reset") }
        service.executeSettings(profile, confirm(running(), draft(running()).copy(name = "x")))
        backend.missing = true
        val checked = service.reconcile(profile, store.last)
        assertEquals(PowerPhase.UNKNOWN, checked.phase); assertTrue(checked.unresolved)
    }

    @Test fun manualReviewClosesAnUncertainEditWithoutClaimingSuccess() = runTest {
        val (store, backend, service) = setup()
        backend.answer = { _, _ -> true to IOException("reset") }
        backend.afterSend = { it.copy(name = "Third") }
        val uncertain = service.executeSettings(profile, confirm(running(), draft(running()).copy(name = "Web")))
        assertEquals(PowerPhase.UNKNOWN, uncertain.phase)
        val review = service.review(profile, store.last)
        assertNotNull(review.settings)
        assertEquals("Name: “Third” (requested “Web”, was “Guest”)", ReviewText.currentState(review))
        assertEquals(SettingsText.reviewExplanation(), ReviewText.explanation(review))
        val closed = service.resolveAfterReview(profile, review, serverHistoryChecked = true)
        assertEquals(PowerPhase.UNKNOWN, closed.phase); assertNotNull(closed.manualResolution); assertFalse(closed.unresolved)
        assertEquals("Closed · Outcome unverified", OperationHistory.status(closed))
        assertTrue(closed.message, closed.message.contains("remains unverified") && closed.message.contains("Nothing was resent"))
        assertEquals(1, backend.sent.size)
        // A change after the review requires a new review.
        val (store2, backend2, service2) = setup()
        backend2.answer = { _, _ -> false to IOException("reset") }
        service2.executeSettings(profile, confirm(running(), draft(running()).copy(name = "Web")))
        val review2 = service2.review(profile, store2.last)
        backend2.state = backend2.state.copy(description = "changed")
        assertTrue(runCatching { service2.resolveAfterReview(profile, review2, true) }.isFailure)
        assertTrue(store2.last.unresolved)
    }

    @Test fun settingsRecordsAreNeverWatchedAsTasks() = runTest {
        val (_, backend, service) = setup()
        backend.answer = { _, _ -> false to IOException("reset") }
        val result = service.executeSettings(profile, confirm(running(), draft(running()).copy(name = "x")))
        assertFalse(OperationWatch.watchable(result))
        assertEquals("Guest · Edit settings", OperationHistory.title(result))
        assertEquals(TrackingPolicy.SHORT_MILLIS, TrackingPolicy.budgetMillis(result))
    }
}
