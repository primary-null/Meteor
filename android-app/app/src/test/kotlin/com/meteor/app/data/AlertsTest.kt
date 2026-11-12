package com.meteor.app.data

import org.junit.Assert.*
import org.junit.Test

class AlertsTest {
    private val t0 = 1_800_000_000_000L
    private fun alert(ref: String, priority: Int? = 3, at: Long? = t0, name: String = "ha_host_failed", cls: String = "VM",
        obj: String = "vm-1", body: String = "body") = AlertMessage(ref, "uuid-$ref", name, priority, cls, obj, at, body)
    private fun fetch(vararg messages: AlertMessage, full: Boolean = false, at: Long = t0 + 1000, truncated: Boolean = false) =
        AlertFetch("pool", at, messages.toList(), full, truncated)
    private val inventory = Inventory("pool", "Fixture pool",
        listOf(Host("host-1", "Host One", true, 1, 1)),
        listOf(Guest("vm-1", "Web VM", "Running", "pool", "host-1", 2, 1024)), 0, 0, 1,
        listOf(StorageRepository("sr-1", "Local SR", "lvm", "user", 10, 5, "")))

    // ---- priority mapping ----
    @Test fun priorityMapsToFiveNamedLevelsAndAnythingElseIsUnrated() {
        assertEquals(listOf("Critical", "High", "Warning", "Low", "Info"), (1..5).map { AlertSeverity.of(it).label })
        for (bad in listOf(null, 0, 6, -1, 99)) assertEquals(AlertSeverity.UNRATED, AlertSeverity.of(bad))
        assertEquals(setOf(AlertSeverity.CRITICAL, AlertSeverity.HIGH, AlertSeverity.WARNING), AlertSeverity.entries.filter { it.urgent }.toSet())
    }
    @Test fun mapperReadsXapiRecordsAndBoundsHostileValues() {
        val records = mapOf(
            "OpaqueRef:a" to mapOf("uuid" to "u-a", "name" to "VM_STARTED", "priority" to 5L, "cls" to "VM", "obj_uuid" to "vm-1",
                "timestamp" to "20261112T10:00:00Z", "body" to "x".repeat(5000)),
            "OpaqueRef:b" to mapOf("uuid" to "u-b", "name" to "ALARM", "priority" to "1", "cls" to "Host", "obj_uuid" to "h", "timestamp" to "20261112T11:00:00Z"),
            "OpaqueRef:c" to mapOf("priority" to "17", "timestamp" to "garbage"),
        )
        val parsed = AlertMapper.parse(records)
        assertEquals(listOf("OpaqueRef:b", "OpaqueRef:a", "OpaqueRef:c"), parsed.map { it.ref })
        assertEquals(AlertSeverity.CRITICAL, parsed[0].severity)
        assertEquals(4096, parsed[1].body.length)
        assertNull(parsed[2].priority); assertNull(parsed[2].timestamp)
        assertEquals(AlertSeverity.UNRATED, parsed[2].severity)
        assertTrue(parsed[0].timestamp!! > parsed[1].timestamp!!)
    }
    @Test fun alertToStringNeverPrintsDetails() {
        assertFalse(alert("a", body = "secret body").toString().contains("secret"))
    }

    // ---- names ----
    @Test fun namesAreHumanizedWithAcronyms() {
        assertEquals("VM started", AlertNames.humanize("VM_STARTED"))
        assertEquals("HA host failed", AlertNames.humanize("ha_host_failed"))
        assertEquals("HA statefile lost", AlertNames.humanize("ha-statefile_lost"))
        assertEquals("Alarm", AlertNames.humanize("ALARM"))
        assertEquals("Server alert", AlertNames.humanize("  "))
    }

    // ---- object resolution ----
    @Test fun objectsResolveAgainstTheCurrentInventoryOnly() {
        assertEquals("VM · Web VM", AlertObjects.resolve(alert("a", cls = "VM", obj = "vm-1"), inventory).display)
        assertEquals("Web VM", AlertObjects.resolve(alert("a", cls = "vm", obj = "vm-1"), inventory).guest?.name)
        assertEquals("Host One", AlertObjects.resolve(alert("a", cls = "Host", obj = "host-1"), inventory).name)
        assertEquals("Local SR", AlertObjects.resolve(alert("a", cls = "SR", obj = "sr-1"), inventory).name)
        assertEquals("Fixture pool", AlertObjects.resolve(alert("a", cls = "Pool", obj = "pool"), inventory).name)
        val missing = AlertObjects.resolve(alert("a", cls = "VM", obj = "template-uuid-123456"), inventory)
        assertFalse(missing.inInventory); assertTrue(missing.display.contains("not in the current inventory"))
        assertTrue(missing.display.contains("template…"))   // short id only
        assertFalse(AlertObjects.resolve(alert("a", cls = "Pool", obj = "other-pool"), inventory).inInventory)
        assertEquals(AlertObjectKind.OTHER, AlertObjects.kind("VMPP"))
        assertFalse(AlertObjects.resolve(alert("a", cls = "VM", obj = "vm-1"), null).inInventory)
        assertEquals("Pool", AlertObjects.resolve(alert("a", cls = "Pool", obj = ""), inventory).display)
    }

