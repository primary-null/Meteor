package com.meteor.app

import com.meteor.app.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ObservabilityControllerTest {
    private val profile = Profile("fixture", "Example", "https://example.invalid/", "tester", "fixture", "a".repeat(64))
    private val target = MetricTarget("pool", "vm", MetricKind.VM, "Example VM")
    private fun state() = MutableStateFlow(MeteorState(profiles = listOf(profile), selectedId = profile.id, connected = true,
        inventory = Inventory("pool", "Pool", emptyList(), listOf(Guest("vm", "Example VM", "Running", "pool", null, 2, 1024)), 0, 0, 1)))
    private class Backend : MeteorGateway {
        var reads = 0
        var metric: suspend (MetricTarget) -> MetricSnapshot = { MetricSnapshot(it, 1000, emptyList(), 0) }
        var feed: suspend (String) -> PoolActivity = { PoolActivity(it, 1000, ReadSection(emptyList()), ReadSection(emptyList())) }
        override suspend fun metrics(profile: Profile, target: MetricTarget): MetricSnapshot { reads++; return metric(target) }
        override suspend fun activity(profile: Profile, authority: String): PoolActivity { reads++; return feed(authority) }
        override suspend fun inventory(profile: Profile): Inventory = error("Unexpected inventory")
        override suspend fun details(profile: Profile, authority: String, uuid: String): VmDetails = error("Unexpected details")
        override suspend fun <T> powerConnection(profile: Profile, action: suspend PowerConnection.() -> T): T = error("No power requests")
    }
    @Test fun lateMetricsCannotSurviveInvalidationEvenIfCancellationIsIgnored() = runTest {
        val state = state(); val backend = Backend(); val gate = CompletableDeferred<MetricSnapshot>()
        backend.metric = { withContext(NonCancellable) { gate.await() } }
        val controller = ObservabilityController(backgroundScope, backend, state)
        controller.openMetrics(target); runCurrent(); assertTrue(state.value.metricsBusy)
        controller.invalidate(); gate.complete(MetricSnapshot(target, 1, emptyList(), 0)); runCurrent()
        assertNull(state.value.metricTarget); assertTrue(state.value.metricSamples.isEmpty()); assertFalse(state.value.metricsBusy)
    }
    @Test fun originAndPoolChangesDiscardLateActivity() = runTest {
        val state = state(); val backend = Backend(); val gate = CompletableDeferred<PoolActivity>()
        backend.feed = { withContext(NonCancellable) { gate.await() } }
        val controller = ObservabilityController(backgroundScope, backend, state)
        controller.refreshActivity(); runCurrent()
        state.value = state.value.copy(profiles = listOf(profile.copy(endpoint = "https://other.invalid/")))
        gate.complete(PoolActivity("pool", 1, ReadSection(emptyList()), ReadSection(emptyList()))); runCurrent()
        assertNull(state.value.poolActivity); assertFalse(state.value.activityBusy)
        state.value = state.value.copy(profiles = listOf(profile))
        backend.feed = { PoolActivity("other-pool", 1, ReadSection(emptyList()), ReadSection(emptyList())) }
        controller.refreshActivity(); runCurrent()
        assertNull(state.value.poolActivity); assertNotNull(state.value.activityProblem)
    }
    @Test fun failedReadsKeepLastKnownSamplesAndDoNotExposeExceptionText() = runTest {
        val state = state(); val backend = Backend(); val controller = ObservabilityController(backgroundScope, backend, state)
        controller.openMetrics(target); runCurrent()
        backend.metric = { error("private transport detail") }
        controller.refreshMetrics(); runCurrent()
        assertEquals(1, state.value.metricSamples.size)
        assertTrue(state.value.metricsProblem!!.contains("last known"))
        assertFalse(state.value.metricsProblem!!.contains("private"))
    }
    @Test fun busyDisconnectedAndUnknownTargetsCannotStartReads() = runTest {
        val state = state(); val backend = Backend(); val controller = ObservabilityController(backgroundScope, backend, state)
        controller.openMetrics(target.copy(uuid = "missing")); runCurrent(); assertEquals(0, backend.reads)
        state.value = state.value.copy(operationBusy = true)
        controller.openMetrics(target); controller.refreshActivity(); runCurrent(); assertEquals(0, backend.reads)
        state.value = state.value.copy(operationBusy = false, connected = false)
        controller.refreshMetrics(); controller.refreshActivity(); runCurrent(); assertEquals(0, backend.reads)
    }
    @Test fun unavailableSectionDoesNotDiscardReadableAlerts() = runTest {
        val state = state(); val backend = Backend(); val controller = ObservabilityController(backgroundScope, backend, state)
        backend.feed = { PoolActivity(it, 1, ReadSection(emptyList(), issue = ReadIssue.FORBIDDEN),
            ReadSection(listOf(PoolAlert("alert", "Example alert", 2, "VM", "vm", null, "Details")))) }
        controller.refreshActivity(); runCurrent()
        assertEquals(ReadIssue.FORBIDDEN, state.value.poolActivity?.tasks?.issue)
        assertEquals("alert", state.value.poolActivity?.alerts?.items?.single()?.uuid)
        assertNull(state.value.activityProblem)
    }
    @Test fun retryPreservesLastKnownWarningsUntilReplacementReadsSucceed() = runTest {
        val state = state(); val backend = Backend(); val controller = ObservabilityController(backgroundScope, backend, state)
        controller.openMetrics(target); controller.refreshActivity(); runCurrent()
        backend.metric = { error("Read failed") }; backend.feed = { error("Read failed") }
        controller.refreshMetrics(); controller.refreshActivity(); runCurrent()
        val metricsProblem = state.value.metricsProblem; val activityProblem = state.value.activityProblem
        assertNotNull(metricsProblem); assertNotNull(activityProblem)
        val sample = CompletableDeferred<MetricSnapshot>(); val activity = CompletableDeferred<PoolActivity>()
        backend.metric = { sample.await() }; backend.feed = { activity.await() }
        controller.refreshMetrics(); controller.refreshActivity(); runCurrent()
        assertEquals(metricsProblem, state.value.metricsProblem); assertEquals(activityProblem, state.value.activityProblem)
        sample.complete(MetricSnapshot(target, 2000, emptyList(), 0))
        activity.complete(PoolActivity("pool", 2000, ReadSection(emptyList()), ReadSection(emptyList()))); runCurrent()
        assertNull(state.value.metricsProblem); assertNull(state.value.activityProblem)
        assertEquals(2000L, state.value.metricSamples.last().observedAt)
        assertEquals(2000L, state.value.poolActivity?.observedAt)
    }
    @Test fun switchingTargetDiscardsLateSamplesEvenWhenCancellationIsIgnored() = runTest {
        val state = state(); val backend = Backend(); val controller = ObservabilityController(backgroundScope, backend, state)
        val other = target.copy(uuid = "other", name = "Other VM")
        state.value = state.value.copy(inventory = state.value.inventory!!.let { it.copy(guests = it.guests + it.guests.single().copy(uuid = other.uuid)) })
        val late = CompletableDeferred<MetricSnapshot>()
        backend.metric = { if (it == target) withContext(NonCancellable) { late.await() } else MetricSnapshot(it, 2000, emptyList(), 0) }
        controller.openMetrics(target); runCurrent(); controller.openMetrics(other); runCurrent()
        late.complete(MetricSnapshot(target, 3000, emptyList(), 0)); runCurrent()
        assertEquals(other, state.value.metricTarget)
        assertEquals(listOf(other), state.value.metricSamples.map { it.target })
    }

    // --- stop watching ---
    @Test fun aWatchedOperationDoesNotHoldMetricsOrActivity() = runTest {
        val state = state(); val backend = Backend(); val controller = ObservabilityController(backgroundScope, backend, state)
        state.value = state.value.copy(operationBusy = true, operationWatching = true)
        controller.openMetrics(target); controller.refreshActivity(); runCurrent()
        assertEquals(2, backend.reads); assertEquals(1, state.value.metricSamples.size); assertNotNull(state.value.poolActivity)
    }
    // --- end stop watching ---
}
