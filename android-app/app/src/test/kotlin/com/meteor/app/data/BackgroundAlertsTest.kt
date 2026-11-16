package com.meteor.app.data

import org.junit.Assert.*
import org.junit.Test

/** B3 background/summary: preferences, planning, watermark and notification text. Pure JVM. */
class BackgroundAlertsTest {
    private val t0 = 1_800_000_000_000L
    private val minute = 60_000L
    private val profile = Profile("p1", "Lab pool", "https://pool.example.invalid/", "root", "s3cret-password", "a".repeat(64))
    private val other = Profile("p2", "Second", "https://second.example.invalid/", "admin", "another-secret", "b".repeat(64))
    private fun alert(ref: String, at: Long?, priority: Int? = 1, cls: String = "VM", obj: String = "vm-uuid", name: String = "ha_host_failed",
        body: String = "Body mentions 203.0.113.5 and root") = AlertMessage(ref, "u-$ref", name, priority, cls, obj, at, body)
    private fun fetch(vararg messages: AlertMessage, authority: String = "pool-1", names: Map<String, String> = emptyMap(), clock: ServerClock? = same(t0 + 30 * minute)) =
        BackgroundAlertFetch(authority, "Production", messages.toList(), names, clock)
    /** The phone and the server agree on the time. */
    private fun same(at: Long) = ServerClock(at, at)
    private val on = AlertNotifySettings(enabled = true, threshold = NotifyThreshold.HIGH, intervalMinutes = 30, enabledAt = t0)

    // ---- preferences ----
    @Test fun preferencesDefaultOffAndRoundTrip() {
        assertFalse(AlertNotifySettings().enabled)
        assertEquals(AlertNotifySettings(), AlertNotifySettings.decode(null))
        assertEquals(AlertNotifySettings(), AlertNotifySettings.decode("garbage"))
        val value = AlertNotifySettings(true, NotifyThreshold.WARNING, 60, t0)
        assertEquals(value, AlertNotifySettings.decode(AlertNotifySettings.encode(value)))
        assertEquals(AlertNotifySettings.DEFAULT_INTERVAL, AlertNotifySettings.decode("v1;1;2;7;").intervalMinutes)
        assertEquals(NotifyThreshold.HIGH, AlertNotifySettings.decode("v1;1;9;15;").threshold)
        assertThrows(IllegalArgumentException::class.java) { AlertNotifySettings(intervalMinutes = 5) }
    }

    @Test fun enablingStampsNowAndRestartsWhileEditingKeepsTheStamp() {
        val off = AlertNotifySettings()
        val enabled = AlertNotifySettings.change(off, off.copy(enabled = true), t0)
        assertEquals(t0, enabled.enabledAt); assertTrue(AlertNotifySettings.restarts(off, enabled))
        val edited = AlertNotifySettings.change(enabled, enabled.copy(intervalMinutes = 60, enabledAt = null), t0 + minute)
        assertEquals(t0, edited.enabledAt); assertFalse(AlertNotifySettings.restarts(enabled, edited))
        val disabled = AlertNotifySettings.change(edited, edited.copy(enabled = false), t0 + 2 * minute)
        assertNull(disabled.enabledAt); assertTrue(AlertNotifySettings.restarts(edited, disabled))
    }

    @Test fun thresholdsIncludeTheRightPriorities() {
        assertTrue(NotifyThreshold.CRITICAL.includes(AlertSeverity.CRITICAL)); assertFalse(NotifyThreshold.CRITICAL.includes(AlertSeverity.HIGH))
        assertTrue(NotifyThreshold.HIGH.includes(AlertSeverity.HIGH)); assertFalse(NotifyThreshold.HIGH.includes(AlertSeverity.WARNING))
        assertTrue(NotifyThreshold.WARNING.includes(AlertSeverity.WARNING)); assertFalse(NotifyThreshold.WARNING.includes(AlertSeverity.LOW))
        NotifyThreshold.entries.forEach { assertFalse(it.includes(AlertSeverity.UNRATED)) }
    }

