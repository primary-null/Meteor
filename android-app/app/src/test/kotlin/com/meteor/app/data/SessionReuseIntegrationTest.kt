package com.meteor.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext

// --- session reuse ---
/**
 * The shared foreground session and kept-alive connections, through the production pin, TLS, HTTP, XML-RPC, gateway and
 * PowerService paths, against a small loopback HTTP/1.1 XAPI server that counts every TCP connection, login and logout.
 * Only loopback addresses are used.
 */
class SessionReuseIntegrationTest {
    /** One request as the server saw it: the TCP connection number, the method and the string arguments. */
    data class Seen(val connection: Int, val method: String, val args: List<String>)

    /** A raw pinned-TLS HTTP/1.1 server. Keeps connections alive unless [dropAfterResponse] closes them silently. */
    inner class XapiServer(tls: SSLContext, port: Int = 0) : AutoCloseable {
        private val socket: ServerSocket = tls.serverSocketFactory.createServerSocket().apply {
            reuseAddress = true
            bind(java.net.InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 50)
        }
        val port get() = socket.localPort
        val connections = AtomicInteger()
        val seen = CopyOnWriteArrayList<Seen>()
        @Volatile var dropAfterResponse = false
        private val open = CopyOnWriteArrayList<Socket>()
        init {
            Thread({
                while (!socket.isClosed) {
                    val client = try { socket.accept() } catch (_: Exception) { break }
                    val id = connections.incrementAndGet()
                    open += client
                    Thread({ serve(client, id) }, "session-reuse-fixture-$id").apply { isDaemon = true }.start()
                }
            }, "session-reuse-fixture").apply { isDaemon = true }.start()
        }
        private fun serve(client: Socket, id: Int) = client.use {
            client.soTimeout = 15_000
            val input = client.getInputStream(); val output = client.getOutputStream()
            while (true) {
                val head = readHead(input) ?: return
                val length = Regex("(?i)content-length:\\s*(\\d+)").find(head)?.groupValues?.get(1)?.toInt() ?: 0
                val body = String(input.readNBytes(length), Charsets.UTF_8)
                val method = body.substringAfter("<methodName>").substringBefore("</methodName>")
                val args = body.split("<param>").drop(1).map { Regex("<string>(.*?)</string>").find(it)?.groupValues?.get(1).orEmpty() }
                seen += Seen(id, method, args)
                val reply = handle(method, args)
                if (reply == null) { client.close(); return } // the reply is lost
                val bytes = reply.toByteArray(Charsets.UTF_8)
                output.write(("HTTP/1.1 200 OK\r\nContent-Type: text/xml; charset=utf-8\r\nContent-Length: ${bytes.size}\r\n\r\n").toByteArray())
                output.write(bytes); output.flush()
                if (dropAfterResponse) { client.close(); return } // no Connection: close header: the client may pool a dead socket
            }
        }
        private fun readHead(input: InputStream): String? {
            val buffer = ByteArrayOutputStream()
            while (true) {
                val next = try { input.read() } catch (_: Exception) { return null }
                if (next == -1) return null
                buffer.write(next)
                val bytes = buffer.toByteArray()
                if (bytes.size >= 4 && String(bytes, bytes.size - 4, 4, Charsets.US_ASCII) == "\r\n\r\n") return String(bytes, Charsets.US_ASCII)
            }
        }
        fun endpoint() = "https://127.0.0.1:$port/"
        fun count(method: String) = seen.count { it.method == method }
        override fun close() { socket.close(); open.forEach { runCatching { it.close() } } }
    }

    private lateinit var fixture: TlsFixture
    private lateinit var server: XapiServer
    private lateinit var profile: Profile
    private val gateway = XapiGateway()

    // Server-side XAPI state.
    private val sessions = ConcurrentHashMap<String, String>() // token -> username:password it was issued for
    private val issued = AtomicInteger()
    @Volatile private var loginDelayMillis = 0L
    @Volatile private var refuseEveryToken = false
    @Volatile private var sessionInvalidFor = emptySet<String>()
    @Volatile private var slowMethod: String? = null
    @Volatile private var power = "Running"
    @Volatile private var taskStatuses = ArrayDeque(listOf("success"))
    @Volatile private var onTaskRead: () -> Unit = {}
    @Volatile private var loseAsyncReply = false

