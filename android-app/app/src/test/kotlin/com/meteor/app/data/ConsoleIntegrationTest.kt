package com.meteor.app.data

import com.meteor.app.ConsoleController
import com.meteor.app.ConsolePhase
import com.meteor.app.ConsoleTarget
import com.meteor.app.data.RfbScript.Companion.argb
import com.meteor.app.data.RfbScript.Companion.pixel
import com.meteor.app.data.RfbScript.Companion.rect
import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread

/**
 * The console over real TLS on loopback: a pinned XAPI fixture (login, consoles, logout) plus a TLS server that
 * answers `CONNECT /console` and then speaks scripted RFB. No lab, device or network service is involved.
 */
class ConsoleIntegrationTest {
    companion object {
        private lateinit var master: TlsIdentity
        private lateinit var member: TlsIdentity
        @JvmStatic @BeforeClass fun certificates() { master = TlsFixtures.create(); member = TlsFixtures.create("member") }
    }

    /** `CONNECT` endpoint: answers with the next of [statuses], then (for 200) plays the RFB script. */
    private class ConsoleServer(identity: TlsIdentity, private val statuses: List<Int> = listOf(200), private val script: () -> ByteArray) : Closeable {
        private val server = identity.context.serverSocketFactory.createServerSocket(0, 8, InetAddress.getByName("127.0.0.1")) as SSLServerSocket
        val port get() = server.localPort
        val requests = CopyOnWriteArrayList<String>()
        val connections = AtomicInteger()
        private val received = ByteArrayOutputStream()
        init {
            thread(isDaemon = true) {
                while (!server.isClosed) {
                    val socket = try { server.accept() as SSLSocket } catch (_: IOException) { break }
                    connections.getAndIncrement()
                    thread(isDaemon = true) {
                        try {
                            val input = socket.getInputStream(); val header = StringBuilder()
                            while (!header.endsWith("\r\n\r\n")) { val byte = input.read(); if (byte < 0) return@thread; header.append(byte.toChar()) }
                            requests += header.toString()
                            // Probes and rejected handshakes are connections without a request, so statuses follow requests.
                            val status = statuses[minOf(requests.size - 1, statuses.size - 1)]
                            if (status != 200) { socket.getOutputStream().apply { write("HTTP/1.1 $status Refused\r\n\r\n".toByteArray()); flush() }; return@thread }
                            socket.getOutputStream().apply { write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray()); write(script()); flush() }
                            val buffer = ByteArray(4096)
                            while (true) { val count = input.read(buffer); if (count < 0) break; synchronized(received) { received.write(buffer, 0, count) } }
                        } catch (_: IOException) {} finally { runCatching { socket.close() } }
                    }
                }
            }
        }
        fun clientBytes(): ByteArray = synchronized(received) { received.toByteArray() }
        override fun close() { server.close() }
    }

    private val calls = CopyOnWriteArrayList<String>()
    private val xapiExecutor = Executors.newCachedThreadPool { action -> Thread(action, "meteor-console-xapi").apply { isDaemon = true } }
    private lateinit var xapi: HttpsServer
    private val consoleServers = mutableListOf<ConsoleServer>()
    private var power = "Running"
    private var consoleRefs = listOf("console-rfb")
    private var location = ""
    private lateinit var profile: Profile
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun script() = RfbScript(8, 8).update(rect(0, 0, 1, 1, RfbEncoding.RAW, pixel(0x112233))).bytes()
    private fun consoleServer(identity: TlsIdentity = master, statuses: List<Int> = listOf(200)) =
        ConsoleServer(identity, statuses, this::script).also { consoleServers += it; location = "https://127.0.0.1:${it.port}/console?uuid=console-uuid-1" }
    private fun authority(server: ConsoleServer) = "127.0.0.1:${server.port}"

    @Before fun startXapi() {
        xapi = HttpsServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            httpsConfigurator = HttpsConfigurator(master.context); executor = xapiExecutor
        }
        xapi.createContext("/") { exchange ->
            exchange.use {
                val body = exchange.requestBody.bufferedReader().use { it.readText() }
                val method = body.substringAfter("<methodName>").substringBefore("</methodName>")
                calls += method
                val bytes = response(result(method, body)).toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "text/xml; charset=utf-8")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
        }
        xapi.start()
        profile = Profile("fixture", "Fixture", "https://127.0.0.1:${xapi.address.port}/", "fixture-user", "fixture-password", master.pin)
    }

    @After fun stop() {
        scope.cancel(); xapi.stop(0); xapiExecutor.shutdownNow(); consoleServers.forEach { it.close() }
    }

    private fun result(method: String, body: String): Any = when (method) {
        "session.login_with_password" -> "OpaqueRef:console-session"
        "session.logout" -> ""
        "pool.get_all_records" -> mapOf("pool-ref" to mapOf("uuid" to "pool-id", "name_label" to "Fixture pool"))
        "VM.get_by_uuid" -> "vm-ref"
        "VM.get_record" -> mapOf("uuid" to "vm-id", "name_label" to "Fixture VM", "power_state" to power)
        "VM.get_consoles" -> consoleRefs
        "console.get_record" -> if ("console-vt100" in body) mapOf("protocol" to "vt100", "location" to "https://127.0.0.1/console?uuid=text")
            else mapOf("protocol" to "rfb", "location" to location)
        else -> error("Unexpected fixture request: $method")
    }
    private fun value(input: Any): String = when (input) {
        is Map<*, *> -> "<struct>" + input.entries.joinToString("") { "<member><name>${it.key}</name><value>${value(requireNotNull(it.value))}</value></member>" } + "</struct>"
        is Boolean -> "<boolean>${if (input) 1 else 0}</boolean>"
        is List<*> -> "<array><data>" + input.joinToString("") { "<value>${value(requireNotNull(it))}</value>" } + "</data></array>"
        else -> "<string>${input.toString().replace("&", "&amp;").replace("<", "&lt;")}</string>"
    }
    private fun response(result: Any) = "<methodResponse><params><param><value>" + value(mapOf("Status" to "Success", "Value" to result)) + "</value></param></params></methodResponse>"

    private fun eventually(what: String, block: () -> Boolean) {
        val end = System.currentTimeMillis() + 6000
        while (!block()) { if (System.currentTimeMillis() > end) fail("Timed out waiting for $what"); Thread.sleep(10) }
    }
    private fun open(): ConsoleSession = runBlocking { XapiGateway().console(profile, "pool-id", "vm-id") }
    private fun noConsoleTraffic(server: ConsoleServer) { Thread.sleep(200); assertTrue("The server must see no request bytes", server.requests.isEmpty()) }

    // ---- success path ----

    @Test fun theConsoleConnectsThroughThePinnedTunnelAndLogsOutOnceWhenClosed() {
        val server = consoleServer() // the coordinator's certificate on another port: same trust, so no review
        val session = open()
        val info = session.start()
        assertEquals(8, info.width)
        session.rfb.readEvent()
        assertEquals(argb(0x112233), session.rfb.framebuffer.pixels[0])
        val request = server.requests.single()
        assertTrue(request.startsWith("CONNECT /console?uuid=console-uuid-1 HTTP/1.1\r\n"))
        assertTrue(request.contains("Cookie: session_id=OpaqueRef:console-session\r\n"))
        assertFalse("The session reference is not placed in the URL", request.lineSequence().first().contains("session_id"))
        assertEquals(listOf("session.login_with_password", "pool.get_all_records", "VM.get_by_uuid", "VM.get_record", "VM.get_consoles", "console.get_record"), calls.toList())
        session.close(); session.close()
        assertEquals("session.logout", calls.last()); assertEquals(1, calls.count { it == "session.logout" })
        assertTrue("A console never sends a VM operation", calls.none { it.startsWith("Async.") || it.startsWith("VM.start") || it.contains("reboot") || it.contains("shutdown") })
    }

    @Test fun theControllerStreamsOverRealTlsAndClosingEndsTheXapiSession() {
        consoleServer()
        val controller = ConsoleController(scope, opener = { p, t, e -> XapiGateway().console(p, t.authority, t.vmUuid, e) },
            profileOf = { profile }, approvePin = { _, _, _ -> }, graceMillis = 60_000, probeMillis = 60_000, minRequestGapMillis = 0)
        controller.open(ConsoleTarget("fixture", "pool-id", "vm-id", "Fixture VM"))
        eventually("connected") { controller.state.value.phase == ConsolePhase.CONNECTED }
        eventually("first frame") { controller.framebuffer?.pixels?.get(0) == argb(0x112233) }
        controller.input.type("a")
        eventually("typed key at the guest") { contains(consoleServers[0].clientBytes(), byteArrayOf(4, 1, 0, 0, 0, 0, 0, 0x61)) }
        controller.close()
        eventually("logout") { calls.count { it == "session.logout" } == 1 }
        assertEquals(ConsolePhase.IDLE, controller.state.value.phase)
    }
    private fun contains(haystack: ByteArray, needle: ByteArray) = haystack.toList().windowed(needle.size).any { it == needle.toList() }

    // ---- certificate pins and member hosts ----

    @Test fun anUnknownMemberWithAnotherCertificateNeedsAReviewAndReceivesNothing() {
        val server = consoleServer(member)
        val failure = assertThrows(MemberTrustRequired::class.java) { open() }
        assertEquals(ConsoleFailureKind.MEMBER_TRUST, failure.kind)
        assertEquals(member.pin, failure.certificate.sha256); assertNull(failure.previousPin)
        assertEquals(authority(server), failure.authority)
        assertEquals("The coordinator pin is not applied to the member and no new pin was stored", master.pin, profile.certificateSha256)
        noConsoleTraffic(server)
        assertEquals("The console's own XAPI session was ended", "session.logout", calls.last())
        assertFalse(failure.message.orEmpty().contains("OpaqueRef"))
    }

    @Test fun anApprovedMemberPinLetsTheConsoleConnectToThatHostOnly() {
        val server = consoleServer(member)
        profile = profile.copy(memberPins = mapOf(authority(server) to member.pin))
        val session = open()
        session.start(); session.rfb.readEvent()
        assertEquals(1, server.requests.size)
        session.close()
        assertEquals("session.logout", calls.last())
        // Another port with the member certificate is a different host entry and is not covered by that approval.
        val other = consoleServer(member)
        assertThrows(MemberTrustRequired::class.java) { open() }
        noConsoleTraffic(other)
    }

    @Test fun aMemberThatChangedItsCertificateAfterApprovalIsReviewedAgainWithThePreviousPin() {
        val server = consoleServer(member)
        val stale = "d".repeat(64)
        profile = profile.copy(memberPins = mapOf(authority(server) to stale))
        val failure = assertThrows(MemberTrustRequired::class.java) { open() }
        assertEquals(stale, failure.previousPin); assertEquals(member.pin, failure.certificate.sha256)
        noConsoleTraffic(server)
        assertEquals("session.logout", calls.last())
    }

    @Test fun aWrongPinIsRejectedDuringTheHandshakeBeforeAnyRequestIsSent() {
        val server = consoleServer()
        val uri = URI(location)
        val failure = assertThrows(ConsoleError::class.java) { ConsoleSession.open(uri, "0".repeat(64), "OpaqueRef:console-session", {}) }
        assertEquals(ConsoleFailureKind.CERTIFICATE, failure.kind)
        noConsoleTraffic(server)
        assertThrows(ConsoleError::class.java) { ConsoleSession.open(uri, member.pin, "OpaqueRef:console-session", {}) }
        noConsoleTraffic(server)
    }

    @Test fun anUnreachableConsoleHostIsANetworkFailureAndEndsTheXapiSession() {
        val closed = ConsoleServer(master, listOf(200), this::script).also { it.close() }
        location = "https://127.0.0.1:${closed.port}/console?uuid=console-uuid-1"
        profile = profile.copy(memberPins = mapOf(authority(closed) to master.pin))
        val failure = assertThrows(ConsoleError::class.java) { open() }
        assertEquals(ConsoleFailureKind.NETWORK, failure.kind)
        assertEquals("session.logout", calls.last())
    }

    // ---- refusal, no console, not running ----

    @Test fun aForbiddenConnectIsRetriedOnceWithTheSessionQueryFormAndThenWorks() {
        val server = consoleServer(master, listOf(403, 200))
        val session = open()
        session.start()
        assertEquals(2, server.requests.size)
        assertEquals("CONNECT /console?uuid=console-uuid-1 HTTP/1.1", server.requests[0].lineSequence().first())
        assertEquals("CONNECT /console?uuid=console-uuid-1&session_id=OpaqueRef:console-session HTTP/1.1", server.requests[1].lineSequence().first())
        session.close()
    }

    @Test fun aConsoleThatStaysForbiddenFailsWithoutLooping() {
        val server = consoleServer(master, listOf(403))
        val failure = assertThrows(ConsoleError::class.java) { open() }
        assertEquals(ConsoleFailureKind.AUTH, failure.kind)
        assertEquals("One attempt plus the single retry", 2, server.requests.size); assertEquals("session.logout", calls.last())
    }

    @Test fun aHaltedVmHasNoConsoleAndNothingConnects() {
        power = "Halted"
        val server = consoleServer()
        val failure = assertThrows(ConsoleError::class.java) { open() }
        assertEquals(ConsoleFailureKind.NOT_RUNNING, failure.kind)
        assertFalse("VM.get_consoles is not even asked for a halted VM", calls.contains("VM.get_consoles"))
        assertEquals("session.logout", calls.last()); assertEquals(0, server.connections.get())
    }

    @Test fun aTextOnlyConsoleIsExplainedAndAVmWithoutConsolesSaysSo() {
        consoleServer(); consoleRefs = listOf("console-vt100")
        assertTrue(assertThrows(ConsoleError::class.java) { open() }.message.orEmpty().contains("vt100"))
        consoleRefs = emptyList()
        val none = assertThrows(ConsoleError::class.java) { open() }
        assertEquals(ConsoleFailureKind.NO_CONSOLE, none.kind)
        assertEquals(2, calls.count { it == "session.logout" })
    }
}