    // ---- planning ----
    @Test fun onlyEnabledDueProfilesWithAPinArePlannedLeastRecentFirst() {
        val unpinned = Profile("p3", "Draft", "https://draft.invalid/", "u", "p", "")
        val settings = mapOf("p1" to on, "p2" to on.copy(intervalMinutes = 60), "p3" to on)
        assertEquals(listOf("p1", "p2"), BackgroundAlerts.due(listOf(profile, other, unpinned), settings, emptyMap(), t0).map { it.id })
        // p1 checked 26 minutes ago is due for a 30-minute interval (5-minute flex); p2 checked 26 minutes ago is not due for 60.
        val checked = mapOf("p1" to ok(t0 - 26 * minute), "p2" to ok(t0 - 26 * minute))
        assertEquals(listOf("p1"), BackgroundAlerts.due(listOf(profile, other), settings, checked, t0).map { it.id })
        assertEquals(emptyList<Profile>(), BackgroundAlerts.due(listOf(profile), mapOf("p1" to on.copy(enabled = false)), emptyMap(), t0))
        assertEquals(emptyList<Profile>(), BackgroundAlerts.due(listOf(profile), emptyMap(), emptyMap(), t0))
        // A last check in the future (clock moved back) is due rather than stuck.
        assertEquals(listOf("p1"), BackgroundAlerts.due(listOf(profile), settings, mapOf("p1" to ok(t0 + 60 * minute)), t0).map { it.id })
        // Least recently checked first.
        assertEquals(listOf("p2", "p1"), BackgroundAlerts.due(listOf(profile, other), mapOf("p1" to on, "p2" to on),
            mapOf("p1" to ok(t0 - 40 * minute), "p2" to ok(t0 - 90 * minute)), t0).map { it.id })
    }

    private fun ok(at: Long) = CheckStatus(at, CheckOutcome.NOTHING_NEW, at, 0)
    private fun failed(at: Long, outcome: CheckOutcome, failures: Int, lastOk: Long? = null) = CheckStatus(at, outcome, lastOk, failures)
    private fun dueIds(statuses: Map<String, CheckStatus>, now: Long, interval: Int = 60) =
        BackgroundAlerts.due(listOf(profile), mapOf("p1" to on.copy(intervalMinutes = interval)), statuses, now).map { it.id }

    // ---- retry after a failed check ----
    @Test fun aTransportFailureIsRetriedSoonerThanTheIntervalButOnlyTwice() {
        val first = mapOf("p1" to failed(t0, CheckOutcome.FAILED, 1))
        assertEquals(emptyList<String>(), dueIds(first, t0 + 3 * minute))
        assertEquals(listOf("p1"), dueIds(first, t0 + 4 * minute))          // a 60-minute profile, retried after 4 minutes
        val second = mapOf("p1" to failed(t0, CheckOutcome.TIMED_OUT, 2))
        assertEquals(emptyList<String>(), dueIds(second, t0 + 7 * minute))
        assertEquals(listOf("p1"), dueIds(second, t0 + 8 * minute))
        // After two quick retries the normal interval applies again: no retry storm.
        val third = mapOf("p1" to failed(t0, CheckOutcome.FAILED, 3))
        assertEquals(emptyList<String>(), dueIds(third, t0 + 30 * minute))
        assertEquals(listOf("p1"), dueIds(third, t0 + 55 * minute))
        assertTrue(BackgroundAlerts.retryPending(first["p1"])); assertTrue(BackgroundAlerts.retryPending(second["p1"])); assertFalse(BackgroundAlerts.retryPending(third["p1"]))
    }

    @Test fun rejectedCredentialsChangedCertificatesAndServerErrorsAreNeverRetriedQuickly() {
        listOf(CheckOutcome.AUTHENTICATION_FAILED, CheckOutcome.CERTIFICATE_CHANGED, CheckOutcome.POOL_MEMBER, CheckOutcome.SERVER_ERROR,
            CheckOutcome.SERVER_TIME_UNAVAILABLE, CheckOutcome.NOTIFY_FAILED).forEach { outcome ->
            val statuses = mapOf("p1" to failed(t0, outcome, 1))
            assertFalse("$outcome", BackgroundAlerts.retryPending(statuses["p1"]))
            assertEquals("$outcome", emptyList<String>(), dueIds(statuses, t0 + 10 * minute))
            assertEquals("$outcome", listOf("p1"), dueIds(statuses, t0 + 55 * minute))
        }
    }

    @Test fun anEarlierReleasesBareCheckTimeStillPacesTheNextCheck() {
        val legacy = mapOf("p1" to CheckStatus(t0, null, null))
        assertEquals(emptyList<String>(), dueIds(legacy, t0 + 10 * minute))
        assertEquals(listOf("p1"), dueIds(legacy, t0 + 55 * minute))
    }

