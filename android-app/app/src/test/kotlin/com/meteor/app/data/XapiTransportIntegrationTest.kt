package com.meteor.app.data

import com.meteor.app.BuildConfig
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

/** Uses the production TLS, HTTP, XML-RPC and inventory paths, with no lab access. */
class XapiTransportIntegrationTest {
    companion object {
        private lateinit var directory: Path
        private lateinit var tls: SSLContext
        private lateinit var pin: String

        @JvmStatic @BeforeClass fun createTemporaryCertificate() {
            directory = Files.createTempDirectory("meteor-tls-fixture-")
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
    private val agents = CopyOnWriteArrayList<String>()
    private val executor = Executors.newCachedThreadPool { action -> Thread(action, "meteor-tls-fixture").apply { isDaemon = true } }
    private lateinit var server: HttpsServer
    private lateinit var profile: Profile
    private var redirect = false
    private var malformedPool = false
    private var structTaskReference = false

    @Before fun startFixture() {
        server = HttpsServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            httpsConfigurator = HttpsConfigurator(tls)
            this.executor = this@XapiTransportIntegrationTest.executor
        }
        server.createContext("/") { exchange ->
            exchange.use {
                val body = exchange.requestBody.bufferedReader().use { it.readText() }
                val method = body.substringAfter("<methodName>").substringBefore("</methodName>")
                calls += method
                bodies += body
                agents += exchange.requestHeaders.getFirst("User-Agent").orEmpty()
                if (redirect) {
                    exchange.responseHeaders.add("Location", "https://127.0.0.1:${server.address.port}/redirected")
                    exchange.sendResponseHeaders(302, -1)
                } else {
                    val xml = if (malformedPool && method == "pool.get_all_records") "<broken>" else response(result(method))
                    val bytes = xml.toByteArray(Charsets.UTF_8)
                    exchange.responseHeaders.add("Content-Type", "text/xml; charset=utf-8")
                    exchange.sendResponseHeaders(200, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                }
            }
        }
        server.start()
        profile = Profile("fixture", "Fixture", "https://127.0.0.1:${server.address.port}/",
            "fixture-user", "fixture<&password", pin)
    }

    @After fun stopFixture() {
        if (::server.isInitialized) server.stop(0)
        executor.shutdownNow()
    }

    private fun result(method: String): Any = when (method) {
        "session.login_with_password" -> "OpaqueRef:fixture-session"
        "session.logout" -> ""
        "pool.get_all_records" -> mapOf("pool-ref" to mapOf("uuid" to "pool-id", "name_label" to "Fixture pool"))
        "host.get_all_records" -> mapOf("host-ref" to mapOf("uuid" to "host-id", "name_label" to "Fixture host", "metrics" to "metrics-ref"))
        "host_metrics.get_all_records" -> mapOf("metrics-ref" to mapOf("live" to true, "memory_total" to "8192", "memory_free" to "4096"))
        "VM.get_all_records" -> mapOf("vm-ref" to mapOf("uuid" to "vm-id", "name_label" to "Fixture VM", "power_state" to "Running",
            "resident_on" to "host-ref", "VCPUs_max" to "2", "memory_static_max" to "4096"))
        "SR.get_all_records" -> emptyMap<String, Any>()
        "task.get_uuid" -> if (structTaskReference) mapOf("unexpected" to "struct") else "task-uuid"
        else -> error("Unexpected fixture request: $method")
    }

    private fun value(input: Any): String = when (input) {
        is Map<*, *> -> "<struct>" + input.entries.joinToString("") {
            "<member><name>${it.key}</name><value>${value(requireNotNull(it.value))}</value></member>"
        } + "</struct>"
        is Boolean -> "<boolean>${if (input) 1 else 0}</boolean>"
        else -> "<string>${input.toString().replace("&", "&amp;").replace("<", "&lt;")}</string>"
    }
    private fun response(result: Any) = "<methodResponse><params><param><value>" +
        value(mapOf("Status" to "Success", "Value" to result)) + "</value></param></params></methodResponse>"

    @Test fun certificateInspectionDoesNotSendCredentials() = runBlocking {
        assertEquals(pin, ServerTrust.inspect(profile.endpoint).sha256)
        assertTrue(calls.isEmpty())
    }

    @Test fun pinnedInventoryUsesRealTransportAndLogsOut() = runBlocking {
        val inventory = XapiGateway().inventory(profile)
        assertEquals("pool-id", inventory.authority)
        assertEquals("Fixture pool", inventory.poolName)
        assertEquals("host-id", inventory.guests.single().hostUuid)
        assertEquals(4096L, inventory.hosts.single().freeBytes)
        assertEquals(listOf("session.login_with_password", "pool.get_all_records", "host.get_all_records",
            "host_metrics.get_all_records", "VM.get_all_records", "SR.get_all_records", "session.logout"), calls.toList())
        assertTrue(bodies.first().contains("fixture&lt;&amp;password"))
        assertTrue(agents.all { it == "Meteor-Spark/${BuildConfig.VERSION_NAME}" })
    }

    @Test fun nonTextReferencesAreTypedGatewayErrors() = runBlocking {
        assertEquals("task-uuid", XapiGateway().powerConnection(profile) { taskUuid("OpaqueRef:task") })
        structTaskReference = true
        calls.clear()
        val failure = runCatching { XapiGateway().powerConnection(profile) { taskUuid("OpaqueRef:task") } }.exceptionOrNull()
        assertEquals("invalid-reference", (failure as? GatewayError)?.reason)
        assertEquals(listOf("session.login_with_password", "task.get_uuid", "session.logout"), calls.toList())
    }

    @Test fun changedPinIsRejectedBeforeCredentialSubmission() = runBlocking {
        val failure = runCatching { XapiGateway().inventory(profile.copy(certificateSha256 = "0".repeat(64))) }.exceptionOrNull()
        assertNotNull(failure)
        assertTrue(generateSequence(failure) { it.cause }.take(8).any { it is CertificateChanged })
        assertTrue(calls.isEmpty())
    }

    @Test fun redirectsAreRefusedWithoutFollowingTheNewLocation() = runBlocking {
        redirect = true
        val failure = runCatching { XapiGateway().inventory(profile) }.exceptionOrNull()
        assertTrue(failure is GatewayError)
        assertEquals("redirect-refused", (failure as GatewayError).reason)
        assertEquals(listOf("session.login_with_password"), calls.toList())
    }

    @Test fun malformedInventoryClosesTheAuthenticatedSession() = runBlocking {
        malformedPool = true
        assertTrue(runCatching { XapiGateway().inventory(profile) }.isFailure)
        assertEquals(listOf("session.login_with_password", "pool.get_all_records", "session.logout"), calls.toList())
    }
}
