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
    private fun fetch(vararg messages: AlertMessage, authority: String = "pool-1", names: Map<String, String> = emptyMap()) =
        BackgroundAlertFetch(authority, "Production", messages.toList(), names)
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
        val checked = mapOf("p1" to t0 - 26 * minute, "p2" to t0 - 26 * minute)
        assertEquals(listOf("p1"), BackgroundAlerts.due(listOf(profile, other), settings, checked, t0).map { it.id })
        assertEquals(emptyList<Profile>(), BackgroundAlerts.due(listOf(profile), mapOf("p1" to on.copy(enabled = false)), emptyMap(), t0))
        assertEquals(emptyList<Profile>(), BackgroundAlerts.due(listOf(profile), emptyMap(), emptyMap(), t0))
        // A last check in the future (clock moved back) is due rather than stuck.
        assertEquals(listOf("p1"), BackgroundAlerts.due(listOf(profile), settings, mapOf("p1" to t0 + 60 * minute), t0).map { it.id })
        // Least recently checked first.
        assertEquals(listOf("p2", "p1"), BackgroundAlerts.due(listOf(profile, other), mapOf("p1" to on, "p2" to on),
            mapOf("p1" to t0 - 40 * minute, "p2" to t0 - 90 * minute), t0).map { it.id })
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
        assertEquals(t0 - BackgroundAlerts.OVERLAP_MILLIS, BackgroundAlerts.since(profile, on, null, t0 + 30 * minute))
        val decision = BackgroundAlerts.decide(profile, on, null, fetch(alert("old", t0 - minute), alert("new", t0 + minute), alert("exact", t0)), t0 + 30 * minute)
        assertEquals(listOf("new", "exact"), decision.notify.map { it.ref })
        assertEquals(t0 + minute, decision.watermark.newest)
        assertEquals(profile.origin(), decision.watermark.origin); assertEquals("pool-1", decision.watermark.authority)
    }

    @Test fun theSameAlertIsNeverNotifiedTwiceAcrossOverlappingReads() {
        val first = BackgroundAlerts.decide(profile, on, null, fetch(alert("a", t0 + minute), alert("b", t0 + 2 * minute)), t0 + 3 * minute)
        assertEquals(setOf("a", "b"), first.notify.map { it.ref }.toSet())
        val since = BackgroundAlerts.since(profile, on, first.watermark, t0 + 30 * minute)
        assertEquals(t0 + 2 * minute - BackgroundAlerts.OVERLAP_MILLIS, since)
        // The overlap re-reads a and b; c arrived out of order with an older timestamp inside the window; d is new.
        val second = BackgroundAlerts.decide(profile, on, first.watermark,
            fetch(alert("a", t0 + minute), alert("b", t0 + 2 * minute), alert("c", t0 + minute + 1), alert("d", t0 + 20 * minute)), t0 + 30 * minute)
        assertEquals(listOf("d", "c"), second.notify.map { it.ref })
        val third = BackgroundAlerts.decide(profile, on, second.watermark, fetch(alert("d", t0 + 20 * minute), alert("c", t0 + minute + 1)), t0 + 60 * minute)
        assertTrue(third.notify.isEmpty())
        assertEquals(t0 + 20 * minute, third.watermark.newest)
    }

    @Test fun belowThresholdAlertsAreHandledSoRaisingTheThresholdNeverReplaysThem() {
        val first = BackgroundAlerts.decide(profile, on, null, fetch(alert("warn", t0 + minute, priority = 3), alert("crit", t0 + minute, priority = 1)), t0 + 2 * minute)
        assertEquals(listOf("crit"), first.notify.map { it.ref })
        assertEquals(2, first.handled)
        val wider = on.copy(threshold = NotifyThreshold.WARNING)
        val second = BackgroundAlerts.decide(profile, wider, first.watermark, fetch(alert("warn", t0 + minute, priority = 3)), t0 + 30 * minute)
        assertTrue(second.notify.isEmpty())
    }

    @Test fun untimedAlertsAreNeverNotifiedAndTheWatermarkNeverMovesBack() {
        val first = BackgroundAlerts.decide(profile, on, null, fetch(alert("a", t0 + 10 * minute)), t0 + 11 * minute)
        val second = BackgroundAlerts.decide(profile, on, first.watermark, fetch(alert("untimed", null), alert("late", t0 + 9 * minute)), t0 + 40 * minute)
        assertEquals(listOf("late"), second.notify.map { it.ref })
        assertEquals(t0 + 10 * minute, second.watermark.newest)
    }

    @Test fun theWatermarkFollowsThePoolNotTheEndpointOrPin() {
        val first = BackgroundAlerts.decide(profile, on, null, fetch(alert("a", t0 + 10 * minute)), t0 + 11 * minute).watermark
        // A re-trusted certificate or a followed master reaches the same pool: nothing is replayed.
        val repinned = profile.copy(certificateSha256 = "c".repeat(64), endpoint = "https://master.example.invalid/")
        assertEquals(t0 + 10 * minute - BackgroundAlerts.OVERLAP_MILLIS, BackgroundAlerts.since(repinned, on, first, t0 + 60 * minute))
        val same = BackgroundAlerts.decide(repinned, on, first, fetch(alert("a", t0 + 10 * minute), alert("b", t0 + 30 * minute)), t0 + 60 * minute)
        assertEquals(listOf("b"), same.notify.map { it.ref })
        // A different pool behind the same profile starts from about now; its history is not replayed.
        val now = t0 + 120 * minute
        val other = BackgroundAlerts.decide(profile, on, first,
            fetch(alert("old", t0 + 20 * minute), alert("fresh", now - minute), authority = "pool-2"), now)
        assertEquals(listOf("fresh"), other.notify.map { it.ref }); assertEquals("pool-2", other.watermark.authority)
    }

    @Test fun recentReferencesStayBoundedAndInsideTheWindow() {
        val many = (0 until 400).map { alert("r$it", t0 + it * 1000L, priority = 5) }.toTypedArray()
        val decision = BackgroundAlerts.decide(profile, on, null, fetch(*many), t0 + 10 * minute)
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