    @Test fun statusTracksTheLastAttemptTheLastSuccessAndTheFailuresInARow() {
        val success = CheckStatus.after(null, t0, CheckOutcome.NOTHING_NEW)
        assertEquals(CheckStatus(t0, CheckOutcome.NOTHING_NEW, t0, 0), success)
        val one = CheckStatus.after(success, t0 + minute, CheckOutcome.FAILED)
        val two = CheckStatus.after(one, t0 + 2 * minute, CheckOutcome.TIMED_OUT)
        assertEquals(CheckStatus(t0 + 2 * minute, CheckOutcome.TIMED_OUT, t0, 2), two)
        assertEquals(CheckStatus(t0 + 3 * minute, CheckOutcome.NOTIFIED, t0 + 3 * minute, 0), CheckStatus.after(two, t0 + 3 * minute, CheckOutcome.NOTIFIED))
        assertEquals(two, CheckStatus.decode(CheckStatus.encode(two)))
        assertNull(CheckStatus.decode("garbage")); assertNull(CheckStatus.decode(null))
        assertEquals(RunNote(t0, RunProblem.VAULT_UNAVAILABLE), RunNote.decode(RunNote.encode(RunNote(t0, RunProblem.VAULT_UNAVAILABLE))))
        assertNull(RunNote.decode("v1;1;NOT_A_PROBLEM"))
    }

    @Test fun planningIsBoundedAndThePeriodIsTheShortestEnabledInterval() {
        val many = (1..25).map { profile.copy(id = "id$it", endpoint = "https://h$it.invalid/") }
        assertEquals(BackgroundAlerts.MAX_PROFILES_PER_RUN, BackgroundAlerts.due(many, many.associate { it.id to on }, emptyMap(), t0).size)
        assertEquals(15, BackgroundAlerts.periodMinutes(listOf(on.copy(intervalMinutes = 60), on.copy(intervalMinutes = 15), AlertNotifySettings(intervalMinutes = 15))))
        assertEquals(60, BackgroundAlerts.periodMinutes(listOf(on.copy(intervalMinutes = 60), AlertNotifySettings(intervalMinutes = 15))))
        assertNull(BackgroundAlerts.periodMinutes(listOf(AlertNotifySettings())))
    }

    // ---- watermark and duplicate suppression ----
    @Test fun firstCheckOnlyNotifiesAlertsFromTheEnableTime() {
        assertEquals(t0 - BackgroundAlerts.OVERLAP_MILLIS, BackgroundAlerts.since(on, null, "pool-1", same(t0 + 30 * minute)))
        val decision = BackgroundAlerts.decide(profile, on, null, fetch(alert("old", t0 - minute), alert("new", t0 + minute), alert("exact", t0)))
        assertEquals(listOf("new", "exact"), decision.notify.map { it.ref })
        assertEquals(t0 + minute, decision.watermark.newest)
        assertEquals(profile.origin(), decision.watermark.origin); assertEquals("pool-1", decision.watermark.authority)
    }

    @Test fun theSameAlertIsNeverNotifiedTwiceAcrossOverlappingReads() {
        val first = BackgroundAlerts.decide(profile, on, null, fetch(alert("a", t0 + minute), alert("b", t0 + 2 * minute), clock = same(t0 + 3 * minute)))
        assertEquals(setOf("a", "b"), first.notify.map { it.ref }.toSet())
        val since = BackgroundAlerts.since(on, first.watermark, "pool-1", same(t0 + 30 * minute))
        assertEquals(t0 + 2 * minute - BackgroundAlerts.OVERLAP_MILLIS, since)
        // The overlap re-reads a and b; c arrived out of order with an older timestamp inside the window; d is new.
        val second = BackgroundAlerts.decide(profile, on, first.watermark,
            fetch(alert("a", t0 + minute), alert("b", t0 + 2 * minute), alert("c", t0 + minute + 1), alert("d", t0 + 20 * minute)))
        assertEquals(listOf("d", "c"), second.notify.map { it.ref })
        val third = BackgroundAlerts.decide(profile, on, second.watermark, fetch(alert("d", t0 + 20 * minute), alert("c", t0 + minute + 1), clock = same(t0 + 60 * minute)))
        assertTrue(third.notify.isEmpty())
        assertEquals(t0 + 20 * minute, third.watermark.newest)
    }

    @Test fun belowThresholdAlertsAreHandledSoRaisingTheThresholdNeverReplaysThem() {
        val first = BackgroundAlerts.decide(profile, on, null, fetch(alert("warn", t0 + minute, priority = 3), alert("crit", t0 + minute, priority = 1)))
        assertEquals(listOf("crit"), first.notify.map { it.ref })
        assertEquals(2, first.handled)
        val wider = on.copy(threshold = NotifyThreshold.WARNING)
        val second = BackgroundAlerts.decide(profile, wider, first.watermark, fetch(alert("warn", t0 + minute, priority = 3)))
        assertTrue(second.notify.isEmpty())
    }

