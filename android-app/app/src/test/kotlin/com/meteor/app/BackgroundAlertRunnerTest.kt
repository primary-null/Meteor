package com.meteor.app

import com.meteor.app.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import javax.net.ssl.SSLHandshakeException

/** B3 background/summary: the background check's planning, bounds and failure handling, with fakes and virtual time. */
@OptIn(ExperimentalCoroutinesApi::class)
class BackgroundAlertRunnerTest {
    private val t0 = 1_800_000_000_000L
    private val minute = 60_000L
    private val a = Profile("a", "Alpha", "https://alpha.invalid/", "root", "alpha-secret", "a".repeat(64))
    private val b = Profile("b", "Beta", "https://beta.invalid/", "root", "beta-secret", "b".repeat(64))
    private val c = Profile("c", "Gamma", "https://gamma.invalid/", "root", "gamma-secret", "c".repeat(64))
    private val on = AlertNotifySettings(enabled = true, threshold = NotifyThreshold.HIGH, intervalMinutes = 15, enabledAt = t0)

    private class Vault(var document: ProfileDocument, var fail: Boolean = false) : ProfileStorage {
        var saves = 0
        override suspend fun load(): ProfileDocument { if (fail) throw IOException("fixture vault failure"); return document }
        override suspend fun save(document: ProfileDocument) { saves++; error("The background check must never write the vault") }
    }

    private class Memory : BackgroundAlertStore {
        val notify = MutableStateFlow<Map<String, AlertNotifySettings>>(emptyMap())
        val marks = mutableMapOf<String, AlertWatermark>()
        val statuses = mutableMapOf<String, CheckStatus>()
        val rewinds = mutableListOf<String>()
        var note: RunNote? = null
        val attentions = mutableMapOf<String, String>()
        override val settings = notify.map { it }
        override suspend fun setSettings(profileId: String, value: AlertNotifySettings) { notify.value = notify.value + (profileId to value) }
        override suspend fun watermark(profileId: String) = marks[profileId]
        override suspend fun setWatermark(profileId: String, value: AlertWatermark, rewind: Boolean) { marks[profileId] = value; if (rewind) rewinds += profileId }
        override suspend fun statuses(): Map<String, CheckStatus> = statuses.toMap()
        override suspend fun setStatus(profileId: String, value: CheckStatus) { statuses[profileId] = value }
        override suspend fun runNote(): RunNote? = note
        override suspend fun setRunNote(value: RunNote) { note = value }
        override val checks = notify.map { BackgroundChecks(statuses.toMap(), note) }
        override suspend fun attention(profileId: String) = attentions[profileId]
        override suspend fun setAttention(profileId: String, marker: String?) { if (marker == null) attentions -= profileId else attentions[profileId] = marker }
        override suspend fun resetProgress(profileId: String) { marks -= profileId; statuses -= profileId; attentions -= profileId }
        override suspend fun remove(profileId: String) { resetProgress(profileId); notify.value = notify.value - profileId }
        override suspend fun retainOnly(profileIds: Set<String>): Set<String> {
            val gone = (notify.value.keys + marks.keys + statuses.keys + attentions.keys) - profileIds
            gone.forEach { remove(it) }
            return gone
        }
    }

    private class Recorder : AlertNotifier {
        override var canPost = true
        val batches = mutableListOf<AlertNotificationBatch>()
        val attentions = mutableListOf<Pair<String, AttentionText>>()
        val cleared = mutableListOf<String>()
        override fun post(batch: AlertNotificationBatch) { batches += batch }
        override fun attention(profileId: String, text: AttentionText) { attentions += profileId to text }
        override fun clearAttention(profileId: String) { cleared += profileId }
    }

