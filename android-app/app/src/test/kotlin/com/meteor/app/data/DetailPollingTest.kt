package com.meteor.app.data

import org.junit.Assert.*
import org.junit.Test

class DetailPollingTest {
    private val topology = VmTopology("origin", listOf("disk"), listOf("network"))
    private val previous = VmDetails("pool", "vm", "VM", "", "Running", null, 2, 1024,
        emptyList(), emptyList(), true, null, emptyList(), "20260101T10:00:00Z", emptySet(), false, 1000, 1000, topology)
    private fun reusable(vm: VmDetails = previous, shape: VmTopology = topology, now: Long = 2000,
        authority: String = "pool", uuid: String = "vm", power: String = "Running", boot: String? = previous.bootTime,
        busy: Boolean = false) = DetailPolling.canReuseTopology(vm, authority, uuid, shape, power, boot, busy, now)

    @Test fun cachedTopologyExpiresAndNeverSurvivesAClockRollback() {
        assertTrue(reusable(now = 60_999))
        assertFalse(reusable(now = 61_000))
        assertFalse(reusable(now = 999))
    }
    @Test fun connectionPoolVmOrDeviceChangesRequireAFullRead() {
        assertFalse(reusable(shape = topology.copy(origin = "other")))
        assertFalse(reusable(authority = "other"))
        assertFalse(reusable(uuid = "other"))
        assertFalse(reusable(shape = topology.copy(disks = listOf("replacement"))))
        assertFalse(reusable(shape = topology.copy(networks = emptyList())))
    }
    @Test fun powerBootAndActiveOperationsInvalidateDisplayTopology() {
        assertFalse(reusable(power = "Halted"))
        assertFalse(reusable(boot = "20260101T10:01:00Z"))
        assertFalse(reusable(busy = true))
        assertFalse(reusable(vm = previous.copy(currentOperations = true)))
        assertFalse(reusable(vm = previous.copy(topology = null)))
    }
}