    @Test fun untimedAlertsAreNeverNotifiedAndTheWatermarkNeverMovesBack() {
        val first = BackgroundAlerts.decide(profile, on, null, fetch(alert("a", t0 + 10 * minute)))
        val second = BackgroundAlerts.decide(profile, on, first.watermark, fetch(alert("untimed", null), alert("late", t0 + 9 * minute)))
        assertEquals(listOf("late"), second.notify.map { it.ref })
        assertEquals(t0 + 10 * minute, second.watermark.newest)
    }

    @Test fun theWatermarkFollowsThePoolNotTheEndpointOrPin() {
        val first = BackgroundAlerts.decide(profile, on, null, fetch(alert("a", t0 + 10 * minute))).watermark
        // A re-trusted certificate or a followed master reaches the same pool: nothing is replayed.
        assertEquals(t0 + 10 * minute - BackgroundAlerts.OVERLAP_MILLIS, BackgroundAlerts.since(on, first, "pool-1", same(t0 + 60 * minute)))
        val repinned = profile.copy(certificateSha256 = "c".repeat(64), endpoint = "https://master.example.invalid/")
        val sameFetch = BackgroundAlerts.decide(repinned, on, first, fetch(alert("a", t0 + 10 * minute), alert("b", t0 + 30 * minute), clock = same(t0 + 60 * minute)))
        assertEquals(listOf("b"), sameFetch.notify.map { it.ref })
        // A different pool behind the same profile starts from about now; its history is not replayed.
        val now = t0 + 120 * minute
        assertEquals(now - 2 * BackgroundAlerts.OVERLAP_MILLIS, BackgroundAlerts.since(on, first, "pool-2", same(now)))
        val other = BackgroundAlerts.decide(profile, on, first,
            fetch(alert("old", t0 + 20 * minute), alert("fresh", now - minute), authority = "pool-2", clock = same(now)))
        assertEquals(listOf("fresh"), other.notify.map { it.ref }); assertEquals("pool-2", other.watermark.authority)
    }

    // ---- the server's clock, not the phone's ----
    private val hour = 60 * minute

    @Test fun aPhoneThatRunsAheadOfTheServerNoLongerHidesNewAlerts() {
        // Switch turned on at phone time t0; the phone is one hour ahead, so the server said t0 - 1h at that moment.
        val clock = ServerClock(serverMillis = t0 - 30 * minute, phoneMillis = t0 + 30 * minute)
        val enabledOnServer = t0 - hour
        assertEquals(enabledOnServer - BackgroundAlerts.OVERLAP_MILLIS, BackgroundAlerts.since(on, null, "pool-1", clock))
        val decision = BackgroundAlerts.decide(profile, on, null, fetch(
            alert("before", enabledOnServer - minute), alert("after", enabledOnServer + 10 * minute), clock = clock))
        assertEquals(listOf("after"), decision.notify.map { it.ref }) // the old code compared with the phone's t0 and dropped it
        // The next check, half an hour later, continues from server time and sees the next alert.
        val later = ServerClock(t0, t0 + hour)
        val second = BackgroundAlerts.decide(profile, on, decision.watermark, fetch(alert("after", enabledOnServer + 10 * minute),
            alert("next", t0 - 10 * minute), clock = later))
        assertEquals(listOf("next"), second.notify.map { it.ref })
    }

    @Test fun aPhoneThatRunsBehindTheServerNoLongerNotifiesAlertsFromBeforeTheSwitch() {
        // The phone is one hour behind: phone time t0 was server time t0 + 1h.
        val clock = ServerClock(serverMillis = t0 + 90 * minute, phoneMillis = t0 + 30 * minute)
        val enabledOnServer = t0 + hour
        val decision = BackgroundAlerts.decide(profile, on, null, fetch(
            alert("older", t0 + 30 * minute), alert("beforeSwitch", enabledOnServer - minute), alert("afterSwitch", enabledOnServer + minute), clock = clock))
        assertEquals(listOf("afterSwitch"), decision.notify.map { it.ref }) // the old code compared with phone time and notified all three
    }

    @Test fun anEmptyPoolStartsFromTheServersMomentOfEnablingInBothDirections() {
        listOf(-hour, 0L, hour).forEach { skew ->
            val clock = ServerClock(serverMillis = t0 + 30 * minute + skew, phoneMillis = t0 + 30 * minute)
            val first = BackgroundAlerts.decide(profile, on, null, fetch(clock = clock))
            assertEquals("skew $skew", t0 + skew, first.watermark.newest) // the enable moment on the server, not the phone's t0
            assertTrue(first.watermark.recent.isEmpty()); assertTrue(first.notify.isEmpty())
            // An alert raised after the switch is notified at the next check, whatever the phone's clock says.
            val next = ServerClock(serverMillis = t0 + 60 * minute + skew, phoneMillis = t0 + 60 * minute)
            val second = BackgroundAlerts.decide(profile, on, first.watermark, fetch(alert("n", t0 + skew + 10 * minute), clock = next))
            assertEquals("skew $skew", listOf("n"), second.notify.map { it.ref })
        }
    }

