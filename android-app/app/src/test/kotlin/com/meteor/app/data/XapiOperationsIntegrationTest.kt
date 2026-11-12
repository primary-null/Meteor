package com.meteor.app.data

import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.*
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

/**
 * A2 operations through the production TLS pin, HTTP, XML-RPC, gateway and PowerService paths,
 * against a stateful loopback XAPI fixture. No lab or network service is used.
 */
class XapiOperationsIntegrationTest {
    companion object {
        private lateinit var directory: Path
        private lateinit var tls: SSLContext
        private lateinit var pin: String

        @JvmStatic @BeforeClass fun createTemporaryCertificate() {
            directory = Files.createTempDirectory("meteor-ops-fixture-")
            val storeFile = directory.resolve("fixture.p12")
            val keytool = Path.of(System.getProperty("java.home"), "bin", "keytool.exe").toFile()
                .takeIf { it.exists() } ?: Path.of(System.getProperty("java.home"), "bin", "keytool").toFile()
            val process = ProcessBuilder(keytool.absolutePath, "-genkeypair", "-alias", "fixture",
                "-keyalg", "RSA", "-keysize", "2048", "-validity", "2", "-dname", "CN=localhost",
                "-storetype", "PKCS12", "-keystore", storeFile.toString(),
                "-storepass", "test-fixture-only", "-keypass", "test-fixture-only", "-noprompt")
                .redirectErrorStream(true).start()
            process.inputStream.use { it.readBytes() }
            check(process.waitFor() == 0) { "Temporary TLS fixture generation failed." }
            val store = KeyStore.getInstance("PKCS12").apply {
                Files.newInputStream(storeFile).use { load(it, "test-fixture-only".toCharArray()) }
            }
            pin = ServerTrust.fingerprint(store.getCertificate("fixture") as X509Certificate)
            val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
                init(store, "test-fixture-only".toCharArray())
            }
            tls = SSLContext.getInstance("TLS").apply { init(keys.keyManagers, null, null) }
        }

