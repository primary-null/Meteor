package com.meteor.app.data

import org.junit.Assert.*
import org.junit.Test
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

class EventsAndErrorsTest {
    // ---- XML-RPC encoding ----
    @Test fun doublesAndDatesAreEncodedAsXmlRpcTypes() {
        val request = String(XmlRpc.request("event.from", listOf("session", listOf("VM", "pool"), "", 30.0)))
        assertTrue(request.contains("<double>30.000</double>"))
        assertTrue(request.contains("<array><data><value><string>VM</string></value><value><string>pool</string></value></data></array>"))
        assertTrue(request.contains("<string></string>"))
        val since = String(XmlRpc.request("message.get_since", listOf("session", XmlRpcDateTime(1_800_000_000_000L))))
        assertTrue(since.contains("<dateTime.iso8601>20270115T08:00:00Z</dateTime.iso8601>"))
        assertTrue(runCatching { XmlRpc.request("event.from", listOf(Double.NaN)) }.isFailure)
    }
    @Test fun xapiFailureKeepsItsCappedParametersButNotInTheMessage() {
        fun failure(vararg description: String): RpcFailure {
            val items = description.joinToString("") { "<value><string>$it</string></value>" }
            val xml = "<methodResponse><params><param><value><struct><member><name>Status</name><value><string>Failure</string></value></member>" +
                "<member><name>ErrorDescription</name><value><array><data>$items</data></array></value></member></struct></value></param></params></methodResponse>"
            return runCatching { XmlRpc.response(xml) }.exceptionOrNull() as RpcFailure
        }
        val slave = failure("HOST_IS_SLAVE", "192.0.2.10")
        assertEquals("HOST_IS_SLAVE", slave.code); assertEquals(listOf("192.0.2.10"), slave.details)
        assertEquals("XAPI request failed (HOST_IS_SLAVE)", slave.message)
        assertTrue(slave.explicitRejection)
        val many = failure("X", *Array(20) { "p$it" })
        assertEquals(8, many.details.size)
        assertEquals(200, failure("X", "y".repeat(500)).details.single().length)
        assertEquals(emptyList<String>(), failure("SESSION_AUTHENTICATION_FAILED").details)
    }

