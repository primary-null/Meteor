package com.meteor.app.data

import org.junit.Assert.*
import org.junit.Ignore
import org.junit.Test

/** `/rrd_updates` and the live data sources against answers recorded from XCP-ng 8.3.0. */
class Live83HistoryTest {
    private val hostUuid get() = Live83.host()["uuid"] as String
    private val guestUuid get() = Live83.guest()["uuid"] as String
    private val dom0Uuid get() = Live83.dom0()["uuid"] as String
    private fun host(name: String, wanted: (RrdLegend) -> Boolean = { it.kind == "host" && it.uuid == hostUuid }) =
        RrdParser.parse(Live83.bytes("$name.response.${if (name.endsWith("xml")) "xml" else "json"}"), wanted)
    private fun guest(name: String) = RrdParser.parse(Live83.bytes("$name.response.json")) { it.kind == "vm" && it.uuid == guestUuid }
    private val ranges = mapOf("10m" to HistoryRange.TEN_MINUTES, "2h" to HistoryRange.TWO_HOURS, "1w" to HistoryRange.ONE_WEEK)
    private fun target(kind: MetricKind, uuid: String) = MetricTarget(Live83.pool()["uuid"] as String, uuid, kind, "x")
    private fun metaStep(name: String) = ((JsonLite.parse(Live83.text("$name.response.json")) as Map<*, *>)["meta"] as Map<*, *>)["step"].toString().toLong()

    // ---- shape ---------------------------------------------------------------------------------------------------------

    @Test fun jsonMetaNumbersAndRowValuesAreStringsAndNaNIsTheWordNaN() {
        val root = JsonLite.parse(Live83.text("rrd-host-10m-json.response.json")) as Map<*, *>
        val meta = root["meta"] as Map<*, *>
        assertEquals(setOf("start", "step", "end", "rows", "columns", "legend"), meta.keys)
        assertTrue(meta["start"] is String && meta["step"] is String && meta["rows"] is String)
        val row = (root["data"] as List<*>).first() as Map<*, *>
        assertEquals(setOf("t", "values"), row.keys)
        assertTrue(row["t"] is String)
        assertTrue((row["values"] as List<*>).all { it is String })
        val answers = Live83.index.filter { (it["name"] as String).startsWith("rrd-") && (it["name"] as String).endsWith("json") }.map { Live83.text(it["responseFile"] as String) }
        assertTrue("NaN is written as the word NaN inside a string", answers.any { it.contains("\"NaN\"") })
        assertTrue(answers.none { it.contains("null") })
        assertTrue("never a bare NaN", answers.none { Regex("[:,\\[]NaN").containsMatchIn(it) })
    }

    @Test fun rowsArriveNewestFirstInJsonAndXmlAndTheParserReturnsThemOldestFirst() {
        for (name in listOf("rrd-host-10m-json", "rrd-host-10m-xml", "rrd-vm-2h-json", "rrd-vm-2h-xml", "rrd-host-1w-json")) {
            val ext = if (name.endsWith("xml")) "xml" else "json"
            val text = Live83.text("$name.response.$ext")
            val raw = if (ext == "json") Regex("\"t\":\"(\\d+)\"").findAll(text).map { it.groupValues[1].toLong() }.toList()
                else Regex("<row><t>(\\d+)</t>").findAll(text).map { it.groupValues[1].toLong() }.toList()
            assertEquals(name, raw.sortedDescending(), raw)
            val parsed = RrdParser.parse(Live83.bytes("$name.response.$ext")) { true }
            assertEquals(name, raw.sorted(), parsed.timestamps)
        }
    }

    @Test fun legendsAreCfKindUuidNameWithLowerCaseUuidsAndEveryColumnParses() {
        val update = RrdParser.parse(Live83.bytes("rrd-no-interval-json.response.json")) { true }
        assertEquals(update.legendCount, update.columns.size)
        assertEquals(setOf("AVERAGE"), update.columns.map { it.legend.consolidation }.toSet())
        assertEquals(setOf("host", "vm"), update.columns.map { it.legend.kind }.toSet())
        assertEquals(setOf(hostUuid, guestUuid, dom0Uuid).map { it.lowercase() }.toSet(), update.columns.map { it.legend.uuid }.toSet())
        assertTrue(update.columns.all { it.values.size == update.timestamps.size })
    }

