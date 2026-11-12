package com.meteor.app.data

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * B3 background/summary: the real pinned TLS, HTTP and XML-RPC paths against a loopback server with a throw-away
 * certificate. Covers the background alert read, the all-pools summary read and the update-check plugin call.
 * No lab access. Every test ends by asserting that nothing but sign-in/out, reads and the one plugin check was sent.
 */
class BackgroundTransportIntegrationTest {
    private lateinit var fixture: TlsFixture
    private lateinit var profile: Profile
    private val calls = CopyOnWriteArrayList<String>()
    private val bodies = CopyOnWriteArrayList<String>()
    private var rpcErrors = emptyMap<String, Pair<String, List<String>>>()
    private var pluginAnswer: Any = "[{\"name\":\"xen-hypervisor\",\"version\":\"4.17.5\",\"release\":\"15\"},{\"name\":\"kernel\",\"version\":\"4.19\"}]"
    private var messages: Map<String, Map<String, Any>> = mapOf(
        "OpaqueRef:m1" to mapOf("uuid" to "m1", "name" to "ha_host_failed", "priority" to "1", "cls" to "Host", "obj_uuid" to "host-id",
            "timestamp" to "20271115T10:00:00Z", "body" to "Host 203.0.113.5 failed"),
        "OpaqueRef:m2" to mapOf("uuid" to "m2", "name" to "VM_STARTED", "priority" to "5", "cls" to "VM", "obj_uuid" to "vm-id",
            "timestamp" to "20271115T10:01:00Z", "body" to "started"),
        "OpaqueRef:m3" to mapOf("uuid" to "m3", "name" to "sr_space_low", "priority" to "2", "cls" to "SR", "obj_uuid" to "gone-sr",
            "timestamp" to "20271115T10:02:00Z", "body" to "low"))