    // ---- filters and search ----
    private val sample = listOf(
        alert("crit", 1, t0 + 5, "host_clock_went_backwards", "Host", "host-1", "Clock stepped back"),
        alert("warn", 3, t0 + 9, "ha_host_failed", "VM", "vm-1", "A host died"),
        alert("info", 5, t0 + 7, "VM_STARTED", "VM", "vm-1", "started"),
        alert("sr", 4, t0 + 1, "multipath_period_alert", "SR", "sr-1", "path"),
    )
    @Test fun filtersCombinePriorityObjectTypeSearchAndNewOnly() {
        fun refs(filter: AlertFilter, seen: Long? = null) = filter.apply(sample, inventory, seen).map { it.ref }
        assertEquals(listOf("crit", "warn", "sr", "info"), refs(AlertFilter()))   // priority order
        assertEquals(listOf("warn", "info", "crit", "sr"), refs(AlertFilter(sort = AlertSort.NEWEST)))
        assertEquals(listOf("crit", "warn"), refs(AlertFilter(severities = setOf(AlertSeverity.CRITICAL, AlertSeverity.WARNING))))
        assertEquals(listOf("warn", "info"), refs(AlertFilter(kinds = setOf(AlertObjectKind.VM))))
        assertEquals(listOf("sr"), refs(AlertFilter(kinds = setOf(AlertObjectKind.STORAGE))))
        assertEquals(listOf("warn"), refs(AlertFilter(kinds = setOf(AlertObjectKind.VM), severities = setOf(AlertSeverity.WARNING))))
        assertEquals(listOf("warn", "info"), refs(AlertFilter(query = "web vm")))            // resolved object name
        assertEquals(listOf("crit"), refs(AlertFilter(query = "clock")))                      // raw and readable name
        assertEquals(listOf("warn"), refs(AlertFilter(query = "A HOST DIED")))               // body, case-insensitive
        assertEquals(listOf("sr"), refs(AlertFilter(query = "local sr")))
        assertEquals(emptyList<String>(), refs(AlertFilter(query = "nothing like this")))
        assertEquals(listOf("warn", "info"), refs(AlertFilter(newOnly = true), seen = t0 + 5))   // strictly newer than last seen
    }

    // ---- last seen and unread ----
    @Test fun unreadCountsOnlyUrgentMessagesNewerThanLastSeen() {
        val inbox = AlertInbox("s", sample.sortedWith(AlertMapper.NEWEST_FIRST))
        assertEquals(2, inbox.unreadUrgent)                 // nothing seen yet: crit + warn (info and low never count)
        assertEquals(1, inbox.copy(lastSeen = t0 + 5).unreadUrgent)   // equal timestamp is seen; warn is newer
        assertEquals(0, inbox.copy(lastSeen = t0 + 9).unreadUrgent)
    }
    @Test fun lastSeenOnlyMovesForwardAndMessagesWithoutTimeAreNotNewOnceSomethingWasSeen() {
        assertEquals(10L, AlertSeen.advance(10, 5)); assertEquals(10L, AlertSeen.advance(5, 10)); assertEquals(7L, AlertSeen.advance(null, 7))
        assertNull(AlertSeen.advance(null, null))
        val undated = alert("u", at = null)
        assertTrue(AlertSeen.isNew(undated, null)); assertFalse(AlertSeen.isNew(undated, 5))
        assertEquals(t0 + 9, AlertSeen.newest(AlertInbox("s", sample)))
        assertNull(AlertSeen.newest(AlertInbox("s")))
    }