    @Test fun theXmlAndJsonFormsCarryTheSameLegend() {
        val json = host("rrd-host-10m-json") { true }
        val xml = host("rrd-host-10m-xml") { true }
        assertEquals(json.columns.map { it.legend }, xml.columns.map { it.legend })
        assertEquals(json.legendCount, xml.legendCount)
        assertEquals(json.step, xml.step)
    }

    @Test fun aHostAnswerIncludesTheGuestsAndTheControlDomain() {
        val kinds = host("rrd-host-10m-json") { true }.columns.groupBy { it.legend.kind to it.legend.uuid }.keys
        assertEquals(setOf("host" to hostUuid, "vm" to guestUuid, "vm" to dom0Uuid).map { it.first to it.second.lowercase() }.toSet(), kinds)
        val vmOnly = RrdParser.parse(Live83.bytes("rrd-vm-10m-json.response.json")) { true }.columns.map { it.legend.kind }.toSet()
        assertEquals("without host=true the answer holds VMs only", setOf("vm"), vmOnly)
    }

    @Test fun theSeriesNamesTheAppSelectsByNameExist() {
        val hostNames = host("rrd-host-10m-json") { it.kind == "host" }.columns.map { it.legend.name }
        assertTrue(hostNames.containsAll(listOf("cpu_avg", "loadavg", "memory_free_kib", "memory_total_kib", "pif_eth0_rx", "pif_eth0_tx", "cpu0")))
        val vmNames = guest("rrd-vm-10m-json").columns.map { it.legend.name }
        assertTrue(vmNames.containsAll(listOf("cpu0", "memory", "memory_internal_free", "vif_0_rx", "vif_0_tx", "vbd_xvda_read", "vbd_xvda_write")))
        assertTrue("cpu_usage exists for VMs", vmNames.contains("cpu_usage"))
        assertEquals(vmNames.distinct(), vmNames)
    }

    // ---- mapping ---------------------------------------------------------------------------------------------------------

    @Test fun theHostAnswerBecomesCpuLoadMemoryAndNicCards() {
        val snapshot = HistoryMapper.build(target(MetricKind.HOST, hostUuid), HistoryRange.TEN_MINUTES, host("rrd-host-10m-json"), 0, null)
        val ids = snapshot.series.map { it.id }
        assertTrue(ids.containsAll(listOf("cpu_avg", "loadavg", "memory_used", "memory_total", "pif_eth0_rx", "pif_eth0_tx")))
        assertTrue(snapshot.series.first { it.id == "memory_total" }.values.any { it.isFinite() && it > 1e8 })   // KiB scaled to bytes
        assertEquals(snapshot.timestamps.size, snapshot.series.first().values.size)
    }

    @Test fun onlyRealNicsGetACard() {
        val snapshot = HistoryMapper.build(target(MetricKind.HOST, hostUuid), HistoryRange.TEN_MINUTES, host("rrd-host-10m-json"), 0, null)
        assertEquals(listOf("NIC eth0"), snapshot.series.map { it.card }.filter { it.startsWith("NIC") }.distinct())
        assertTrue("the aggregate repeats the only NIC, so it gets no card", snapshot.series.none { it.id.contains("aggr") || it.card == "All NICs" })
    }

    @Test fun theGuestAnswerBecomesCpuMemoryNetworkAndDiskCards() {
        val snapshot = HistoryMapper.build(target(MetricKind.VM, guestUuid), HistoryRange.TEN_MINUTES, guest("rrd-vm-10m-json"), 0, null)
        val cards = snapshot.series.map { it.card }.distinct()
        assertTrue(cards.containsAll(listOf("CPU", "Memory", "Network VIF 0", "Disk xvda")))
        assertTrue(snapshot.series.any { it.id == "memory_internal_free" })
        assertTrue("the guest-tools free-memory note is absent when tools report", snapshot.notes.isEmpty())
        // CD drives (xvdd, xvde) also have vbd_* series; without the VM's drive list every device still gets a card.
        assertEquals(listOf("Disk xvda", "Disk xvdd", "Disk xvde"), cards.filter { it.startsWith("Disk") })
    }

    @Test fun cdDrivesAreNotDisks() {
        val disks = Live83.records("VBD").values.filter { it["type"] == "Disk" }.map { it["device"] as String }.toSet()
        assertEquals(setOf("xvda"), disks)
        assertEquals(setOf("xvdd", "xvde"), Live83.records("VBD").values.filter { it["type"] == "CD" }.map { it["device"] as String }.toSet())
        val snapshot = HistoryMapper.build(target(MetricKind.VM, guestUuid), HistoryRange.TEN_MINUTES, guest("rrd-vm-10m-json"), 0, null, disks)
        assertEquals(listOf("Disk xvda"), snapshot.series.map { it.card }.distinct().filter { it.startsWith("Disk") })
        assertTrue(snapshot.series.none { it.id.startsWith("vbd_xvdd") || it.id.startsWith("vbd_xvde") })
    }

