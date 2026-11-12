package com.meteor.app.data

import com.meteor.app.BuildConfig
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Real pinned TLS, HTTP GET, XML-RPC and parsers against a loopback server with a throw-away certificate:
 * the `/rrd_updates` history path and the read-only host, VM, network and storage reads. No lab access.
 */
class HistoryTransportIntegrationTest {
    private lateinit var fixture: TlsFixture
    private lateinit var profile: Profile
    /** Every XML-RPC method and every HTTP GET in arrival order. */
    private val events = CopyOnWriteArrayList<String>()
    private val queries = CopyOnWriteArrayList<String>()
    private val accepts = CopyOnWriteArrayList<String>()
    private val agents = CopyOnWriteArrayList<String>()
    private val methods get() = events.filterNot { it.startsWith("GET ") }

    private var rrdBody: ByteArray = xmlBody()
    private var rrdStatus = 200
    private var rrdChunked = false
    private var rrdDeclaredLength: Long? = null
    private var vmResident = "host-ref"
    private var vmPower = "Running"
    private var rpcErrors = emptyMap<String, String>()
    private var poolAfterRrd = "pool-id"
    private var snapshotCount = 3
    private var vdiCount = 5
    private var vdiHasVbd = true

    private val hostUuid = "11111111-aaaa-bbbb-cccc-000000000001"
    private val vmUuid = "22222222-aaaa-bbbb-cccc-000000000002"

    @Before fun start() {
        fixture = TlsFixture()
        fixture.server.createContext("/") { exchange ->
            try {
                if (exchange.requestMethod == "GET") get(exchange) else post(exchange)
            } catch (error: Throwable) {
                runCatching { exchange.sendResponseHeaders(500, -1) }
            } finally { exchange.close() }
        }
        fixture.start()
        profile = fixture.profile()
    }

    @After fun stop() {
        // Nothing in this feature may write: only login, logout and *.get_* reads are ever sent.
        assertTrue("unexpected method in $methods", methods.all { it.startsWith("session.") || it.substringAfter('.').startsWith("get_") })
        assertTrue(methods.none { it.startsWith("Async.") || "call_plugin" in it })
        fixture.close()
    }

    private fun get(exchange: com.sun.net.httpserver.HttpExchange) {
        events += "GET ${exchange.requestURI.path}"
        queries += exchange.requestURI.rawQuery.orEmpty()
        accepts += exchange.requestHeaders.getFirst("Accept").orEmpty()
        agents += exchange.requestHeaders.getFirst("User-Agent").orEmpty()
        when {
            rrdStatus in 300..399 -> { exchange.responseHeaders.add("Location", "${fixture.endpoint}elsewhere"); exchange.sendResponseHeaders(rrdStatus, -1) }
            rrdStatus != 200 -> exchange.sendResponseHeaders(rrdStatus, -1)
            rrdDeclaredLength != null -> { exchange.sendResponseHeaders(200, rrdDeclaredLength!!); exchange.responseBody.write(ByteArray(64) { ' '.code.toByte() }) }
            rrdChunked -> { exchange.sendResponseHeaders(200, 0); exchange.responseBody.write(rrdBody) }
            else -> { exchange.sendResponseHeaders(200, rrdBody.size.toLong()); exchange.responseBody.write(rrdBody) }
        }
    }