    @Test fun anEnableTimeInTheFutureOfTheServerIsHeldAtTheServersNow() {
        // The phone clock was set back after the switch: the enable stamp is later than the phone's now.
        val clock = ServerClock(serverMillis = t0 + 10 * minute, phoneMillis = t0 + 10 * minute)
        val late = on.copy(enabledAt = t0 + 5 * hour)
        assertEquals(t0 + 10 * minute - BackgroundAlerts.OVERLAP_MILLIS, BackgroundAlerts.since(late, null, "pool-1", clock))
        assertEquals(t0 + 10 * minute, BackgroundAlerts.decide(profile, late, null, fetch(clock = clock)).watermark.newest)
    }

    @Test fun anAlertFromJustBeforeTheSwitchIsNotNotifiedByTheNextOverlapRead() {
        val clock = same(t0 + 30 * minute)
        val first = BackgroundAlerts.decide(profile, on, null, fetch(alert("justBefore", t0 - 3 * minute), clock = clock))
        assertTrue(first.notify.isEmpty())
        // The next read starts five minutes before the watermark and returns it again: it is already handled.
        val second = BackgroundAlerts.decide(profile, on, first.watermark, fetch(alert("justBefore", t0 - 3 * minute), alert("fresh", t0 + 40 * minute),
            clock = same(t0 + 60 * minute)))
        assertEquals(listOf("fresh"), second.notify.map { it.ref })
    }

    @Test fun theFirstCheckAndAnotherPoolNeedTheServersClockButAnExistingWatermarkDoesNot() {
        assertNull(BackgroundAlerts.since(on, null, "pool-1", null))
        assertThrows(ServerTimeUnavailable::class.java) { BackgroundAlerts.decide(profile, on, null, fetch(alert("a", t0 + minute), clock = null)) }
        val mark = AlertWatermark(profile.origin(), "pool-1", t0 + 10 * minute, mapOf("a" to t0 + 10 * minute))
        assertNull(BackgroundAlerts.since(on, mark, "pool-2", null))
        assertEquals(t0 + 10 * minute - BackgroundAlerts.OVERLAP_MILLIS, BackgroundAlerts.since(on, mark, "pool-1", null))
        val decision = BackgroundAlerts.decide(profile, on, mark, fetch(alert("a", t0 + 10 * minute), alert("b", t0 + 20 * minute), clock = null))
        assertEquals(listOf("b"), decision.notify.map { it.ref })
    }

    // ---- watermarks saved by the current release ----
    @Test fun aSavedWatermarkAheadOfTheServerIsPulledBackSoNoAlertIsHiddenAfterTheUpgrade() {
        // The current release saved the phone's enable time (t0) as the watermark of a pool with no messages; the phone is one hour ahead.
        val saved = AlertWatermark(profile.origin(), "pool-1", t0)
        val clock = ServerClock(serverMillis = t0 - 30 * minute, phoneMillis = t0 + 30 * minute)
        assertEquals(t0 - 30 * minute - BackgroundAlerts.OVERLAP_MILLIS, BackgroundAlerts.since(on, saved, "pool-1", clock))
        val quiet = BackgroundAlerts.decide(profile, on, saved, fetch(clock = clock))
        assertTrue(quiet.rewound); assertEquals(t0 - 30 * minute, quiet.watermark.newest)
        val alerted = BackgroundAlerts.decide(profile, on, saved, fetch(alert("hidden", t0 - 25 * minute), clock = clock))
        assertEquals(listOf("hidden"), alerted.notify.map { it.ref }) // with the old watermark this stayed hidden for an hour
        assertEquals(t0 - 25 * minute, alerted.watermark.newest)
    }

    @Test fun aSavedWatermarkFromRealMessagesIsKeptAndCausesNoBurstAfterTheUpgrade() {
        val saved = AlertWatermark(profile.origin(), "pool-1", t0 + 20 * minute, mapOf("a" to t0 + 10 * minute, "b" to t0 + 20 * minute))
        val clock = same(t0 + 40 * minute)
        assertEquals(t0 + 20 * minute - BackgroundAlerts.OVERLAP_MILLIS, BackgroundAlerts.since(on, saved, "pool-1", clock))
        val decision = BackgroundAlerts.decide(profile, on, saved, fetch(alert("a", t0 + 10 * minute), alert("b", t0 + 20 * minute),
            alert("old", t0 + 5 * minute), clock = clock))
        assertTrue(decision.notify.isEmpty()); assertFalse(decision.rewound)
        assertEquals(t0 + 20 * minute, decision.watermark.newest)
    }