    // ---- which archive answers --------------------------------------------------------------------------------------------

    private fun alignedDown(seconds: Long, step: Long) = seconds / step * step

    @Test fun theRecordedApplikeRequestsAreTheOldStartThatTheAppNoLongerSends() {
        val sent = Live83.index.filter { (it["name"] as String).startsWith("rrd-applike-") }
        assertEquals(18, sent.size)
        for (entry in sent) {
            val range = ranges.getValue(entry["range"] as String)
            val now = entry["serverNow"] as Long
            // The old start: aligned DOWN to the step, so it lies one row before the finest archive's oldest row.
            assertEquals(entry["name"].toString(), alignedDown(now - range.spanSeconds, range.stepSeconds), entry["startEpoch"])
            assertTrue("the app now starts two steps inside the span", range.query(now).startSeconds > (entry["startEpoch"] as Long))
        }
    }

    @Test fun theQueryTheAppBuildsIsExactlyWhatTheRecordedRequestsSent() {
        val sent = Live83.entries("rrd-fixed-host-").filter { (it["name"] as String).contains("-r") } + Live83.entries("rrd-fixed-vm-")
        assertEquals(12, sent.size)
        for (entry in sent) {
            val range = ranges.getValue(entry["range"] as String)
            val query = range.query(entry["serverNow"] as Long)
            assertEquals(entry["name"].toString(), entry["startEpoch"], query.startSeconds)
            assertEquals(entry["stepS"], query.intervalSeconds)
        }
        // The incremental reads: two steps before the newest row held.
        for (entry in Live83.entries("rrd-fixed-host-").filter { (it["name"] as String).endsWith("-incremental") }) {
            val range = ranges.getValue(entry["range"] as String)
            val query = range.query(entry["serverNow"] as Long, entry["lastRow"] as Long)
            assertEquals(entry["name"].toString(), entry["startEpoch"], query.startSeconds)
        }
    }

    @Test fun theFirstReadOfEachRangeHasTheStepTheRangeAsksFor() {
        val firstReads = Live83.index.filter { (it["name"] as String).startsWith("rrd-fixed-") && !(it["name"] as String).endsWith("-incremental") }
        assertEquals(12, firstReads.size)
        for (entry in firstReads) {
            val range = ranges.getValue(entry["range"] as String)
            assertEquals(entry["name"].toString(), range.stepSeconds, metaStep(entry["name"] as String))
            // 119, 119 and 167 rows: the span less the two steps the start lies inside it.
            val rows = RrdParser.parse(Live83.bytes(entry["responseFile"] as String)) { true }.timestamps.size
            assertEquals(entry["name"].toString(), (range.spanSeconds / range.stepSeconds).toInt() - 1, rows)
        }
    }

    @Test fun aGuestRequestWithVmUuidCarriesOnlyThatGuestsColumns() {
        for (label in listOf("10m", "2h", "1w")) {
            val update = RrdParser.parse(Live83.bytes("rrd-fixed-vm-$label-r1.response.json")) { true }
            assertEquals(setOf(guestUuid.lowercase()), update.columns.map { it.legend.uuid }.toSet())
            assertEquals(setOf("vm"), update.columns.map { it.legend.kind }.toSet())
        }
        val hostAnswer = RrdParser.parse(Live83.bytes("rrd-fixed-host-10m-r1.response.json")) { true }
        assertTrue("a host answer holds the host and every VM on it", hostAnswer.columns.size > 100)
    }

    @Test fun theCoarserArchiveIsWhatTheServerActuallyAnswersAtTheOldStart() {
        for (entry in Live83.index.filter { (it["name"] as String).startsWith("rrd-applike-") }) {
            val expected = ranges.getValue(entry["range"] as String).stepSeconds
            assertTrue(entry["name"].toString(), metaStep(entry["name"] as String) > expected)
        }
        // The first read is therefore 10 rows for "10 minutes", 2 rows for "2 hours", 7 rows for "1 week".
        assertEquals(10, host("rrd-applike-10m-r1").timestamps.size)
        assertEquals(2, host("rrd-applike-2h-r1").timestamps.size)
        assertEquals(7, host("rrd-applike-1w-r1").timestamps.size)
    }

