package com.meteor.app.data

import org.junit.Assert.*
import org.junit.Test

class FleetFilterTest {
    private val guests = listOf(
        Guest("a", "API", "Running", "pool", "h1", 2, 1024),
        Guest("b", "Worker", "Halted", "pool", null, 2, 1024),
        Guest("c", "Worker", "Paused", "pool", "h2", 2, 1024),
        Guest("d", "Unknown guest", "Unknown", "pool", null, 2, 1024),
    )
    private val inventory = Inventory("pool", "Pool",
        listOf(Host("h1", "North", true, null, null), Host("h2", "South", true, null, null)), guests, 0, 0, 123)

    @Test fun searchTrimsWhitespaceAndMatchesVmAndHostNames() {
        assertEquals(listOf("a"), inventory.filterFleet("  north ", FleetFilter.ALL).guests.map { it.uuid })
        assertEquals(listOf("b", "c"), inventory.filterFleet("WORKER", FleetFilter.ALL).guests.map { it.uuid })
        assertEquals(guests, inventory.filterFleet("   ", FleetFilter.ALL).guests)
    }
    @Test fun powerFiltersCombineWithSearchWithoutHidingOtherStates() {
        assertEquals(listOf("a"), inventory.filterFleet("", FleetFilter.RUNNING).guests.map { it.uuid })
        assertEquals(listOf("b"), inventory.filterFleet("worker", FleetFilter.HALTED).guests.map { it.uuid })
        assertEquals(listOf("c", "d"), inventory.filterFleet("", FleetFilter.OTHER).guests.map { it.uuid })
        assertTrue(inventory.filterFleet("south", FleetFilter.RUNNING).guests.isEmpty())
    }
    @Test fun filteredGroupingPreservesIdentitiesPlacementAndSnapshotMetadata() {
        val filtered = inventory.filterFleet("worker", FleetFilter.ALL)
        assertEquals(inventory.authority, filtered.authority)
        assertEquals(inventory.observedAtMillis, filtered.observedAtMillis)
        assertEquals(inventory.hosts, filtered.hosts)
        assertEquals(listOf(guests[2], guests[1]), InventoryMapper.groups(filtered).flatMap { it.guests })
        assertEquals(guests, inventory.guests)
    }
}