    @Test fun aSavedWatermarkBehindTheServerIsSimplyContinuedFrom() {
        // Saved from the phone's enable time while the phone ran behind: older than the server's clock, so alerts since then are read once.
        val saved = AlertWatermark(profile.origin(), "pool-1", t0)
        val clock = ServerClock(serverMillis = t0 + 2 * hour, phoneMillis = t0 + hour)
        val decision = BackgroundAlerts.decide(profile, on, saved, fetch(alert("x", t0 + 30 * minute), clock = clock))
        assertEquals(listOf("x"), decision.notify.map { it.ref })
        assertFalse(decision.rewound)
    }

    @Test fun alertsTheAppAlreadyShowedAreHandledButNotNotified() {
        val clock = same(t0 + 40 * minute)
        val saved = AlertWatermark(profile.origin(), "pool-1", t0 + 5 * minute)
        val decision = BackgroundAlerts.decide(profile, on, saved,
            fetch(alert("read", t0 + 10 * minute), alert("atSeen", t0 + 20 * minute), alert("unread", t0 + 30 * minute), clock = clock), seen = t0 + 20 * minute)
        assertEquals(listOf("unread"), decision.notify.map { it.ref })
        assertEquals(3, decision.handled)
        assertEquals(t0 + 30 * minute, decision.watermark.newest)
        // Nothing seen yet: everything fresh is notified.
        assertEquals(3, BackgroundAlerts.decide(profile, on, saved, fetch(alert("read", t0 + 10 * minute), alert("atSeen", t0 + 20 * minute),
            alert("unread", t0 + 30 * minute), clock = clock), seen = null).notify.size)
    }

    @Test fun recentReferencesStayBoundedAndInsideTheWindow() {
        val many = (0 until 400).map { alert("r$it", t0 + it * 1000L, priority = 5) }.toTypedArray()
        val decision = BackgroundAlerts.decide(profile, on, null, fetch(*many, clock = same(t0 + 10 * minute)))
        assertEquals(BackgroundAlerts.MAX_RECENT, decision.watermark.recent.size)
        assertTrue(decision.watermark.recent.values.all { it > decision.watermark.newest - BackgroundAlerts.OVERLAP_MILLIS })
        assertTrue("r399" in decision.watermark.recent)
    }

    @Test fun watermarkCodecRoundTripsAndRejectsDamage() {
        val mark = AlertWatermark(profile.origin(), "pool-1", t0, mapOf("OpaqueRef:abc-1" to t0, "ref.2" to t0 - 1))
        assertEquals(mark, AlertWatermark.decode(AlertWatermark.encode(mark)))
        assertNull(AlertWatermark.decode(null)); assertNull(AlertWatermark.decode("v1\nnot-a-hash\npool\n1"))
        assertNull(AlertWatermark.decode(AlertWatermark.encode(mark.copy(recent = mapOf("bad ref with spaces" to t0)))))
        assertFalse(mark.toString().contains(profile.origin()))
    }

    // ---- notification content ----
    @Test fun notificationTextNamesPoolObjectAndAlertButNeverSecretsOrBodies() {
        val f = fetch(alert("a", t0, priority = 1, cls = "VM", obj = "vm-uuid"), names = mapOf("vm:vm-uuid" to "web-01"))
        val batch = requireNotNull(AlertNotificationContent.batch(profile, f, f.messages))
        val item = batch.items.single()
        assertEquals("Critical: HA host failed", item.title)
        assertEquals("Production · VM web-01", item.text)
        assertNull(batch.summary)
        val all = listOf(item.title, item.text).joinToString(" ")
        listOf(profile.password, profile.username, "pool.example.invalid", "203.0.113.5", "Body", profile.certificateSha256).forEach {
            assertFalse("leaked $it", all.contains(it))
        }
    }

    @Test fun unknownObjectsAndPoolsFallBackWithoutIdsAndBlankPoolUsesTheProfileLabel() {
        val f = BackgroundAlertFetch("pool-1", "", listOf(alert("a", t0, cls = "SR", obj = "sr-uuid"), alert("b", t0, cls = "pool", obj = "pool-1")))
        val batch = requireNotNull(AlertNotificationContent.batch(profile, f, f.messages))
        assertEquals(listOf("Lab pool · Storage", "Lab pool · Pool Lab pool"), batch.items.map { it.text })
        assertTrue(batch.items.none { it.text.contains("sr-uuid") })
    }