    @Test fun aStartAFewStepsInsideTheSpanGetsTheFinestArchive() {
        for (entry in Live83.index.filter { (it["name"] as String).startsWith("rrd-edge-") }) {
            val range = ranges.getValue(entry["range"] as String)
            assertEquals(entry["name"].toString(), range.stepSeconds, metaStep(entry["name"] as String))
            val rows = host(entry["name"] as String).timestamps.size
            assertEquals((range.spanSeconds / range.stepSeconds).toInt() - (entry["insideSteps"] as Long).toInt(), rows)
        }
    }

    @Test fun anIncrementalReadKeepsTheEarlierRows() {
        val target = target(MetricKind.HOST, hostUuid)
        val first = HistoryMapper.build(target, HistoryRange.TEN_MINUTES, host("rrd-applike-10m-r1"), 0, null)       // step 60, 10 rows
        val next = HistoryMapper.build(target, HistoryRange.TEN_MINUTES, host("rrd-no-interval-json"), 1, null)       // step 5, ~1 minute
        assertTrue(HistoryWindow.merge(first, next).timestamps.size >= first.timestamps.size)
    }

    @Test fun aFineReadAfterACoarseOneAddsItsRowsAndKeepsTheCoarseRowOnlyItHolds() {
        // Two real reads of the same host a few seconds apart: the old coarse first read (60 s rows to :160) and a fine one (5 s rows to :135).
        val target = target(MetricKind.HOST, hostUuid)
        val first = HistoryMapper.build(target, HistoryRange.TEN_MINUTES, host("rrd-applike-10m-r1"), 0, null)
        val next = HistoryMapper.build(target, HistoryRange.TEN_MINUTES, host("rrd-edge-10m-k1"), 1, null)
        assertEquals(60L, first.stepSeconds); assertEquals(5L, next.stepSeconds)
        assertTrue("the coarse read has a newer row than the fine one", first.timestamps.last() > next.timestamps.last())
        val merged = HistoryWindow.merge(first, next)
        val cutoff = first.timestamps.last() - 600
        assertEquals((first.timestamps + next.timestamps).distinct().filter { it >= cutoff }.sorted(), merged.timestamps)
        assertTrue(merged.timestamps.containsAll(next.timestamps.filter { it >= cutoff }))
        assertEquals("the one coarse-only row keeps the window on the coarse step, so the line does not break at it", 60L, merged.stepSeconds)
        merged.series.forEach { assertEquals(merged.timestamps.size, it.values.size) }
        val overlap = next.timestamps.first { it in first.timestamps && it >= cutoff }
        assertEquals("a row both reads hold comes from the fine read", next.series.first { it.id == "cpu_avg" }.values[next.timestamps.indexOf(overlap)],
            merged.series.first { it.id == "cpu_avg" }.values[merged.timestamps.indexOf(overlap)], 0.0)
    }

    @Test fun anIncrementalReadAddsToAFullReadOfEveryRangeAndKeepsItsStep() {
        val target = target(MetricKind.HOST, hostUuid)
        for ((label, range) in ranges) {
            val first = HistoryMapper.build(target, range, host("rrd-fixed-host-$label-r1"), 0, null)
            val next = HistoryMapper.build(target, range, host("rrd-fixed-host-$label-incremental"), 1, null)
            val merged = HistoryWindow.merge(first, next)
            assertEquals(label, range.stepSeconds, merged.stepSeconds)
            assertEquals(label, next.timestamps.last(), merged.timestamps.last())
            val cutoff = next.timestamps.last() - range.spanSeconds
            assertEquals(label, (first.timestamps + next.timestamps).distinct().filter { it >= cutoff }.sorted(), merged.timestamps)
            assertTrue("the earlier rows are still there: $label", merged.timestamps.size >= first.timestamps.size)
            val cpu = merged.series.first { it.id == "cpu_avg" }
            assertEquals(merged.timestamps.size, cpu.values.size)
            // A row both reads hold comes from the newer read.
            val overlap = next.timestamps.first { it in first.timestamps }
            assertEquals(next.series.first { it.id == "cpu_avg" }.values[next.timestamps.indexOf(overlap)], cpu.values[merged.timestamps.indexOf(overlap)], 0.0)
        }
    }