        @JvmStatic @AfterClass fun removeTemporaryCertificate() {
            if (::directory.isInitialized) {
                Files.deleteIfExists(directory.resolve("fixture.p12"))
                Files.deleteIfExists(directory)
            }
        }
    }

    private val calls = CopyOnWriteArrayList<String>()
    private val bodies = CopyOnWriteArrayList<String>()
    private val executor = Executors.newCachedThreadPool { action -> Thread(action, "meteor-ops-fixture").apply { isDaemon = true } }
    private lateinit var server: HttpsServer
    private lateinit var profile: Profile

    // Server-side VM state, changed only by the async calls below.
    @Volatile private var power = "Running"
    @Volatile private var resident = "host-a-ref"
    @Volatile private var boot = "20260101T10:00:00Z"
    @Volatile private var taskStatus = "success"
    @Volatile private var taskError: List<String> = emptyList()
    @Volatile private var dropAsyncResponse = false
    private var rpcErrors = emptyMap<String, String>()
    private var superuser = true
    private val hosts = mapOf(
        "host-a-ref" to mapOf("uuid" to "host-a", "name_label" to "Host A", "enabled" to true, "metrics" to "metrics-a"),
        "host-b-ref" to mapOf("uuid" to "host-b", "name_label" to "Host B", "enabled" to true, "metrics" to "metrics-b"),
        "host-c-ref" to mapOf("uuid" to "host-c", "name_label" to "Host C", "enabled" to false, "metrics" to "metrics-c"))
    private val metrics = mapOf("metrics-a" to mapOf("live" to true), "metrics-b" to mapOf("live" to true), "metrics-c" to mapOf("live" to true))

    @Before fun startFixture() {
        server = HttpsServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            httpsConfigurator = HttpsConfigurator(tls)
            this.executor = this@XapiOperationsIntegrationTest.executor
        }
        server.createContext("/") { exchange ->
            exchange.use {
                val body = exchange.requestBody.bufferedReader().use { it.readText() }
                val method = body.substringAfter("<methodName>").substringBefore("</methodName>")
                calls += method; bodies += body
                val args = Regex("<param><value><string>(.*?)</string>").findAll(body).map { it.groupValues[1] }.toList()
                val failure = rpcErrors[method]
                val xml = when {
                    failure != null -> "<methodResponse><params><param><value>" +
                        value(mapOf("Status" to "Failure", "ErrorDescription" to listOf(failure, "detail"))) + "</value></param></params></methodResponse>"
                    else -> response(result(method, args))
                }
                if (dropAsyncResponse && method.startsWith("Async.")) {
                    // The request reached the server and was applied, but the reply is lost.
                    exchange.sendResponseHeaders(500, -1)
                } else {
                    val bytes = xml.toByteArray(Charsets.UTF_8)
                    exchange.responseHeaders.add("Content-Type", "text/xml; charset=utf-8")
                    exchange.sendResponseHeaders(200, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                }
            }
        }
        server.start()
        profile = Profile("fixture", "Fixture", "https://127.0.0.1:${server.address.port}/", "fixture-user", "fixture-password", pin)
    }

    @After fun stopFixture() {
        if (::server.isInitialized) server.stop(0)
        executor.shutdownNow()
    }

    private fun allowed(): List<String> = when (power) {
        "Running" -> listOf("clean_shutdown", "clean_reboot", "hard_shutdown", "hard_reboot", "suspend", "pause", "pool_migrate")
        "Suspended" -> listOf("resume", "hard_shutdown")
        "Paused" -> listOf("unpause", "hard_shutdown")
        else -> listOf("start", "start_on")
    }
    private fun async(method: String, args: List<String>): String {
        if (taskStatus == "success") when (method) {
            "Async.VM.hard_shutdown" -> { power = "Halted"; resident = "OpaqueRef:NULL" }
            "Async.VM.suspend" -> { power = "Suspended"; resident = "OpaqueRef:NULL" }
            "Async.VM.resume" -> { power = "Running"; resident = "host-a-ref" }
            "Async.VM.pool_migrate" -> resident = args[2]
            "Async.VM.hard_reboot" -> boot = "20260101T10:05:00Z"
        }
        return "OpaqueRef:task-1"
    }
    private fun result(method: String, args: List<String>): Any = when (method) {
        "session.login_with_password" -> "OpaqueRef:fixture-session"
        "session.logout" -> ""
        "session.get_is_local_superuser" -> superuser
        "session.get_subject" -> "OpaqueRef:subject"
        "subject.get_roles" -> listOf("OpaqueRef:role-operator")
        "role.get_name_label" -> "vm-operator"
        "pool.get_all_records" -> mapOf("pool-ref" to mapOf("uuid" to "pool-id", "name_label" to "Fixture pool"))
        "VM.get_by_uuid" -> "vm-ref"
        "VM.get_record" -> mapOf("uuid" to "vm-id", "name_label" to "Fixture VM", "power_state" to power, "resident_on" to resident,
            "VCPUs_max" to "2", "memory_static_max" to "4096", "metrics" to "vm-metrics", "VBDs" to emptyList<String>(),
            "VIFs" to emptyList<String>(), "allowed_operations" to allowed(), "current_operations" to emptyMap<String, String>())
        "VM_metrics.get_record" -> mapOf("start_time" to boot)
        "host.get_all_records" -> hosts
        "host_metrics.get_all_records" -> metrics
        "host.get_record" -> hosts.getValue(args[1])
        "host_metrics.get_record" -> metrics.getValue(args[1])
        "host.get_by_uuid" -> hosts.entries.single { it.value["uuid"] == args[1] }.key
        "task.get_uuid" -> "task-uuid-1"
        "task.get_by_uuid" -> "OpaqueRef:task-1"
        "task.get_record" -> mapOf("uuid" to "task-uuid-1", "status" to taskStatus, "progress" to "1.0", "error_info" to taskError)
        else -> if (method.startsWith("Async.VM.")) async(method, args) else error("Unexpected fixture request: $method")
    }
    private fun value(input: Any): String = when (input) {
        is Map<*, *> -> "<struct>" + input.entries.joinToString("") {
            "<member><name>${it.key}</name><value>${value(requireNotNull(it.value))}</value></member>"
        } + "</struct>"
        is Boolean -> "<boolean>${if (input) 1 else 0}</boolean>"
        is List<*> -> "<array><data>" + input.joinToString("") { "<value>${value(requireNotNull(it))}</value>" } + "</data></array>"
        else -> "<string>${input.toString().replace("&", "&amp;").replace("<", "&lt;")}</string>"
    }
    private fun response(result: Any) = "<methodResponse><params><param><value>" +
        value(mapOf("Status" to "Success", "Value" to result)) + "</value></param></params></methodResponse>"

    private class Store : OperationStore {
        val records = linkedMapOf<String, PowerOperation>()
        override suspend fun load() = records.values.map { JournalCodec.decode(JournalCodec.encode(it)) }
        override suspend fun put(operation: PowerOperation) { records[operation.id] = operation }
    }
    private val gateway = XapiGateway()
    private val store = Store()
    private val service by lazy { PowerService(gateway, store, System::currentTimeMillis, {}, 3) }
    private suspend fun run(action: PowerAction, target: HostChoice? = null): PowerOperation {
        val details = gateway.details(profile, "pool-id", "vm-id")
        return service.execute(profile, PowerConfirmation(profile.id, profile.origin(), details, action, target, details.name))
    }
    private fun asyncCalls() = calls.filter { it.startsWith("Async.") }
    private fun body(method: String) = bodies.single { it.contains("<methodName>$method</methodName>") }

    @Test fun hardShutdownIsSentOnceAndVerifiedHalted() = runBlocking {
        val detail = gateway.details(profile, "pool-id", "vm-id")
        assertEquals("host-a", detail.residentHostUuid); assertEquals("Host A", detail.host)
        val result = run(PowerAction.HARD_SHUTDOWN)
        assertEquals(result.message, PowerPhase.SUCCEEDED, result.phase)
        assertEquals("Halted", power)
        assertEquals(listOf("Async.VM.hard_shutdown"), asyncCalls())
        assertEquals(1, Regex("<string>").findAll(body("Async.VM.hard_shutdown")).count() - 1) // session + VM ref only
        assertTrue(calls.none { it == "Async.VM.shutdown" || it == "Async.VM.clean_shutdown" })
    }

    @Test fun suspendThenResumeVerifyEachEndState() = runBlocking {
        assertEquals(PowerPhase.SUCCEEDED, run(PowerAction.SUSPEND).phase)
        assertEquals("Suspended", gateway.details(profile, "pool-id", "vm-id").power)
        val resumed = run(PowerAction.RESUME)
        assertEquals(resumed.message, PowerPhase.SUCCEEDED, resumed.phase)
        assertEquals(listOf("Async.VM.suspend", "Async.VM.resume"), asyncCalls())
        // resume(vm, start_paused=false, force=false)
        assertEquals(2, Regex("<boolean>0</boolean>").findAll(body("Async.VM.resume")).count())
    }

    @Test fun liveMigrationSendsLiveOptionAndVerifiesTheNewResidentHost() = runBlocking {
        val choices = gateway.hostChoices(profile, "pool-id", "vm-id")
        assertEquals(listOf("host-a", "host-b", "host-c"), choices.map { it.uuid })
        assertTrue(choices.single { it.uuid == "host-a" }.resident)
        val candidates = PowerRules.hostCandidates(PowerAction.POOL_MIGRATE, choices, "host-a")
        assertEquals(listOf("host-b"), candidates.map { it.uuid })
        val result = run(PowerAction.POOL_MIGRATE, candidates.single())
        assertEquals(result.message, PowerPhase.SUCCEEDED, result.phase)
        assertEquals("host-b-ref", resident)
        assertEquals("host-b", result.targetHostUuid)
        val sent = body("Async.VM.pool_migrate")
        assertTrue(sent.contains("<string>vm-ref</string>") && sent.contains("<string>host-b-ref</string>"))
        assertTrue(sent.contains("<struct><member><name>live</name><value><string>true</string></value></member></struct>"))
        assertEquals("host-b", gateway.details(profile, "pool-id", "vm-id").residentHostUuid)
    }

    @Test fun failedMigrationTaskReportsTheServerCodeAndKeepsPlacement() = runBlocking {
        taskStatus = "failure"; taskError = listOf("VM_MIGRATE_FAILED", "private detail")
        val target = gateway.hostChoices(profile, "pool-id", "vm-id").single { it.uuid == "host-b" }
        val result = run(PowerAction.POOL_MIGRATE, target)
        assertEquals(PowerPhase.FAILED, result.phase)
        assertTrue(result.message.contains("VM_MIGRATE_FAILED")); assertFalse(result.message.contains("private"))
        assertEquals("host-a-ref", resident)
    }

    @Test fun explicitXapiRejectionIsFailedAndLostReplyIsUnknownWithoutResend() = runBlocking {
        rpcErrors = mapOf("Async.VM.suspend" to "VM_BAD_POWER_STATE")
        val rejected = run(PowerAction.SUSPEND)
        assertEquals(PowerPhase.FAILED, rejected.phase); assertTrue(rejected.message.contains("VM_BAD_POWER_STATE"))
        rpcErrors = emptyMap(); dropAsyncResponse = true
        val lost = run(PowerAction.PAUSE)
        assertEquals(PowerPhase.UNKNOWN, lost.phase); assertTrue(lost.unresolved)
        assertEquals(PowerPhase.UNKNOWN, service.reconcile(profile, lost).phase)
        assertTrue(runCatching { run(PowerAction.HARD_SHUTDOWN) }.isFailure)
        assertEquals(listOf("Async.VM.suspend", "Async.VM.pause"), asyncCalls())
    }

    @Test fun hardRebootVerifiesTheChangedBootMarker() = runBlocking {
        val result = run(PowerAction.HARD_REBOOT)
        assertEquals(result.message, PowerPhase.SUCCEEDED, result.phase)
        assertEquals("20260101T10:00:00Z", result.beforeBoot)
    }

    @Test fun permissionsReadSuperuserOrRoleNamesAndDegradeOnMissingMethods() = runBlocking {
        assertTrue(gateway.permissions(profile).superuser)
        superuser = false
        assertEquals(listOf("vm-operator"), gateway.permissions(profile).roles)
        assertEquals(listOf("session.login_with_password", "session.get_is_local_superuser", "session.get_subject",
            "subject.get_roles", "role.get_name_label", "session.logout"), calls.takeLast(6))
        rpcErrors = mapOf("subject.get_roles" to "MESSAGE_METHOD_UNKNOWN")
        val missing = gateway.permissions(profile)
        assertFalse(missing.known); assertEquals("MESSAGE_METHOD_UNKNOWN", missing.problem)
        rpcErrors = mapOf("session.get_is_local_superuser" to "RBAC_PERMISSION_DENIED")
        assertFalse(gateway.permissions(profile).known)
        assertTrue(asyncCalls().isEmpty())
        assertEquals("session.logout", calls.last())
    }
}
