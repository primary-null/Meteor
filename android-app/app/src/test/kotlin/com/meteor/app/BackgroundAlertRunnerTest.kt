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
        val checked = mutableMapOf<String, Long>()
        val attentions = mutableMapOf<String, String>()
        override val settings = notify.map { it }
        override suspend fun setSettings(profileId: String, value: AlertNotifySettings) { notify.value = notify.value + (profileId to value) }
        override suspend fun watermark(profileId: String) = marks[profileId]
        override suspend fun setWatermark(profileId: String, value: AlertWatermark) { marks[profileId] = value }
        override suspend fun lastChecked(): Map<String, Long> = checked.toMap()
        override suspend fun setLastChecked(profileId: String, millis: Long) { checked[profileId] = millis }
        override suspend fun attention(profileId: String) = attentions[profileId]
        override suspend fun setAttention(profileId: String, marker: String?) { if (marker == null) attentions -= profileId else attentions[profileId] = marker }
        override suspend fun resetProgress(profileId: String) { marks -= profileId; checked -= profileId; attentions -= profileId }
        override suspend fun remove(profileId: String) { resetProgress(profileId); notify.value = notify.value - profileId }
        override suspend fun retainOnly(profileIds: Set<String>): Set<String> {
            val gone = (notify.value.keys + marks.keys + checked.keys + attentions.keys) - profileIds
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
    private class Backend : MeteorGateway {
        val calls = mutableListOf<Pair<String, Long>>()
        var answer: suspend (Profile, Long) -> BackgroundAlertFetch = { _, _ -> BackgroundAlertFetch("pool", "Pool", emptyList()) }
        override suspend fun backgroundAlerts(profile: Profile, sinceMillis: Long, wanted: (AlertMessage) -> Boolean): BackgroundAlertFetch {
            calls += profile.id to sinceMillis
            return answer(profile, sinceMillis)
        }
        override suspend fun inventory(profile: Profile): Inventory = error("Unexpected inventory")
        override suspend fun details(profile: Profile, authority: String, uuid: String): VmDetails = error("Unexpected details")
        override suspend fun <T> powerConnection(profile: Profile, action: suspend PowerConnection.() -> T): T = error("Background work must never touch the power path")
        override suspend fun dismissAlerts(profile: Profile, authority: String, targets: List<AlertTarget>): DismissOutcome = error("Background work must never write")
    }

    private fun alert(ref: String, at: Long, priority: Int = 1) = AlertMessage(ref, "u-$ref", "ha_host_failed", priority, "Host", "h", at, "body")

    private inner class Fixture(scope: TestScope, profiles: List<Profile> = listOf(a, b)) {
        val vault = Vault(ProfileDocument(profiles))
        val store = Memory()
        val notifier = Recorder()
        val backend = Backend()
        var now = t0 + minute
        val runner = BackgroundAlertRunner(vault, backend, store, notifier, clock = { now + scope.testScheduler.currentTime },
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
        assertNotNull(f.store.checked["a"])
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
        // Failed profiles still wait for their interval.
        f.now += 5 * minute
        f.runner.run()
        assertEquals(2, f.backend.calls.size)
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
        assertEquals(5, f.store.checked.size)
    }

    @Test fun blockedNotificationsMeanNoSignInAndNoWatermarkChange() = runTest {
        val f = Fixture(this)
        f.store.setSettings("a", on)
        f.notifier.canPost = false
        val report = f.runner.run()
        assertTrue(report.blocked); assertTrue(f.backend.calls.isEmpty()); assertTrue(f.store.checked.isEmpty())
        assertEquals(15, report.periodMinutes)
    }

    @Test fun anUnreadableVaultChecksNothingAndPrunesNothing() = runTest {
        val f = Fixture(this)
        f.store.setSettings("a", on); f.store.setSettings("gone", on)
        f.vault.fail = true
        val report = f.runner.run()
        assertTrue(report.vaultUnavailable); assertTrue(f.backend.calls.isEmpty())
        assertEquals(setOf("a", "gone"), f.store.notify.value.keys)
        assertEquals(15, report.periodMinutes)
    }

    @Test fun preferencesOfForgottenProfilesAreRemovedAndNothingLeftStopsTheSchedule() = runTest {
        val f = Fixture(this, listOf(b))
        f.store.setSettings("a", on); f.store.setLastChecked("a", t0)
        val report = f.runner.run()
        assertEquals(setOf("a"), report.removedProfiles)
        assertTrue(f.store.notify.value.isEmpty()); assertTrue(f.store.checked.isEmpty())
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
}