    @Test fun anInvalidSessionIsAnHttp401WithABasicChallengeThatTheAppMapsToDenied() {
        val entry = Live83.entry("rrd-bad-session")
        assertEquals(401L, entry["status"])
        assertTrue((entry["responseHead"] as List<*>).any { it.toString().startsWith("WWW-Authenticate: Basic") })
    }
}

/** The live data sources (`VM.get_data_sources`, `host.get_data_sources`) as the app's live charts read them. */
class Live83MetricsTest {
    private fun target(kind: MetricKind) = MetricTarget("pool", "uuid", kind, "x")
    @Suppress("UNCHECKED_CAST")
    private fun sources(name: String) = (Live83.value(name) as List<Map<String, Any?>>)
    private val guestDisks get() = Live83.records("VBD").values.filter { it["type"] == "Disk" }.map { it["device"] as String }.toSet()

    @Test fun aSourceIsNameLabelDescriptionEnabledStandardUnitsMinMaxValueAndNothingElse() {
        val first = sources("VM.get_data_sources").first()
        assertEquals(setOf("name_label", "name_description", "enabled", "standard", "units", "min", "max", "value"), first.keys)
        assertTrue(first["value"] is Double && first["enabled"] is Boolean && first["units"] is String)
    }

    @Test fun theGuestsDefaultEnabledSourcesAreThe49StandardOnesAndTenAreDisabledByDefault() {
        val list = sources("VM.get_data_sources")
        assertEquals(59, list.size)
        assertEquals(49, list.count { it["enabled"] == true })
        assertTrue(list.filter { it["enabled"] == false }.all { it["standard"] == false })
        val disabled = list.filter { it["enabled"] == false }.map { it["name_label"] }
        assertTrue(disabled.containsAll(listOf("runstate_fullrun", "runnable_vcpus", "vif_0_rx_errors")))
        assertEquals(100, sources("host.get_data_sources").count { it["enabled"] == true })
        assertEquals(listOf("pif_eth0_rx_errors", "pif_eth0_tx_errors"), sources("host.get_data_sources").filter { it["enabled"] == false }.map { it["name_label"] })
    }

    @Test fun unitsAreSpelledTheWayTheRrdSeriesAreScaled() {
        val units = (sources("VM.get_data_sources") + sources("host.get_data_sources")).associate { it["name_label"] to it["units"] }
        assertEquals("(fraction)", units["cpu0"]); assertEquals("B", units["memory"]); assertEquals("KiB", units["memory_internal_free"])
        assertEquals("KiB", units["memory_free_kib"]); assertEquals("B/s", units["pif_eth0_rx"]); assertEquals("B/s", units["vbd_xvda_read"])
    }

    @Test fun theGuestPlanIsAFixedSetOfEnabledSourcesInDisplayOrder() {
        val plan = LiveMetrics.plan(target(MetricKind.VM), sources("VM.get_data_sources"), guestDisks)
        assertEquals(59, plan.advertisedCount)
        assertEquals(listOf("cpu_usage", "memory", "memory_internal_free", "vbd_xvda_read", "vbd_xvda_write", "vif_0_rx", "vif_0_tx"),
            plan.items.map { it.name })
        val enabled = sources("VM.get_data_sources").filter { it["enabled"] == true }.map { it["name_label"] }.toSet()
        assertTrue("only enabled sources", plan.items.all { it.name in enabled })
        assertTrue("no queue sizes, latencies or error counters", plan.items.none { Regex("avgqu|inflight|latency|iowait|errors|iops|throughput|runstate|runnable").containsMatchIn(it.name) })
        assertEquals(plan.items.size, plan.queryNames.size)
        assertEquals(listOf(MetricGroup.CPU, MetricGroup.MEMORY, MetricGroup.DISK, MetricGroup.NETWORK), plan.items.map { it.group }.distinct())
        assertEquals("(fraction) is spelled fraction", "fraction", plan.items.first { it.name == "cpu_usage" }.unit)
        assertEquals(listOf("B", "KiB", "B/s", "B/s"), listOf("memory", "memory_internal_free", "vbd_xvda_read", "vif_0_rx").map { name -> plan.items.first { it.name == name }.unit })
    }

    @Test fun withoutTheDriveListEveryDiskDeviceCountsButOnlyTwoAreRead() {
        val plan = LiveMetrics.plan(target(MetricKind.VM), sources("VM.get_data_sources"), null)
        assertEquals(listOf("vbd_xvda_read", "vbd_xvda_write", "vbd_xvdd_read", "vbd_xvdd_write"), plan.items.filter { it.group == MetricGroup.DISK }.map { it.name })
    }

