package com.meteor.app

import com.meteor.app.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import javax.net.ssl.SSLHandshakeException

/** B3 background/summary: the all-pools summary, its bounds, row taps and the manual update check. */
@OptIn(ExperimentalCoroutinesApi::class)
class PoolSummaryControllerTest {
    private val profiles = (1..7).map { Profile("p$it", "Pool $it", "https://p$it.invalid/", "root", "secret-$it", "%064x".format(it)) }
    private val connectedInventory = Inventory("pool-1", "First", listOf(Host("h1", "Host", true, null, null)),
        listOf(Guest("vm", "VM", "Running", "pool-1", "h1", 1, 1)), 0, 0, 5)

    private class Seen : AlertSeenStore {
        override suspend fun lastSeen(profileId: String): Long? = if (profileId == "p2") 1_000L else null
        override suspend fun save(profileId: String, millis: Long) = error("The summary must not change the in-app seen state")
    }

    private class Backend : MeteorGateway {
        var active = 0
        var maxActive = 0
        val summaries = mutableListOf<Pair<String, Long?>>()
        val inventories = mutableListOf<String>()
        val updateChecks = mutableListOf<Pair<String, String>>()
        var behaviour: suspend (Profile) -> PoolSummaryRead = { profile ->
            PoolSummaryRead("pool-${profile.id}", "Name ${profile.id}", 1, 2, 3, 4, 2, 0)
        }
        var updates: suspend () -> HostUpdateResult = { HostUpdateResult(listOf(HostUpdate("xen", "4.17-1")), 1, true) }
        override suspend fun poolSummary(profile: Profile, lastSeen: Long?): PoolSummaryRead {
            summaries += profile.id to lastSeen
            active++; maxActive = maxOf(maxActive, active)
            try { delay(1_000); return behaviour(profile) } finally { active-- }
        }
        override suspend fun inventory(profile: Profile): Inventory { inventories += profile.id; return Inventory("pool-${profile.id}", "Inv", emptyList(), emptyList(), 0, 0, 1) }
        override suspend fun checkHostUpdates(profile: Profile, authority: String, hostUuid: String): HostUpdateResult {
            updateChecks += authority to hostUuid; return updates()
        }
        override suspend fun details(profile: Profile, authority: String, uuid: String): VmDetails = error("Unexpected details")
        override suspend fun <T> powerConnection(profile: Profile, action: suspend PowerConnection.() -> T): T = error("Unexpected power access")
    }

    private fun TestScope.controller(state: MutableStateFlow<MeteorState>, backend: Backend) =
        // The test scope itself (not backgroundScope), so advanceUntilIdle drives the reads and their delays.
        PoolSummaryController(this, backend, state, Seen(), clock = { testScheduler.currentTime },
            ioContext = StandardTestDispatcher(testScheduler))

    @Test fun atMostThreeReadsRunAtOnceAndEveryOtherProfileIsRead() = runTest {
        val backend = Backend()
        val state = MutableStateFlow(MeteorState(profiles = profiles))
        controller(state, backend).refresh()
        runCurrent()
        assertTrue(state.value.allPools.busy); assertEquals(7, state.value.allPools.checking.size)
        advanceUntilIdle()
        assertEquals(PoolSummaries.MAX_PARALLEL, backend.maxActive)
        assertEquals(profiles.map { it.id }.toSet(), backend.summaries.map { it.first }.toSet())
        assertEquals(3_000L, testScheduler.currentTime) // 7 reads of 1 s, three at a time
        assertFalse(state.value.allPools.busy); assertTrue(state.value.allPools.checking.isEmpty())
        assertTrue(state.value.allPools.rows.values.all { it.reachability == PoolReachability.REACHABLE })
        assertEquals(1_000L, backend.summaries.first { it.first == "p2" }.second) // A4's seen value, read-only
    }

    @Test fun slowUnreachableAndChangedCertificatesAreReportedPerRowWithTimeouts() = runTest {
        val backend = Backend()
        backend.behaviour = { profile ->
            when (profile.id) {
                "p1" -> { delay(60_000); error("too late") }
                "p2" -> throw IOException("unreachable")
                "p3" -> throw SSLHandshakeException("pin").apply { initCause(CertificateChanged()) }
                else -> PoolSummaryRead("x", "Fine", 1, 1, 0, 0, null, 0)
            }
        }
        val state = MutableStateFlow(MeteorState(profiles = profiles.take(4)))
        controller(state, backend).refresh()
        advanceUntilIdle()
        val rows = state.value.allPools.rows
        assertEquals(PoolReachability.UNREACHABLE, rows.getValue("p1").reachability)
        assertTrue(rows.getValue("p1").problem!!.contains("20 s"))
        assertEquals(PoolReachability.UNREACHABLE, rows.getValue("p2").reachability)
        assertEquals(PoolReachability.CERTIFICATE_CHANGED, rows.getValue("p3").reachability)
        assertEquals(PoolReachability.REACHABLE, rows.getValue("p4").reachability)
        assertNull(rows.getValue("p4").read!!.unreadUrgent)
        assertTrue(testScheduler.currentTime <= PoolSummaries.TIMEOUT_MILLIS + 1_000)
        // Trust is untouched.
        assertEquals(profiles.take(4), state.value.profiles)
    }

