package com.meteor.app.data

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class InventoryTest {
    private val pools = mapOf("pool-ref" to mapOf("uuid" to "pool-id", "name_label" to "Constellation"))
    private val hosts = mapOf("host-ref" to mapOf("uuid" to "host-id", "name_label" to "Planet", "metrics" to "metrics-ref"))
    private val metrics = mapOf("metrics-ref" to mapOf("live" to true, "memory_total" to "17179869184", "memory_free" to "8589934592"))
    private fun vm(id: String, power: String = "Running") = mapOf<String, Any?>("uuid" to id, "name_label" to id,
        "power_state" to power, "resident_on" to "host-ref", "VCPUs_max" to "2", "memory_static_max" to "4294967296")
    @Test fun joinsOpaqueReferencesAndFiltersInfrastructure() {
        val vms = mapOf("a" to vm("running"), "b" to vm("halted", "Halted"), "c" to (vm("dom0") + ("is_control_domain" to true)),
            "d" to (vm("template") + ("is_a_template" to true)), "e" to (vm("snapshot") + ("is_a_snapshot" to true)))
        val inventory = InventoryMapper.normalize(pools, hosts, metrics, vms, emptyMap(), 1234)
        assertEquals(2, inventory.guests.size)
        assertEquals("host-id", inventory.guests.single { it.uuid == "running" }.hostUuid)
        assertNull(inventory.guests.single { it.uuid == "halted" }.hostUuid)
        assertEquals(17179869184L, inventory.hosts.single().totalBytes)
        assertEquals(1234, inventory.observedAtMillis)
        val groups = InventoryMapper.groups(inventory)
        assertEquals(listOf("running"), groups.single { it.id == "host:host-id" }.guests.map { it.uuid })
        assertEquals(listOf("halted"), groups.single { it.id == "unassigned" }.guests.map { it.uuid })
    }
    @Test fun identitySurvivesMigrationAndUnknownPlacementIsExplicit() {
        val original = vm("vm-id")
        val first = InventoryMapper.normalize(pools, hosts, metrics, mapOf("ref" to original), emptyMap(), 1)
        val moved = InventoryMapper.normalize(pools, hosts, metrics, mapOf("ref" to (original + ("resident_on" to "unknown"))), emptyMap(), 2)
        assertEquals(first.guests.single().identity(first.authority), moved.guests.single().identity(moved.authority))
        assertEquals("unknown", InventoryMapper.groups(moved).last().id)
        assertNull(InventoryMapper.normalize(pools, hosts, emptyMap(), emptyMap(), emptyMap(), 1).hosts.single().totalBytes)
    }
    @Test fun excludesIsoCapacityAndRejectsAmbiguousIdentity() {
        val storage = mapOf("disk" to mapOf("physical_size" to "1000", "physical_utilisation" to "250"),
            "iso" to mapOf("content_type" to "iso", "physical_size" to "1000", "physical_utilisation" to "1000"))
        val inventory = InventoryMapper.normalize(pools, hosts, metrics, emptyMap(), storage, 1)
        assertEquals(1000, inventory.storageTotal); assertEquals(250, inventory.storageUsed)
        assertThrows(IllegalArgumentException::class.java) { InventoryMapper.normalize(emptyMap(), hosts, metrics, emptyMap(), storage, 1) }
        assertThrows(IllegalArgumentException::class.java) { InventoryMapper.normalize(pools, hosts, metrics, mapOf("a" to vm("duplicate"), "b" to vm("duplicate")), storage, 1) }
    }
    @Test fun malformedHostIdentitiesAreRejectedBeforeRendering() {
        for (id in listOf("", " ")) {
            val invalid = mapOf("ref" to mapOf("uuid" to id))
            assertThrows(IllegalArgumentException::class.java) {
                InventoryMapper.normalize(pools, invalid, metrics, emptyMap(), emptyMap(), 1)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            InventoryMapper.normalize(pools, hosts + ("duplicate-ref" to hosts.values.single()), metrics, emptyMap(), emptyMap(), 1)
        }
    }
    @Test fun syntheticGroupsCannotCollideWithHostIdentities() {
        val unusualHosts = mapOf("host-ref" to mapOf("uuid" to "unassigned"))
        val inventory = InventoryMapper.normalize(pools, unusualHosts, metrics,
            mapOf("a" to vm("running"), "b" to vm("halted", "Halted")), emptyMap(), 1)
        val groups = InventoryMapper.groups(inventory)
        assertEquals(groups.size, groups.map { it.id }.distinct().size)
        assertEquals(2, groups.flatMap { it.guests }.size)
    }
    @Test fun everyVmAppearsExactlyOnceAcrossChangingPlacements() {
        val random = Random(1701)
        repeat(200) { topology ->
            val fixtureHosts = (0 until random.nextInt(9)).associate { "host-ref-$it" to mapOf("uuid" to "host-$it") }
            val fixtureVms = (0 until random.nextInt(81)).associate { index ->
                val state = listOf("Running", "Halted", "Paused", "Unknown")[random.nextInt(4)]
                "vm-ref-$index" to (vm("vm-$topology-$index", state) +
                    ("resident_on" to "host-ref-${random.nextInt(10)}"))
            }
            val inventory = InventoryMapper.normalize(pools, fixtureHosts, emptyMap(), fixtureVms, emptyMap(), topology.toLong())
            val grouped = InventoryMapper.groups(inventory).flatMap { it.guests }
            assertEquals(inventory.guests.map { it.uuid }.toSet(), grouped.map { it.uuid }.toSet())
            assertEquals(inventory.guests.size, grouped.size)
            assertTrue(inventory.guests.filter { it.power == "Halted" }.all { it.hostUuid == null })
        }
    }
}
