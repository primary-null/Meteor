package com.meteor.app.data

import org.junit.Assert.*
import org.junit.Test

/** Edge cases of the live-view plan and readings that the recorded XCP-ng 8.3 answers (Live83HistoryTest) do not reach. */
class LiveMetricsTest {
    private val vm = MetricTarget("pool", "vm", MetricKind.VM, "Example VM")
    private val host = MetricTarget("pool", "host", MetricKind.HOST, "Example host")
    private fun source(name: String, enabled: Boolean = true, unit: String = "(fraction)", max: Any? = 1.0) =
        mapOf("name_label" to name, "enabled" to enabled, "units" to unit, "max" to max, "value" to 0.0)
    private fun plan(target: MetricTarget, vararg names: String, disks: Set<String>? = null) =
        LiveMetrics.plan(target, names.map { source(it) }, disks)
    private fun names(plan: MetricPlan) = plan.items.map { it.name }

    @Test fun disabledSourcesAreNeverChosen() {
        val plan = LiveMetrics.plan(vm, listOf(source("cpu_usage", enabled = false), source("memory", unit = "B", max = Double.POSITIVE_INFINITY),
            source("memory_internal_free", enabled = false, unit = "KiB")), null)
        assertEquals(listOf("memory"), names(plan))
        assertEquals(3, plan.advertisedCount)
    }

    @Test fun anInfiniteMaximumIsNoLimitAndAFractionDefaultsToOne() {
        val plan = LiveMetrics.plan(vm, listOf(source("memory", unit = "B", max = Double.POSITIVE_INFINITY), source("cpu_usage", max = Double.NaN),
            source("vif_0_rx", unit = "B/s", max = "inf"), source("vif_0_tx", unit = "B/s", max = 100.0)), null)
        assertNull(plan.items.first { it.name == "memory" }.limit)
        assertEquals("a fraction without a finite maximum is still drawn 0 to 100%", 1.0, plan.items.first { it.name == "cpu_usage" }.limit!!, 0.0)
        assertNull(plan.items.first { it.name == "vif_0_rx" }.limit)
        assertEquals(100.0, plan.items.first { it.name == "vif_0_tx" }.limit!!, 0.0)
    }

    @Test fun perCoreCpuOnlyForTwoToFourCores() {
        fun cpuNames(cores: Int) = names(plan(vm, "cpu_usage", *(0 until cores).map { "cpu$it" }.toTypedArray())).filter { it.startsWith("cpu") }
        assertEquals(listOf("cpu_usage"), cpuNames(1))
        assertEquals(listOf("cpu_usage", "cpu0", "cpu1"), cpuNames(2))
        assertEquals(listOf("cpu_usage", "cpu0", "cpu1", "cpu2", "cpu3"), cpuNames(4))
        assertEquals("more cores are summarised by the average", listOf("cpu_usage"), cpuNames(5))
        assertEquals("numeric order, not text order", listOf("cpu_avg", "cpu2", "cpu10"), names(plan(host, "cpu_avg", "cpu10", "cpu2")))
        assertEquals(listOf("cpu_avg"), names(plan(host, "cpu_avg", "cpu0-C0", "cpu0-C1", "cpu1-C0", "cpu1-C1")))
    }

    @Test fun aSourceWithoutItsPairIsLeftOut() {
        assertEquals(listOf("vif_0_rx", "vif_0_tx"), names(plan(vm, "vif_0_rx", "vif_0_tx", "vif_1_rx", "vbd_xvda_read")))
        assertEquals(emptyList<String>(), names(plan(vm, "vbd_xvda_read")))
    }

    @Test fun diskAndInterfaceCountsAreCapped() {
        val disks = ('a'..'f').flatMap { listOf("vbd_xvd${it}_read", "vbd_xvd${it}_write") }.toTypedArray()
        assertEquals(LiveMetrics.MAX_DISKS * 2, plan(vm, *disks).items.size)
        val vifs = (0..5).flatMap { listOf("vif_${it}_rx", "vif_${it}_tx") }.toTypedArray()
        assertEquals(listOf("vif_0_rx", "vif_0_tx", "vif_1_rx", "vif_1_tx"), names(plan(vm, *vifs)))
        val ten = (0..10).flatMap { listOf("vif_${it}_rx", "vif_${it}_tx") }.toTypedArray()
        assertEquals("interfaces are ordered by number, not by text", "vif_1_rx", names(plan(vm, *ten))[2])
    }

    @Test fun cdDrivesAreLeftOutWhenTheDriveListIsKnown() {
        val sources = arrayOf("vbd_xvda_read", "vbd_xvda_write", "vbd_xvdd_read", "vbd_xvdd_write")
        assertEquals(listOf("vbd_xvda_read", "vbd_xvda_write"), names(plan(vm, *sources, disks = setOf("xvda"))))
        assertEquals(emptyList<String>(), names(plan(vm, *sources, disks = emptySet())))
    }