    // ---- event parsing and triggers ----
    @Test fun batchParsingReadsOnlyTokenOperationClassAndRef() {
        val value = mapOf("token" to "t-2", "valid_ref_counts" to mapOf("VM" to 3L), "events" to listOf(
            mapOf("id" to "9", "class" to "VM", "operation" to "MOD", "ref" to "OpaqueRef:vm", "snapshot" to mapOf("name_label" to "ignored")),
            "not a map", mapOf("class" to "message", "operation" to "del", "ref" to "OpaqueRef:m")))
        val batch = EventParser.batch(value)
        assertEquals("t-2", batch.token)
        assertEquals(listOf(ServerEvent("mod", "vm", "OpaqueRef:vm"), ServerEvent("del", "message", "OpaqueRef:m")), batch.events)
        for (bad in listOf<Any?>(null, "x", mapOf("events" to emptyList<Any>()), mapOf("token" to "", "events" to emptyList<Any>()),
            mapOf("token" to "t", "events" to "no"))) assertEquals("invalid-events", (runCatching { EventParser.batch(bad) }.exceptionOrNull() as GatewayError).reason)
    }
    @Test fun classesDecideWhichExistingRefreshesRun() {
        fun triggers(vararg events: ServerEvent) = EventTriggers.classify(events.toList())
        assertEquals(EventTriggers(inventory = true, details = true), triggers(ServerEvent("mod", "vm", "r")))
        assertEquals(EventTriggers(inventory = true), triggers(ServerEvent("mod", "host", "r"), ServerEvent("add", "sr", "r"), ServerEvent("mod", "pbd", "r"), ServerEvent("mod", "pool", "r")))
        assertEquals(EventTriggers(details = true), triggers(ServerEvent("mod", "vbd", "r"), ServerEvent("mod", "vif", "r")))
        assertEquals(EventTriggers(details = true, activity = true), triggers(ServerEvent("mod", "task", "r")))
        assertEquals(EventTriggers(alerts = true, activity = true), triggers(ServerEvent("add", "message", "r")))
        assertEquals(EventTriggers.NONE, triggers(ServerEvent("mod", "session", "r")))
        val deleted = triggers(ServerEvent("del", "message", "OpaqueRef:gone"), ServerEvent("del", "message", ""), ServerEvent("add", "message", "OpaqueRef:new"))
        assertEquals(setOf("OpaqueRef:gone"), deleted.deletedMessages)     // the only payload field that is used
        assertTrue(EventTriggers.FULL.full && EventTriggers.FULL.inventory && EventTriggers.FULL.alerts)
        assertFalse(EventTriggers.START.full)
        assertEquals(EventTriggers(inventory = true, alerts = true, activity = true), EventTriggers(inventory = true) + EventTriggers(alerts = true, activity = true))
        assertFalse(EventTriggers.NONE.any)
    }
    @Test fun failuresAreClassifiedForResyncReloginAndBackoff() {
        assertEquals(EventFailureKind.EVENTS_LOST, EventFailures.classify(RpcFailure("EVENTS_LOST")))
        assertEquals(EventFailureKind.SESSION_INVALID, EventFailures.classify(RpcFailure("SESSION_INVALID")))
        for (code in listOf("HOST_IS_SLAVE", "SESSION_AUTHENTICATION_FAILED", "RBAC_PERMISSION_DENIED"))
            assertEquals(EventFailureKind.SLOW, EventFailures.classify(RpcFailure(code)))
        assertEquals(EventFailureKind.SLOW, EventFailures.classify(UnsupportedOperationException()))
        assertEquals(EventFailureKind.EVENTS_LOST, EventFailures.classify(GatewayError("response-limit")))     // too large to read: resync
        assertEquals(EventFailureKind.EVENTS_LOST, EventFailures.classify(IllegalArgumentException("Response is too complex.")))
        assertEquals(EventFailureKind.TRANSIENT, EventFailures.classify(IllegalArgumentException("Pool identity changed.")))
        assertEquals(EventFailureKind.TRANSIENT, EventFailures.classify(java.io.IOException("reset")))
        assertEquals(EventFailureKind.TRANSIENT, EventFailures.classify(RpcFailure("RPC_FAULT", false)))
    }
    @Test fun backoffGrowsToItsCapAndResets() {
        val backoff = Backoff(listOf(1, 2, 4))
        assertEquals(listOf(1L, 2L, 4L, 4L, 4L), List(5) { backoff.next() })
        backoff.reset(); assertEquals(1L, backoff.next())
        assertEquals(4L, backoff.longest()); assertEquals(4L, backoff.next())
        assertTrue(EventPolicy.BACKOFF_MILLIS.zipWithNext().all { (a, b) -> b >= a })
    }
    @Test fun httpReadTimeoutAlwaysExceedsTheEventTimeoutAndPolicyNumbersAreOrdered() {
        assertTrue(EventPolicy.readTimeoutMillis(EventPolicy.POLL_TIMEOUT_SECONDS) > (EventPolicy.POLL_TIMEOUT_SECONDS * 1000).toLong())
        assertEquals(50_000L, EventPolicy.readTimeoutMillis(30.0))
        assertTrue(EventPolicy.readTimeoutMillis(1.0, 0) == 1000L)
        assertTrue(EventPolicy.HEALTHY_STALE_MILLIS > EventPolicy.POLLING_STALE_MILLIS)
        // Overview must not call a healthy-but-quiet snapshot "last known" before its next fallback read (90 s + a 30 s tick).
        assertTrue(EventPolicy.HEALTHY_FRESH_WINDOW_MILLIS > EventPolicy.HEALTHY_STALE_MILLIS + 30_000)
        assertTrue(EventPolicy.MIN_REFRESH_GAP_MILLIS >= EventPolicy.DEBOUNCE_MILLIS)
        assertEquals(listOf("VM", "host", "pool", "SR", "PBD", "VBD", "VIF", "task", "message"), EventPolicy.CLASSES)
    }

    // ---- master address ----
    private val member = Profile("p1", "Lab", "https://member.example:8443/", "root", "pw", "a".repeat(64))
    private fun slave(vararg details: String) = RpcFailure("HOST_IS_SLAVE", details = details.toList())
    @Test fun masterAddressesAreValidatedBeforeAnythingIsOffered() {
        assertEquals("192.0.2.10", MasterFollow.address(listOf("192.0.2.10")))
        assertEquals("xcp-master.lab.example", MasterFollow.address(listOf(" XCP-Master.lab.example ")))
        assertEquals("fe80::1", MasterFollow.address(listOf("[fe80::1]")))
        for (bad in listOf("", "evil.example/path", "user@evil.example", "evil example", "-bad.example", "a..b/", "https://x", "203.0.113.4:443", "::g")) {
            assertNull("accepted: $bad", MasterFollow.address(listOf(bad)))
        }
        assertNull(MasterFollow.address(emptyList()))
    }
    @Test fun offerKeepsProfileIdentityClearsThePinAndKeepsTheMemberPort() {
        val offer = MasterFollow.offer(member, slave("192.0.2.10", "ignored"))!!
        assertEquals("https://192.0.2.10:8443/", offer.masterEndpoint)
        assertEquals("https://member.example:8443/", offer.memberEndpoint)
        assertEquals(member.id, offer.draft.id)
        assertEquals(member.copy(endpoint = offer.masterEndpoint, certificateSha256 = ""), offer.draft)
        assertEquals("https://[fe80::1]/", MasterFollow.offer(member.copy(endpoint = "https://member.example/"), slave("fe80::1"))!!.masterEndpoint)
        assertEquals(offer.key, MasterFollow.offer(member, slave("192.0.2.10"))!!.key)
        assertNotEquals(offer.key, MasterFollow.offer(member, slave("192.0.2.11"))!!.key)
    }
    @Test fun noOfferWithoutAUsableDistinctMaster() {
        assertNull(MasterFollow.offer(member, slave()))
        assertNull(MasterFollow.offer(member, slave("bad/host")))
        assertNull(MasterFollow.offer(member, slave("member.example")))               // names itself: no loop
        assertNull(MasterFollow.offer(member, RpcFailure("SESSION_AUTHENTICATION_FAILED", details = listOf("192.0.2.10"))))
        assertNull(MasterFollow.offer(member, GatewayError("http-error")))
        assertFalse(MasterFollow.offer(member, slave("192.0.2.10"))!!.toString().contains("pw"))
    }

