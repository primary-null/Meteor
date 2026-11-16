package com.meteor.app.data

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Ignore
import org.junit.Test

/**
 * The real [XapiGateway] (pinned TLS, XML-RPC transport, `/rrd_updates`, parsers and mappers) against a loopback server
 * that replays the answers recorded from XCP-ng 8.3.0. Reads only: the server refuses to count anything else as fine.
 */
class Live83GatewayTest {
    private lateinit var server: Live83Server
    private val gateway = XapiGateway { 1_794_757_700_000L }
    private val authority get() = Live83.pool()["uuid"] as String
    private val hostUuid get() = Live83.host()["uuid"] as String
    private val guestUuid get() = Live83.guest()["uuid"] as String

    @Before fun start() { server = Live83Server() }
    @After fun stop() { try { server.assertReadOnly() } finally { server.close() } }

    @Test fun inventoryComesBackFromTheRealRecords() = runBlocking {
        val inventory = gateway.inventory(server.profile)
        assertEquals(authority, inventory.authority)
        assertEquals(1, inventory.hosts.size)
        assertEquals(1, inventory.guests.size)
        assertEquals(guestUuid, inventory.guests.single().uuid)
        // inventory scale: the server filters templates and the control domain out; the injected clock dates the result
        assertTrue("VM.get_all_records_where" in server.calls); assertFalse("VM.get_all_records" in server.calls)
        assertEquals(1_794_757_700_000L, inventory.observedAtMillis); assertTrue(inventory.notLoaded.isEmpty())
    }

    @Test fun vmDetailsAreReadFromTheRealRecords() = runBlocking {
        val details = gateway.details(server.profile, authority, guestUuid)
        assertEquals("Running", details.power)
        assertEquals(Live83.guest()["name_label"], details.name)
        assertTrue(details.vcpus >= 1)
        assertTrue(details.memoryBytes > 100_000_000)
        assertTrue("the agent reports", details.guestToolsLive)
        assertTrue(details.os.orEmpty().startsWith("Debian"))
        assertTrue(details.addresses.isNotEmpty())
        assertTrue(details.disks.any { it.device == "0" })
        assertEquals(1, details.networks.size)
        assertEquals(hostUuid, details.residentHostUuid)
        assertTrue("start is not allowed on a running VM", "start" !in details.allowed && "clean_shutdown" in details.allowed)
    }

    @Test fun vmInfoHostInfoNetworksAndStorageAreReadFromTheRealRecords() = runBlocking {
        val info = gateway.vmInfo(server.profile, authority, guestUuid)
        assertEquals("BIOS", info.firmware)
        assertEquals(SecureBoot.NOT_UEFI, info.secureBoot)
        assertTrue(info.cdDrives.isNotEmpty())
        val host = gateway.hostInfo(server.profile, authority, hostUuid)
        assertTrue(host.coordinator)
        assertEquals("8.3.0", host.productVersion)
        assertTrue(host.cpuModel.isNotBlank() && (host.cpuCount ?: 0) >= 1)
        assertTrue(host.nics.any { it.management && it.ip.isNotBlank() })
        assertNotNull("get_servertime is read", host.serverTimeMillis)
        val networks = gateway.networks(server.profile, authority)
        assertEquals(Live83.records("network").size, networks.total)
        val sr = Live83.records("SR").values.first { it["type"] == "ext" || it["type"] == "lvm" }
        val storage = gateway.storageInfo(server.profile, authority, sr["uuid"] as String)
        assertTrue((storage.physicalSize ?: 0) > 0)
        assertTrue(storage.hostLinks.isNotEmpty())
    }

    @Test fun permissionsOfTheRootLoginArePoolAdmin() = runBlocking {
        val permissions = gateway.permissions(server.profile)
        assertTrue(permissions.superuser)
    }

    @Test fun alertsAreReadFullAndIncrementally() = runBlocking {
        val full = gateway.alerts(server.profile, authority, null)
        assertTrue(full.full)
        assertEquals(Live83.records("message").size, full.messages.size)
        val since = gateway.alerts(server.profile, authority, 1_700_000_000_000L)
        assertFalse(since.full)
        assertTrue(since.messages.isNotEmpty())
    }

