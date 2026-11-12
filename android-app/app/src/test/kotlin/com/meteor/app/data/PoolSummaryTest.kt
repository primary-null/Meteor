package com.meteor.app.data

import org.junit.Assert.*
import org.junit.Test
import java.net.SocketTimeoutException

/** B3 background/summary: summary counting and the defensive update-check parser. */
class PoolSummaryTest {
    @Test fun countsIgnoreTemplatesSnapshotsAndControlDomains() {
        val read = PoolSummaries.counts(
            mapOf("pool" to mapOf("uuid" to "pool-1", "name_label" to "Prod")),
            mapOf("h1" to mapOf("metrics" to "m1"), "h2" to mapOf("metrics" to "m2"), "h3" to mapOf("metrics" to "missing")),
            mapOf("m1" to mapOf("live" to true), "m2" to mapOf("live" to false)),
            mapOf("v1" to mapOf("power_state" to "Running"), "v2" to mapOf("power_state" to "Halted"),
                "t" to mapOf("is_a_template" to true, "power_state" to "Running"), "s" to mapOf("is_a_snapshot" to "true"),
                "dom0" to mapOf("is_control_domain" to true, "power_state" to "Running")),
            unreadUrgent = 2, alertsPartial = false, now = 5)
        assertEquals(PoolSummaryRead("pool-1", "Prod", 1, 3, 1, 2, 2, 5), read)
        assertThrows(IllegalArgumentException::class.java) { PoolSummaries.counts(emptyMap(), emptyMap(), emptyMap(), emptyMap(), null, false, 0) }
    }

    @Test fun unreadCountsOnlyNewPriorityOneToThree() {
        val messages = listOf(1, 2, 3, 4, 5, null).mapIndexed { index, priority -> AlertMessage("r$index", "", "x", priority, "VM", "", 100L + index, "") } +
            AlertMessage("old", "", "x", 1, "VM", "", 50, "")
        assertEquals(4, PoolSummaries.unread(messages, null)) // "old" counts too before any view
        assertEquals(3, PoolSummaries.unread(messages, 60))
    }

    @Test fun failuresAreClassifiedWithoutLeakingDetails() {
        val profile = Profile("p", "P", "https://p.invalid/", "root", "pw", "a".repeat(64))
        val changed = PoolSummaries.failed(profile, javax.net.ssl.SSLHandshakeException("x").apply { initCause(CertificateChanged()) }, 1)
        assertEquals(PoolReachability.CERTIFICATE_CHANGED, changed.reachability)
        val auth = PoolSummaries.failed(profile, RpcFailure("SESSION_AUTHENTICATION_FAILED", details = listOf("root")), 1)
        assertEquals(PoolReachability.UNREACHABLE, auth.reachability)
        assertTrue(auth.problem!!.contains("SESSION_AUTHENTICATION_FAILED")); assertFalse(auth.problem!!.contains("root"))
        assertTrue(PoolSummaries.failed(profile, SocketTimeoutException("203.0.113.11"), 1).problem!!.contains("did not answer"))
        assertEquals(profile.origin(), auth.origin)
    }

    @Test fun updateListIsParsedFromTheExpectedJsonArray() {
        val raw = """[{"name":"xen-hypervisor","version":"4.17.5","release":"15.xcpng8.3","description":"Xen","url":"https://mirror.example/x.rpm"},
            {"name":"kernel","version":"4.19.19","release":"8.0.38"}, {"name":"bad name; rm -rf /","version":"1"}]"""
        val result = HostUpdates.parse(raw)
        assertTrue(result.recognized); assertEquals(3, result.total)
        assertEquals(listOf("kernel", "Unnamed package", "xen-hypervisor"), result.updates.map { it.name })
        assertEquals("4.17.5-15.xcpng8.3", result.updates.first { it.name == "xen-hypervisor" }.version)
        assertTrue(result.updates.none { it.name.contains("mirror") })
    }

    @Test fun noUpdatesAndOtherShapesAreHandledDefensively() {
        assertEquals(HostUpdateResult(emptyList(), 0, true), HostUpdates.parse("[]"))
        assertEquals(listOf("a", "b"), HostUpdates.parse("[\"b\",\"a\"]").updates.map { it.name })
        assertEquals(listOf("pkg"), HostUpdates.parse("{\"pkg\":{\"version\":\"1\"}}").updates.map { it.name })
        listOf(null, "", "   ", "not json", "{\"error\":\"yum lock\"}", "[1,2]", "42", 17L, mapOf("a" to 1)).forEach { raw ->
            assertFalse("should not be recognized: $raw", HostUpdates.parse(raw).recognized)
        }
        val many = (1..500).joinToString(",", "[", "]") { "{\"name\":\"p$it\"}" }
        HostUpdates.parse(many).let { assertEquals(500, it.total); assertEquals(HostUpdates.MAX_SHOWN, it.updates.size) }
    }

    @Test fun updateCheckErrorsExplainTheServersReasonFromClosedVocabularies() {
        assertTrue(HostUpdates.problem(RpcFailure("RBAC_PERMISSION_DENIED", details = listOf("host.call_plugin"))).let {
            it.contains("RBAC_PERMISSION_DENIED") && it.contains("pool-admin") })
        assertEquals("The updater plugin is not installed on this host. (XENAPI_MISSING_PLUGIN)", HostUpdates.problem(RpcFailure("XENAPI_MISSING_PLUGIN")))
        val failure = HostUpdates.problem(RpcFailure("XENAPI_PLUGIN_FAILURE", details = listOf("check_update", "YumException", "Cannot reach http://203.0.113.9/repo")))
        assertTrue(failure.contains("YumException")); assertFalse(failure.contains("203.0.113.9"))
        assertTrue(HostUpdates.problem(SocketTimeoutException()).contains("did not answer"))
    }
}