    @Before fun start() {
        fixture = TlsFixture()
        fixture.server.createContext("/") { exchange ->
            try {
                val body = exchange.requestBody.bufferedReader().use { it.readText() }
                val method = body.substringAfter("<methodName>").substringBefore("</methodName>")
                calls += method; bodies += body
                val error = rpcErrors[method]
                val xml = "<methodResponse><params><param><value>" + value(
                    if (error != null) mapOf("Status" to "Failure", "ErrorDescription" to listOf(error.first) + error.second)
                    else result(method, body)?.let { mapOf("Status" to "Success", "Value" to it) }
                        ?: mapOf("Status" to "Failure", "ErrorDescription" to listOf("UUID_INVALID", "fixture"))) + "</value></param></params></methodResponse>"
                val bytes = xml.toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "text/xml; charset=utf-8")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.write(bytes)
            } catch (_: Throwable) { runCatching { exchange.sendResponseHeaders(500, -1) } } finally { exchange.close() }
        }
        fixture.start()
        profile = fixture.profile()
    }

    @After fun stop() {
        val allowed = setOf("session.login_with_password", "session.logout", "message.get_since", "VM.get_all_records_where", "host.call_plugin")
        assertTrue("unexpected method in $calls", calls.all { it in allowed || it.substringAfter('.').startsWith("get_") })
        // The only plugin call is the read-only update check.
        bodies.filter { "host.call_plugin" in it }.forEach { assertTrue(it.contains("<string>check_update</string>")); assertFalse(it.contains("<string>update</string>")) }
        assertTrue(calls.none { it.startsWith("Async.") || it.endsWith(".destroy") || it.contains("set_") })
        fixture.close()
    }

    private fun args(body: String) = Regex("<string>(.*?)</string>", RegexOption.DOT_MATCHES_ALL).findAll(body).map { it.groupValues[1] }.toList()

    private fun result(method: String, body: String): Any? = when (method) {
        "session.login_with_password" -> "OpaqueRef:session"
        "session.logout" -> ""
        "pool.get_all_records" -> mapOf("OpaqueRef:pool" to mapOf("uuid" to "pool-id", "name_label" to "Fixture pool"))
        "host.get_all_records" -> mapOf("OpaqueRef:h1" to mapOf("uuid" to "host-id", "metrics" to "OpaqueRef:hm1"),
            "OpaqueRef:h2" to mapOf("uuid" to "host-2", "metrics" to "OpaqueRef:hm2"))
        "host_metrics.get_all_records" -> mapOf("OpaqueRef:hm1" to mapOf("live" to true), "OpaqueRef:hm2" to mapOf("live" to false))
        "VM.get_all_records_where" -> mapOf("OpaqueRef:v1" to mapOf("power_state" to "Running"), "OpaqueRef:v2" to mapOf("power_state" to "Halted"),
            "OpaqueRef:dom0" to mapOf("power_state" to "Running", "is_control_domain" to true))
        "message.get_since", "message.get_all_records" -> messages
        "host.get_by_uuid" -> if (args(body)[1] == "host-id") "OpaqueRef:h1" else null
        "SR.get_by_uuid", "VM.get_by_uuid" -> null
        "host.get_name_label" -> "node-01"
        "host.get_record" -> mapOf("uuid" to "host-id")
        "host.call_plugin" -> pluginAnswer
        else -> error("Unexpected fixture request: $method")
    }

    private fun value(input: Any): String = when (input) {
        is Map<*, *> -> "<struct>" + input.entries.joinToString("") { "<member><name>${it.key}</name><value>${value(requireNotNull(it.value))}</value></member>" } + "</struct>"
        is Boolean -> "<boolean>${if (input) 1 else 0}</boolean>"
        is List<*> -> "<array><data>" + input.joinToString("") { "<value>${value(requireNotNull(it))}</value>" } + "</data></array>"
        else -> "<string>${input.toString().replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")}</string>"
    }

    // ---- background alert read ----
    @Test fun backgroundReadIsOneSessionWithGetSinceAndBoundedNameReads() = runBlocking {
        val since = 1_800_000_000_000L
        val fetch = XapiGateway { since }.backgroundAlerts(profile, since) { it.severity.urgent }
        assertEquals(listOf("session.login_with_password", "pool.get_all_records", "pool.get_all_records", "message.get_since", "pool.get_all_records",
            "SR.get_by_uuid", "host.get_by_uuid", "host.get_name_label", "session.logout"), calls.toList())
        assertEquals(1, calls.count { it == "session.login_with_password" })
        assertTrue(bodies[calls.indexOf("message.get_since")].contains("<dateTime.iso8601>20270115T08:00:00Z</dateTime.iso8601>"))
        assertEquals("pool-id", fetch.authority); assertEquals("Fixture pool", fetch.poolName)
        assertEquals(setOf("OpaqueRef:m1", "OpaqueRef:m2", "OpaqueRef:m3"), fetch.messages.map { it.ref }.toSet())
        // The VM message is not wanted (priority 5), so its name is never read; the missing SR only loses its name.
        assertEquals(mapOf("host:host-id" to "node-01"), fetch.objectNames)
    }

    @Test fun backgroundReadEndToEndProducesNotificationTextWithoutBodiesOrAddresses() = runBlocking {
        val settings = AlertNotifySettings(true, NotifyThreshold.HIGH, 15, 1_800_000_000_000L)
        val fetch = XapiGateway().backgroundAlerts(profile, BackgroundAlerts.since(profile, settings, null, 0), BackgroundAlerts.wanted(settings))
        val decision = BackgroundAlerts.decide(profile, settings, null, fetch, 0)
        val batch = requireNotNull(AlertNotificationContent.batch(profile, fetch, decision.notify))
        assertEquals(listOf("Critical: HA host failed" to "Fixture pool · Host node-01", "High: SR space low" to "Fixture pool · Storage"),
            batch.items.map { it.title to it.text })
        val text = batch.items.joinToString { it.title + it.text } + batch.summary!!.lines.joinToString()
        listOf("203.0.113.5", "127.0.0.1", profile.username, profile.password, "OpaqueRef").forEach { assertFalse("leaked $it", text.contains(it)) }
    }

    @Test fun aChangedPinFailsBeforeAnyCredentialIsSent() = runBlocking {
        val error = runCatching { XapiGateway().backgroundAlerts(profile.copy(certificateSha256 = "0".repeat(64)), 1) { true } }.exceptionOrNull()
        assertNotNull(error)
        assertTrue(PoolSummaries.certificateChanged(error!!))
        assertTrue(calls.isEmpty())
    }

    @Test fun anAuthenticationFailureIsTypedAndNothingElseIsSent() = runBlocking {
        rpcErrors = mapOf("session.login_with_password" to ("SESSION_AUTHENTICATION_FAILED" to listOf("fixture-user")))
        val error = runCatching { XapiGateway().backgroundAlerts(profile, 1) { true } }.exceptionOrNull()
        assertEquals("SESSION_AUTHENTICATION_FAILED", (error as RpcFailure).code)
        assertEquals(listOf("session.login_with_password"), calls.toList())
    }

    // ---- all-pools summary ----
    @Test fun summaryIsOneSessionWithAFilteredVmReadAndUnreadFromTheSeenValue() = runBlocking {
        val seen = 1_826_000_000_000L // 2027-11-15 ~09:33 UTC: m1, m2 and m3 are newer
        val read = XapiGateway { 7L }.poolSummary(profile, seen)
        assertEquals(listOf("session.login_with_password", "pool.get_all_records", "host.get_all_records", "host_metrics.get_all_records",
            "VM.get_all_records_where", "pool.get_all_records", "message.get_since", "pool.get_all_records", "session.logout"), calls.toList())
        assertTrue(args(bodies[calls.indexOf("VM.get_all_records_where")])[1].contains("field &quot;is_a_template&quot;=&quot;false&quot;"))
        assertEquals(PoolSummaryRead("pool-id", "Fixture pool", 1, 2, 1, 2, 2, 7), read)
    }

    @Test fun summaryWithoutASeenValueUsesTheBoundedFullReadAndARefusedAlertReadKeepsTheCounts() = runBlocking {
        val first = XapiGateway().poolSummary(profile, null)
        assertTrue("message.get_all_records" in calls); assertEquals(2, first.unreadUrgent)
        calls.clear()
        rpcErrors = mapOf("message.get_all_records" to ("RBAC_PERMISSION_DENIED" to listOf("message.get_all_records")))
        val refused = XapiGateway().poolSummary(profile, null)
        assertNull(refused.unreadUrgent); assertEquals(2, refused.hostsTotal)
        assertEquals("session.logout", calls.last())
    }

    @Test fun summaryPinMismatchSendsNothing() = runBlocking {
        val error = runCatching { XapiGateway().poolSummary(profile.copy(certificateSha256 = "0".repeat(64)), null) }.exceptionOrNull()
        assertTrue(PoolSummaries.certificateChanged(requireNotNull(error)))
        assertTrue(calls.isEmpty())
    }

    // ---- update check ----
    @Test fun updateCheckCallsTheUpdaterPluginCheckFunctionOnTheVerifiedHost() = runBlocking {
        val result = XapiGateway().checkHostUpdates(profile, "pool-id", "host-id")
        assertEquals(listOf("session.login_with_password", "pool.get_all_records", "host.get_by_uuid", "host.get_record", "host.call_plugin",
            "pool.get_all_records", "session.logout"), calls.toList())
        val body = bodies[calls.indexOf("host.call_plugin")]
        assertEquals(listOf("OpaqueRef:session", "OpaqueRef:h1", "updater.py", "check_update"), args(body))
        assertTrue(body.contains("<struct></struct>"))
        assertTrue(result.recognized); assertEquals(listOf("kernel", "xen-hypervisor"), result.updates.map { it.name })
    }

    @Test fun updateCheckReportsPluginErrorsAndUnknownShapes() = runBlocking {
        rpcErrors = mapOf("host.call_plugin" to ("XENAPI_MISSING_PLUGIN" to listOf("updater.py")))
        val missing = runCatching { XapiGateway().checkHostUpdates(profile, "pool-id", "host-id") }.exceptionOrNull()
        assertEquals("The updater plugin is not installed on this host. (XENAPI_MISSING_PLUGIN)", HostUpdates.problem(requireNotNull(missing)))
        rpcErrors = emptyMap(); pluginAnswer = "Loaded plugins: fastestmirror"
        assertFalse(XapiGateway().checkHostUpdates(profile, "pool-id", "host-id").recognized)
        // Another pool or an unknown host never reaches the plugin.
        calls.clear()
        assertTrue(runCatching { XapiGateway().checkHostUpdates(profile, "other-pool", "host-id") }.isFailure)
        assertTrue(runCatching { XapiGateway().checkHostUpdates(profile, "pool-id", "no-such-host") }.isFailure)
        assertTrue("host.call_plugin" !in calls)
    }
}