    /** Only the background read exists; every other gateway path fails the test. */
    private inner class Backend : MeteorGateway {
        val calls = mutableListOf<Pair<String, Long>>()
        /** The server's clock as the real session would read it; null means the server did not answer `host.get_servertime`. */
        var serverClock: ServerClock? = ServerClock(t0 + minute, t0 + minute)
        var answer: suspend (Profile, Long) -> BackgroundAlertFetch = { _, _ -> BackgroundAlertFetch("pool", "Pool", emptyList()) }
        override suspend fun backgroundAlerts(profile: Profile, since: BackgroundSince, wanted: (AlertMessage) -> Boolean): BackgroundAlertFetch {
            // Like the real session: the pool is known first (its identity is "pool"), then the clock, then the since value.
            val sinceMillis = since.since("pool", serverClock) ?: run { calls += profile.id to -1L; throw ServerTimeUnavailable() }
            calls += profile.id to sinceMillis
            return answer(profile, sinceMillis).copy(serverClock = serverClock)
        }
        override suspend fun inventory(profile: Profile): Inventory = error("Unexpected inventory")
        override suspend fun details(profile: Profile, authority: String, uuid: String): VmDetails = error("Unexpected details")
        override suspend fun <T> powerConnection(profile: Profile, action: suspend PowerConnection.() -> T): T = error("Background work must never touch the power path")
        override suspend fun dismissAlerts(profile: Profile, authority: String, targets: List<AlertTarget>): DismissOutcome = error("Background work must never write")
    }

    private fun alert(ref: String, at: Long, priority: Int = 1) = AlertMessage(ref, "u-$ref", "ha_host_failed", priority, "Host", "h", at, "body")

    private class Seen(var value: Long? = null) : AlertSeenStore {
        override suspend fun lastSeen(profileId: String) = value
        override suspend fun save(profileId: String, millis: Long) = error("The background check must never write the in-app seen value")
    }

    private inner class Fixture(scope: TestScope, profiles: List<Profile> = listOf(a, b)) {
        val vault = Vault(ProfileDocument(profiles))
        val store = Memory()
        val notifier = Recorder()
        val backend = Backend()
        val seen = Seen()
        var now = t0 + minute
        val runner = BackgroundAlertRunner(vault, backend, store, notifier, seen, clock = { now + scope.testScheduler.currentTime },
            ioContext = StandardTestDispatcher(scope.testScheduler))
    }

    @Test fun onlyOptedInProfilesAreCheckedOnceEachWithoutWrites() = runTest {
        val f = Fixture(this, listOf(a, b, c))
        f.store.setSettings("a", on); f.store.setSettings("b", on.copy(enabled = false))
        val report = f.runner.run()
        assertEquals(listOf("a"), f.backend.calls.map { it.first })
        assertEquals(t0 - BackgroundAlerts.OVERLAP_MILLIS, f.backend.calls.single().second)
        assertEquals(mapOf("a" to CheckOutcome.NOTHING_NEW), report.outcomes)
        assertEquals(15, report.periodMinutes)
        assertEquals(0, f.vault.saves)
        assertEquals(CheckOutcome.NOTHING_NEW, f.store.statuses["a"]?.outcome)
    }

    @Test fun newAlertsAreNotifiedOnceAndTheWatermarkSuppressesRepeats() = runTest {
        val f = Fixture(this)
        f.store.setSettings("a", on)
        f.backend.answer = { _, _ -> BackgroundAlertFetch("pool", "Pool", listOf(alert("m1", t0 + 1000), alert("m2", t0 + 2000, priority = 4))) }
        val first = f.runner.run()
        assertEquals(CheckOutcome.NOTIFIED, first.outcomes["a"]); assertEquals(1, first.notified)
        assertEquals(listOf("m1"), f.notifier.batches.single().items.map { it.ref })
        // The next run reads from the saved watermark and the server returns the same messages: nothing is repeated.
        f.now += 20 * minute
        val second = f.runner.run()
        assertEquals(t0 + 2000 - BackgroundAlerts.OVERLAP_MILLIS, f.backend.calls.last().second)
        assertEquals(CheckOutcome.NOTHING_NEW, second.outcomes["a"])
        assertEquals(1, f.notifier.batches.size)
    }

    @Test fun aProfileThatIsNotDueIsNotContacted() = runTest {
        val f = Fixture(this)
        f.store.setSettings("a", on.copy(intervalMinutes = 60))
        f.runner.run()
        f.now += 20 * minute
        val report = f.runner.run()
        assertEquals(1, f.backend.calls.size)
        assertTrue(report.outcomes.isEmpty())
    }