    @Before fun start() {
        fixture = TlsFixture()
        server = XapiServer(fixture.server.httpsConfigurator.sslContext)
        profile = Profile("fixture", "Fixture", server.endpoint(), "fixture-user", "fixture<&password", fixture.pin)
    }

    @After fun stop() {
        runBlocking { gateway.releaseSession(); gateway.reuse.awaitEndings() }
        server.close(); fixture.close()
    }

    private fun handle(method: String, args: List<String>): String? {
        if (method == "session.login_with_password") {
            if (loginDelayMillis > 0) Thread.sleep(loginDelayMillis)
            val token = "OpaqueRef:session-${issued.incrementAndGet()}"
            sessions[token] = "${args[0]}:${args[1]}"
            return success(token)
        }
        if (method == "session.logout") { sessions.remove(args[0]); return success("") }
        if (refuseEveryToken || method in sessionInvalidFor || args.firstOrNull() !in sessions.keys) return failure("SESSION_INVALID", args.firstOrNull().orEmpty())
        if (method == slowMethod) Thread.sleep(10_000)
        if (method.startsWith("Async.VM.") && loseAsyncReply) { power = "Halted"; return null }
        return success(when (method) {
            "pool.get_all_records" -> mapOf("pool-ref" to mapOf("uuid" to "pool-id", "name_label" to "Fixture pool", "master" to "host-ref"))
            "message.get_since" -> emptyMap<String, Any>()
            "message.destroy" -> ""
            "VM.get_by_uuid" -> "vm-ref"
            "VM.get_record" -> mapOf("uuid" to "vm-id", "name_label" to "Fixture VM", "power_state" to power,
                "resident_on" to if (power == "Running") "host-ref" else "OpaqueRef:NULL", "VCPUs_max" to "2", "memory_static_max" to "4096",
                "metrics" to "vm-metrics", "VBDs" to emptyList<String>(), "VIFs" to emptyList<String>(),
                "allowed_operations" to if (power == "Running") listOf("hard_shutdown", "clean_shutdown") else listOf("start"),
                "current_operations" to emptyMap<String, String>(), "guest_metrics" to "OpaqueRef:NULL")
            "VM_metrics.get_record" -> mapOf("start_time" to "20260101T10:00:00Z")
            "VM.get_data_sources" -> listOf(mapOf("name_label" to "cpu_usage", "enabled" to true, "units" to "(fraction)", "max" to 1.0, "value" to 0.0))
            "VM.query_data_source" -> 0.25
            "host.get_record" -> mapOf("uuid" to "host-id", "name_label" to "Fixture host")
            "Async.VM.hard_shutdown" -> { power = "Halted"; "OpaqueRef:task-1" }
            "task.get_uuid" -> "task-uuid-1"
            "task.get_record" -> {
                onTaskRead()
                val status = synchronized(this) { if (taskStatuses.size > 1) taskStatuses.removeFirst() else taskStatuses.first() }
                mapOf("uuid" to "task-uuid-1", "status" to status, "progress" to "0.5", "error_info" to emptyList<String>())
            }
            else -> return failure("MESSAGE_METHOD_UNKNOWN", method)
        })
    }
    private fun value(input: Any): String = when (input) {
        is Map<*, *> -> "<struct>" + input.entries.joinToString("") { "<member><name>${it.key}</name><value>${value(requireNotNull(it.value))}</value></member>" } + "</struct>"
        is List<*> -> "<array><data>" + input.joinToString("") { "<value>${value(requireNotNull(it))}</value>" } + "</data></array>"
        is Double -> "<double>$input</double>"
        else -> "<string>${input.toString().replace("&", "&amp;").replace("<", "&lt;")}</string>"
    }
    private fun success(result: Any) = "<methodResponse><params><param><value>" + value(mapOf("Status" to "Success", "Value" to result)) + "</value></param></params></methodResponse>"
    private fun failure(code: String, detail: String) = "<methodResponse><params><param><value>" +
        value(mapOf("Status" to "Failure", "ErrorDescription" to listOf(code, detail))) + "</value></param></params></methodResponse>"

    private fun logins() = server.count("session.login_with_password")
    private fun logouts() = server.count("session.logout")
    private suspend fun read(p: Profile = profile) = gateway.alerts(p, "pool-id", 0L)
    private suspend fun settle() = gateway.reuse.awaitEndings()
    private fun eventually(what: String, condition: () -> Boolean) {
        val until = System.currentTimeMillis() + 5_000
        while (!condition()) { check(System.currentTimeMillis() < until) { "Timed out waiting for $what" }; Thread.sleep(20) }
    }

