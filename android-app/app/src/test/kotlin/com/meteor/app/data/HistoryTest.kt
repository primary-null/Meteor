package com.meteor.app.data

import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class HistoryTest {
    private val nan = Double.NaN
    private val hostTarget = MetricTarget("pool", "host-1", MetricKind.HOST, "Host 1")
    private val vmTarget = MetricTarget("pool", "vm-1", MetricKind.VM, "VM 1")
    private val times = listOf(1_000L, 1_005L, 1_010L)
    private fun update(kind: String, uuid: String, vararg columns: Pair<String, List<Double>>, timestamps: List<Long> = times, step: Long = 5) =
        RrdUpdates(timestamps.first(), step, timestamps.last(), timestamps,
            columns.map { (name, values) -> RrdColumn(RrdLegend("AVERAGE", kind, uuid, name), values) }, columns.size)
    private fun host(vararg columns: Pair<String, List<Double>>) =
        HistoryMapper.build(hostTarget, HistoryRange.TEN_MINUTES, update("host", "host-1", *columns), 5_000, 1_010)
    private fun vm(vararg columns: Pair<String, List<Double>>) =
        HistoryMapper.build(vmTarget, HistoryRange.TEN_MINUTES, update("vm", "vm-1", *columns), 5_000, 1_010)
    private fun HistorySnapshot.byId(id: String) = series.single { it.id == id }

    // ---- range, start and interval ----

    @Test fun rangesMapToTheThreeRrdResolutions() {
        assertEquals(listOf(600L to 5L, 7_200L to 60L, 604_800L to 3_600L), HistoryRange.entries.map { it.spanSeconds to it.stepSeconds })
        assertEquals(listOf("10 minutes", "2 hours", "1 week"), HistoryRange.entries.map { it.label })
    }
    @Test fun queryStartIsAlignedDownToTheStepAndUsesTheRangeAsInterval() {
        val now = 1_700_000_123L
        assertEquals(HistoryQuery(1_699_999_520, 5), HistoryRange.TEN_MINUTES.query(now))
        assertEquals(HistoryQuery(1_699_992_900, 60), HistoryRange.TWO_HOURS.query(now))
        assertEquals(HistoryQuery(1_699_394_400, 3_600), HistoryRange.ONE_WEEK.query(now))
        HistoryRange.entries.forEach { assertEquals(0L, it.query(now).startSeconds % it.stepSeconds) }
        assertEquals(0L, HistoryRange.ONE_WEEK.query(100).startSeconds)
    }
    @Test fun incrementalQueriesRestartTwoStepsBeforeTheNewestRowButNeverBeforeTheWindow() {
        val now = 1_700_000_123L
        assertEquals(1_700_000_090L, HistoryRange.TEN_MINUTES.query(now, 1_700_000_100).startSeconds)
        assertEquals(HistoryRange.TEN_MINUTES.query(now).startSeconds, HistoryRange.TEN_MINUTES.query(now, 1_000_000_000).startSeconds)
        assertEquals(1_700_000_100L - 120, HistoryRange.TWO_HOURS.query(now, 1_700_000_100).startSeconds)
    }

    // ---- host series ----

    @Test fun hostSeriesCoverCpuLoadMemoryAndNicsWithDeclaredUnits() {
        val snapshot = host("cpu_avg" to listOf(.1, nan, .3), "loadavg" to listOf(.5, .6, .7),
            "memory_total_kib" to listOf(1000.0, 1000.0, 1000.0), "memory_free_kib" to listOf(400.0, nan, 1200.0),
            "pif_eth0_rx" to listOf(10.0, 20.0, 30.0), "pif_eth0_tx" to listOf(1.0, 2.0, 3.0),
            "pif_eth0_rx_errors" to listOf(0.0, 0.0, 0.0), "pif_eth1_rx" to listOf(5.0, 5.0, 5.0),
            "iops_read_abcd1234" to listOf(1.0, 1.0, 1.0), "cpu0" to listOf(.9, .9, .9))
        assertEquals(listOf("cpu_avg", "loadavg", "memory_used", "memory_total", "pif_eth0_rx", "pif_eth0_tx", "pif_eth1_rx"), snapshot.series.map { it.id })
        assertEquals(listOf("CPU", "Load average", "Memory", "Memory", "NIC eth0", "NIC eth0", "NIC eth1"), snapshot.series.map { it.card })
        assertEquals(times, snapshot.timestamps); assertEquals(5L, snapshot.stepSeconds)
        assertEquals(1_010L, snapshot.serverTimeSeconds); assertTrue(snapshot.notes.isEmpty())
        val cpu = snapshot.byId("cpu_avg")
        assertEquals(HistoryUnit.FRACTION, cpu.unit); assertEquals("fraction", cpu.declaredUnit)
        assertTrue("a missing sample is a gap, not zero", cpu.values[1].isNaN())
        val used = snapshot.byId("memory_used")
        assertEquals(600.0 * 1024, used.values[0], 0.0)
        assertTrue("one missing operand makes the derived value missing", used.values[1].isNaN())
        assertTrue("free larger than total is invalid, not a negative amount", used.values[2].isNaN())
        assertEquals("KiB", used.declaredUnit); assertEquals(HistoryUnit.BYTES, used.unit)
        assertEquals(1000.0 * 1024, snapshot.byId("memory_total").values[0], 0.0)
        assertEquals(HistoryUnit.BYTES_PER_SECOND, snapshot.byId("pif_eth0_rx").unit); assertEquals("bytes/s", snapshot.byId("pif_eth0_rx").declaredUnit)
        assertEquals(listOf("Receive", "Transmit"), snapshot.series.filter { it.card == "NIC eth0" }.map { it.label })
        assertEquals(HistoryUnit.LOAD, snapshot.byId("loadavg").unit)
    }
    @Test fun memoryNeedsBothTotalAndFreeAndNicCardsAreCapped() {
        assertTrue(host("memory_total_kib" to listOf(1.0, 1.0, 1.0)).series.isEmpty())
        val many = (0..9).flatMap { listOf("pif_eth${it}_rx" to listOf(1.0, 1.0, 1.0), "pif_eth${it}_tx" to listOf(1.0, 1.0, 1.0)) }
        val snapshot = host(*many.toTypedArray())
        assertEquals((0 until HistoryMapper.MAX_NIC_CARDS).map { "NIC eth$it" }, snapshot.series.map { it.card }.distinct())
    }

    // ---- VM series ----

    @Test fun vmCpuIsTheAverageOfFiniteVcpusAndMemoryUsesGuestFreeWhenToolsReportIt() {
        val snapshot = vm("cpu0" to listOf(.2, nan, nan), "cpu1" to listOf(.4, .6, nan), "cpu_usage" to listOf(.9, .9, .9),
            "memory" to listOf(2048.0, 2048.0, 2048.0), "memory_internal_free" to listOf(512.0, nan, 256.0),
            "vif_0_rx" to listOf(1.0, 2.0, 3.0), "vif_0_tx" to listOf(1.0, 1.0, 1.0), "vif_0_rx_errors" to listOf(0.0, 0.0, 0.0),
            "vbd_xvda_read" to listOf(7.0, 7.0, 7.0), "vbd_xvda_write" to listOf(8.0, 8.0, 8.0),
            "vbd_xvda_iops_read" to listOf(1.0, 1.0, 1.0), "vbd_xvda_read_latency" to listOf(1.0, 1.0, 1.0))
        val cpu = snapshot.byId("cpu_avg")
        assertEquals("Average of 2 vCPUs", cpu.label)
        assertEquals(.3, cpu.values[0], 1e-9); assertEquals(.6, cpu.values[1], 1e-9)
        assertTrue("no vCPU reported at that time", cpu.values[2].isNaN())
        assertEquals(2048.0, snapshot.byId("memory").values[0], 0.0); assertEquals("bytes", snapshot.byId("memory").declaredUnit)
        val free = snapshot.byId("memory_internal_free")
        assertEquals(512.0 * 1024, free.values[0], 0.0); assertTrue(free.values[1].isNaN()); assertEquals("KiB", free.declaredUnit)
        assertEquals(listOf("cpu_avg", "memory", "memory_internal_free", "vif_0_rx", "vif_0_tx", "vbd_xvda_read", "vbd_xvda_write"), snapshot.series.map { it.id })
        assertEquals(listOf("CPU", "Memory", "Memory", "Network VIF 0", "Network VIF 0", "Disk xvda", "Disk xvda"), snapshot.series.map { it.card })
        assertTrue(snapshot.notes.isEmpty())
    }
    @Test fun vmWithoutGuestToolsGetsANoteInsteadOfInventedFreeMemory() {
        val snapshot = vm("cpu0" to listOf(.1, .1, .1), "memory" to listOf(1.0, 1.0, 1.0))
        assertTrue(snapshot.series.none { it.id == "memory_internal_free" })
        assertTrue(snapshot.notes.single().contains("guest tools"))
    }
    @Test fun emptyResultsExplainThemselvesAndAmbiguousMetricNamesAreRejected() {
        assertEquals(listOf("The server returned no history for this host."), host().notes)
        assertEquals(listOf("The server returned no history for this VM."), vm().notes)
        assertTrue(runCatching { vm("cpu0" to listOf(1.0, 1.0, 1.0), "cpu0" to listOf(2.0, 2.0, 2.0)) }.isFailure)
    }
    @Test fun vifAndDiskCardsAreCapped() {
        val vifs = (0..7).flatMap { listOf("vif_${it}_rx" to listOf(1.0, 1.0, 1.0)) }
        assertEquals(HistoryMapper.MAX_VIF_CARDS, vm(*vifs.toTypedArray()).series.size)
        val disks = ('a'..'h').map { "vbd_xvd${it}_read" to listOf(1.0, 1.0, 1.0) }
        assertEquals(HistoryMapper.MAX_DISK_CARDS, vm(*disks.toTypedArray()).series.size)
    }

    // ---- merging incremental reads ----

    private fun snap(times: List<Long>, vararg series: Pair<String, List<Double>>, range: HistoryRange = HistoryRange.TEN_MINUTES, observedAt: Long = 1,
        target: MetricTarget = hostTarget) =
        HistorySnapshot(target, range, range.stepSeconds, times, series.map { (id, values) ->
            HistorySeries("card", id, id, HistoryGroup.CPU, HistoryUnit.FRACTION, "fraction", values) }, observedAt)

    @Test fun mergeReplacesOverlappingRowsWithTheNewerReadAndAppendsNewOnes() {
        val old = snap(listOf(0, 5, 10), "a" to listOf(1.0, 2.0, nan))
        val next = snap(listOf(10, 15), "a" to listOf(3.0, 4.0), observedAt = 9)
        val merged = HistoryWindow.merge(old, next)
        assertEquals(listOf(0L, 5L, 10L, 15L), merged.timestamps)
        assertEquals(listOf(1.0, 2.0, 3.0, 4.0), merged.series.single().values)
        assertEquals(9L, merged.observedAt)
    }
    @Test fun mergeTrimsToTheRangeSpanAndKeepsSeriesThatAppearOrDisappear() {
        val old = snap(listOf(0, 5, 10), "a" to listOf(1.0, 2.0, 3.0), "gone" to listOf(1.0, 1.0, 1.0))
        val next = snap(listOf(605, 610), "a" to listOf(8.0, 9.0), "new" to listOf(5.0, 6.0))
        val merged = HistoryWindow.merge(old, next)
        assertEquals(listOf(10L, 605L, 610L), merged.timestamps.filter { it >= 10 })
        assertTrue(merged.timestamps.all { it >= 610 - HistoryRange.TEN_MINUTES.spanSeconds })
        assertEquals(listOf("a", "new", "gone"), merged.series.map { it.id })
        val new = merged.series.single { it.id == "new" }
        assertTrue("rows the new series was not part of stay missing", new.values.dropLast(2).all { it.isNaN() })
        assertEquals(listOf(5.0, 6.0), new.values.takeLast(2))
        val gone = merged.series.single { it.id == "gone" }
        assertTrue("a series absent from a newer row is missing there, not carried forward", gone.values.takeLast(2).all { it.isNaN() })
        merged.series.forEach { assertEquals(merged.timestamps.size, it.values.size) }
    }
    @Test fun mergeStartsOverWhenTheTargetRangeOrStepChangesAndKeepsHistoryOnAnEmptyDelta() {
        val old = snap(listOf(0, 5), "a" to listOf(1.0, 2.0))
        val next = snap(listOf(10), "a" to listOf(3.0))
        assertSame(next, HistoryWindow.merge(null, next))
        for (other in listOf(next.copy(target = vmTarget), next.copy(range = HistoryRange.TWO_HOURS), next.copy(stepSeconds = 60)))
            assertSame(other, HistoryWindow.merge(old, other))
        val empty = HistoryWindow.merge(old, snap(emptyList(), "a" to emptyList(), observedAt = 77))
        assertEquals(old.timestamps, empty.timestamps); assertEquals(old.series, empty.series); assertEquals(77L, empty.observedAt)
    }

    // ---- where history can be read ----

    @Test fun onlyTheCoordinatorsObjectsCanBeReadOverThePinnedConnection() {
        assertNull(HistoryRouting.hostReason("Host A", true))
        assertTrue(HistoryRouting.hostReason("Host B", false)!!.contains("Host B"))
        assertNull(HistoryRouting.vmReason("Running", "OpaqueRef:master", "OpaqueRef:master", null))
        val member = HistoryRouting.vmReason("Running", "OpaqueRef:member", "OpaqueRef:master", "Host B")!!
        assertTrue(member.contains("Host B")); assertTrue(member.contains("live values"))
        assertTrue(HistoryRouting.vmReason("Running", "OpaqueRef:member", "OpaqueRef:master", null)!!.contains("pool member"))
        assertTrue(HistoryRouting.vmReason("Halted", "OpaqueRef:NULL", "OpaqueRef:master", null)!!.contains("running VM"))
        assertNotNull(HistoryRouting.vmReason("Running", "OpaqueRef:NULL", "OpaqueRef:master", null))
        assertNotNull(HistoryRouting.vmReason("Running", "", "OpaqueRef:master", null))
        assertNotNull(HistoryRouting.vmReason("Paused", "OpaqueRef:master", "OpaqueRef:master", null))
    }

    // ---- chart math and formatting ----

    @Test fun gapsBreakLinesAtMissingValuesAndAtHolesInTime() {
        val t = listOf(0L, 5, 10, 15, 20, 40, 45)
        val v = listOf(1.0, 2.0, nan, 4.0, 5.0, 6.0, 7.0)
        // 20 -> 40 is a hole of four steps, so 3..4 and 5..6 are separate runs
        assertEquals(listOf(0..1, 3..4, 5..6), HistoryChartMath.runs(t, v, 5))
        assertEquals(listOf(0..0), HistoryChartMath.runs(listOf(0L, 5, 10), listOf(1.0, nan, nan), 5))
        assertEquals(emptyList<IntRange>(), HistoryChartMath.runs(listOf(0L, 5), listOf(nan, nan), 5))
        assertEquals(listOf(0..2), HistoryChartMath.runs(listOf(0L, 5, 12), listOf(1.0, 2.0, 3.0), 5))
        assertEquals(listOf(0..1, 2..2), HistoryChartMath.runs(listOf(0L, 5, 13), listOf(1.0, 2.0, 3.0), 5))
        assertTrue(runCatching { HistoryChartMath.runs(listOf(0L), emptyList(), 5) }.isFailure)
    }
    @Test fun axesStartAtZeroAndCpuStaysOnAHundredPercentScale() {
        assertEquals(1.0, HistoryChartMath.axisMax(HistoryUnit.FRACTION, listOf(listOf(.02, nan))), 0.0)
        assertEquals(2.0, HistoryChartMath.axisMax(HistoryUnit.FRACTION, listOf(listOf(1.5))), 0.0)
        assertEquals(1.0, HistoryChartMath.axisMax(HistoryUnit.BYTES, listOf(listOf(nan))), 0.0)
        assertEquals(1.0, HistoryChartMath.axisMax(HistoryUnit.BYTES, emptyList()), 0.0)
        assertEquals(5_000.0, HistoryChartMath.axisMax(HistoryUnit.BYTES_PER_SECOND, listOf(listOf(3_200.0), listOf(10.0))), 0.0)
        assertEquals(listOf(1.0, 2.0, 5.0, 10.0, 100.0), listOf(.7, 1.2, 3.0, 7.0, 100.0).map { HistoryChartMath.niceCeiling(it) })
        assertEquals(1.0, HistoryChartMath.niceCeiling(Double.NaN), 0.0)
        assertNull(HistoryChartMath.finiteBounds(listOf(nan, nan)))
        assertEquals(1.0 to 3.0, HistoryChartMath.finiteBounds(listOf(nan, 3.0, 1.0, 2.0)))
    }
    @Test fun valuesAreFormattedWithTheirUnitAndMissingStaysLabelled() {
        val l = Locale.ROOT
        assertEquals("25.6%", HistoryFormat.value(.256, HistoryUnit.FRACTION, l))
        assertEquals("0.42", HistoryFormat.value(.42, HistoryUnit.LOAD, l))
        assertEquals("1.5 GiB", HistoryFormat.value(1.5 * 1024 * 1024 * 1024, HistoryUnit.BYTES, l))
        assertEquals("512 B", HistoryFormat.value(512.0, HistoryUnit.BYTES, l))
        assertEquals("2.0 KiB/s", HistoryFormat.value(2048.0, HistoryUnit.BYTES_PER_SECOND, l))
        assertEquals("No data", HistoryFormat.value(nan, HistoryUnit.FRACTION, l))
        assertEquals("No data", HistoryFormat.value(Double.POSITIVE_INFINITY, HistoryUnit.BYTES, l))
        assertEquals("3.0 TiB", HistoryFormat.size(3L * 1024 * 1024 * 1024 * 1024, l))
    }
}