    @Test fun theAggregateNicIsAllNicsAndOnlyWhenThereAreSeveralNics() {
        val one = names(plan(host, "pif_eth0_rx", "pif_eth0_tx", "pif_aggr_rx", "pif_aggr_tx"))
        assertEquals(listOf("pif_eth0_rx", "pif_eth0_tx"), one)
        val two = plan(host, "pif_aggr_rx", "pif_aggr_tx", "pif_eth1_rx", "pif_eth1_tx", "pif_eth0_rx", "pif_eth0_tx")
        assertEquals(listOf("pif_aggr_rx", "pif_aggr_tx", "pif_eth0_rx", "pif_eth0_tx", "pif_eth1_rx", "pif_eth1_tx"), names(two))
        assertEquals("All NICs receive", two.items.first().label)
        assertEquals(emptyList<String>(), names(plan(host, "pif_aggr_rx", "pif_aggr_tx")))
        val four = names(plan(host, *(listOf("aggr") + (0..3).map { "eth$it" }).flatMap { listOf("pif_${it}_rx", "pif_${it}_tx") }.toTypedArray()))
        assertEquals("the aggregate and two NICs", 6, four.size)
        assertEquals(listOf("pif_eth0_rx", "pif_eth0_tx", "pif_eth1_rx", "pif_eth1_tx"), four.drop(2))
    }

    @Test fun hostMemoryIsUsedFreeTotalAndUsedIsDerived() {
        val memory = LiveMetrics.plan(host, listOf(source("memory_total_kib", unit = "KiB", max = Double.POSITIVE_INFINITY),
            source("memory_free_kib", unit = "KiB", max = Double.POSITIVE_INFINITY)), null)
        assertEquals(listOf("memory_used", "memory_free_kib", "memory_total_kib"), names(memory))
        assertEquals(listOf("memory_free_kib", "memory_total_kib"), memory.queryNames)
        fun used(values: Map<String, Double?>) = LiveMetrics.snapshot(host, memory, values, 1).readings.first { it.name == "memory_used" }
        assertEquals(600.0, used(mapOf("memory_total_kib" to 1000.0, "memory_free_kib" to 400.0)).value!!, 0.0)
        assertNull("free larger than total is invalid", used(mapOf("memory_total_kib" to 1000.0, "memory_free_kib" to 1200.0)).value)
        assertNull(used(mapOf("memory_total_kib" to 1000.0, "memory_free_kib" to null)).value)
        assertEquals("No valid sample", used(mapOf("memory_total_kib" to Double.NaN, "memory_free_kib" to 5.0)).unavailable)
        assertEquals("KiB", used(mapOf("memory_total_kib" to 1.0, "memory_free_kib" to 0.0)).unit)
        assertEquals("without both sources there is no derived reading", listOf("memory_free_kib"), names(plan(host, "memory_free_kib")))
        assertEquals(listOf("memory_total_kib"), names(plan(host, "memory_total_kib")))
    }

    @Test fun aVmHasAllocatedAndGuestReportedFreeMemoryAndNoInventedUsedFigure() {
        val plan = LiveMetrics.plan(vm, listOf(source("memory", unit = "B", max = Double.POSITIVE_INFINITY),
            source("memory_internal_free", unit = "KiB", max = Double.POSITIVE_INFINITY), source("memory_target", unit = "B")), null)
        assertEquals(listOf("memory", "memory_internal_free"), names(plan))
        assertEquals(listOf("Allocated memory", "Guest-reported free memory"), plan.items.map { it.label })
    }

    @Test fun nonFiniteNegativeAndMissingSamplesAreGapsNeverNumbers() {
        val plan = plan(vm, "cpu_usage", "memory", "vif_0_rx", "vif_0_tx")
        val values = mapOf("cpu_usage" to Double.NaN, "memory" to Double.POSITIVE_INFINITY, "vif_0_rx" to -1.0, "vif_0_tx" to 5.0)
        val snapshot = LiveMetrics.snapshot(vm, plan, values, 1)
        assertEquals(listOf(null, null, null, 5.0), snapshot.readings.map { it.value })
        assertEquals(listOf("No valid sample", "No valid sample", "No valid sample", null), snapshot.readings.map { it.unavailable })
        assertNull(LiveMetrics.sample("Infinity")); assertNull(LiveMetrics.sample(null)); assertNull(LiveMetrics.sample(Double.NEGATIVE_INFINITY))
        assertEquals(0.0, LiveMetrics.sample(0L)!!, 0.0); assertEquals(1.5, LiveMetrics.sample("1.5")!!, 0.0)
        assertEquals("a missing value is not zero", null, LiveMetrics.snapshot(vm, plan, emptyMap(), 1).readings.first().value)
    }