    @Test fun theUpdateCheckAndThePoolSummaryWork() = runBlocking {
        val updates = gateway.checkHostUpdates(server.profile, authority, hostUuid)
        assertTrue(updates.recognized)
        assertTrue(updates.total > 0)
        val summary = gateway.poolSummary(server.profile, null)
        assertEquals(1, summary.hostsTotal); assertEquals(1, summary.hostsLive); assertEquals(1, summary.vmsTotal); assertEquals(1, summary.vmsRunning)
        assertNotNull(summary.unreadUrgent)
        assertEquals(1, server.calls.count { it == "VM.get_all_records_where" })
    }

    @Test fun theEventSessionBeginsWithAPoolTokenAndGetsTheBacklogThenAQuietAnswer() = runBlocking {
        val session = gateway.openEvents(server.profile, authority)
        try {
            val token = session.begin()
            assertTrue(Regex("\\d{20},\\d{20}").matches(token))
            val backlog = session.next(token, 30.0)
            assertTrue(backlog.events.isNotEmpty())
            val quiet = session.next(backlog.token, 30.0)
            assertTrue(quiet.events.isEmpty())
            assertEquals(backlog.token, quiet.token)
        } finally { session.close() }
    }

    // ---- history through the gateway --------------------------------------------------------------------------------------

    @Test fun hostHistoryIsReadAtTheFiveSecondStep() = runBlocking {
        val snapshot = gateway.history(server.profile, MetricTarget(authority, hostUuid, MetricKind.HOST, "h"), HistoryRange.TEN_MINUTES, null)
        assertEquals(5L, snapshot.stepSeconds)
        assertTrue(snapshot.timestamps.size > 100)
        assertTrue(snapshot.series.any { it.id == "cpu_avg" })
        assertTrue(server.rrdQueries.single().contains("&interval=5&host=true&json=true"))
        // The request starts two steps inside the span, measured on the server's clock (host.get_servertime).
        val serverNow = ActivityMapper.time(Live83.value("host.get_servertime"))!! / 1000
        assertTrue(server.rrdQueries.single().contains("&start=${HistoryRange.TEN_MINUTES.query(serverNow).startSeconds}&"))
    }

    @Test fun guestHistoryIsReadForThatGuestOnlyAndWithoutItsCdDrives() = runBlocking {
        val snapshot = gateway.history(server.profile, MetricTarget(authority, guestUuid, MetricKind.VM, "g"), HistoryRange.TWO_HOURS, null)
        assertEquals(60L, snapshot.stepSeconds)
        assertTrue(snapshot.series.any { it.id == "memory" })
        val query = server.rrdQueries.single()
        assertFalse(query.contains("host=true"))
        assertTrue("the request names the guest so a busy host does not send every VM", query.contains("&vm_uuid=$guestUuid"))
        assertEquals(listOf("Disk xvda"), snapshot.series.map { it.card }.distinct().filter { it.startsWith("Disk") })
        // The VBD records were read once (three drives); the answer is kept for the next refresh.
        assertEquals(3, server.calls.count { it == "VBD.get_record" })
        gateway.history(server.profile, MetricTarget(authority, guestUuid, MetricKind.VM, "g"), HistoryRange.TWO_HOURS, null)
        assertEquals(3, server.calls.count { it == "VBD.get_record" })
    }

    @Test fun everyRangeAsksForTheStepItShows() = runBlocking {
        for (range in HistoryRange.entries) {
            val snapshot = gateway.history(server.profile, MetricTarget(authority, hostUuid, MetricKind.HOST, "h"), range, null)
            assertEquals(range.label, range.stepSeconds, snapshot.stepSeconds)
        }
    }

    @Test fun theTenMinuteRangeHasFiveSecondRows() = runBlocking {
        val snapshot = gateway.history(server.profile, MetricTarget(authority, hostUuid, MetricKind.HOST, "h"), HistoryRange.TEN_MINUTES, null)
        assertEquals(5L, snapshot.stepSeconds)
    }

    // ---- live metrics through the gateway -----------------------------------------------------------------------------------

    @Test fun liveMetricsLoadForAGuestAndAHost() = runBlocking {
        val vm = gateway.metrics(server.profile, MetricTarget(authority, guestUuid, MetricKind.VM, "g"))
        assertTrue(vm.readings.isNotEmpty())
        val host = gateway.metrics(server.profile, MetricTarget(authority, hostUuid, MetricKind.HOST, "h"))
        assertTrue(host.readings.isNotEmpty())
    }