    @Test fun aChangedPinPostsOneAttentionNotificationAndNeverRetriesOrChangesTrust() = runTest {
        val f = Fixture(this)
        f.store.setSettings("a", on)
        f.backend.answer = { _, _ -> throw SSLHandshakeException("pin").apply { initCause(CertificateChanged()) } }
        val report = f.runner.run()
        assertEquals(CheckOutcome.CERTIFICATE_CHANGED, report.outcomes["a"])
        assertEquals(1, f.backend.calls.size) // one attempt, no retry in the same run
        assertEquals("a", f.notifier.attentions.single().first)
        assertTrue(f.notifier.batches.isEmpty())
        assertEquals("a".repeat(64), f.vault.document.profiles.first { it.id == "a" }.certificateSha256)
        f.now += 20 * minute
        f.runner.run()
        assertEquals(2, f.backend.calls.size)
        assertEquals(1, f.notifier.attentions.size) // same problem: not posted again
        // Once the check works again the attention notification is cleared.
        f.backend.answer = { _, _ -> BackgroundAlertFetch("pool", "Pool", emptyList()) }
        f.now += 20 * minute
        f.runner.run()
        assertEquals(listOf("a"), f.notifier.cleared); assertNull(f.store.attentions["a"])
    }

    @Test fun loginFailuresAreNotRetriedAndAuthenticationFailureAsksForAttention() = runTest {
        val f = Fixture(this)
        f.store.setSettings("a", on); f.store.setSettings("b", on)
        f.backend.answer = { profile, _ ->
            if (profile.id == "a") throw RpcFailure("SESSION_AUTHENTICATION_FAILED") else throw IOException("unreachable")
        }
        val report = f.runner.run()
        assertEquals(CheckOutcome.AUTHENTICATION_FAILED, report.outcomes["a"])
        assertEquals(CheckOutcome.FAILED, report.outcomes["b"])
        assertEquals(listOf("a", "b"), f.backend.calls.map { it.first })
        assertEquals(listOf("a"), f.notifier.attentions.map { it.first })
        assertTrue(f.store.marks.isEmpty())
        // Neither is contacted again straight away, and the refused credentials are not retried quickly at all.
        f.now += 3 * minute
        f.runner.run()
        assertEquals(2, f.backend.calls.size)
        f.now += 3 * minute
        f.runner.run()
        assertEquals(listOf("a", "b", "b"), f.backend.calls.map { it.first }) // only the unreachable server got its quick retry
    }

    @Test fun aPoolMemberAnswerAsksForAttentionWithoutFollowing() = runTest {
        val f = Fixture(this)
        f.store.setSettings("a", on)
        f.backend.answer = { _, _ -> throw RpcFailure("HOST_IS_SLAVE", details = listOf("192.0.2.10")) }
        assertEquals(CheckOutcome.POOL_MEMBER, f.runner.run().outcomes["a"])
        assertFalse(f.notifier.attentions.single().second.text.contains("192.0.2.10"))
        assertEquals("https://alpha.invalid/", f.vault.document.profiles.first().endpoint)
    }

    @Test fun eachProfileIsBoundedAndTheRunStopsStartingProfilesNearItsBudget() = runTest {
        val profiles = (1..8).map { Profile("p$it", "P$it", "https://p$it.invalid/", "root", "secret", "e".repeat(64)) }
        val f = Fixture(this, profiles)
        profiles.forEach { f.store.setSettings(it.id, on) }
        f.backend.answer = { _, _ -> delay(10 * minute); error("never answers in time") }
        val report = f.runner.run()
        // 4-minute run budget with 45-second profile budgets: profiles start at 0, 45, 90, 135, 180 s; the rest are skipped.
        assertEquals(5, f.backend.calls.size)
        assertEquals(5, report.outcomes.values.count { it == CheckOutcome.TIMED_OUT })
        assertEquals(3, report.outcomes.values.count { it == CheckOutcome.SKIPPED_BUDGET })
        assertTrue(testScheduler.currentTime <= BackgroundAlertRunner.RUN_BUDGET_MILLIS)
        // Skipped profiles were not marked as checked, so they are first in line next time.
        assertEquals(5, f.store.statuses.size)
    }