    @Test fun severalAlertsAreGroupedUnderOneSummaryWithAMoreCount() {
        val messages = (1..8).map { alert("a$it", t0 + it, priority = if (it == 8) 1 else 2) }
        val batch = requireNotNull(AlertNotificationContent.batch(profile, fetch(*messages.toTypedArray()), messages))
        assertEquals(BackgroundAlerts.MAX_INDIVIDUAL, batch.items.size)
        assertEquals("a8", batch.items.first().ref) // most urgent first
        val summary = requireNotNull(batch.summary)
        assertEquals("8 new alerts · Production", summary.title)
        assertEquals(8, summary.count); assertEquals(AlertSeverity.CRITICAL, summary.severity)
        assertEquals("+3 more in Meteor", summary.lines.last())
        assertEquals("p1", batch.profileId)
        assertNull(AlertNotificationContent.batch(profile, fetch(), emptyList()))
    }

    // ---- what is actually posted ----
    private fun posted(ref: String, priority: Int, pool: String = "Production") =
        AlertNotificationText("${AlertSeverity.of(priority).label}: $ref", "$pool · Host h", AlertSeverity.of(priority), ref)

    @Test fun theSummaryCountsWhatIsPostedNotJustTheCurrentBatch() {
        val earlier = listOf(posted("e1", 2), posted("e2", 3))
        val batch = listOf(posted("n1", 1))
        val merged = AlertNotificationContent.merge(earlier, batch)
        assertEquals(listOf("n1", "e1", "e2"), merged.kept.map { it.ref }); assertTrue(merged.dropped.isEmpty())
        val summary = requireNotNull(AlertNotificationContent.summary("Production", merged.kept, overflow = 0))
        assertEquals("3 new alerts · Production", summary.title); assertEquals(3, summary.count); assertEquals(AlertSeverity.CRITICAL, summary.severity)
        // The summary of this batch alone would have said one alert and had no summary at all.
        assertNull(AlertNotificationContent.summary("Production", batch, 0))
    }

    @Test fun anAlertPostedAgainReplacesItsNotificationAndTheShadeIsCapped() {
        val earlier = (1..8).map { posted("e$it", 3) }
        val merged = AlertNotificationContent.merge(earlier, listOf(posted("e2", 1), posted("n1", 2), posted("n2", 2), posted("n3", 2)), max = 10)
        assertEquals(1, merged.kept.count { it.ref == "e2" }); assertEquals(AlertSeverity.CRITICAL, merged.kept.first { it.ref == "e2" }.severity)
        assertEquals(10, merged.kept.size)
        assertEquals(listOf("e2", "n1", "n2", "n3"), merged.kept.take(4).map { it.ref }) // most urgent first
        assertEquals(listOf("e8"), merged.dropped.map { it.ref }) // the least urgent and oldest goes, to be counted as overflow
        val summary = requireNotNull(AlertNotificationContent.summary("P", merged.kept, merged.dropped.size))
        assertEquals(11, summary.count); assertEquals("+1 more in Meteor", summary.lines.last())
    }

    @Test fun oneRemainingAlertNeedsNoSummary() {
        assertNull(AlertNotificationContent.summary("P", listOf(posted("a", 1)), 0))
        assertNotNull(AlertNotificationContent.summary("P", listOf(posted("a", 1)), 1))
        assertNull(AlertNotificationContent.summary("P", emptyList(), 0))
    }

    @Test fun failingChecksGetOneAttentionTextWithoutSecrets() {
        val text = AlertNotificationContent.attention(AttentionKind.CHECKS_FAILING, profile)
        assertTrue(text.title.contains("Lab pool")); assertTrue(text.text.contains("failed several times"))
        listOf(profile.password, profile.username, "pool.example.invalid", profile.certificateSha256).forEach { assertFalse((text.title + text.text).contains(it)) }
    }

    // ---- plain words in Settings ----
    private val utc = java.time.ZoneOffset.UTC
    private val us = java.util.Locale.US
    private fun at(value: Long, sameDay: Boolean = true) =
        (if (sameDay) java.time.format.DateTimeFormatter.ofLocalizedTime(java.time.format.FormatStyle.SHORT)
         else java.time.format.DateTimeFormatter.ofLocalizedDateTime(java.time.format.FormatStyle.SHORT)).withLocale(us).withZone(utc).format(java.time.Instant.ofEpochMilli(value))
    private fun lines(status: CheckStatus?, allowed: Boolean = true, now: Long = t0 + 5 * minute) = BackgroundCheckText.lines(status, allowed, now, utc, us)

