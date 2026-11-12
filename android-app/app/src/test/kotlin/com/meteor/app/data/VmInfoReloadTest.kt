package com.meteor.app.data

import org.junit.Assert.*
import org.junit.Test

class VmInfoReloadTest {
    private val before = VmDetails("pool", "vm", "VM", "", "Running", "host-a", 2, 1024, emptyList(), emptyList(), true, null, emptyList(),
        "20261107T08:00:00Z", emptySet(), false, 1000, 1000, null, residentHostUuid = "host-a")

    @Test fun powerHostOrBootChangesMarkTheInfoStale() {
        assertTrue(VmInfoReload.markersChanged(before, before.copy(power = "Halted")))
        assertTrue("a migration changes the resident host", VmInfoReload.markersChanged(before, before.copy(residentHostUuid = "host-b", host = "host-b")))
        assertTrue("a restart changes the boot marker", VmInfoReload.markersChanged(before, before.copy(bootTime = "20261107T09:30:00Z")))
        assertTrue(VmInfoReload.markersChanged(before, before.copy(residentHostUuid = null)))
        assertTrue(VmInfoReload.markersChanged(before, before.copy(bootTime = null)))
    }
    @Test fun anUnchangedPollNeverTriggersAReadSoRequestsStayBounded() {
        assertFalse(VmInfoReload.markersChanged(before, before))
        // Everything a five-second poll normally varies is ignored.
        assertFalse(VmInfoReload.markersChanged(before, before.copy(observedAt = 6000, topologyObservedAt = 6000, addresses = listOf("203.0.113.5"),
            guestToolsLive = false, currentOperations = true, allowed = setOf("clean_shutdown"), vcpus = 4, memoryBytes = 2048)))
    }
    @Test fun aFirstReadOrAnotherVmIsNotAChange() {
        assertFalse("no previous read: the page loads it on its own", VmInfoReload.markersChanged(null, before))
        assertFalse(VmInfoReload.markersChanged(before, before.copy(uuid = "other", power = "Halted")))
        assertFalse(VmInfoReload.markersChanged(before, before.copy(authority = "other-pool", power = "Halted")))
    }

    private fun op(phase: PowerPhase, uuid: String = "vm", authority: String = "pool", resolved: Boolean = false) =
        PowerOperation("id", "profile", "a".repeat(64), authority, uuid, "VM", PowerAction.POOL_MIGRATE, null, phase, 1, 2,
            manualResolution = if (resolved) ManualResolution(3, "b".repeat(64), "Running", null) else null)

    @Test fun aClosedOperationOnTheOpenVmMarksTheInfoStale() {
        assertTrue(VmInfoReload.operationClosed("vm", "pool", op(PowerPhase.SUCCEEDED)))
        assertTrue(VmInfoReload.operationClosed("vm", "pool", op(PowerPhase.FAILED)))
        assertTrue("a manually reviewed and closed record", VmInfoReload.operationClosed("vm", "pool", op(PowerPhase.UNKNOWN, resolved = true)))
    }
    @Test fun unresolvedOtherVmOrNothingOpenDoesNotTrigger() {
        for (phase in listOf(PowerPhase.PREPARED, PowerPhase.SUBMITTING, PowerPhase.TRACKING, PowerPhase.VERIFYING, PowerPhase.UNKNOWN))
            assertFalse("$phase is still in progress", VmInfoReload.operationClosed("vm", "pool", op(phase)))
        assertFalse(VmInfoReload.operationClosed("vm", "pool", op(PowerPhase.SUCCEEDED, uuid = "another-vm")))
        assertFalse(VmInfoReload.operationClosed("vm", "pool", op(PowerPhase.SUCCEEDED, authority = "other-pool")))
        assertFalse(VmInfoReload.operationClosed(null, "pool", op(PowerPhase.SUCCEEDED)))
        assertFalse(VmInfoReload.operationClosed("vm", null, op(PowerPhase.SUCCEEDED)))
    }
}