    @Test fun blockedNotificationsMeanNoSignInAndNoWatermarkChange() = runTest {
        val f = Fixture(this)
        f.store.setSettings("a", on)
        f.notifier.canPost = false
        val report = f.runner.run()
        assertTrue(report.blocked); assertTrue(f.backend.calls.isEmpty()); assertTrue(f.store.statuses.isEmpty())
        assertEquals(15, report.periodMinutes)
        assertEquals(RunNote(f.now, RunProblem.NOTIFICATIONS_BLOCKED), f.store.note)
    }

    @Test fun anUnreadableVaultChecksNothingAndPrunesNothing() = runTest {
        val f = Fixture(this)
        f.store.setSettings("a", on); f.store.setSettings("gone", on)
        f.vault.fail = true
        val report = f.runner.run()
        assertTrue(report.vaultUnavailable); assertTrue(f.backend.calls.isEmpty())
        assertEquals(setOf("a", "gone"), f.store.notify.value.keys)
        assertEquals(15, report.periodMinutes)
        assertEquals(RunNote(f.now, RunProblem.VAULT_UNAVAILABLE), f.store.note)
    }

    @Test fun preferencesOfForgottenProfilesAreRemovedAndNothingLeftStopsTheSchedule() = runTest {
        val f = Fixture(this, listOf(b))
        f.store.setSettings("a", on); f.store.setStatus("a", CheckStatus(t0, CheckOutcome.NOTHING_NEW, t0, 0))
        val report = f.runner.run()
        assertEquals(setOf("a"), report.removedProfiles)
        assertTrue(f.store.notify.value.isEmpty()); assertTrue(f.store.statuses.isEmpty())
        assertNull(report.periodMinutes)
        assertTrue(f.backend.calls.isEmpty())
    }

    @Test fun cancellingTheWorkerCancelsTheInFlightCheck() = runTest {
        val f = Fixture(this)
        f.store.setSettings("a", on)
        var cancelled = false
        f.backend.answer = { _, _ -> try { awaitCancellation() } finally { cancelled = true } }
        val job = launch { f.runner.run() }
        advanceTimeBy(1_000); runCurrent()
        job.cancelAndJoin()
        runCurrent()
        assertTrue(cancelled)
    }

    // ---- the check reports what happened ----
    @Test fun everyCheckRecordsItsTimeOutcomeAndTheLastSuccess() = runTest {
        val f = Fixture(this)
        f.store.setSettings("a", on)
        f.runner.run()
        val firstAt = f.now
        assertEquals(CheckStatus(firstAt, CheckOutcome.NOTHING_NEW, firstAt, 0), f.store.statuses["a"])
        f.backend.answer = { _, _ -> throw IOException("unreachable") }
        f.now += 20 * minute
        f.runner.run()
        assertEquals(CheckStatus(f.now, CheckOutcome.FAILED, firstAt, 1), f.store.statuses["a"]) // the last success stays on record
        f.backend.answer = { _, _ -> BackgroundAlertFetch("pool", "Pool", listOf(alert("m1", t0 + 2 * minute))) }
        f.now += 20 * minute
        f.runner.run()
        assertEquals(CheckStatus(f.now, CheckOutcome.NOTIFIED, f.now, 0), f.store.statuses["a"])
    }

    @Test fun outcomesAreClassifiedForSettings() = runTest {
        val cases = listOf<Pair<Throwable, CheckOutcome>>(
            IOException("down") to CheckOutcome.FAILED, RpcFailure("INTERNAL_ERROR") to CheckOutcome.SERVER_ERROR,
            GatewayError("http-error") to CheckOutcome.SERVER_ERROR, RpcFailure("SESSION_AUTHENTICATION_FAILED") to CheckOutcome.AUTHENTICATION_FAILED)
        cases.forEach { (error, expected) ->
            val f = Fixture(this)
            f.store.setSettings("a", on)
            f.backend.answer = { _, _ -> throw error }
            assertEquals(expected, f.runner.run().outcomes["a"])
            assertEquals(expected, f.store.statuses["a"]?.outcome)
        }
        val slow = Fixture(this)
        slow.store.setSettings("a", on)
        slow.backend.answer = { _, _ -> delay(10 * minute); error("late") }
        assertEquals(CheckOutcome.TIMED_OUT, slow.runner.run().outcomes["a"])
    }