    @Test fun withoutAKeptSessionEveryCallLogsInAndOutAsBefore() = runBlocking {
        repeat(3) { read() }
        assertEquals(3, logins()); assertEquals(3, logouts()); assertTrue(sessions.isEmpty())
    }

    @Test fun oneLoginServesManyCallsOnFewConnectionsAndDisconnectLogsOut() = runBlocking {
        gateway.keepSession(profile)
        repeat(10) { read() }
        gateway.details(profile, "pool-id", "vm-id")
        assertEquals(1, logins()); assertEquals(0, logouts())
        val reads = server.seen.filter { XapiMethods.isRead(it.method) }
        assertTrue(reads.size >= 30)
        assertTrue("Back-to-back reads reuse one kept-alive connection: ${reads.map { it.connection }.distinct()}",
            reads.map { it.connection }.distinct().size <= 2)
        // The login (not a read) had a connection of its own that no read used.
        val login = server.seen.single { it.method == "session.login_with_password" }.connection
        assertTrue(reads.none { it.connection == login })
        gateway.releaseSession(); settle()
        assertEquals(1, logouts()); assertTrue(sessions.isEmpty())
        read() // after disconnect the profile is not kept: a per-call session again
        assertEquals(2, logins()); assertEquals(2, logouts())
    }

    @Test fun concurrentFirstUseLogsInOnce() = runBlocking {
        gateway.keepSession(profile)
        loginDelayMillis = 400
        (1..8).map { async(Dispatchers.IO) { read() } }.awaitAll()
        assertEquals(1, logins())
        val token = sessions.keys.single()
        assertTrue(server.seen.filter { it.method != "session.login_with_password" }.all { it.args.first() == token })
    }

    @Test fun aReadRefusedWithSessionInvalidLogsInOnceAndIsRepeatedOnce() = runBlocking {
        gateway.keepSession(profile)
        read()
        sessions.clear() // the toolstack restarted or the session expired
        val before = server.seen.size
        read()
        assertEquals(listOf("pool.get_all_records" to "OpaqueRef:session-1", "session.login_with_password" to "fixture-user",
            "pool.get_all_records" to "OpaqueRef:session-2", "message.get_since" to "OpaqueRef:session-2", "pool.get_all_records" to "OpaqueRef:session-2"),
            server.seen.drop(before).map { it.method to it.args.first() })
        // Concurrent refusals share one re-login.
        sessions.clear()
        (1..6).map { async(Dispatchers.IO) { read() } }.awaitAll()
        assertEquals(3, logins())
    }

    @Test fun aLiveQueryIsAReadItUsesTheKeptSessionAndConnectionAndASessionInvalidIsRepeatedOnlyOnce() = runBlocking {
        gateway.keepSession(profile)
        val target = MetricTarget("pool-id", "vm-id", MetricKind.VM, "Fixture VM")
        assertEquals(0.25, gateway.metrics(profile, target).readings.single().value!!, 0.0)
        gateway.metrics(profile, target)
        assertEquals(1, logins())
        val queries = server.seen.filter { it.method == "VM.query_data_source" }
        assertEquals(listOf(listOf("OpaqueRef:session-1", "vm-ref", "cpu_usage")), queries.take(1).map { it.args })
        assertEquals(2, queries.size)
        assertEquals("the query travels on a kept-alive connection like the other reads", queries.first().connection, queries.last().connection)
        // A refused query logs in again once and is repeated once; the second refusal surfaces and nothing loops.
        sessionInvalidFor = setOf("VM.query_data_source")
        val before = server.seen.size
        val failure = runCatching { gateway.metrics(profile, target) }.exceptionOrNull()
        assertEquals("SESSION_INVALID", (failure as? RpcFailure)?.code)
        val after = server.seen.drop(before)
        assertEquals(2, after.count { it.method == "VM.query_data_source" })
        assertEquals(1, after.count { it.method == "session.login_with_password" })
    }

    @Test fun aSecondSessionInvalidAfterReloginSurfacesWithoutLooping() = runBlocking {
        gateway.keepSession(profile)
        read()
        refuseEveryToken = true
        val before = server.seen.size
        val error = runCatching { read() }.exceptionOrNull()
        assertEquals("SESSION_INVALID", (error as RpcFailure).code)
        val after = server.seen.drop(before).map { it.method }
        assertEquals(listOf("pool.get_all_records", "session.login_with_password", "pool.get_all_records"), after)
    }

