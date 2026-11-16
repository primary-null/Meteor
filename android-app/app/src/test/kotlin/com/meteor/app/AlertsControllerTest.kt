package com.meteor.app

import com.meteor.app.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AlertsControllerTest {
    private val t0 = 1_800_000_000_000L
    private val profile = Profile("fixture", "Example", "https://example.invalid/", "tester", "fixture", "a".repeat(64))
    private val inventory = Inventory("pool", "Pool", emptyList(), listOf(Guest("vm", "Example VM", "Running", "pool", null, 2, 1024)), 0, 0, 1)
    private fun state() = MutableStateFlow(MeteorState(profiles = listOf(profile), selectedId = profile.id, connected = true, inventory = inventory))
    private fun alert(ref: String, priority: Int = 3, at: Long = t0, name: String = "ha_host_failed") =
        AlertMessage(ref, "u-$ref", name, priority, "VM", "vm", at, "body")

    private class Seen(var stored: Long? = null) : AlertSeenStore {
        val saves = mutableListOf<Pair<String, Long>>()
        override suspend fun lastSeen(profileId: String) = stored
        override suspend fun save(profileId: String, millis: Long) { saves += profileId to millis; stored = maxOf(stored ?: 0, millis) }
    }
    private class Backend : MeteorGateway {
        val reads = mutableListOf<Long?>()
        var now = 1_800_000_100_000L
        var read: suspend (Long?) -> List<AlertMessage> = { emptyList() }
        var dismissed = mutableListOf<List<AlertTarget>>()
        var dismissal: suspend (List<AlertTarget>) -> DismissOutcome = { targets -> DismissOutcome(targets.map { DismissResult(it.ref, DismissStatus.REMOVED) }) }
        override suspend fun alerts(profile: Profile, authority: String, sinceMillis: Long?): AlertFetch {
            reads += sinceMillis
            return AlertFetch(authority, now, read(sinceMillis), sinceMillis == null)
        }
        override suspend fun dismissAlerts(profile: Profile, authority: String, targets: List<AlertTarget>): DismissOutcome {
            dismissed += targets; return dismissal(targets)
        }
        override suspend fun inventory(profile: Profile): Inventory = error("Unexpected inventory")
        override suspend fun details(profile: Profile, authority: String, uuid: String): VmDetails = error("Unexpected details")
        override suspend fun <T> powerConnection(profile: Profile, action: suspend PowerConnection.() -> T): T = error("Alerts must never touch the power path")
    }
    /** The background check's notifications, as the Android notifier would be asked to remove them. */
    private class Shade : AlertNotificationCleaner {
        val profiles = mutableListOf<String>()
        val alerts = mutableListOf<Pair<String, Set<String>>>()
        override fun clearProfile(profileId: String) { profiles += profileId }
        override fun clearAlerts(profileId: String, refs: Collection<String>) { alerts += profileId to refs.toSet() }
    }
    private class Fixture(scope: CoroutineScope, val state: MutableStateFlow<MeteorState>, val backend: Backend = Backend(), val seen: Seen = Seen(), val shade: Shade = Shade()) {
        val controller = AlertsController(scope, backend, state, seen, shade) { backend.now }
    }

    @Test fun firstReadIsFullThenIncrementalFromTheServerCursorMinusTheOverlap() = runTest {
        val f = Fixture(backgroundScope, state())
        f.backend.read = { listOf(alert("a", at = t0 + 10), alert("b", at = t0 + 20)) }
        f.controller.refresh(); runCurrent()
        assertEquals(listOf<Long?>(null), f.backend.reads)
        assertEquals(listOf("b", "a"), f.state.value.alerts?.messages?.map { it.ref })
        f.backend.read = { listOf(alert("b", at = t0 + 20), alert("c", at = t0 + 30)) }       // re-read overlap + a new one
        f.controller.refresh(); runCurrent()
        assertEquals(t0 + 20 - AlertMerge.OVERLAP_MILLIS, f.backend.reads.last())              // server timestamps only
        assertEquals(listOf("c", "b", "a"), f.state.value.alerts?.messages?.map { it.ref })    // no duplicate "b"
        assertFalse(f.state.value.alertsBusy); assertNull(f.state.value.alertsProblem)
    }
    @Test fun aFullReadHappensAgainAfterTheReconcileInterval() = runTest {
        val f = Fixture(backgroundScope, state())
        f.backend.read = { listOf(alert("a", at = t0 + 10), alert("gone", at = t0 + 20)) }
        f.controller.refresh(); runCurrent()
        f.backend.now += AlertMerge.FULL_RECONCILE_MILLIS + 1
        f.backend.read = { listOf(alert("a", at = t0 + 10)) }                                  // "gone" was deleted on the server
        f.controller.refresh(); runCurrent()
        assertEquals(listOf<Long?>(null, null), f.backend.reads)
        assertEquals(listOf("a"), f.state.value.alerts?.messages?.map { it.ref })
    }
    @Test fun failedRefreshKeepsLastKnownAlertsAndNeverLeaksExceptionText() = runTest {
        val f = Fixture(backgroundScope, state())
        f.backend.read = { listOf(alert("a")) }
        f.controller.refresh(); runCurrent()
        f.backend.read = { error("private transport detail") }
        f.controller.refresh(); runCurrent()
        assertEquals(listOf("a"), f.state.value.alerts?.messages?.map { it.ref })
        assertTrue(f.state.value.alertsProblem!!.contains("last known")); assertFalse(f.state.value.alertsProblem!!.contains("private"))
        f.backend.read = { throw RpcFailure("RBAC_PERMISSION_DENIED") }
        f.controller.refresh(); runCurrent()
        assertEquals("Alerts is not available to this account.", f.state.value.alertsProblem)
        f.backend.read = { listOf(alert("a"), alert("b", at = t0 + 5)) }
        f.controller.refresh(); runCurrent()
        assertNull(f.state.value.alertsProblem); assertEquals(2, f.state.value.alerts?.messages?.size)
    }
    @Test fun aLateAnswerFromAnotherConnectionIsDroppedEvenIfCancellationIsIgnored() = runTest {
        val f = Fixture(backgroundScope, state())
        val gate = CompletableDeferred<List<AlertMessage>>()
        f.backend.read = { withContext(NonCancellable) { gate.await() } }
        f.controller.refresh(); runCurrent(); assertTrue(f.state.value.alertsBusy)
        f.state.value = f.state.value.copy(profiles = listOf(profile.copy(endpoint = "https://other.invalid/")))   // new origin
        f.controller.invalidate()
        gate.complete(listOf(alert("late"))); runCurrent()
        assertNull(f.state.value.alerts); assertFalse(f.state.value.alertsBusy)
        // A different pool under the same profile is a different inbox, too.
        f.state.value = f.state.value.copy(profiles = listOf(profile))
        f.backend.read = { listOf(alert("a")) }
        f.controller.refresh(); runCurrent()
        f.state.value = f.state.value.copy(inventory = inventory.copy(authority = "other-pool"))
        assertNull(f.state.value.currentAlerts); assertEquals(0, f.state.value.unreadAlertBadge)
    }
    @Test fun aRequestDuringARunIsRememberedAndRunsExactlyOnceMore() = runTest {
        val f = Fixture(backgroundScope, state())
        val gate = CompletableDeferred<Unit>()
        f.backend.read = { gate.await(); listOf(alert("a")) }
        f.controller.refresh(); runCurrent()
        f.controller.refresh(); f.controller.refresh(full = true); f.controller.refresh(); runCurrent()
        assertEquals(1, f.backend.reads.size)
        gate.complete(Unit); runCurrent()
        assertEquals(listOf<Long?>(null, null), f.backend.reads)           // the remembered request asked for a full read
        runCurrent(); assertEquals(2, f.backend.reads.size)
    }
    @Test fun readsNeedAKnownPoolAndAnUnpendingTrustButNotTheTransientConnectedFlag() = runTest {
        val f = Fixture(backgroundScope, state())
        f.state.value = f.state.value.copy(selectedId = null); f.controller.refresh(); runCurrent()                 // disconnected
        f.state.value = f.state.value.copy(selectedId = profile.id, inventory = null); f.controller.refresh(); runCurrent()   // no pool identity yet
        f.state.value = f.state.value.copy(inventory = inventory, pendingTrust = PendingTrust(profile, ServerCertificate("b".repeat(64), "x", 1, 2)))
        f.controller.refresh(); runCurrent()                                                                          // a certificate review is open
        assertEquals(0, f.backend.reads.size)
        // An inventory refresh clears `connected` while it runs; the resync that follows lost events must still read.
        f.state.value = f.state.value.copy(pendingTrust = null, connected = false, loading = true)
        f.controller.refresh(full = true); runCurrent()
        assertEquals(listOf<Long?>(null), f.backend.reads)
        f.state.value = f.state.value.copy(connected = true, loading = false)
        f.controller.refresh(minGapMillis = 10_000); runCurrent()          // same instant as the read above: throttled
        assertEquals(1, f.backend.reads.size)
        f.backend.now += 10_001
        f.controller.refresh(minGapMillis = 10_000); runCurrent()          // gap elapsed
        assertEquals(2, f.backend.reads.size)
        f.controller.refresh(minGapMillis = 10_000); runCurrent()
        f.controller.refresh(); runCurrent()                                 // unthrottled
        assertEquals(3, f.backend.reads.size)
    }

    // ---- unread / last seen ----
    @Test fun lastSeenIsLoadedPerProfileAndTheBadgeCountsOnlyNewUrgentAlerts() = runTest {
        val f = Fixture(backgroundScope, state(), seen = Seen(stored = t0 + 15))
        f.backend.read = { listOf(alert("old", 1, t0 + 10), alert("new-warn", 3, t0 + 20), alert("new-info", 5, t0 + 25), alert("new-low", 4, t0 + 30)) }
        f.controller.refresh(); runCurrent()
        assertEquals(t0 + 15, f.state.value.currentAlerts?.lastSeen)
        assertEquals(1, f.state.value.unreadAlertBadge)
    }
    @Test fun markSeenPersistsOnlyTheNewestServerTimestampAndOnlyForward() = runTest {
        val f = Fixture(backgroundScope, state())
        f.backend.read = { listOf(alert("a", 1, t0 + 10), alert("b", 2, t0 + 20)) }
        f.controller.refresh(); runCurrent()
        assertEquals(2, f.state.value.unreadAlertBadge)
        f.controller.markSeen(); runCurrent()
        assertEquals(listOf(profile.id to t0 + 20), f.seen.saves)
        assertEquals(0, f.state.value.unreadAlertBadge)
        f.controller.markSeen(); runCurrent()
        assertEquals(1, f.seen.saves.size)                                    // nothing newer, nothing written
        f.backend.read = { listOf(alert("c", 3, t0 + 40)) }
        f.controller.refresh(); runCurrent()
        assertEquals(1, f.state.value.unreadAlertBadge)
        f.controller.markSeen(); runCurrent()
        assertEquals(t0 + 40, f.seen.stored)
        assertTrue("only timestamps are persisted", f.seen.saves.all { it.second > 0 })
    }
    @Test fun aFailingSeenStoreNeverBreaksReading() = runTest {
        val broken = object : AlertSeenStore {
            override suspend fun lastSeen(profileId: String): Long? = throw java.io.IOException("disk")
            override suspend fun save(profileId: String, millis: Long) = throw java.io.IOException("disk")
        }
        val state = state(); val backend = Backend(); backend.read = { listOf(alert("a", 1)) }
        val controller = AlertsController(backgroundScope, backend, state, broken) { backend.now }
        controller.refresh(); runCurrent()
        assertEquals(1, state.value.unreadAlertBadge)
        controller.markSeen(); runCurrent()
        assertEquals(0, state.value.unreadAlertBadge)                         // visible state still moves; the write was best-effort
    }

    // ---- events ----
    @Test fun anEventThatReportsADeletedMessageRemovesItAtOnceAndThenReadsIncrementally() = runTest {
        val f = Fixture(backgroundScope, state())
        f.backend.read = { listOf(alert("a", at = t0 + 10), alert("b", at = t0 + 20)) }
        f.controller.refresh(); runCurrent()
        f.backend.read = { emptyList() }                                      // get_since cannot show deletions
        f.controller.onMessageEvents(setOf("b"), full = false); runCurrent()
        assertEquals(listOf("a"), f.state.value.alerts?.messages?.map { it.ref })
        assertEquals(t0 + 20 - AlertMerge.OVERLAP_MILLIS, f.backend.reads.last())
    }

    // ---- dismissal ----
    @Test fun onlyReadBackConfirmedRemovalsLeaveTheListAndEverythingIsReportedHonestly() = runTest {
        val f = Fixture(backgroundScope, state())
        val all = listOf(alert("a", at = t0 + 1), alert("b", at = t0 + 2), alert("c", at = t0 + 3))
        f.backend.read = { all }
        f.controller.refresh(); runCurrent()
        f.backend.dismissal = { DismissOutcome(listOf(DismissResult("a", DismissStatus.REMOVED), DismissResult("b", DismissStatus.STILL_PRESENT),
            DismissResult("c", DismissStatus.UNKNOWN))) }
        f.backend.read = { listOf(alert("b", at = t0 + 2), alert("c", at = t0 + 3)) }    // the re-read after the dismissal
        f.controller.dismiss(all); runCurrent()
        assertEquals(listOf(listOf(AlertTarget("a", "u-a"), AlertTarget("b", "u-b"), AlertTarget("c", "u-c"))), f.backend.dismissed)
        assertEquals(listOf("c", "b"), f.state.value.alerts?.messages?.map { it.ref })
        val notice = f.state.value.notice!!
        assertEquals(NoticeKind.WARNING, notice.kind)
        assertTrue(notice.text, notice.text.contains("1 of 3 confirmed removed") && notice.text.contains("2 could not be confirmed"))
        assertFalse(f.state.value.alertDismissBusy)
        assertEquals(listOf<Long?>(null, null), f.backend.reads)             // the list is re-read in full after a dismissal
    }
    @Test fun aConfirmedDismissalReportsSuccessOnlyAfterTheServerNoLongerListsIt() = runTest {
        val f = Fixture(backgroundScope, state())
        f.backend.read = { listOf(alert("a"), alert("b", at = t0 + 1)) }
        f.controller.refresh(); runCurrent()
        f.backend.read = { listOf(alert("b", at = t0 + 1)) }
        f.controller.dismiss(listOf(alert("a"))); runCurrent()
        assertEquals(NoticeKind.INFO, f.state.value.notice?.kind)
        assertTrue(f.state.value.notice!!.text.contains("The server no longer lists it"))
        assertEquals(listOf("b"), f.state.value.alerts?.messages?.map { it.ref })
    }
    @Test fun aPermissionRefusalShowsTheServersReasonAndKeepsEveryAlert() = runTest {
        val f = Fixture(backgroundScope, state())
        val shown = listOf(alert("a", at = t0 + 1), alert("b", at = t0 + 2))
        f.backend.read = { shown }
        f.controller.refresh(); runCurrent()
        f.backend.dismissal = { DismissOutcome(listOf(DismissResult("a", DismissStatus.REFUSED, "RBAC_PERMISSION_DENIED", "message.destroy"),
            DismissResult("b", DismissStatus.NOT_SENT, "RBAC_PERMISSION_DENIED", "message.destroy"))) }
        f.controller.dismiss(shown); runCurrent()
        assertEquals(2, f.state.value.alerts?.messages?.size)
        val notice = f.state.value.notice!!
        assertEquals(NoticeKind.ERROR, notice.kind)
        assertTrue(notice.text, notice.text.contains("RBAC_PERMISSION_DENIED") && notice.text.contains("message.destroy") && notice.text.contains("pool-operator"))
        assertTrue(notice.text.contains("0 of 2 confirmed removed"))
    }
    @Test fun aTransportFailureDuringDismissalAssumesNothingAndReReads() = runTest {
        val f = Fixture(backgroundScope, state())
        f.backend.read = { listOf(alert("a")) }
        f.controller.refresh(); runCurrent()
        f.backend.dismissal = { throw java.net.SocketTimeoutException("password=hunter2") }
        f.controller.dismiss(listOf(alert("a"))); runCurrent()
        assertEquals(listOf("a"), f.state.value.alerts?.messages?.map { it.ref })
        val notice = f.state.value.notice!!
        assertEquals(NoticeKind.ERROR, notice.kind)
        assertTrue(notice.text.contains("Nothing is assumed to have changed") && notice.text.contains("did not answer in time"))
        assertFalse(notice.text.contains("hunter2")); assertFalse(notice.text.contains("refresh"))
        assertEquals(2, f.backend.reads.size)
        assertFalse(f.state.value.alertDismissBusy)
    }
    @Test fun dismissalIsGuardedAgainstDoubleSubmitDisconnectAndOversizedBatches() = runTest {
        val f = Fixture(backgroundScope, state())
        f.backend.read = { (0 until 150).map { alert("m$it", at = t0 + it) } }
        f.controller.refresh(); runCurrent()
        val gate = CompletableDeferred<Unit>()
        f.backend.dismissal = { gate.await(); DismissOutcome(emptyList()) }
        f.controller.dismiss(f.state.value.alerts!!.messages); runCurrent()
        f.controller.dismiss(f.state.value.alerts!!.messages); runCurrent()       // double tap
        assertEquals(1, f.backend.dismissed.size)
        assertEquals(AlertDismissal.MAX_BATCH, f.backend.dismissed.single().size)
        assertTrue(f.state.value.alertDismissBusy)
        gate.complete(Unit); runCurrent()
        f.state.value = f.state.value.copy(connected = false)
        f.controller.dismiss(listOf(alert("x"))); runCurrent()                // disconnected
        f.state.value = f.state.value.copy(connected = true)
        f.controller.dismiss(emptyList()); runCurrent()                       // nothing chosen
        assertEquals(1, f.backend.dismissed.size)
    }

    // ---- B3: notifications follow the inbox ----
    @Test fun openingTheInboxClearsThatProfilesAlertNotifications() = runTest {
        val f = Fixture(backgroundScope, state())
        f.backend.read = { listOf(alert("a", at = t0 + 10)) }
        f.controller.refresh(); runCurrent()
        assertTrue(f.shade.profiles.isEmpty()) // reading in the background of the app is not seeing
        f.controller.markSeen()
        assertEquals(listOf("fixture"), f.shade.profiles)
        // Still cleared when nothing is newer than before, so notifications posted since a view do not linger.
        f.controller.markSeen()
        assertEquals(listOf("fixture", "fixture"), f.shade.profiles)
    }

    @Test fun anEmptyOrNotYetLoadedInboxClearsNothing() = runTest {
        val f = Fixture(backgroundScope, state())
        f.controller.markSeen()
        assertTrue(f.shade.profiles.isEmpty())
        f.backend.read = { emptyList() }
        f.controller.refresh(); runCurrent()
        f.controller.markSeen()
        assertEquals(listOf("fixture"), f.shade.profiles) // an inbox that was loaded and shown, even if empty
    }

    @Test fun dismissingInTheAppCancelsTheNotificationsOfTheConfirmedAlertsOnly() = runTest {
        val f = Fixture(backgroundScope, state())
        f.backend.read = { listOf(alert("a", at = t0 + 10), alert("b", at = t0 + 20)) }
        f.controller.refresh(); runCurrent()
        f.backend.dismissal = { targets -> DismissOutcome(targets.map { DismissResult(it.ref, if (it.ref == "a") DismissStatus.REMOVED else DismissStatus.STILL_PRESENT) }) }
        f.controller.dismiss(f.state.value.alerts!!.messages); runCurrent()
        assertTrue(f.shade.alerts.all { it.first == "fixture" && it.second == setOf("a") })
        assertTrue(f.shade.alerts.isNotEmpty())
    }

    @Test fun anAlertDeletedOnTheServerCancelsItsNotificationToo() = runTest {
        val f = Fixture(backgroundScope, state())
        f.backend.read = { listOf(alert("a", at = t0 + 10)) }
        f.controller.refresh(); runCurrent()
        f.controller.onMessageEvents(setOf("a"), full = false); runCurrent()
        assertEquals(listOf("fixture" to setOf("a")), f.shade.alerts)
    }

    @Test fun aNotifierThatFailsNeverBreaksTheInbox() = runTest {
        val broken = object : AlertNotificationCleaner {
            override fun clearProfile(profileId: String) = throw IllegalStateException("notification service")
            override fun clearAlerts(profileId: String, refs: Collection<String>) = throw IllegalStateException("notification service")
        }
        val s = state(); val backend = Backend(); backend.read = { listOf(alert("a", at = t0 + 10)) }
        val controller = AlertsController(backgroundScope, backend, s, Seen(), broken) { backend.now }
        controller.refresh(); runCurrent()
        controller.markSeen()
        controller.remove(setOf("a"))
        assertTrue(s.value.alerts?.messages.orEmpty().isEmpty())
    }
}
