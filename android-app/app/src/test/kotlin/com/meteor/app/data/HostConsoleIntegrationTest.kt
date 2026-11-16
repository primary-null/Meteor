package com.meteor.app.data

import com.meteor.app.data.RfbScript.Companion.argb
import com.meteor.app.data.RfbScript.Companion.pixel
import com.meteor.app.data.RfbScript.Companion.rect
import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread

/**
 * The host console over real TLS on loopback: a pinned XAPI fixture shaped like the recorded 8.3 host (host record with
 * `control_domain` and `address`, the control domain's `rfb` and `vt100` consoles) and a TLS server that answers
 * `CONNECT /console`. Covers the coordinator, a member host with and without an approved pin, and every refusal.
 */
class HostConsoleIntegrationTest {
    companion object {
        private lateinit var master: TlsIdentity
        private lateinit var member: TlsIdentity
        @JvmStatic @BeforeClass fun certificates() { master = TlsFixtures.create(); member = TlsFixtures.create("member") }
    }

    private class ConsoleServer(identity: TlsIdentity, private val status: Int = 200) : Closeable {
        private val server = identity.context.serverSocketFactory.createServerSocket(0, 8, InetAddress.getByName("127.0.0.1")) as SSLServerSocket
        val port get() = server.localPort
        val requests = CopyOnWriteArrayList<String>()
        init {
            thread(isDaemon = true) {
                while (!server.isClosed) {
                    val socket = try { server.accept() as SSLSocket } catch (_: IOException) { break }
                    thread(isDaemon = true) {
                        try {
                            val input = socket.getInputStream(); val header = StringBuilder()
                            while (!header.endsWith("\r\n\r\n")) { val byte = input.read(); if (byte < 0) return@thread; header.append(byte.toChar()) }
                            requests += header.toString()
                            if (status != 200) { socket.getOutputStream().apply { write("HTTP/1.1 $status Forbidden\r\n\r\n".toByteArray()); flush() }; return@thread }
                            // The recorded host console: RFB 3.3, 640x384, "XenServer Virtual Terminal".
                            val script = RfbScript(640, 384, "RFB 003.003\n", name = "XenServer Virtual Terminal")
                                .update(rect(0, 0, 1, 1, RfbEncoding.RAW, pixel(0x00ff00))).bytes()
                            socket.getOutputStream().apply { write("HTTP/1.1 200 OK\r\n\r\n".toByteArray()); write(script); flush() }
                            val buffer = ByteArray(4096)
                            while (input.read(buffer) >= 0) Unit
                        } catch (_: IOException) {} finally { runCatching { socket.close() } }
                    }
                }
            }
        }
        override fun close() { server.close() }
    }

    private val calls = CopyOnWriteArrayList<String>()
    private val xapiExecutor = Executors.newCachedThreadPool { action -> Thread(action, "meteor-host-console-xapi").apply { isDaemon = true } }
    private lateinit var xapi: HttpsServer
    private val consoleServers = mutableListOf<ConsoleServer>()
    private lateinit var profile: Profile
    private var coordinator = true
    private var live = true
    private var address = "127.0.0.1"
    private var namesControlDomain = true
    private var consoles = listOf("console-rfb", "console-vt100")
    private var location = ""

    private fun consoleServer(identity: TlsIdentity = master, status: Int = 200) =
        ConsoleServer(identity, status).also { consoleServers += it; location = "https://127.0.0.1:${it.port}/console?ref=OpaqueRef:console-rfb" }

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

    @After fun stop() { xapi.stop(0); xapiExecutor.shutdownNow(); consoleServers.forEach { it.close() } }

    private val dom0 = mapOf("uuid" to "dom0-id", "name_label" to "Control domain on host: fixture", "is_control_domain" to true,
        "is_a_template" to false, "is_a_snapshot" to false, "resident_on" to "host-ref", "power_state" to "Running")

    private fun result(method: String, body: String): Any = when (method) {
        "session.login_with_password" -> "OpaqueRef:console-session"
        "session.logout" -> ""
        "pool.get_all_records" -> mapOf("pool-ref" to mapOf("uuid" to "pool-id", "name_label" to "Fixture pool",
            "master" to if (coordinator) "host-ref" else "other-host-ref"))
        "host.get_by_uuid" -> "host-ref"
        "host.get_record" -> mapOf("uuid" to "host-id", "name_label" to "fixture", "address" to address, "metrics" to "metrics-ref") +
            (if (namesControlDomain) mapOf("control_domain" to "dom0-ref") else emptyMap())
        "host_metrics.get_record" -> mapOf("live" to live)
        "VM.get_all_records_where" -> mapOf("dom0-ref" to dom0)
        "VM.get_record" -> dom0 + mapOf("consoles" to consoles)
        "console.get_record" -> if ("console-vt100" in body) mapOf("protocol" to "vt100", "VM" to "dom0-ref", "location" to "https://127.0.0.1/console?ref=OpaqueRef:console-vt100")
            else mapOf("protocol" to "rfb", "VM" to "dom0-ref", "location" to location)
        else -> error("Unexpected fixture request: $method")
    }
    private fun value(input: Any): String = when (input) {
        is Map<*, *> -> "<struct>" + input.entries.joinToString("") { "<member><name>${it.key}</name><value>${value(requireNotNull(it.value))}</value></member>" } + "</struct>"
        is Boolean -> "<boolean>${if (input) 1 else 0}</boolean>"
        is List<*> -> "<array><data>" + input.joinToString("") { "<value>${value(requireNotNull(it))}</value>" } + "</data></array>"
        else -> "<string>${input.toString().replace("&", "&amp;").replace("<", "&lt;")}</string>"
    }
    private fun response(result: Any) = "<methodResponse><params><param><value>" + value(mapOf("Status" to "Success", "Value" to result)) + "</value></param></params></methodResponse>"