    @Test fun theGuestsLiveReadingsCarryTheRealCurrentValues() = runBlocking {
        val vm = gateway.metrics(server.profile, MetricTarget(authority, guestUuid, MetricKind.VM, "g"))
        fun reading(name: String) = vm.readings.single { it.name == name }
        // The recorded source list has one vCPU (cpu0 only), so the average stands alone; the CD drives xvdd and xvde are not disks.
        assertEquals(listOf("cpu_usage", "memory", "memory_internal_free", "vbd_xvda_read", "vbd_xvda_write", "vif_0_rx", "vif_0_tx"),
            vm.readings.map { it.name })
        assertEquals(1_073_729_536.0, reading("memory").value!!, 0.0)           // query-VM-memory
        assertEquals(747_108.0, reading("memory_internal_free").value!!, 0.0)    // KiB, free (not used) memory
        assertEquals("KiB", reading("memory_internal_free").unit)
        assertEquals(0.005382667295634747, reading("cpu_usage").value!!, 1e-12)
        assertEquals("fraction", reading("cpu_usage").unit)
        assertEquals(1.0, reading("cpu_usage").limit!!, 0.0)
        assertTrue("the traffic the guest had", reading("vif_0_rx").value!! > 0)
        assertNull("infinite maximum = no limit", reading("memory").limit)
        assertTrue(vm.readings.none { it.unavailable != null })
        assertEquals(59, vm.advertisedCount)
    }

    @Test fun theHostsLiveReadingsCarryTheRealCurrentValues() = runBlocking {
        val host = gateway.metrics(server.profile, MetricTarget(authority, hostUuid, MetricKind.HOST, "h"))
        fun reading(name: String) = host.readings.single { it.name == name }
        assertEquals(listOf("cpu_avg", "loadavg", "memory_used", "memory_free_kib", "memory_total_kib", "pif_eth0_rx", "pif_eth0_tx"), host.readings.map { it.name })
        assertEquals(3_211_944.0, reading("memory_free_kib").value!!, 0.0)
        assertEquals(6_290_932.0, reading("memory_total_kib").value!!, 0.0)
        assertEquals(6_290_932.0 - 3_211_944.0, reading("memory_used").value!!, 0.0)
        assertEquals(0.1500000059604645, reading("loadavg").value!!, 0.0)
        assertEquals("load", reading("loadavg").unit)
        assertEquals(0.009105032309889793, reading("cpu_avg").value!!, 1e-12)
        assertTrue(host.readings.none { it.unavailable != null })
    }

    @Test fun aLiveTickIsTheIdentityChecksPlusOneSmallReadPerSourceAndAWarmTickSkipsTheCatalogue() = runBlocking {
        val target = MetricTarget(authority, guestUuid, MetricKind.VM, "g")
        gateway.metrics(server.profile, target)
        val first = server.calls.toList()
        assertEquals(listOf("session.login_with_password", "pool.get_all_records", "VM.get_by_uuid", "VM.get_record"), first.take(4))
        assertEquals(1, first.count { it == "VM.get_data_sources" })
        assertEquals(3, first.count { it == "VBD.get_record" })
        assertEquals(7, first.count { it == "VM.query_data_source" })
        assertEquals("a cold VM tick: 3 identity reads, the catalogue, 3 drive records, 7 queries, 1 identity read", 15, first.count { !it.startsWith("session.") })
        server.calls.clear(); server.queried.clear()
        gateway.metrics(server.profile, target)
        val warm = server.calls.filter { !it.startsWith("session.") }
        assertEquals(0, warm.count { it == "VM.get_data_sources" || it == "VBD.get_record" })
        assertEquals(7, warm.count { it == "VM.query_data_source" })
        assertEquals("a warm VM tick: pool, get_by_uuid, get_record, 7 queries, pool", 11, warm.size)
        // The same seven names, in the same order, every tick.
        assertEquals(listOf("cpu_usage", "memory", "memory_internal_free", "vbd_xvda_read", "vbd_xvda_write", "vif_0_rx", "vif_0_tx").map { "VM $it" },
            server.queried.toList())
        val hostTarget = MetricTarget(authority, hostUuid, MetricKind.HOST, "h")
        gateway.metrics(server.profile, hostTarget); server.calls.clear()
        gateway.metrics(server.profile, hostTarget)
        assertEquals("a warm host tick is 10 calls: pool, get_by_uuid, get_record, 6 queries, pool", 10, server.calls.count { !it.startsWith("session.") })
    }
}
