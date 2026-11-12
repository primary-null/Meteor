package com.meteor.app.data

import com.meteor.app.BuildConfig
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.net.Socket

/** Console location rules, the CONNECT request, refusal handling and exactly-once logout. RFB decoding is in [RfbProtocolTest]. */
class ConsoleTest {
    private class MemorySocket(response: ByteArray) : Socket() {
        val output = ByteArrayOutputStream()
        val input = response.inputStream()
        var stopped = false
        override fun getInputStream(): InputStream = input
        override fun getOutputStream(): OutputStream = output
        override fun close() { stopped = true }
    }
    private val uri get() = ConsoleSession.location("https://example.invalid/console?ref=OpaqueRef:fake")
    private fun connect(response: String, sessionQuery: Boolean = false, verify: Boolean = true): MemorySocket {
        val socket = MemorySocket(response.toByteArray())
        ConsoleSession.connect(socket, uri, "OpaqueRef:session", sessionQuery) { verify }
        return socket
    }

    @Test fun rejectsUnsafeLocations() {
        listOf("http://example.invalid/console?uuid=fake", "https://user:password@example.invalid/console?uuid=fake",
            "https://example.invalid/other?uuid=fake", "https://example.invalid/console?uuid=fake&session_id=fake",
            "https://example.invalid/console", "https://example.invalid/console?uuid=fake#frag", "not a url at all",
            "https://example.invalid:70000/console?uuid=fake").forEach {
            assertThrows(it, ConsoleError::class.java) { ConsoleSession.location(it) }
        }
        assertEquals("example.invalid", ConsoleSession.location("https://example.invalid/console?uuid=abc-1").host)
        assertEquals(8443, ConsoleSession.location("https://203.0.113.5:8443/console?ref=OpaqueRef:a-b").port)
    }

    @Test fun connectPathCookieAndAgent() {
        val socket = connect("HTTP/1.1 200 Connection Established\r\n\r\nRFB")
        val request = socket.output.toString("US-ASCII")
        assertTrue(request.startsWith("CONNECT /console?ref=OpaqueRef:fake HTTP/1.1\r\n"))
        assertTrue(request.contains("Host: example.invalid\r\n"))
        assertTrue(request.contains("Cookie: session_id=OpaqueRef:session\r\n"))
        assertTrue(request.contains("User-Agent: Meteor-Spark/${BuildConfig.VERSION_NAME}\r\n"))
        assertFalse("The first attempt keeps the session out of the URL", request.lineSequence().first().contains("session_id"))
        assertFalse(request.contains("https://"))
        assertEquals('R'.code, socket.input.read())
    }

    @Test fun theSessionQueryFormAddsTheParameterToo() {
        val request = connect("HTTP/1.0 200 OK\r\n\r\n", sessionQuery = true).output.toString("US-ASCII")
        assertTrue(request.startsWith("CONNECT /console?ref=OpaqueRef:fake&session_id=OpaqueRef:session HTTP/1.1\r\n"))
        assertTrue(request.contains("Cookie: session_id=OpaqueRef:session\r\n"))
    }

    @Test fun pinMismatchHappensBeforeAnyBytesAreSent() {
        val socket = MemorySocket("RFB 003.008\n".toByteArray())
        val failure = assertThrows(ConsoleError::class.java) { ConsoleSession.connect(socket, uri, "fake") { false } }
        assertEquals(ConsoleFailureKind.CERTIFICATE, failure.kind)
        assertEquals(0, socket.output.size()); assertEquals('R'.code, socket.input.read())
    }

    @Test fun malformedSessionReferencesNeverReachTheRequestLine() {
        val socket = MemorySocket(ByteArray(0))
        assertThrows(ConsoleError::class.java) { ConsoleSession.connect(socket, uri, "bad token\r\nInjected: yes") { true } }
        assertEquals(0, socket.output.size())
    }

    @Test fun refusedConnectsAreClassifiedByStatus() {
        val forbidden = assertThrows(ConnectRefused::class.java) { connect("HTTP/1.1 403 Forbidden\r\n\r\n") }
        assertEquals(403, forbidden.status); assertEquals(ConsoleFailureKind.AUTH, forbidden.kind)
        assertEquals(ConsoleFailureKind.NO_CONSOLE, assertThrows(ConnectRefused::class.java) { connect("HTTP/1.1 404 Not Found\r\n\r\n") }.kind)
        assertEquals(ConsoleFailureKind.FAILED, assertThrows(ConnectRefused::class.java) { connect("HTTP/1.1 500 Oops\r\n\r\n") }.kind)
        assertThrows(ConsoleError::class.java) { connect("garbage\r\n\r\n") }
        assertThrows(ConsoleError::class.java) { connect("HTTP/1.1 200 OK\r\n") }
        assertFalse(forbidden.message.orEmpty().contains("OpaqueRef"))
    }

    @Test fun closesAndLogsOutOnce() {
        val socket = MemorySocket(ByteArray(0)); var logouts = 0
        val session = ConsoleSession(socket) { logouts++ }
        session.close(); session.close(); assertTrue(socket.stopped); assertEquals(1, logouts)
    }

    @Test fun logoutRunsEvenWhenClosingTheSocketFails() {
        var logouts = 0
        val socket = object : Socket() {
            override fun getInputStream(): InputStream = ByteArray(0).inputStream()
            override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
            override fun close() { throw IOException("already broken") }
        }
        val session = ConsoleSession(socket) { logouts++ }
        assertThrows(IOException::class.java) { session.close() }
        assertEquals(1, logouts)
    }

    @Test fun transportFailuresAreClassifiedWithoutLeakingDetails() {
        assertEquals(ConsoleFailureKind.TIMEOUT, ConsoleFailures.classify(java.net.SocketTimeoutException("secret detail")).kind)
        assertEquals(ConsoleFailureKind.NETWORK, ConsoleFailures.classify(java.net.UnknownHostException("host")).kind)
        assertEquals(ConsoleFailureKind.NETWORK, ConsoleFailures.classify(java.net.ConnectException("refused")).kind)
        assertEquals(ConsoleFailureKind.LOST, ConsoleFailures.classify(EOFException()).kind)
        assertEquals(ConsoleFailureKind.LOST, ConsoleFailures.classify(java.net.SocketException("reset")).kind)
        assertEquals(ConsoleFailureKind.CERTIFICATE, ConsoleFailures.classify(javax.net.ssl.SSLHandshakeException("x").apply { initCause(CertificateChanged()) }).kind)
        assertEquals(ConsoleFailureKind.CERTIFICATE, ConsoleFailures.classify(javax.net.ssl.SSLException("x")).kind)
        assertEquals(ConsoleFailureKind.AUTH, ConsoleFailures.classify(RpcFailure("SESSION_AUTHENTICATION_FAILED")).kind)
        assertEquals(ConsoleFailureKind.AUTH, ConsoleFailures.classify(RpcFailure("RBAC_PERMISSION_DENIED")).kind)
        assertEquals(ConsoleFailureKind.FAILED, ConsoleFailures.classify(IllegalStateException("x")).kind)
        val own = ConsoleError("kept", ConsoleFailureKind.NOT_RUNNING)
        assertSame(own, ConsoleFailures.classify(own))
        assertFalse(ConsoleFailures.classify(java.net.SocketTimeoutException("secret detail")).message.orEmpty().contains("secret"))
    }
}