    // ---- error reasons ----
    @Test fun xapiCodesGetAnExplanationAndTheRawCode() {
        val cases = mapOf(
            "SESSION_AUTHENTICATION_FAILED" to "username and password", "RBAC_PERMISSION_DENIED" to "role", "HOST_OFFLINE" to "offline",
            "HOST_IS_SLAVE" to "pool member", "SESSION_INVALID" to "expired", "EVENTS_LOST" to "events", "HANDLE_INVALID" to "no longer exists")
        for ((code, word) in cases) {
            val text = ErrorReasons.describe(RpcFailure(code))
            assertTrue("$code: $text", text.contains(word) && text.endsWith("($code)"))
        }
        assertEquals("The server refused this request. (ODD_CODE)", ErrorReasons.describe(RpcFailure("ODD_CODE", details = listOf("private argument"))))
    }
    @Test fun onlyValidatedParametersAreShown() {
        assertTrue(ErrorReasons.describe(RpcFailure("HOST_IS_SLAVE", details = listOf("192.0.2.10"))).contains("192.0.2.10"))
        assertFalse(ErrorReasons.describe(RpcFailure("HOST_IS_SLAVE", details = listOf("evil/<script>"))).contains("evil"))
        val denied = ErrorReasons.describe(RpcFailure("RBAC_PERMISSION_DENIED", details = listOf("message.destroy", "No permission in user session")))
        assertTrue(denied.contains("message.destroy")); assertFalse(denied.contains("No permission in user session"))
        assertFalse(ErrorReasons.describe(RpcFailure("RBAC_PERMISSION_DENIED", details = listOf("a b <x>"))).contains("a b"))
    }
    @Test fun gatewayReasonsAndTransportCausesAreExplainedWithoutExceptionText() {
        assertTrue(ErrorReasons.describe(GatewayError("http-error")).endsWith("(http-error)"))
        assertTrue(ErrorReasons.describe(GatewayError("response-limit")).contains("larger than Meteor accepts"))
        assertTrue(ErrorReasons.describe(GatewayError("invalid-records")).contains("expected format"))
        assertEquals("The server redirected the request. Verify the server address.", ErrorReasons.describe(GatewayError("redirect-refused")))
        assertEquals("Meteor could not complete the request. (weird-reason)", ErrorReasons.describe(GatewayError("weird-reason")))
        assertEquals("Meteor could not complete the request.", ErrorReasons.describe(GatewayError("Bad Reason <with> secrets")))
        for ((error, word) in listOf<Pair<Throwable, String>>(SocketTimeoutException("password=hunter2") to "in time", UnknownHostException("host") to "resolved",
            ConnectException("refused") to "reached", SSLHandshakeException("handshake") to "secure connection")) {
            val text = ErrorReasons.describe(RuntimeException("wrapper", error))
            assertTrue(text, text.contains(word)); assertFalse(text.contains("hunter2"))
        }
        assertEquals(ErrorReasons.GENERIC, ErrorReasons.describe(IllegalStateException("secret detail")))
        assertTrue(ErrorReasons.describe(CertificateChanged()).contains("certificate changed"))
        assertTrue(ErrorReasons.describe(RuntimeException(CertificateChanged())).contains("certificate changed"))
    }
    @Test fun readIssuesKeepLastKnownWordingAndAddTheReason() {
        assertEquals("Metrics is not supported by this server.", ErrorReasons.readIssue(RpcFailure("MESSAGE_METHOD_UNKNOWN"), "Metrics"))
        assertEquals("Alerts is not available to this account.", ErrorReasons.readIssue(RpcFailure("RBAC_PERMISSION_DENIED"), "Alerts"))
        val rpc = ErrorReasons.readIssue(RpcFailure("HOST_OFFLINE"), "Pool activity")
        assertTrue(rpc.contains("(HOST_OFFLINE)") && rpc.contains("last known"))
        val gateway = ErrorReasons.readIssue(GatewayError("http-error"), "Pool activity")
        assertTrue(gateway.contains("(http-error)") && gateway.contains("last known"))
        val generic = ErrorReasons.readIssue(IllegalStateException("private transport detail"), "Metrics")
        assertTrue(generic.contains("last known")); assertFalse(generic.contains("private"))
        assertTrue(ErrorReasons.readIssue(SocketTimeoutException("x"), "Metrics").contains("did not answer in time"))
    }
}