    @Test fun aRunThatChecksSomethingLeavesAnOkNoteAndOneThatCannotSaysWhy() = runTest {
        val f = Fixture(this)
        f.store.setSettings("a", on)
        f.vault.fail = true
        f.runner.run()
        assertEquals(RunProblem.VAULT_UNAVAILABLE, f.store.note?.problem)
        f.vault.fail = false
        f.runner.run()
        assertEquals(RunNote(f.now, RunProblem.OK), f.store.note) // the earlier problem is cleared by a working run
        // Nobody opted in: nothing to report, and the old note is left alone.
        val idle = Fixture(this)
        idle.runner.run()
        assertNull(idle.store.note)
    }

    // ---- retrying a failed check ----
    @Test fun aTransportFailureIsRetriedAfterAShortBackoffTwiceAndThenWaitsForItsInterval() = runTest {
        val f = Fixture(this)
        f.store.setSettings("a", on.copy(intervalMinutes = 60))
        f.backend.answer = { _, _ -> throw IOException("unreachable") }
        assertTrue(f.runner.run().retryWanted)                         // asks WorkManager for a retry with backoff
        f.now += 3 * minute
        assertTrue(f.runner.run().retryWanted); assertEquals(1, f.backend.calls.size)  // too early: not contacted, retry still wanted
        f.now += 1 * minute
        assertTrue(f.runner.run().retryWanted); assertEquals(2, f.backend.calls.size)  // first quick retry, after 4 minutes
        f.now += 8 * minute
        val last = f.runner.run()
        assertEquals(3, f.backend.calls.size)                          // second quick retry, after 8 more
        assertFalse(last.retryWanted)                                  // quick retries are used up: no storm
        f.now += 30 * minute
        f.runner.run()
        assertEquals(3, f.backend.calls.size)                          // the normal 60-minute interval applies again
        f.now += 25 * minute
        f.runner.run()
        assertEquals(4, f.backend.calls.size)
    }

    @Test fun refusedCredentialsAndAChangedCertificateAreNeverRetriedQuickly() = runTest {
        listOf<Throwable>(RpcFailure("SESSION_AUTHENTICATION_FAILED"), RpcFailure("HOST_IS_SLAVE"),
            SSLHandshakeException("pin").apply { initCause(CertificateChanged()) }, RpcFailure("RBAC_PERMISSION_DENIED")).forEach { error ->
            val f = Fixture(this)
            f.store.setSettings("a", on.copy(intervalMinutes = 60))
            f.backend.answer = { _, _ -> throw error }
            val first = f.runner.run()
            assertFalse(first.retryWanted)
            f.now += 10 * minute
            f.runner.run()
            f.now += 40 * minute
            f.runner.run()
            assertEquals("$error", 1, f.backend.calls.size)         // not contacted again for the rest of the hour
        }
    }

    @Test fun aRecoveredNetworkEndsTheRetriesAndStartsTheNormalRhythm() = runTest {
        val f = Fixture(this)
        f.store.setSettings("a", on)
        f.backend.answer = { _, _ -> throw IOException("unreachable") }
        f.runner.run()
        f.backend.answer = { _, _ -> BackgroundAlertFetch("pool", "Pool", emptyList()) }
        f.now += 4 * minute
        val report = f.runner.run()
        assertEquals(CheckOutcome.NOTHING_NEW, report.outcomes["a"]); assertFalse(report.retryWanted)
        assertEquals(0, f.store.statuses["a"]?.failures)
    }

    @Test fun anInterruptedRunRecordsNothingSoTheProfileStaysDue() = runTest {
        val f = Fixture(this)
        f.store.setSettings("a", on)
        f.backend.answer = { _, _ -> awaitCancellation() }
        val job = launch { f.runner.run() }
        advanceTimeBy(1_000); runCurrent()
        job.cancelAndJoin()
        assertTrue(f.store.statuses.isEmpty())
        f.backend.answer = { _, _ -> BackgroundAlertFetch("pool", "Pool", emptyList()) }
        assertEquals(CheckOutcome.NOTHING_NEW, f.runner.run().outcomes["a"])
    }