    @Test fun theHostPlanHasCpuLoadMemoryAndTheRealNicOnly() {
        val plan = LiveMetrics.plan(target(MetricKind.HOST), sources("host.get_data_sources"), null)
        assertEquals(102, plan.advertisedCount)
        assertEquals(listOf("cpu_avg", "loadavg", "memory_used", "memory_free_kib", "memory_total_kib", "pif_eth0_rx", "pif_eth0_tx"), plan.items.map { it.name })
        assertEquals("six cores are summarised by the average", 0, plan.items.count { Regex("cpu[0-9]+").matches(it.name) })
        assertEquals("load", plan.items.first { it.name == "loadavg" }.unit)
        assertEquals(listOf(MetricGroup.CPU, MetricGroup.LOAD, MetricGroup.MEMORY, MetricGroup.NETWORK), plan.items.map { it.group }.distinct())
        assertEquals(listOf("memory_used"), plan.items.filter { it.derived }.map { it.name })
        assertEquals(6, plan.queryNames.size)
        assertTrue("an infinite maximum is no limit", plan.items.first { it.name == "memory_total_kib" }.limit == null)
        assertEquals(1.0, plan.items.first { it.name == "cpu_avg" }.limit!!, 0.0)
    }

    @Test fun theRealQueriedValuesBecomeTheReadings() {
        fun values(kind: String, plan: MetricPlan) = plan.queryNames.associateWith { name -> Live83.value("query-$kind-$name") as Double }
        val vmPlan = LiveMetrics.plan(target(MetricKind.VM), sources("VM.get_data_sources"), guestDisks)
        val vm = LiveMetrics.snapshot(target(MetricKind.VM), vmPlan, values("VM", vmPlan), 7)
        assertEquals(7L, vm.observedAt)
        assertTrue(vm.readings.none { it.unavailable != null })
        assertEquals(1_073_729_536.0, vm.readings.first { it.name == "memory" }.value!!, 0.0)
        assertEquals(747_108.0, vm.readings.first { it.name == "memory_internal_free" }.value!!, 0.0)
        val hostPlan = LiveMetrics.plan(target(MetricKind.HOST), sources("host.get_data_sources"), null)
        val host = LiveMetrics.snapshot(target(MetricKind.HOST), hostPlan, values("host", hostPlan), 7)
        assertEquals(6_290_932.0 - 3_211_944.0, host.readings.first { it.name == "memory_used" }.value!!, 0.0)
        assertTrue(host.readings.none { it.unavailable != null })
    }

    @Test fun getDataSourcesReturnsZeroForEverySourceEvenOnARunningGuest() {
        for (name in listOf("VM.get_data_sources", "host.get_data_sources", "VM.get_data_sources.dom0"))
            assertTrue(name, sources(name).all { it["value"] == 0.0 })
        assertEquals("Running", Live83.guest()["power_state"])
    }

    // (The old ignored test "getDataSourcesCarriesTheCurrentValues" asserted something about the SERVER (value > 0 in
    // get_data_sources), which can never hold; the app's requirement, real values in the live view, is the test above and
    // Live83GatewayTest.theGuestsLiveReadingsCarryTheRealCurrentValues.)

    @Test fun queryDataSourceReturnsTheRealCurrentValueAsADouble() {
        fun query(name: String) = Live83.value(name) as Double
        assertTrue(query("query-VM-memory") > 1e8)
        assertTrue(query("query-VM-memory_target") >= query("query-VM-memory"))
        assertTrue(query("query-VM-cpu0") in 0.0..1.0)
        assertTrue(query("query-VM-vif_0_rx") > 0.0)
        assertTrue(query("query-host-memory_total_kib") > query("query-host-memory_free_kib"))
        assertTrue(query("query-host-loadavg") >= 0.0)
        // An unknown source is an INTERNAL_ERROR with an IDL message, not a typed XAPI error.
        val error = runCatching { Live83.value("query-VM-no-such-source") }.exceptionOrNull() as RpcFailure
        assertEquals("INTERNAL_ERROR", error.code)
    }

    @Test fun anInfiniteMaximumIsInfinityNotAFiniteNumber() {
        assertTrue(sources("VM.get_data_sources").any { (it["max"] as Double).isInfinite() })
        assertTrue(sources("host.get_data_sources").any { it["min"] == Double.NEGATIVE_INFINITY })
    }
}