    @Test fun settingsSaysWhatTheLastCheckCameTo() {
        val last = t0 + minute
        assertEquals(listOf("Not checked yet. Android runs the first check when the network is up."), lines(null))
        assertEquals(listOf("Last checked ${at(last)}: nothing new."), lines(CheckStatus(last, CheckOutcome.NOTHING_NEW, last, 0)))
        assertEquals(listOf("Last checked ${at(last)}: new alerts were shown as notifications."), lines(CheckStatus(last, CheckOutcome.NOTIFIED, last, 0)))
        assertEquals(listOf("Last checked ${at(last)}."), lines(CheckStatus(last, null, null, 0)))
    }

    @Test fun settingsSaysWhyACheckFailedAndWhenTheLastOneWorked() {
        val worked = t0 - 3 * hour
        assertEquals(listOf("Last check failed at ${at(t0)}: could not reach the server. Last successful check ${at(worked)}."),
            lines(CheckStatus(t0, CheckOutcome.FAILED, worked, 1), now = t0))
        assertEquals(listOf("Last check failed at ${at(t0)}: the server took too long to answer. No check has worked yet. Failed 3 times in a row."),
            lines(CheckStatus(t0, CheckOutcome.TIMED_OUT, null, 3), now = t0))
        CheckOutcome.entries.filter { !it.success && it != CheckOutcome.SKIPPED_BUDGET }.forEach { outcome ->
            val text = lines(CheckStatus(t0, outcome, worked, 1), now = t0).single()
            assertTrue("$outcome: $text", text.startsWith("Last check failed at") && !text.contains("no reason recorded"))
        }
    }

    @Test fun anEarlierDayIsShownWithItsDateAndBlockedNotificationsComeFirst() {
        val yesterday = t0 - 30 * hour
        assertEquals("Last checked ${at(yesterday, sameDay = false)}: nothing new.", lines(CheckStatus(yesterday, CheckOutcome.NOTHING_NEW, yesterday, 0)).single())
        val blocked = lines(CheckStatus(t0, CheckOutcome.NOTHING_NEW, t0, 0), allowed = false, now = t0)
        assertEquals("Notifications are turned off for Meteor in Android settings. Meteor will not check in the background until they are allowed.", blocked.first())
        assertEquals(2, blocked.size)
    }

    @Test fun aRunThatCouldNotCheckAnythingIsExplained() {
        assertNull(BackgroundCheckText.runLine(null, t0, utc, us)); assertNull(BackgroundCheckText.runLine(RunNote(t0, RunProblem.OK), t0, utc, us))
        assertEquals("The last background run (${at(t0)}) could not open the saved connections, so nothing was checked.",
            BackgroundCheckText.runLine(RunNote(t0, RunProblem.VAULT_UNAVAILABLE), t0, utc, us))
        assertTrue(BackgroundCheckText.runLine(RunNote(t0, RunProblem.NOTIFICATIONS_BLOCKED), t0, utc, us)!!.contains("notifications are turned off"))
        assertTrue(BackgroundCheckText.runLine(RunNote(t0, RunProblem.STOPPED), t0, utc, us)!!.contains("unexpected error"))
    }

    // ---- a notification tap is applied once ----
    @Test fun aTapIsAppliedOncePerTapAcrossRecreation() {
        // A new launch applies its intent, with or without a stamp (notifications posted by an earlier release have none).
        assertTrue(NotificationTaps.applyOnCreate(t0, restored = false, lastApplied = null))
        assertTrue(NotificationTaps.applyOnCreate(null, restored = false, lastApplied = null))
        // A rotation re-creates the activity with the spent launch intent: not applied again.
        assertFalse(NotificationTaps.applyOnCreate(t0, restored = true, lastApplied = t0))
        // A restored activity that arrives with a different tap applies it: it is not lost.
        assertTrue(NotificationTaps.applyOnCreate(t0 + minute, restored = true, lastApplied = t0))
        assertTrue(NotificationTaps.applyOnCreate(t0, restored = true, lastApplied = null))
        // A restored activity whose intent has no stamp keeps the old behaviour: the stale intent is not replayed.
        assertFalse(NotificationTaps.applyOnCreate(null, restored = true, lastApplied = t0))
    }

    @Test fun attentionTextExplainsWithoutSecrets() {
        AttentionKind.entries.forEach { kind ->
            val text = AlertNotificationContent.attention(kind, profile)
            assertTrue(text.title.contains("Lab pool"))
            listOf(profile.password, profile.username, "pool.example.invalid", profile.certificateSha256).forEach { assertFalse((text.title + text.text).contains(it)) }
        }
        assertNotEquals(BackgroundAlerts.attentionMarker(AttentionKind.CERTIFICATE_CHANGED, profile),
            BackgroundAlerts.attentionMarker(AttentionKind.CERTIFICATE_CHANGED, profile.copy(certificateSha256 = "c".repeat(64))))
    }
}