    @Test fun theConnectedProfileIsNotReadAgainAndItsStateIsLeftAlone() = runTest {
        val backend = Backend()
        val before = MeteorState(profiles = profiles.take(3), selectedId = "p1", inventory = connectedInventory, connected = true,
            notice = UiNotice.info("kept"))
        val state = MutableStateFlow(before)
        controller(state, backend).refresh()
        advanceUntilIdle()
        assertEquals(listOf("p2", "p3"), backend.summaries.map { it.first }.sorted())
        assertEquals(before, state.value.copy(allPools = AllPoolsSummary()))
        val lines = PoolSummaryLines.of(state.value)
        assertEquals(listOf("p1", "p2", "p3"), lines.map { it.profile.id })
        val current = lines.first()
        assertTrue(current.current); assertEquals(PoolReachability.REACHABLE, current.reachability)
        assertEquals(1, current.read!!.hostsLive); assertEquals(1, current.read!!.vmsRunning)
    }

    @Test fun nothingIsReadWithFewerThanTwoProfilesAndStaleRefreshWaitsFiveMinutes() = runTest {
        val backend = Backend()
        val single = MutableStateFlow(MeteorState(profiles = profiles.take(1)))
        controller(single, backend).refresh(); advanceUntilIdle()
        assertTrue(backend.summaries.isEmpty())
        val state = MutableStateFlow(MeteorState(profiles = profiles.take(2)))
        val controller = controller(state, backend)
        controller.refreshIfStale(); advanceUntilIdle()
        assertEquals(2, backend.summaries.size)
        controller.refreshIfStale(); advanceUntilIdle()
        assertEquals(2, backend.summaries.size)
        advanceTimeBy(PoolSummaries.STALE_MILLIS); runCurrent()
        controller.refreshIfStale(); advanceUntilIdle()
        assertEquals(4, backend.summaries.size)
    }

    @Test fun aRowForAnOlderOriginIsNeverShown() = runTest {
        val backend = Backend()
        val state = MutableStateFlow(MeteorState(profiles = profiles.take(2)))
        controller(state, backend).refresh(); advanceUntilIdle()
        val repinned = profiles[1].copy(certificateSha256 = "f".repeat(64))
        state.value = state.value.copy(profiles = listOf(profiles[0], repinned))
        val line = PoolSummaryLines.of(state.value).first { it.profile.id == "p2" }
        assertEquals(PoolReachability.NOT_CHECKED, line.reachability); assertNull(line.read)
    }

    @Test fun tappingARowUsesTheExistingConnectionFlow() = runTest {
        val backend = Backend()
        val state = MutableStateFlow(MeteorState(profiles = profiles.take(2), selectedId = "p1", journalProblem = false))
        val vault = object : ProfileStorage {
            var document = ProfileDocument(profiles.take(2), "p1")
            override suspend fun load() = document
            override suspend fun save(document: ProfileDocument) { this.document = document }
        }
        val journal = object : OperationStore {
            override suspend fun load() = emptyList<PowerOperation>()
            override suspend fun put(operation: PowerOperation) = error("unexpected")
        }
        val connections = ConnectionController(backgroundScope, backend, vault, journal, CertificateInspector { error("no review expected") },
            state, {}, {}, {}, { "failed" }).apply { initialized = true }
        val summary = controller(state, backend)
        summary.open("missing", connections::switchProfile)
        runCurrent()
        assertTrue(backend.inventories.isEmpty())
        summary.open("p2", connections::switchProfile)
        runCurrent() // the connection controller runs in backgroundScope, as in ConnectionControllerTest
        assertEquals(listOf("p2"), backend.inventories)
        assertEquals("p2", state.value.selectedId); assertEquals("p2", vault.document.selectedId)
        assertEquals(1, state.value.manualConnectionEvent) // the same manual flow as Connections
        assertTrue(state.value.connected)
    }

    @Test fun updateChecksAreManualFencedAndShowTheServersReason() = runTest {
        val backend = Backend()
        val state = MutableStateFlow(MeteorState(profiles = profiles.take(1), selectedId = "p1", inventory = connectedInventory, connected = true))
        val updates = HostUpdateController(this, backend, state, clock = { testScheduler.currentTime })
        updates.check("unknown-host"); runCurrent()
        assertTrue(backend.updateChecks.isEmpty())
        updates.check("h1"); runCurrent()
        assertEquals(listOf("pool-1" to "h1"), backend.updateChecks)
        assertEquals(listOf("xen"), state.value.currentUpdateChecks.getValue("h1").result!!.updates.map { it.name })
        backend.updates = { throw RpcFailure("XENAPI_MISSING_PLUGIN", details = listOf("updater.py")) }
        updates.check("h1"); runCurrent()
        assertTrue(state.value.currentUpdateChecks.getValue("h1").problem!!.contains("not installed"))
        // A result from an older connection is not shown on the new one.
        backend.updates = { delay(1_000); HostUpdateResult(emptyList(), 0, true) }
        updates.check("h1"); runCurrent()
        state.value = state.value.copy(inventory = connectedInventory.copy(authority = "pool-2"))
        advanceUntilIdle()
        assertTrue(state.value.currentUpdateChecks.isEmpty())
        // Not connected: nothing is sent.
        state.value = state.value.copy(connected = false, inventory = connectedInventory)
        val sent = backend.updateChecks.size
        updates.check("h1"); runCurrent()
        assertEquals(sent, backend.updateChecks.size)
    }
}