    @Test fun unitsLoseTheServersBrackets() {
        assertEquals("fraction", LiveMetrics.unit("(fraction)")); assertEquals("B/s", LiveMetrics.unit("B/s")); assertEquals("", LiveMetrics.unit(null))
        assertEquals("KiB", LiveMetrics.unit(" KiB "))
    }

    @Test fun aSnapshotWithoutSupportedSourcesSaysSo() {
        val snapshot = LiveMetrics.snapshot(vm, plan(vm, "runstate_fullrun"), emptyMap(), 1)
        assertTrue(snapshot.readings.isEmpty()); assertEquals("No supported metric sources were reported.", snapshot.note)
    }

    @Test fun duplicateBlankAndOversizedSourceIdentitiesAreRejected() {
        assertTrue(runCatching { LiveMetrics.plan(vm, listOf(source("cpu0"), source("cpu0")), null) }.isFailure)
        assertTrue(runCatching { LiveMetrics.plan(vm, List(4097) { source("cpu$it") }, null) }.isFailure)
        assertTrue(runCatching { LiveMetrics.plan(vm, listOf(source(" ")), null) }.isFailure)
        assertTrue(runCatching { LiveMetrics.plan(vm, listOf(mapOf("enabled" to true)), null) }.isFailure)
    }

    @Test fun queryDataSourceIsAReadAndOtherNonGetMethodsStayWrites() {
        for (method in listOf("VM.query_data_source", "host.query_data_source", "VM.get_data_sources", "event.from")) assertTrue(method, XapiMethods.isRead(method))
        for (method in listOf("host.call_plugin", "Async.VM.start", "message.destroy", "session.login_with_password", "VM.query_data_source_now", "query_data_source.destroy"))
            assertFalse(method, XapiMethods.isRead(method))
    }

    // ---- the zero baseline ----

    @Test fun everyLiveSparklineStartsAtZeroSoOneToTwoPercentIsNotASpike() {
        // Percentages: 0 to 100%, whatever the samples are.
        assertEquals(1.0, LiveMetrics.scaleTop(1.0, listOf(0.01, 0.02)), 0.0)
        assertEquals(1.0, LiveMetrics.scaleTop(1.0, emptyList()), 0.0)
        assertEquals("a sample above the limit is still drawn", 1.5, LiveMetrics.scaleTop(1.0, listOf(1.5)), 0.0)
        // Rates and amounts: from zero up to a round figure above the largest sample, never a window around the samples.
        assertEquals(2.0, LiveMetrics.scaleTop(null, listOf(1.2, 1.3)), 0.0)
        assertEquals(2_000_000_000.0, LiveMetrics.scaleTop(null, listOf(1_073_729_536.0)), 0.0)
        assertEquals(1.0, LiveMetrics.scaleTop(null, listOf(0.0, 0.0)), 0.0)
        assertEquals(1.0, LiveMetrics.scaleTop(null, emptyList()), 0.0)
        assertEquals("non-finite samples are not part of the scale", 5.0, LiveMetrics.scaleTop(null, listOf(Double.NaN, 3.0, Double.POSITIVE_INFINITY)), 0.0)
        assertEquals(100.0, LiveMetrics.scaleTop(100.0, listOf(5.0)), 0.0)
        // A move from 1% to 2% is 1% of the height, not the full height.
        val top = LiveMetrics.scaleTop(1.0, listOf(0.01, 0.02))
        assertTrue((0.02 - 0.01) / top <= 0.011)
    }

    // ---- the cache ----

    @Test fun theCacheKeepsAChoiceForAMinuteAndForgetsItOnRequest() {
        val cache = LiveCache(ttlMillis = 60_000)
        val key = LiveCache.Key("profile", "https://h/", MetricKind.VM, "uuid")
        val plan = plan(vm, "cpu_usage")
        assertNull(cache.plan(key, 0))
        cache.putPlan(key, plan, 1_000)
        assertSame(plan, cache.plan(key, 1_000)); assertSame(plan, cache.plan(key, 61_000))
        assertNull("stale after a minute", cache.plan(key, 61_001))
        assertNull("a clock that went backwards is not trusted", cache.plan(key, 999))
        cache.putPlan(key, plan, 2_000); cache.dropPlan(key)
        assertNull(cache.plan(key, 2_000))
        assertNull(cache.plan(key.copy(profileId = "other"), 1_000))
        cache.putDisks(key, setOf("xvda"), 0)
        assertEquals(setOf("xvda"), cache.disks(key, 10)); assertNull(cache.disks(key.copy(kind = MetricKind.HOST), 10))
    }
}