    private fun post(exchange: com.sun.net.httpserver.HttpExchange) {
        val body = exchange.requestBody.bufferedReader().use { it.readText() }
        val method = body.substringAfter("<methodName>").substringBefore("</methodName>")
        val args = Regex("<string>(.*?)</string>", RegexOption.DOT_MATCHES_ALL).findAll(body).map { it.groupValues[1] }.toList()
        events += method
        val xml = rpcErrors[method]?.let { code -> "<methodResponse><params><param><value>" +
            value(mapOf("Status" to "Failure", "ErrorDescription" to listOf(code, "private argument"))) + "</value></param></params></methodResponse>" }
            ?: ("<methodResponse><params><param><value>" + value(mapOf("Status" to "Success", "Value" to result(method, args))) + "</value></param></params></methodResponse>")
        val bytes = xml.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "text/xml; charset=utf-8")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.write(bytes)
    }

    private fun value(input: Any): String = when (input) {
        is Map<*, *> -> "<struct>" + input.entries.joinToString("") { "<member><name>${it.key}</name><value>${value(requireNotNull(it.value))}</value></member>" } + "</struct>"
        is Boolean -> "<boolean>${if (input) 1 else 0}</boolean>"
        is List<*> -> "<array><data>" + input.joinToString("") { "<value>${value(requireNotNull(it))}</value>" } + "</data></array>"
        else -> "<string>${input.toString().replace("&", "&amp;").replace("<", "&lt;")}</string>"
    }

    private fun host(uuid: String, name: String) = mapOf("uuid" to uuid, "name_label" to name, "address" to "127.0.0.1", "enabled" to true,
        "cpu_info" to mapOf("modelname" to "Fixture CPU", "cpu_count" to "8", "socket_count" to "1", "speed" to "2400.000"),
        "software_version" to mapOf("product_brand" to "XCP-ng", "product_version" to "8.3.0", "build_number" to "58", "xapi" to "25.6.0", "xen" to "4.17.5"))
    private fun vmRecord() = mapOf("uuid" to vmUuid, "name_label" to "Fixture VM", "name_description" to "Fixture description", "power_state" to vmPower,
        "resident_on" to vmResident, "is_a_template" to false, "is_control_domain" to false, "is_a_snapshot" to false,
        "HVM_boot_policy" to "BIOS order", "HVM_boot_params" to mapOf("firmware" to "uefi", "order" to "cd"),
        "platform" to mapOf("secureboot" to "true"), "VTPMs" to emptyList<String>(), "ha_restart_priority" to "restart",
        "other_config" to mapOf("auto_poweron" to "true"), "snapshots" to List(snapshotCount) { "snap-$it" }, "VBDs" to listOf("vbd-cd", "vbd-disk"),
        "tags" to listOf("prod"), "memory_dynamic_min" to "1024", "memory_dynamic_max" to "2048", "memory_static_min" to "512", "memory_static_max" to "4096",
        "VCPUs_max" to "4", "VCPUs_at_startup" to "2")

    private fun result(method: String, args: List<String>): Any = when (method) {
        "session.login_with_password" -> "OpaqueRef:fixture-session"
        "session.logout" -> ""
        "pool.get_all_records" -> mapOf("pool-ref" to mapOf("uuid" to if (events.any { it.startsWith("GET ") }) poolAfterRrd else "pool-id",
            "name_label" to "Fixture pool", "master" to "host-ref", "other_config" to mapOf("auto_poweron" to "true")))
        "host.get_by_uuid" -> when (args[1]) { hostUuid -> "host-ref"; "member-id" -> "member-ref"; else -> error("unknown host") }
        "host.get_record" -> when (args[1]) { "host-ref" -> host(hostUuid, "xcp-a"); "member-ref" -> host("member-id", "xcp-b"); else -> error("unknown host ref") }
        "host.get_servertime" -> "20231114T22:15:00Z"
        "host.get_all_records" -> mapOf("host-ref" to host(hostUuid, "xcp-a"), "member-ref" to host("member-id", "xcp-b"))
        "VM.get_by_uuid" -> "vm-ref"
        "VM.get_record" -> if (args[1].startsWith("snap-")) mapOf("uuid" to "snap-uuid-${args[1]}", "name_label" to "Snapshot ${args[1]}", "is_a_snapshot" to true,
            "snapshot_time" to "20260101T10:00:0${args[1].last()}Z") else vmRecord()
        "VBD.get_record" -> if (args[1] == "vbd-cd") mapOf("type" to "CD", "userdevice" to "3", "empty" to false, "VDI" to "vdi-iso", "currently_attached" to true)
            else mapOf("type" to "Disk", "userdevice" to "0", "empty" to false, "VDI" to "OpaqueRef:NULL", "currently_attached" to true)
        "VDI.get_record" -> if (args[1] == "vdi-iso") mapOf("name_label" to "debian.iso") else mapOf("uuid" to "vdi-uuid-${args[1]}", "name_label" to "disk ${args[1]}",
            "virtual_size" to "1000", "physical_utilisation" to "100", "managed" to true, "VBDs" to if (vdiHasVbd) listOf("vbd-use") else emptyList<String>())
        "PIF.get_all_records" -> mapOf("pif-1" to mapOf("host" to "host-ref", "device" to "eth0", "MAC" to "aa:bb:cc:00:00:01", "IP" to "192.0.2.10", "management" to true,
            "currently_attached" to true, "VLAN" to "-1", "physical" to true, "metrics" to "pifm-1", "network" to "net-1"),
            "pif-2" to mapOf("host" to "member-ref", "device" to "eth0", "IP" to "192.0.2.11", "management" to true, "currently_attached" to true, "VLAN" to "-1",
                "physical" to true, "metrics" to "pifm-2", "network" to "net-1"))
        "PIF_metrics.get_all_records" -> mapOf("pifm-1" to mapOf("carrier" to true, "speed" to "1000", "duplex" to true),
            "pifm-2" to mapOf("carrier" to false, "speed" to "0", "duplex" to false))
        "network.get_all_records" -> mapOf("net-1" to mapOf("uuid" to "net-uuid", "name_label" to "Pool-wide network", "bridge" to "xenbr0", "MTU" to "1500",
            "PIFs" to listOf("pif-1", "pif-2"), "VIFs" to listOf("vif-1")))
        "SR.get_by_uuid" -> "sr-ref"
        "SR.get_record" -> mapOf("uuid" to "sr-id", "name_label" to "Shared SR", "type" to "nfs", "content_type" to "user", "shared" to true,
            "physical_size" to "1000", "physical_utilisation" to "400", "virtual_allocation" to "1500", "VDIs" to List(vdiCount) { "vdi-$it" })
        "PBD.get_all_records" -> mapOf("pbd-1" to mapOf("SR" to "sr-ref", "host" to "host-ref", "currently_attached" to true),
            "pbd-2" to mapOf("SR" to "sr-ref", "host" to "member-ref", "currently_attached" to false),
            "pbd-3" to mapOf("SR" to "another-sr", "host" to "host-ref", "currently_attached" to true))
        "VBD.get_all_records" -> mapOf("vbd-use" to mapOf("VM" to "vm-ref"))
        "VM.get_all_records" -> mapOf("vm-ref" to mapOf("name_label" to "Fixture VM"))
        else -> error("Unexpected fixture request: $method")
    }

    // ---- RRD bodies ----

    private fun xmlBody(legend: List<String> = listOf("AVERAGE:host:$HOST:cpu_avg", "AVERAGE:host:$HOST:memory_total_kib", "AVERAGE:host:$HOST:memory_free_kib",
        "AVERAGE:vm:$VM:cpu0", "AVERAGE:vm:$VM:memory"), rows: List<Pair<Long, List<String>>> = listOf(
        1_700_000_100L to listOf("0.30", "8192", "4096", "0.5", "2048"),
        1_700_000_095L to listOf("NaN", "8192", "6144", "0.25", "2048"),
        1_700_000_090L to listOf("0.10", "8192", "4096", "0.0", "1024")), step: Long = 5) =
        ("<?xml version=\"1.0\"?><xport><meta><start>${rows.minOf { it.first }}</start><step>$step</step><end>${rows.maxOf { it.first }}</end><rows>${rows.size}</rows>" +
            "<columns>${legend.size}</columns><legend>" + legend.joinToString("") { "<entry>$it</entry>" } + "</legend></meta><data>" +
            rows.joinToString("") { (t, v) -> "<row><t>$t</t>" + v.joinToString("") { "<v>$it</v>" } + "</row>" } + "</data></xport>").toByteArray()
    private fun jsonBody(legend: List<String>, rows: List<Pair<Long, List<String>>>, step: Long = 60) =
        ("{\"meta\":{\"start\":${rows.minOf { it.first }},\"step\":$step,\"end\":${rows.maxOf { it.first }},\"rows\":${rows.size},\"columns\":${legend.size},\"legend\":[" +
            legend.joinToString(",") { "\"$it\"" } + "]},\"data\":[" + rows.joinToString(",") { (t, v) -> "{\"t\":$t,\"values\":[" + v.joinToString(",") + "]}" } + "]}").toByteArray()

    private val hostTarget get() = MetricTarget("pool-id", hostUuid, MetricKind.HOST, "xcp-a")
    private val vmTarget get() = MetricTarget("pool-id", vmUuid, MetricKind.VM, "Fixture VM")
    private fun gateway(now: Long = 1_700_000_050_000L) = XapiGateway { now }
    private fun history(target: MetricTarget, range: HistoryRange = HistoryRange.TEN_MINUTES, since: Long? = null, profile: Profile = this.profile,
        now: Long = 1_700_000_050_000L) = runBlocking { gateway(now).history(profile, target, range, since) }
    private fun failure(block: () -> Unit) = runCatching { block() }.exceptionOrNull()

    companion object {
        private const val HOST = "11111111-aaaa-bbbb-cccc-000000000001"
        private const val VM = "22222222-aaaa-bbbb-cccc-000000000002"
        private const val OTHER = "33333333-aaaa-bbbb-cccc-000000000003"
    }

    // ---- history over the pinned GET path ----

    @Test fun hostHistoryUsesThePinnedGetAndParsesTheXmlBody() {
        val snapshot = history(hostTarget)
        assertEquals(listOf("session.login_with_password", "pool.get_all_records", "host.get_by_uuid", "host.get_record", "host.get_servertime",
            "GET /rrd_updates", "pool.get_all_records", "session.logout"), events.toList())
        val query = queries.single()
        // Start comes from the server's clock (22:15:00Z), aligned to 5 s, even though this phone's clock is 50 s behind.
        assertEquals(setOf("session_id=OpaqueRef:fixture-session", "start=1699999500", "cf=AVERAGE", "interval=5", "host=true", "json=true"), query.split('&').toSet())
        assertEquals("application/json, application/xml;q=0.8", accepts.single())
        assertEquals("Meteor-Spark/${BuildConfig.VERSION_NAME}", agents.single())
        assertEquals(listOf(1_700_000_090L, 1_700_000_095L, 1_700_000_100L), snapshot.timestamps)
        assertEquals(1_700_000_100L, snapshot.serverTimeSeconds); assertEquals(1_700_000_050_000L, snapshot.observedAt)
        assertEquals(listOf("cpu_avg", "memory_used", "memory_total"), snapshot.series.map { it.id })
        assertTrue("NaN stays a gap", snapshot.series.first().values[1].isNaN())
        assertEquals(4096.0 * 1024, snapshot.series.single { it.id == "memory_used" }.values[0], 0.0)
        assertTrue("another object's columns are not mixed in", snapshot.series.none { it.id == "memory" })
    }
    @Test fun vmHistoryParsesTheJsonBodyAndKeepsOnlyThatVmsColumns() {
        val legend = listOf("AVERAGE:vm:$OTHER:cpu0", "AVERAGE:vm:${VM.uppercase()}:cpu0", "AVERAGE:vm:$VM:memory", "AVERAGE:vm:$VM:vif_0_rx", "AVERAGE:host:$HOST:cpu_avg")
        rrdBody = jsonBody(legend, listOf(1_700_000_040L to listOf("0.9", "0.4", "1024", "NaN", "0.2"), 1_700_000_100L to listOf("0.9", "0.6", "2048", "5", "0.3")))
        val snapshot = history(vmTarget, HistoryRange.TWO_HOURS)
        val query = queries.single().split('&').toSet()
        assertTrue("start=1699992900" in query && "interval=60" in query && "host=true" !in query)
        assertEquals(listOf(1_700_000_040L, 1_700_000_100L), snapshot.timestamps); assertEquals(60L, snapshot.stepSeconds)
        assertEquals(listOf(.4, .6), snapshot.series.single { it.id == "cpu_avg" }.values)
        assertEquals(listOf("cpu_avg", "memory", "vif_0_rx"), snapshot.series.map { it.id })
        assertTrue(snapshot.series.single { it.id == "vif_0_rx" }.values[0].isNaN())
        assertTrue(snapshot.notes.single().contains("guest tools"))
        assertEquals(listOf("session.login_with_password", "pool.get_all_records", "pool.get_all_records", "VM.get_by_uuid", "VM.get_record",
            "host.get_servertime", "GET /rrd_updates", "pool.get_all_records", "session.logout"), events.toList())
    }
    @Test fun incrementalReadsAskOnlyForRowsAfterTheNewestOneHeld() {
        history(hostTarget, since = 1_700_000_095)
        assertTrue("start=1700000085" in queries.single().split('&'))
        queries.clear()
        history(hostTarget, since = 1_600_000_000)
        assertTrue("a stale marker falls back to the whole window", "start=1699999500" in queries.single().split('&'))
    }
    @Test fun withoutAServerClockTheWindowIsMeasuredOnThePhone() {
        rpcErrors = mapOf("host.get_servertime" to "RBAC_PERMISSION_DENIED")
        val snapshot = history(hostTarget, now = 1_700_000_050_000L)
        assertTrue("start=1699999450" in queries.single().split('&'))
        assertNull(snapshot.serverTimeSeconds)
    }
    @Test fun vmOnAMemberHostIsUnavailableAndNoRrdRequestIsSent() {
        vmResident = "member-ref"
        val error = failure { history(vmTarget) } as HistoryUnavailable
        assertTrue(error.reason.contains("xcp-b")); assertTrue(error.reason.contains("live values"))
        assertTrue(events.none { it.startsWith("GET ") })
        assertEquals("session.logout", events.last())
        assertFalse("the session reference is never put in a reason", error.reason.contains("OpaqueRef"))
    }
    @Test fun aMemberHostAndAHaltedVmAreUnavailableToo() {
        val member = failure { history(MetricTarget("pool-id", "member-id", MetricKind.HOST, "xcp-b")) } as HistoryUnavailable
        assertTrue(member.reason.contains("xcp-b")); assertTrue(events.none { it.startsWith("GET ") })
        events.clear(); vmPower = "Halted"; vmResident = "OpaqueRef:NULL"
        val halted = failure { history(vmTarget) } as HistoryUnavailable
        assertTrue(halted.reason.contains("running VM")); assertTrue(events.none { it.startsWith("GET ") })
    }
    @Test fun aWrongObjectIdentityOrPoolIsRefusedBeforeAnyHistoryRequest() {
        assertTrue(failure { history(hostTarget.copy(authority = "someone-elses-pool")) } is IllegalArgumentException)
        assertTrue(events.none { it.startsWith("GET ") }); assertEquals("session.logout", events.last())
        events.clear()
        assertNotNull(failure { history(hostTarget.copy(uuid = "unknown-host")) }); assertTrue(events.none { it.startsWith("GET ") })
    }
    @Test fun aPinMismatchSendsNothingAtAll() {
        assertNotNull(failure { history(hostTarget, profile = fixture.profile(pin = "0".repeat(64))) })
        assertTrue("no credentials, session or URL ever reach a host with another certificate", events.isEmpty())
    }
    @Test fun redirectsAndHttpErrorsAreTypedAndTheSessionIsClosed() {
        for ((status, reason) in listOf(302 to "redirect-refused", 403 to "http-denied", 401 to "http-denied", 404 to "http-unsupported", 500 to "http-error")) {
            events.clear(); rrdStatus = status
            val error = failure { history(hostTarget) } as GatewayError
            assertEquals("HTTP $status", reason, error.reason)
            assertEquals("session.logout", events.last())
            assertEquals("the redirect target is never requested", 1, events.count { it.startsWith("GET ") })
            assertFalse(error.message.orEmpty().contains("session_id")); assertFalse(error.message.orEmpty().contains("OpaqueRef"))
        }
    }
    @Test fun bodiesOverTheCapAreRejectedWhetherDeclaredOrStreamed() {
        rrdDeclaredLength = RrdParser.MAX_BYTES + 1L
        assertEquals("response-limit", (failure { history(hostTarget) } as GatewayError).reason)
        rrdDeclaredLength = null; rrdChunked = true; rrdBody = ByteArray(RrdParser.MAX_BYTES + 1) { ' '.code.toByte() }
        assertEquals("response-limit", (failure { history(hostTarget) } as GatewayError).reason)
        assertEquals("session.logout", events.last())
        rrdBody = xmlBody()
        assertEquals(3, history(hostTarget).timestamps.size)
    }
    @Test fun malformedAndEmptyBodiesAreRejectedAndTheSessionIsClosed() {
        for (body in listOf("<xport><meta></xport>", "{\"meta\":{}}", "not rrd data", "")) {
            events.clear(); rrdBody = body.toByteArray()
            assertNotNull(body, failure { history(hostTarget) })
            assertEquals("session.logout", events.last())
        }
    }
    @Test fun aPoolThatChangesDuringTheReadRejectsTheWholeSnapshot() {
        poolAfterRrd = "other-pool"
        assertTrue(failure { history(hostTarget) } is IllegalArgumentException)
        assertEquals(listOf("pool.get_all_records", "session.logout"), events.takeLast(2))
    }

    // ---- read-only detail reads ----

    @Test fun hostInfoReadsRecordPifsMetricsAndTheServerClockInOneSession() {
        val info = runBlocking { gateway().hostInfo(profile, "pool-id", hostUuid) }
        assertEquals(listOf("session.login_with_password", "pool.get_all_records", "host.get_by_uuid", "host.get_record", "PIF.get_all_records",
            "PIF_metrics.get_all_records", "host.get_servertime", "pool.get_all_records", "session.logout"), events.toList())
        assertTrue(info.coordinator); assertEquals("Fixture CPU", info.cpuModel); assertEquals("8.3.0", info.productVersion)
        assertEquals(listOf("eth0"), info.nics.map { it.device })
        assertEquals(true, info.nics.single().carrier); assertEquals(1000L, info.nics.single().speedMbit)
        assertEquals("192.0.2.10", info.managementIp)
        assertEquals(1_700_000_100_000L, info.serverTimeMillis); assertEquals(50_000L, info.clockSkewMillis)
    }
    @Test fun aMemberHostIsReportedAsOneAndAServerClockFailureIsNotFatal() {
        rpcErrors = mapOf("host.get_servertime" to "HOST_OFFLINE")
        val info = runBlocking { gateway().hostInfo(profile, "pool-id", "member-id") }
        assertFalse(info.coordinator); assertNull(info.serverTimeMillis); assertNull(info.clockSkewMillis)
        assertEquals(listOf("192.0.2.11"), info.nics.map { it.ip }); assertEquals("session.logout", events.last())
        rpcErrors = mapOf("host.get_servertime" to "SESSION_AUTHENTICATION_FAILED")
        assertTrue("an expired session is never hidden", failure { runBlocking { gateway().hostInfo(profile, "pool-id", hostUuid) } } is RpcFailure)
    }
    @Test fun networksReadHostsNetworksPifsAndMetricsInOneSession() {
        val overview = runBlocking { gateway().networks(profile, "pool-id") }
        assertEquals(listOf("session.login_with_password", "pool.get_all_records", "host.get_all_records", "network.get_all_records", "PIF.get_all_records",
            "PIF_metrics.get_all_records", "pool.get_all_records", "session.logout"), events.toList())
        val network = overview.networks.single()
        assertEquals("xenbr0", network.bridge); assertEquals(1, network.vifCount)
        assertEquals(listOf("xcp-a" to true, "xcp-b" to false), network.pifs.map { it.hostName to it.carrier })
    }
    @Test fun storageReadsAreBoundedAndOnlyFetchPoolWideVbdAndVmListsWhenADiskIsInUse() {
        vdiCount = 45
        val info = runBlocking { gateway().storageInfo(profile, "pool-id", "sr-id") }
        assertEquals(StorageMapper.MAX_VDIS, methods.count { it == "VDI.get_record" })
        assertEquals(45, info.totalVdis); assertEquals(StorageMapper.MAX_VDIS, info.vdis.size)
        assertEquals(listOf("Fixture VM"), info.vdis.first().attachedVms)
        assertEquals(listOf("Not attached on xcp-b"), info.warnings); assertTrue(info.overProvisioned)
        assertTrue(methods.containsAll(listOf("PBD.get_all_records", "VBD.get_all_records", "VM.get_all_records")))
        assertEquals("session.logout", events.last())
        events.clear(); vdiHasVbd = false; vdiCount = 2
        val idle = runBlocking { gateway().storageInfo(profile, "pool-id", "sr-id") }
        assertTrue(idle.vdis.all { it.attachedVms.isEmpty() })
        assertTrue(methods.none { it == "VBD.get_all_records" || it == "VM.get_all_records" })
    }
    @Test fun vmInfoReadsOnlyThisVmsSnapshotsAndDrivesAndCapsSnapshots() {
        snapshotCount = 3
        val info = runBlocking { gateway().vmInfo(profile, "pool-id", vmUuid) }
        assertEquals(listOf("session.login_with_password", "pool.get_all_records", "pool.get_all_records", "VM.get_by_uuid", "VM.get_record",
            "VM.get_record", "VM.get_record", "VM.get_record", "VBD.get_record", "VBD.get_record", "VDI.get_record", "pool.get_all_records", "session.logout"), events.toList())
        assertEquals("UEFI", info.firmware); assertEquals(SecureBoot.ON, info.secureBoot); assertEquals(false, info.vtpm)
        assertEquals(3, info.snapshotCount); assertEquals("Snapshot snap-2", info.snapshots.first().name)
        assertEquals(listOf(VmCdDrive("3", false, "debian.iso", true)), info.cdDrives)
        assertTrue(info.autoPowerOn); assertEquals(true, info.poolAutoPowerOn)
        events.clear(); snapshotCount = 30
        val many = runBlocking { gateway().vmInfo(profile, "pool-id", vmUuid) }
        assertEquals(30, many.snapshotCount); assertEquals(VmInfoMapper.MAX_SNAPSHOTS, many.snapshots.size)
        assertEquals(VmInfoMapper.MAX_SNAPSHOTS + 1, methods.count { it == "VM.get_record" })
    }
    @Test fun detailReadsRejectAnotherPoolAndPinMismatchesSendNothing() {
        assertTrue(failure { runBlocking { gateway().vmInfo(profile, "someone-elses-pool", vmUuid) } } is IllegalArgumentException)
        assertTrue(failure { runBlocking { gateway().networks(profile, "someone-elses-pool") } } is IllegalArgumentException)
        assertEquals("session.logout", events.last())
        events.clear()
        val wrong = fixture.profile(pin = "0".repeat(64))
        assertNotNull(failure { runBlocking { gateway().storageInfo(wrong, "pool-id", "sr-id") } }); assertTrue(events.isEmpty())
    }
}