    // ---- failing checks are reported once ----
    @Test fun repeatedFailuresPostOneAttentionNotificationAndASuccessClearsIt() = runTest {
        val f = Fixture(this)
        f.store.setSettings("a", on)
        f.backend.answer = { _, _ -> throw IOException("unreachable") }
        // Three failures in a row (a first try and two quick retries) are not yet reported.
        f.runner.run(); f.now += 4 * minute; f.runner.run(); f.now += 8 * minute; f.runner.run()
        assertEquals(3, f.store.statuses["a"]?.failures); assertTrue(f.notifier.attentions.isEmpty())
        // The fourth, at the next interval, is.
        f.now += 10 * minute
        f.runner.run()
        assertEquals(4, f.store.statuses["a"]?.failures)
        val (profileId, text) = f.notifier.attentions.single()
        assertEquals("a", profileId); assertTrue(text.title.contains("checks are failing")); assertTrue(text.title.contains("Alpha"))
        listOf("alpha.invalid", "alpha-secret", "root", "a".repeat(64)).forEach { assertFalse((text.title + text.text).contains(it)) }
        // More failures do not post it again.
        f.now += 10 * minute; f.runner.run(); f.now += 10 * minute; f.runner.run()
        assertEquals(1, f.notifier.attentions.size)
        // A working check clears the notification and the failure count.
        f.backend.answer = { _, _ -> BackgroundAlertFetch("pool", "Pool", emptyList()) }
        f.now += 10 * minute
        f.runner.run()
        assertEquals(listOf("a"), f.notifier.cleared); assertNull(f.store.attentions["a"]); assertEquals(0, f.store.statuses["a"]?.failures)
        // After a recovery the next run of failures counts from zero.
        f.backend.answer = { _, _ -> throw IOException("unreachable") }
        f.now += 20 * minute; f.runner.run()
        assertEquals(1, f.notifier.attentions.size)
    }

    @Test fun aProblemThatAlreadyHasItsOwnNotificationDoesNotAddAFailingOne() = runTest {
        val f = Fixture(this)
        f.store.setSettings("a", on)
        f.backend.answer = { _, _ -> throw RpcFailure("SESSION_AUTHENTICATION_FAILED") }
        repeat(6) { f.runner.run(); f.now += 20 * minute }
        assertEquals(6, f.store.statuses["a"]?.failures)
        assertEquals(1, f.notifier.attentions.size)
        assertTrue(f.notifier.attentions.single().second.text.contains("Sign-in failed"))
    }

    // ---- the server's clock ----
    @Test fun aPhoneAheadOfTheServerStillNotifiesNewAlertsThroughTheWholeRunner() = runTest {
        val f = Fixture(this)
        f.store.setSettings("a", on) // enabled at phone time t0
        f.backend.serverClock = ServerClock(serverMillis = t0 - 59 * minute, phoneMillis = t0 + minute) // the phone is an hour ahead
        f.backend.answer = { _, _ -> BackgroundAlertFetch("pool", "Pool", listOf(alert("before", t0 - 61 * minute), alert("after", t0 - 55 * minute))) }
        val report = f.runner.run()
        assertEquals(t0 - 60 * minute - BackgroundAlerts.OVERLAP_MILLIS, f.backend.calls.single().second) // server time, not the phone's t0
        assertEquals(listOf("after"), f.notifier.batches.single().items.map { it.ref })
        assertEquals(CheckOutcome.NOTIFIED, report.outcomes["a"])
        assertEquals(t0 - 55 * minute, f.store.marks["a"]?.newest)
    }

    @Test fun aPhoneBehindTheServerDoesNotNotifyAlertsFromBeforeTheSwitch() = runTest {
        val f = Fixture(this)
        f.store.setSettings("a", on)
        f.backend.serverClock = ServerClock(serverMillis = t0 + 61 * minute, phoneMillis = t0 + minute) // an hour behind
        f.backend.answer = { _, _ -> BackgroundAlertFetch("pool", "Pool", listOf(alert("before", t0 + 30 * minute), alert("after", t0 + 60 * minute + 1000))) }
        f.runner.run()
        assertEquals(listOf("after"), f.notifier.batches.single().items.map { it.ref })
    }