    // ---- merge ----
    @Test fun fullReadReplacesTheListDedupesAndSetsTheServerCursor() {
        val inbox = AlertMerge.full("s", fetch(alert("a", at = t0 + 1), alert("a", at = t0 + 1), alert("b", at = t0 + 3), full = true), lastSeen = 4)
        assertEquals(listOf("b", "a"), inbox.messages.map { it.ref })
        assertEquals(t0 + 3, inbox.cursor); assertEquals(4L, inbox.lastSeen)
        assertEquals(t0 + 1000, inbox.fullSyncAt)
    }
    @Test fun incrementalMergeDeduplicatesUpdatesInPlaceAndNeverMovesTheCursorBackwards() {
        val base = AlertMerge.full("s", fetch(alert("a", at = t0 + 10, body = "old"), alert("b", at = t0 + 20), full = true), null)
        // get_since re-reads the overlap window: "b" again (same reference) and an updated "a", plus a new "c".
        val merged = AlertMerge.incremental(base, fetch(alert("b", at = t0 + 20), alert("a", at = t0 + 10, body = "new"), alert("c", at = t0 + 25)))
        assertEquals(listOf("c", "b", "a"), merged.messages.map { it.ref })
        assertEquals("new", merged.messages.single { it.ref == "a" }.body)
        assertEquals(t0 + 25, merged.cursor)
        // An empty or older answer keeps the cursor and the list.
        val unchanged = AlertMerge.incremental(merged, fetch(alert("a", at = t0 + 10)))
        assertEquals(t0 + 25, unchanged.cursor); assertEquals(3, unchanged.messages.size)
        assertEquals(t0 + 25, AlertMerge.incremental(merged, fetch()).cursor)
    }
    @Test fun clockSkewIsAbsorbedByTheOverlapAndTheCursorIsServerTimeOnly() {
        val base = AlertMerge.full("s", fetch(alert("a", at = t0 + 1_000_000), full = true, at = 5L), null)
        // The next request starts before the newest server timestamp, by the overlap, regardless of the phone clock (observedAt = 5).
        assertEquals(t0 + 1_000_000 - AlertMerge.OVERLAP_MILLIS, AlertMerge.since(base.cursor))
        // A message stamped slightly behind the newest one (another host's clock) is still inside the window and is merged.
        val late = alert("late", at = t0 + 1_000_000 - 60_000)
        val merged = AlertMerge.incremental(base, fetch(late))
        assertTrue(merged.messages.any { it.ref == "late" })
        assertEquals(t0 + 1_000_000, merged.cursor)
        assertNull(AlertMerge.since(null))
        assertEquals(1L, AlertMerge.since(10))
    }
    @Test fun deletionsAreAppliedByReferenceAndAFullReadCatchesTheRest() {
        val base = AlertMerge.full("s", fetch(alert("a"), alert("b", at = t0 + 1), alert("c", at = t0 + 2), full = true), null)
        assertEquals(listOf("c", "a"), AlertMerge.remove(base, setOf("b")).messages.map { it.ref })
        assertSame(base, AlertMerge.remove(base, emptySet()))
        // A later full read is authoritative: messages the server no longer returns disappear.
        val reread = AlertMerge.full("s", fetch(alert("c", at = t0 + 2), full = true), base.lastSeen)
        assertEquals(listOf("c"), reread.messages.map { it.ref })
    }
    @Test fun inboxIsCappedAndMarkedTruncated() {
        val many = (0 until AlertMerge.MAX_KEPT + 5).map { alert("m$it", at = t0 + it) }
        val inbox = AlertMerge.full("s", AlertFetch("pool", t0, many, true), null)
        assertEquals(AlertMerge.MAX_KEPT, inbox.messages.size); assertTrue(inbox.truncated)
        assertEquals("m${AlertMerge.MAX_KEPT + 4}", inbox.messages.first().ref)       // newest kept
        assertTrue(AlertMerge.full("s", fetch(alert("x"), full = true, truncated = true), null).truncated)
    }

    // ---- dismissal outcome ----
    @Test fun dismissOutcomeSeparatesConfirmedRemovalFromEverythingElse() {
        val outcome = DismissOutcome(listOf(DismissResult("a", DismissStatus.REMOVED), DismissResult("b", DismissStatus.STILL_PRESENT),
            DismissResult("c", DismissStatus.UNKNOWN), DismissResult("d", DismissStatus.REFUSED, "RBAC_PERMISSION_DENIED", "message.destroy"),
            DismissResult("e", DismissStatus.NOT_SENT, "RBAC_PERMISSION_DENIED", "message.destroy")))
        assertEquals(setOf("a"), outcome.removed)
        assertEquals(2, outcome.unconfirmed)
        assertEquals("message.destroy", outcome.denied?.permission)
    }
}
