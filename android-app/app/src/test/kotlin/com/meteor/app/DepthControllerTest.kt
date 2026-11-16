package com.meteor.app

import com.meteor.app.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DepthControllerTest {
    private val profile = Profile("fixture", "Example", "https://example.invalid/", "tester", "fixture", "a".repeat(64))
    private val host = MetricTarget("pool", "host-1", MetricKind.HOST, "Host 1")
    private val vm = MetricTarget("pool", "vm-1", MetricKind.VM, "VM 1")
    private fun state(target: MetricTarget? = host) = MutableStateFlow(MeteorState(profiles = listOf(profile), selectedId = profile.id, connected = true,
        inventory = Inventory("pool", "Pool", listOf(Host("host-1", "Host 1", true, null, null)),
            listOf(Guest("vm-1", "VM 1", "Running", "pool", null, 2, 1024), Guest("vm-2", "VM 2", "Running", "pool", null, 2, 1024)), 0, 0, 1,
            listOf(StorageRepository("sr-1", "SR", "nfs", "user", 1000, 10, ""))), metricTarget = target))

    private fun snapshot(target: MetricTarget, range: HistoryRange, times: List<Long>, value: Double = 1.0) = HistorySnapshot(target, range, range.stepSeconds, times,
        listOf(HistorySeries("CPU", "cpu_avg", "CPU", HistoryGroup.CPU, HistoryUnit.FRACTION, "fraction", times.map { value })), 1)
    private fun vmInfo(uuid: String, authority: String = "pool") = VmInfoMapper.map(authority, mapOf("uuid" to uuid), emptyMap(), emptyList(), emptyList(), emptyMap(), 1)
    private fun hostInfo(uuid: String) = HostInfoMapper.map("pool", mapOf("uuid" to uuid), true, emptyList(), emptyMap(), null, 0, 0, 1)
    private fun storageInfo(uuid: String) = StorageMapper.map("pool", mapOf("uuid" to uuid), emptyMap(), emptyList(), emptyList(), 0, emptyMap(), emptyMap(), 1)

    private class Backend(private val owner: DepthControllerTest) : MeteorGateway {
        var reads = 0
        var metricReads = 0
        val sinces = mutableListOf<Long?>()
        var onHistory: suspend (MetricTarget, HistoryRange) -> HistorySnapshot = { t, r -> owner.snapshot(t, r, listOf(100L)) }
        var onVm: suspend (String) -> VmInfo = { owner.vmInfo(it) }
        var onHost: suspend (String) -> HostInfo = { owner.hostInfo(it) }
        var onStorage: suspend (String) -> StorageInfo = { owner.storageInfo(it) }
        var onNetworks: suspend () -> NetworkOverview = { NetworkOverview("pool", emptyList(), 0, 1) }
        override suspend fun history(profile: Profile, target: MetricTarget, range: HistoryRange, sinceSeconds: Long?): HistorySnapshot {
            reads++; sinces += sinceSeconds; return onHistory(target, range)
        }
        override suspend fun vmInfo(profile: Profile, authority: String, uuid: String): VmInfo { reads++; return onVm(uuid) }
        override suspend fun hostInfo(profile: Profile, authority: String, uuid: String): HostInfo { reads++; return onHost(uuid) }
        override suspend fun storageInfo(profile: Profile, authority: String, uuid: String): StorageInfo { reads++; return onStorage(uuid) }
        override suspend fun networks(profile: Profile, authority: String): NetworkOverview { reads++; return onNetworks() }
        override suspend fun metrics(profile: Profile, target: MetricTarget): MetricSnapshot { metricReads++; return MetricSnapshot(target, 1000, emptyList(), 0) }
        override suspend fun inventory(profile: Profile): Inventory = error("Unexpected inventory")
        override suspend fun details(profile: Profile, authority: String, uuid: String): VmDetails = error("Unexpected details")
        override suspend fun <T> powerConnection(profile: Profile, action: suspend PowerConnection.() -> T): T = error("No power requests")
    }

    // ---- history ----

    @Test fun historyIsReadIncrementallyAndPolledOnTheRangeCadence() = runTest {
        val state = state(); val backend = Backend(this@DepthControllerTest); var now = 1_000_000L; var live = 0
        backend.onHistory = { t, r -> if (backend.sinces.size == 1) snapshot(t, r, listOf(100, 105, 110)) else snapshot(t, r, listOf(110, 115), 2.0) }
        val controller = DepthController(backgroundScope, backend, state, { now }) { live++ }
        controller.selectRange(HistoryRange.TEN_MINUTES); runCurrent()
        assertEquals(listOf<Long?>(null), backend.sinces)
        assertEquals(listOf(100L, 105L, 110L), state.value.depth.history.snapshot!!.timestamps)
        now += 5_000; controller.refreshHistoryIfDue(); runCurrent()
        assertEquals("not yet due after five seconds", 1, backend.sinces.size)
        now += 5_000; controller.refreshHistoryIfDue(); runCurrent()
        assertEquals(listOf<Long?>(null, 110L), backend.sinces)
        val merged = state.value.depth.history.snapshot!!
        assertEquals(listOf(100L, 105L, 110L, 115L), merged.timestamps)
        assertEquals(listOf(1.0, 1.0, 2.0, 2.0), merged.series.single().values)
        assertEquals(0, live)
    }
    @Test fun eachRangePollsAtItsOwnCadence() = runTest {
        for ((range, seconds) in listOf(HistoryRange.TEN_MINUTES to 10L, HistoryRange.TWO_HOURS to 60L, HistoryRange.ONE_WEEK to 600L)) {
            val state = state(); val backend = Backend(this@DepthControllerTest); var now = 5_000_000L
            val controller = DepthController(backgroundScope, backend, state, { now })
            controller.selectRange(range); runCurrent()
            now += seconds * 1000 - 1; controller.refreshHistoryIfDue(); runCurrent()
            assertEquals("$range early", 1, backend.reads)
            now += 1; controller.refreshHistoryIfDue(); runCurrent()
            assertEquals("$range due", 2, backend.reads)
        }
    }
    @Test fun aRangeChangeDiscardsALateResultEvenIfCancellationIsIgnored() = runTest {
        val state = state(); val backend = Backend(this@DepthControllerTest); val gate = CompletableDeferred<HistorySnapshot>()
        backend.onHistory = { t, r -> if (r == HistoryRange.TEN_MINUTES) withContext(NonCancellable) { gate.await() } else snapshot(t, r, listOf(7)) }
        val controller = DepthController(backgroundScope, backend, state)
        controller.selectRange(HistoryRange.TEN_MINUTES); runCurrent(); assertTrue(state.value.depth.history.busy)
        controller.selectRange(HistoryRange.TWO_HOURS); runCurrent()
        gate.complete(snapshot(host, HistoryRange.TEN_MINUTES, listOf(1))); runCurrent()
        val history = state.value.depth.history
        assertEquals(HistoryRange.TWO_HOURS, history.range); assertEquals(listOf(7L), history.snapshot!!.timestamps); assertFalse(history.busy)
    }
    @Test fun invalidationProfileAndPoolChangesDiscardLateHistory() = runTest {
        val state = state(); val backend = Backend(this@DepthControllerTest); val gate = CompletableDeferred<HistorySnapshot>()
        backend.onHistory = { _, _ -> withContext(NonCancellable) { gate.await() } }
        val controller = DepthController(backgroundScope, backend, state)
        controller.selectRange(HistoryRange.TEN_MINUTES); runCurrent()
        controller.invalidate(); gate.complete(snapshot(host, HistoryRange.TEN_MINUTES, listOf(1))); runCurrent()
        assertEquals(DepthState(), state.value.depth)

        val second = CompletableDeferred<HistorySnapshot>(); backend.onHistory = { _, _ -> withContext(NonCancellable) { second.await() } }
        controller.selectRange(HistoryRange.TEN_MINUTES); runCurrent()
        state.value = state.value.copy(profiles = listOf(profile.copy(endpoint = "https://other.invalid/")))
        second.complete(snapshot(host, HistoryRange.TEN_MINUTES, listOf(1))); runCurrent()
        assertNull(state.value.depth.history.snapshot); assertFalse(state.value.depth.history.busy)

        state.value = state.value.copy(profiles = listOf(profile))
        backend.onHistory = { t, r -> snapshot(t, r, listOf(1)) }
        controller.selectRange(HistoryRange.TEN_MINUTES); runCurrent()
        assertNotNull(state.value.depth.history.snapshot)
        val third = CompletableDeferred<HistorySnapshot>(); backend.onHistory = { _, _ -> withContext(NonCancellable) { third.await() } }
        controller.selectRange(HistoryRange.TWO_HOURS); runCurrent()
        state.value = state.value.copy(inventory = state.value.inventory!!.copy(authority = "other-pool"))
        third.complete(snapshot(host, HistoryRange.TWO_HOURS, listOf(2))); runCurrent()
        assertNull(state.value.depth.history.snapshot)
    }
    @Test fun aResultForAnotherObjectIsRejectedAndNeverStored() = runTest {
        val state = state(); val backend = Backend(this@DepthControllerTest)
        backend.onHistory = { _, r -> snapshot(vm, r, listOf(1)) }
        val controller = DepthController(backgroundScope, backend, state)
        controller.selectRange(HistoryRange.TEN_MINUTES); runCurrent()
        assertNull(state.value.depth.history.snapshot)
        assertTrue(state.value.depth.history.problem!!.contains("could not verify"))
    }
    @Test fun anUnavailableObjectFallsBackToTheLiveViewAndSaysWhy() = runTest {
        val state = state(); val backend = Backend(this@DepthControllerTest); var now = 1_000_000L; var live = 0
        backend.onHistory = { _, _ -> throw HistoryUnavailable("This VM runs on a pool member.") }
        val controller = DepthController(backgroundScope, backend, state, { now }) { live++ }
        controller.selectRange(HistoryRange.TWO_HOURS); runCurrent()
        val history = state.value.depth.history
        assertNull("live view again", history.range); assertEquals("This VM runs on a pool member.", history.unavailable)
        assertEquals(host, history.target); assertEquals(1, live); assertFalse(history.busy)
        now += 3_600_000; controller.refreshHistoryIfDue(); runCurrent()
        assertEquals("no further history reads once the live view is showing", 1, backend.reads)
    }
    @Test fun failedReadsKeepTheLastSnapshotAndNeverExposeExceptionText() = runTest {
        val state = state(); val backend = Backend(this@DepthControllerTest); var now = 1_000_000L
        val controller = DepthController(backgroundScope, backend, state, { now })
        controller.selectRange(HistoryRange.TEN_MINUTES); runCurrent()
        backend.onHistory = { _, _ -> error("private transport detail") }
        now += 10_000; controller.refreshHistoryIfDue(); runCurrent()
        assertNotNull(state.value.depth.history.snapshot)
        assertTrue(state.value.depth.history.problem!!.contains("last known")); assertFalse(state.value.depth.history.problem!!.contains("private"))
        backend.onHistory = { t, r -> snapshot(t, r, listOf(100, 105)) }
        now += 10_000; controller.refreshHistoryIfDue(); runCurrent()
        assertNull(state.value.depth.history.problem)
        assertEquals(listOf(100L, 105L), state.value.depth.history.snapshot!!.timestamps)
        for ((failure, text) in listOf(UnsupportedOperationException() to "not supported", GatewayError("http-denied") to "not available to this account",
            GatewayError("http-unsupported") to "not supported", GatewayError("response-limit") to "more data than Meteor will read",
            RpcFailure("RBAC_PERMISSION_DENIED") to "not available to this account")) {
            backend.onHistory = { _, _ -> throw failure }
            now += 10_000; controller.refreshHistoryIfDue(); runCurrent()
            assertTrue("$failure -> ${state.value.depth.history.problem}", state.value.depth.history.problem!!.contains(text))
        }
    }
    @Test fun selectingLiveStopsHistoryAndResumesLivePolling() = runTest {
        val state = state(); val backend = Backend(this@DepthControllerTest); var live = 0
        val controller = DepthController(backgroundScope, backend, state) { live++ }
        controller.selectRange(HistoryRange.TEN_MINUTES); runCurrent()
        controller.selectRange(null); runCurrent()
        assertNull(state.value.depth.history.range); assertNull(state.value.depth.history.snapshot); assertEquals(1, live)
        controller.refreshHistoryIfDue(); controller.refreshHistory(); runCurrent(); assertEquals(1, backend.reads)
    }
    @Test fun historyOnlyRunsForTheCurrentTargetWhileReadable() = runTest {
        val state = state(); val backend = Backend(this@DepthControllerTest); var now = 1_000_000L
        val controller = DepthController(backgroundScope, backend, state, { now })
        state.value = state.value.copy(metricTarget = null); controller.selectRange(HistoryRange.TEN_MINUTES); runCurrent(); assertEquals(0, backend.reads)
        state.value = state.value.copy(metricTarget = host); controller.selectRange(HistoryRange.TEN_MINUTES); runCurrent(); assertEquals(1, backend.reads)
        state.value = state.value.copy(metricTarget = vm); now += 60_000; controller.refreshHistoryIfDue(); runCurrent()
        assertEquals("another object's target cannot reuse the history range", 1, backend.reads)
        state.value = state.value.copy(metricTarget = host)
        for (blocker in listOf<(MeteorState) -> MeteorState>({ it.copy(operationBusy = true) }, { it.copy(connected = false) },
            { it.copy(loading = true) }, { it.copy(profileBusy = true) })) {
            val saved = state.value; state.value = blocker(saved)
            controller.refreshHistoryIfDue(); controller.refreshHistory(); runCurrent(); assertEquals(1, backend.reads)
            state.value = saved
        }
        controller.refreshHistoryIfDue(); runCurrent(); assertEquals(2, backend.reads)
    }

    // ---- detail reads ----

    @Test fun detailReadsAreFencedByTheObjectAndStaleResultsAreDropped() = runTest {
        val state = state(); val backend = Backend(this@DepthControllerTest); val gate = CompletableDeferred<VmInfo>()
        backend.onVm = { if (it == "vm-1") withContext(NonCancellable) { gate.await() } else vmInfo(it) }
        val controller = DepthController(backgroundScope, backend, state)
        controller.loadVm("missing"); runCurrent(); assertEquals(0, backend.reads)
        controller.loadVm("vm-1"); runCurrent(); assertTrue(state.value.depth.vm.busyFor("pool:vm-1"))
        controller.loadVm("vm-2"); runCurrent()
        gate.complete(vmInfo("vm-1")); runCurrent()
        val slot = state.value.depth.vm
        assertEquals("pool:vm-2", slot.key); assertNotNull(slot.valueFor("pool:vm-2")); assertNull(slot.valueFor("pool:vm-1")); assertFalse(slot.busy)
    }
    @Test fun refreshingTheSameObjectKeepsItsLastValueButAnotherObjectStartsEmpty() = runTest {
        val state = state(); val backend = Backend(this@DepthControllerTest)
        val controller = DepthController(backgroundScope, backend, state)
        controller.loadVm("vm-1"); runCurrent(); assertNotNull(state.value.depth.vm.valueFor("pool:vm-1"))
        val gate = CompletableDeferred<VmInfo>(); backend.onVm = { gate.await() }
        controller.loadVm("vm-1"); runCurrent()
        assertTrue(state.value.depth.vm.busyFor("pool:vm-1")); assertNotNull("last value stays while refreshing", state.value.depth.vm.valueFor("pool:vm-1"))
        controller.loadVm("vm-1"); runCurrent(); assertEquals("a read already in flight is not duplicated", 2, backend.reads)
        backend.onVm = { error("private detail") }
        gate.completeExceptionally(IllegalStateException("private detail")); runCurrent()
        val failed = state.value.depth.vm
        assertNotNull(failed.valueFor("pool:vm-1")); assertTrue(failed.problemFor("pool:vm-1")!!.contains("last known")); assertFalse(failed.problem!!.contains("private"))
        controller.loadVm("vm-2"); runCurrent()
        assertNull(state.value.depth.vm.valueFor("pool:vm-1")); assertNull("an old object's problem is not shown for a new one", state.value.depth.vm.problemFor("pool:vm-1"))
        assertNotNull(state.value.depth.vm.problemFor("pool:vm-2"))
        backend.onVm = { vmInfo(it) }
        controller.loadVm("vm-2"); runCurrent()
        assertNull(state.value.depth.vm.problem); assertEquals("vm-2", state.value.depth.vm.valueFor("pool:vm-2")!!.uuid)
    }
    @Test fun anAnswerForTheWrongObjectOrPoolIsAProblemNotAValue() = runTest {
        val state = state(); val backend = Backend(this@DepthControllerTest)
        val controller = DepthController(backgroundScope, backend, state)
        backend.onVm = { vmInfo("other") }; controller.loadVm("vm-1"); runCurrent()
        assertNull(state.value.depth.vm.value); assertTrue(state.value.depth.vm.problem!!.contains("could not verify"))
        backend.onHost = { hostInfo("other") }; controller.loadHost("host-1"); runCurrent(); assertNull(state.value.depth.host.value)
        backend.onStorage = { storageInfo("other") }; controller.loadStorage("sr-1"); runCurrent(); assertNull(state.value.depth.storage.value)
        backend.onNetworks = { NetworkOverview("another-pool", emptyList(), 0, 1) }; controller.loadNetworks(); runCurrent(); assertNull(state.value.depth.networks.value)
        backend.onVm = { vmInfo(it, authority = "another-pool") }; controller.loadVm("vm-1"); runCurrent(); assertNull(state.value.depth.vm.value)
    }
    @Test fun hostStorageAndNetworkReadsStoreTheirOwnObjectsAndInvalidationClearsThem() = runTest {
        val state = state(); val backend = Backend(this@DepthControllerTest)
        val controller = DepthController(backgroundScope, backend, state)
        controller.loadHost("host-1"); controller.loadStorage("sr-1"); controller.loadNetworks(); controller.loadHost("host-9"); controller.loadStorage("sr-9"); runCurrent()
        val depth = state.value.depth
        assertEquals("host-1", depth.host.valueFor("pool:host-1")!!.uuid); assertEquals("sr-1", depth.storage.valueFor("pool:sr-1")!!.uuid)
        assertNotNull(depth.networks.valueFor("pool:networks")); assertEquals(3, backend.reads)
        controller.invalidate(); assertEquals(DepthState(), state.value.depth)
    }
    @Test fun busyDisconnectedAndLoadingStatesCannotStartDetailReads() = runTest {
        val state = state(); val backend = Backend(this@DepthControllerTest)
        val controller = DepthController(backgroundScope, backend, state)
        for (blocker in listOf<(MeteorState) -> MeteorState>({ it.copy(operationBusy = true) }, { it.copy(connected = false) },
            { it.copy(loading = true) }, { it.copy(profileBusy = true) }, { it.copy(selectedId = null) }, { it.copy(inventory = null) })) {
            val saved = state.value; state.value = blocker(saved)
            controller.loadVm("vm-1"); controller.loadHost("host-1"); controller.loadStorage("sr-1"); controller.loadNetworks(); runCurrent()
            assertEquals(0, backend.reads); state.value = saved
        }
    }
    @Test fun aProfileChangeDiscardsALateDetailRead() = runTest {
        val state = state(); val backend = Backend(this@DepthControllerTest); val gate = CompletableDeferred<HostInfo>()
        backend.onHost = { withContext(NonCancellable) { gate.await() } }
        val controller = DepthController(backgroundScope, backend, state)
        controller.loadHost("host-1"); runCurrent()
        state.value = state.value.copy(profiles = listOf(profile.copy(username = "someone-else")))
        gate.complete(hostInfo("host-1")); runCurrent()
        assertNull(state.value.depth.host.value); assertFalse(state.value.depth.host.busy)
    }

    // ---- stale VM info (fold 3 device findings) ----

    private fun vmInfoAt(uuid: String, observedAt: Long) = VmInfoMapper.map("pool", mapOf("uuid" to uuid), emptyMap(), emptyList(), emptyList(), emptyMap(), observedAt)

    @Test fun aVmThatChangedIsMarkedStaleKeepsItsValueAndIsReadAgainNotOnEveryPoll() = runTest {
        val state = state(); val backend = Backend(this@DepthControllerTest); var observed = 1L
        backend.onVm = { vmInfoAt(it, observed) }
        val controller = DepthController(backgroundScope, backend, state)
        controller.loadVm("vm-1"); runCurrent()
        assertEquals(1L, state.value.depth.vm.valueFor("pool:vm-1")!!.observedAt); assertFalse(state.value.depth.vm.staleFor("pool:vm-1"))
        assertEquals(1, backend.reads)
        // Marking reads nothing by itself (a five-second poll that found no change never gets here).
        controller.markVmStale("pool", "vm-1"); runCurrent()
        assertEquals(1, backend.reads)
        val stale = state.value.depth.vm
        assertTrue(stale.staleFor("pool:vm-1")); assertFalse(stale.busy)
        assertEquals("the last value stays visible meanwhile", 1L, stale.valueFor("pool:vm-1")!!.observedAt)
        // The page reads again when it may; the fresh value replaces it and clears the mark.
        observed = 2L
        controller.loadVm("vm-1"); runCurrent()
        val fresh = state.value.depth.vm
        assertEquals(2, backend.reads); assertEquals(2L, fresh.valueFor("pool:vm-1")!!.observedAt)
        assertFalse(fresh.stale); assertFalse(fresh.staleFor("pool:vm-1"))
    }
    @Test fun markingCancelsAReadThatStartedBeforeTheChangeAndItsLateResultIsDiscarded() = runTest {
        val state = state(); val backend = Backend(this@DepthControllerTest); val gate = CompletableDeferred<VmInfo>()
        backend.onVm = { withContext(NonCancellable) { gate.await() } }
        val controller = DepthController(backgroundScope, backend, state)
        controller.loadVm("vm-1"); runCurrent(); assertTrue(state.value.depth.vm.busyFor("pool:vm-1"))
        controller.markVmStale("pool", "vm-1")
        assertFalse("not left busy", state.value.depth.vm.busy); assertTrue(state.value.depth.vm.staleFor("pool:vm-1"))
        gate.complete(vmInfoAt("vm-1", 5)); runCurrent()
        assertNull("the pre-change read must not look current", state.value.depth.vm.valueFor("pool:vm-1"))
        assertTrue(state.value.depth.vm.staleFor("pool:vm-1")); assertFalse(state.value.depth.vm.busy)
        // The next read (the page's retry) is not blocked by the discarded one.
        backend.onVm = { vmInfoAt(it, 6) }
        controller.loadVm("vm-1"); runCurrent()
        assertEquals(6L, state.value.depth.vm.valueFor("pool:vm-1")!!.observedAt); assertFalse(state.value.depth.vm.stale)
    }
    @Test fun markingClearsAnEarlierProblemSoTheChangedVmIsTriedAgain() = runTest {
        val state = state(); val backend = Backend(this@DepthControllerTest)
        backend.onVm = { error("private detail") }
        val controller = DepthController(backgroundScope, backend, state)
        controller.loadVm("vm-1"); runCurrent()
        assertNotNull(state.value.depth.vm.problemFor("pool:vm-1"))
        controller.markVmStale("pool", "vm-1")
        assertNull(state.value.depth.vm.problemFor("pool:vm-1")); assertTrue(state.value.depth.vm.staleFor("pool:vm-1"))
    }
    @Test fun aFailedReadAfterTheMarkIsNotRetriedByItselfBecauseStaleIsCleared() = runTest {
        val state = state(); val backend = Backend(this@DepthControllerTest)
        val controller = DepthController(backgroundScope, backend, state)
        controller.loadVm("vm-1"); runCurrent()
        controller.markVmStale("pool", "vm-1")
        backend.onVm = { error("private detail") }
        controller.loadVm("vm-1"); runCurrent()
        val slot = state.value.depth.vm
        assertFalse("the read in flight cleared the mark; a failure leaves a problem, which stops automatic retries", slot.staleFor("pool:vm-1"))
        assertNotNull(slot.problemFor("pool:vm-1")); assertNotNull("and the last value stays", slot.valueFor("pool:vm-1"))
    }
    @Test fun markingAnObjectThatIsNotHeldOrIsAnotherVmDoesNothing() = runTest {
        val state = state(); val backend = Backend(this@DepthControllerTest)
        val controller = DepthController(backgroundScope, backend, state)
        controller.markVmStale("pool", "vm-1")
        assertEquals("nothing held yet: the page loads it on first appearance", InfoSlot<VmInfo>(), state.value.depth.vm)
        controller.loadVm("vm-1"); runCurrent()
        controller.markVmStale("pool", "vm-2"); controller.markVmStale("other-pool", "vm-1")
        assertFalse(state.value.depth.vm.stale)
        assertNotNull(state.value.depth.vm.valueFor("pool:vm-1"))
    }
    @Test fun aReopenedVmNoLongerShowsTheTimeOfItsFirstRead() = runTest {
        // Open → read at t=1; close; re-open (openDetails marks stale) → the page reads again and shows t=3.
        val state = state(); val backend = Backend(this@DepthControllerTest); var observed = 1L
        backend.onVm = { vmInfoAt(it, observed) }
        val controller = DepthController(backgroundScope, backend, state)
        controller.loadVm("vm-1"); runCurrent()
        observed = 3L
        controller.markVmStale("pool", "vm-1"); controller.loadVm("vm-1"); runCurrent()
        assertEquals(3L, state.value.depth.vm.valueFor("pool:vm-1")!!.observedAt)
        // A connection change still drops everything, stale mark included.
        controller.markVmStale("pool", "vm-1")
        controller.invalidate(); assertEquals(DepthState(), state.value.depth)
    }

    // ---- integration with the live metrics controller ----

    @Test fun liveRefreshPausesWhileHistoryIsShownAndOpeningAnotherTargetResetsIt() = runTest {
        val state = state(); val backend = Backend(this@DepthControllerTest)
        val controller = ObservabilityController(backgroundScope, backend, state)
        controller.openMetrics(host); runCurrent(); assertEquals(1, backend.metricReads)
        controller.depth.selectRange(HistoryRange.TEN_MINUTES); runCurrent()
        controller.refreshMetrics(); runCurrent(); assertEquals("history replaces live polling", 1, backend.metricReads)
        controller.depth.selectRange(null); runCurrent(); assertEquals("returning to live reads again", 2, backend.metricReads)
        controller.depth.selectRange(HistoryRange.TWO_HOURS); runCurrent()
        controller.openMetrics(vm); runCurrent()
        assertEquals(HistoryState(), state.value.depth.history)
        assertEquals(vm, state.value.metricTarget)
        controller.depth.loadVm("vm-1"); runCurrent(); assertNotNull(state.value.depth.vm.value)
        controller.invalidate(); assertEquals(DepthState(), state.value.depth); assertNull(state.value.metricTarget)
    }

    // --- stop watching ---
    @Test fun aWatchedOperationDoesNotHoldChartsOrDetailReads() = runTest {
        val state = state(); val backend = Backend(this@DepthControllerTest)
        state.value = state.value.copy(operationBusy = true, operationWatching = true)
        val controller = DepthController(backgroundScope, backend, state)
        controller.loadHost("host-1"); controller.loadNetworks(); runCurrent()
        assertEquals(2, backend.reads)
        controller.selectRange(HistoryRange.TEN_MINUTES); runCurrent()
        assertEquals(3, backend.reads)
    }
    // --- end stop watching ---
}