    private fun open(): ConsoleSession = runBlocking { XapiGateway().hostConsole(profile, "pool-id", "host-id") }
    private fun offer(): HostConsoleOffer = runBlocking { XapiGateway().hostConsoleOffer(profile, "pool-id", "host-id") }
    private fun noConsoleTraffic(server: ConsoleServer) { Thread.sleep(200); assertTrue("The server must see no request bytes", server.requests.isEmpty()) }
    private fun neverAnOperation() = assertTrue("A host console never sends a host or VM operation: ${calls.toList()}",
        calls.all { it.startsWith("session.") || XapiMethods.isRead(it) })

    @Test fun theCoordinatorsConsoleIsItsControlDomainsRfbConsoleOverThePinnedTunnel() {
        val server = consoleServer()
        val session = open()
        val info = session.start()
        assertEquals(RfbServerInfo(640, 384, "XenServer Virtual Terminal"), info)
        session.rfb.readEvent()
        assertEquals(argb(0x00ff00), session.rfb.framebuffer.pixels[0])
        assertTrue(server.requests.single().startsWith("CONNECT /console?ref=OpaqueRef:console-rfb HTTP/1.1\r\n"))
        assertEquals(listOf("session.login_with_password", "pool.get_all_records", "host.get_by_uuid", "host.get_record", "host_metrics.get_record",
            "VM.get_record", "console.get_record", "console.get_record", "pool.get_all_records"), calls.toList())
        session.close()
        assertEquals(1, calls.count { it == "session.logout" })
        neverAnOperation()
    }

    @Test fun aServerWithoutTheControlDomainFieldIsAnsweredByTheControlDomainResidentOnTheHost() {
        namesControlDomain = false
        consoleServer()
        open().close()
        assertTrue(calls.contains("VM.get_all_records_where"))
        neverAnOperation()
    }

    @Test fun aMemberHostsConsoleNeedsItsCertificateReviewedFirstAndReceivesNothingBefore() {
        coordinator = false
        val server = consoleServer(member)
        val failure = assertThrows(MemberTrustRequired::class.java) { open() }
        assertEquals("127.0.0.1:${server.port}", failure.authority)
        assertEquals(member.pin, failure.certificate.sha256)
        assertTrue(failure.message.orEmpty().startsWith("This host's console is served by a pool member"))
        noConsoleTraffic(server)
        assertEquals("session.logout", calls.last())
    }

    @Test fun anApprovedMemberPinOpensThatMembersConsole() {
        coordinator = false
        val server = consoleServer(member)
        profile = profile.copy(memberPins = mapOf("127.0.0.1:${server.port}" to member.pin))
        open().use { it.start() }
        assertEquals(1, server.requests.size)
        neverAnOperation()
    }

    @Test fun aLocationThatIsNotTheHostsOwnAddressIsRefusedBeforeAnyConnection() {
        coordinator = false
        address = "192.0.2.77"
        val server = consoleServer(member)
        profile = profile.copy(memberPins = mapOf("127.0.0.1:${server.port}" to member.pin))
        val failure = assertThrows(ConsoleError::class.java) { open() }
        assertEquals(HostConsoleRules.WRONG_HOST, failure.message)
        noConsoleTraffic(server)
        assertEquals(HostConsoleRules.WRONG_HOST, offer().reason)
    }

    @Test fun aHostThatIsNotLiveHasNoConsole() {
        live = false
        val server = consoleServer()
        val failure = assertThrows(ConsoleError::class.java) { open() }
        assertEquals(ConsoleFailureKind.NOT_RUNNING, failure.kind)
        assertEquals(HostConsoleRules.NOT_LIVE, offer().reason)
        noConsoleTraffic(server)
    }

    @Test fun aControlDomainWithOnlyTheTextConsoleIsNeverConnectedTo() {
        consoles = listOf("console-vt100")
        val server = consoleServer()
        val failure = assertThrows(ConsoleError::class.java) { open() }
        assertEquals(HostConsoleRules.TEXT_ONLY, failure.message)
        assertEquals(ConsoleFailureKind.NO_CONSOLE, failure.kind)
        noConsoleTraffic(server)
    }

    @Test fun theOfferIsAvailableWhenTheHostHasAGraphicalConsole() {
        consoleServer()
        assertTrue(offer().available)
        neverAnOperation()
    }

    @Test fun aRoleThatMayNotOpenTheHostConsoleSeesTheServersRefusalInPlainWords() {
        val server = consoleServer(status = 403)
        val failure = assertThrows(ConsoleError::class.java) { open() }
        assertEquals(ConsoleFailureKind.AUTH, failure.kind)
        assertEquals("The server refused this host's console to this account (HTTP 403). Its role may not allow host consoles.", failure.message)
        assertEquals("Both CONNECT forms were tried, as for a VM console", 2, server.requests.size)
        assertEquals("session.logout", calls.last())
    }
}