    @Test fun aQuietPoolKeepsAServerTimeWatermark() = runTest {
        val f = Fixture(this)
        f.store.setSettings("a", on)
        f.backend.serverClock = ServerClock(serverMillis = t0 - 59 * minute, phoneMillis = t0 + minute)
        f.runner.run()
        assertEquals(t0 - 60 * minute, f.store.marks["a"]?.newest) // not the phone's t0
    }

    @Test fun withoutTheServersClockAFirstCheckFailsPlainlyAndAnExistingWatermarkStillWorks() = runTest {
        val f = Fixture(this)
        f.store.setSettings("a", on)
        f.backend.serverClock = null
        val first = f.runner.run()
        assertEquals(CheckOutcome.SERVER_TIME_UNAVAILABLE, first.outcomes["a"]); assertFalse(first.retryWanted)
        assertTrue(f.store.marks.isEmpty()); assertTrue(f.notifier.batches.isEmpty())
        assertEquals(CheckOutcome.SERVER_TIME_UNAVAILABLE, f.store.statuses["a"]?.outcome)
        // Once it has a watermark of the pool, a missing clock is not needed.
        f.store.marks["a"] = AlertWatermark(a.origin(), "pool", t0 + 30 * minute)
        f.now += 20 * minute
        f.backend.answer = { _, _ -> BackgroundAlertFetch("pool", "Pool", listOf(alert("n", t0 + 40 * minute))) }
        assertEquals(CheckOutcome.NOTIFIED, f.runner.run().outcomes["a"])
    }

    @Test fun aWatermarkSavedAheadOfTheServerByTheCurrentReleaseIsRewoundAndNothingIsHidden() = runTest {
        val f = Fixture(this)
        f.store.setSettings("a", on)
        f.store.marks["a"] = AlertWatermark(a.origin(), "pool", t0 + 60 * minute) // the phone's enable time, saved with no messages
        f.backend.answer = { _, _ -> BackgroundAlertFetch("pool", "Pool", listOf(alert("hidden", t0 + 30_000))) }
        f.runner.run()
        assertEquals(t0 + minute - BackgroundAlerts.OVERLAP_MILLIS, f.backend.calls.single().second)
        assertEquals(listOf("hidden"), f.notifier.batches.single().items.map { it.ref })
        assertEquals(listOf("a"), f.store.rewinds); assertEquals(t0 + minute, f.store.marks["a"]?.newest)
    }

    // ---- alerts already read in the app ----
    @Test fun anAlertTheInboxAlreadyShowedIsNotNotifiedButIsStillHandled() = runTest {
        val f = Fixture(this)
        f.store.setSettings("a", on)
        f.seen.value = t0 + 2000
        f.backend.answer = { _, _ -> BackgroundAlertFetch("pool", "Pool", listOf(alert("read", t0 + 1000), alert("alsoRead", t0 + 2000), alert("unread", t0 + 3000))) }
        val report = f.runner.run()
        assertEquals(listOf("unread"), f.notifier.batches.single().items.map { it.ref })
        assertEquals(1, report.notified)
        // Everything was read in the app: no notification and nothing replayed on the next check.
        f.seen.value = t0 + 3000
        f.now += 20 * minute
        assertEquals(CheckOutcome.NOTHING_NEW, f.runner.run().outcomes["a"])
        assertEquals(1, f.notifier.batches.size)
    }

    @Test fun aSeenStoreThatCannotBeReadDoesNotStopTheCheck() = runTest {
        val vault = Vault(ProfileDocument(listOf(a)))
        val store = Memory(); val notifier = Recorder(); val backend = Backend()
        val broken = object : AlertSeenStore {
            override suspend fun lastSeen(profileId: String): Long? = throw IOException("seen store")
            override suspend fun save(profileId: String, millis: Long) = Unit
        }
        store.setSettings("a", on)
        backend.answer = { _, _ -> BackgroundAlertFetch("pool", "Pool", listOf(alert("m", t0 + 1000))) }
        val runner = BackgroundAlertRunner(vault, backend, store, notifier, broken, clock = { t0 + minute }, ioContext = StandardTestDispatcher(testScheduler))
        assertEquals(CheckOutcome.NOTIFIED, runner.run().outcomes["a"])
    }
}