    @Test fun aWriteRefusedWithSessionInvalidIsNotResentAndIsRecordedAsRefused() = runBlocking {
        gateway.keepSession(profile)
        read()
        sessionInvalidFor = setOf("message.destroy")
        val outcome = gateway.dismissAlerts(profile, "pool-id", listOf(AlertTarget("OpaqueRef:alert-1", "alert-1")))
        assertEquals(1, server.count("message.destroy"))
        assertEquals(1, logins()) // a refused write never triggers a re-login and repeat
        assertEquals(DismissStatus.REFUSED, outcome.results.single().status)
        assertEquals("SESSION_INVALID", outcome.results.single().code)
    }

    private val store = object : OperationStore {
        val records = linkedMapOf<String, PowerOperation>()
        override suspend fun load() = records.values.toList()
        override suspend fun put(operation: PowerOperation) { records[operation.id] = operation }
    }
    private suspend fun shutdown(): PowerOperation {
        val details = gateway.details(profile, "pool-id", "vm-id")
        return PowerService(gateway, store, System::currentTimeMillis, {}, 6)
            .execute(profile, PowerConfirmation(profile.id, profile.origin(), details, PowerAction.HARD_SHUTDOWN, null, details.name))
    }

    @Test fun aPowerWriteRefusedWithSessionInvalidIsAClosedRejectionSentOnce() = runBlocking {
        gateway.keepSession(profile)
        sessionInvalidFor = setOf("Async.VM.hard_shutdown")
        val result = shutdown()
        assertEquals(PowerPhase.FAILED, result.phase)
        assertEquals("Server rejected the request (SESSION_INVALID).", result.message)
        assertFalse(result.unresolved)
        assertEquals(1, server.count("Async.VM.hard_shutdown")); assertEquals(1, logins())
    }

    @Test fun aWriteNeverUsesAKeptAliveConnectionAndALostReplyIsNeverResent() = runBlocking {
        gateway.keepSession(profile)
        loseAsyncReply = true
        val result = shutdown()
        assertEquals(PowerPhase.UNKNOWN, result.phase)
        assertEquals(1, server.count("Async.VM.hard_shutdown"))
        val write = server.seen.single { it.method == "Async.VM.hard_shutdown" }
        assertEquals("The write had a brand-new connection", 1, server.seen.count { it.connection == write.connection })
    }

    @Test fun staleKeptAliveConnectionsNeverCauseAResend() = runBlocking {
        gateway.keepSession(profile)
        server.dropAfterResponse = true // every connection dies silently after one reply, while the client may keep it pooled
        repeat(4) { read() }
        val result = shutdown()
        assertEquals(1, server.count("Async.VM.hard_shutdown"))
        assertEquals(result.message, PowerPhase.SUCCEEDED, result.phase)
        assertEquals(1, logins())
        assertEquals("Each request had its own connection", server.seen.size, server.connections.get())
    }

    @Test fun trackingSurvivesOneSessionLossWithOneRelogin() = runBlocking {
        gateway.keepSession(profile)
        taskStatuses = ArrayDeque(listOf("pending", "pending", "success"))
        var reads = 0
        onTaskRead = { if (++reads == 1) sessions.clear() } // the session is lost while the task runs
        val result = shutdown()
        assertEquals(result.message, PowerPhase.SUCCEEDED, result.phase)
        assertEquals(1, server.count("Async.VM.hard_shutdown"))
        assertEquals(2, logins()); assertEquals(0, logouts())
    }

