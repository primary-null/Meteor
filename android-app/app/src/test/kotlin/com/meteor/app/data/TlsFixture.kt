package com.meteor.app.data

import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.concurrent.Executors
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

/**
 * A loopback HTTPS server with a freshly generated, throw-away certificate. The production [ServerTrust] pin check,
 * HTTP transport and parsers run against it; there is no lab access and nothing outside the temp directory is touched.
 */
class TlsFixture : AutoCloseable {
    private val directory: Path = Files.createTempDirectory("meteor-tls-fixture-")
    private val executor = Executors.newCachedThreadPool { action -> Thread(action, "meteor-tls-fixture").apply { isDaemon = true } }
    val pin: String
    val server: HttpsServer
    val port get() = server.address.port
    val endpoint get() = "https://127.0.0.1:$port/"

    init {
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
        val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, "test-fixture-only".toCharArray()) }
        val tls = SSLContext.getInstance("TLS").apply { init(keys.keyManagers, null, null) }
        server = HttpsServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            httpsConfigurator = HttpsConfigurator(tls)
            this.executor = this@TlsFixture.executor
        }
    }

    fun start() = server.start()
    fun profile(pin: String = this.pin) = Profile("fixture", "Fixture", endpoint, "fixture-user", "fixture<&password", pin)

    override fun close() {
        server.stop(0)
        executor.shutdownNow()
        Files.deleteIfExists(directory.resolve("fixture.p12"))
        Files.deleteIfExists(directory)
    }
}