    @Test fun eachIdentityChangeEndsTheSessionAndNoOtherProfileUsesIt() = runBlocking {
        gateway.keepSession(profile); read()
        // Another profile is served by its own per-call session, never the kept one.
        val other = profile.copy(id = "other", password = "other-password")
        read(other)
        assertEquals("other-password", server.seen.filter { it.method == "session.login_with_password" }[1].args[1])
        assertTrue(server.seen.any { it.method == "pool.get_all_records" && it.args.first() == "OpaqueRef:session-2" })
        assertEquals(setOf("OpaqueRef:session-1"), sessions.keys) // its own session was logged out; the kept one is untouched

        // Credential edit.
        val edited = profile.copy(password = "new-password")
        gateway.keepSession(edited); settle()
        assertFalse(sessions.containsKey("OpaqueRef:session-1"))
        read(edited); read(edited)
        assertEquals(1, sessions.size); assertEquals("fixture-user:new-password", sessions.values.single())
        // The old identity is no longer kept: a late call for it gets a per-call session.
        val loginsBefore = logins(); read(profile); assertEquals(loginsBefore + 1, logins())
        assertEquals(1, sessions.size)

        // Profile switch.
        gateway.keepSession(other); settle()
        assertTrue(sessions.isEmpty())
        read(other)
        assertEquals("fixture-user:other-password", sessions.values.single())

        // Forget: only the matching profile id ends it.
        gateway.releaseSession("unrelated"); settle(); assertEquals(1, sessions.size)
        gateway.releaseSession("other"); settle(); assertTrue(sessions.isEmpty())

        // Endpoint change (following the pool master): the logout goes to the old server.
        XapiServer(fixture.server.httpsConfigurator.sslContext).use { master ->
            gateway.keepSession(profile); read()
            val moved = profile.copy(endpoint = master.endpoint())
            gateway.keepSession(moved); settle()
            assertTrue(sessions.isEmpty())
            assertEquals(0, master.connections.get())
        }

        // Pin change: the old session is logged out with the old pin; nothing is sent under a pin the server fails.
        gateway.keepSession(profile); read()
        val repinned = profile.copy(certificateSha256 = "0".repeat(64))
        gateway.keepSession(repinned); settle()
        assertTrue(sessions.isEmpty())
        val seenBefore = server.seen.size
        assertTrue(runCatching { read(repinned) }.exceptionOrNull()?.pinFailure() == true)
        assertEquals("No request is sent before the pin check", seenBefore, server.seen.size)
    }

    @Test fun aCertificateChangeMidSessionDropsTheSessionWithoutSendingAnything() = runBlocking {
        gateway.keepSession(profile); read()
        val port = server.port
        server.close()
        TlsFixture().use { replaced ->
            XapiServer(replaced.server.httpsConfigurator.sslContext, port).use { impostor ->
                Thread.sleep(SessionReusePolicy.IDLE_REUSE_MILLIS + 200) // no kept-alive socket is reused
                assertTrue(runCatching { read() }.exceptionOrNull()?.pinFailure() == true)
                settle()
                assertTrue(runCatching { read() }.exceptionOrNull()?.pinFailure() == true)
                settle()
                assertTrue("Nothing reached a server that failed the pin", impostor.seen.isEmpty())
            }
        }
    }

    @Test fun theSessionIsLoggedOutAfterTheBackgroundGraceAndReopenedOnReturn() = runBlocking {
        gateway.reuse.backgroundGraceMillis = 150
        gateway.keepSession(profile); read()
        gateway.backgrounded(true); gateway.backgrounded(false) // a quick trip away keeps it
        delay(300); assertEquals(0, logouts())
        gateway.backgrounded(true)
        eventually("background logout") { logouts() == 1 }
        assertTrue(sessions.isEmpty())
        read() // a call while away does not reopen a kept session
        assertEquals(2, logins()); assertEquals(2, logouts())
        gateway.backgrounded(false)
        read(); read()
        assertEquals(3, logins()); assertEquals(2, logouts()); assertEquals(1, sessions.size)
    }

    @Test fun aCancelledReadDoesNotWaitOutItsTimeoutAndTheSessionStaysUsable() = runBlocking {
        gateway.keepSession(profile); read()
        slowMethod = "message.get_since"
        val job = launch(Dispatchers.IO) { read() }
        delay(400)
        val started = System.currentTimeMillis()
        job.cancelAndJoin()
        assertTrue("Cancellation returned promptly", System.currentTimeMillis() - started < 3_000)
        slowMethod = null
        read()
        assertEquals(1, logins())
    }

    @Test fun aCallerCancelledDuringThePreSendReadNeverSendsTheWrite() = runBlocking {
        gateway.keepSession(profile); read()
        slowMethod = "VM.get_by_uuid"
        val job = launch(Dispatchers.IO) {
            runCatching { gateway.powerConnection(profile) { submitOperation("pool-id", "vm-id", PowerAction.HARD_SHUTDOWN, null) } }
        }
        delay(400)
        val started = System.currentTimeMillis()
        job.cancelAndJoin()
        assertTrue("Cancellation returned promptly", System.currentTimeMillis() - started < 3_000)
        delay(500)
        assertEquals(0, server.count("Async.VM.hard_shutdown"))
    }
}
// --- end session reuse ---
