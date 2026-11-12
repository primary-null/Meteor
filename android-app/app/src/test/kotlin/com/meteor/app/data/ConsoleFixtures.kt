package com.meteor.app.data

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.zip.Deflater
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

/** A throwaway self-signed certificate for loopback tests. Generated with keytool; nothing is stored in the repository. */
class TlsIdentity(val context: SSLContext, val pin: String)

object TlsFixtures {
    fun create(commonName: String = "localhost"): TlsIdentity {
        val directory: Path = Files.createTempDirectory("meteor-console-tls-")
        try {
            val storeFile = directory.resolve("fixture.p12")
            val keytool = Path.of(System.getProperty("java.home"), "bin", "keytool.exe").toFile()
                .takeIf { it.exists() } ?: Path.of(System.getProperty("java.home"), "bin", "keytool").toFile()
            val process = ProcessBuilder(keytool.absolutePath, "-genkeypair", "-alias", "fixture",
                "-keyalg", "RSA", "-keysize", "2048", "-validity", "2", "-dname", "CN=$commonName",
                "-storetype", "PKCS12", "-keystore", storeFile.toString(),
                "-storepass", "test-fixture-only", "-keypass", "test-fixture-only", "-noprompt")
                .redirectErrorStream(true).start()
            process.inputStream.use { it.readBytes() }
            check(process.waitFor() == 0) { "Temporary TLS fixture generation failed." }
            val store = KeyStore.getInstance("PKCS12").apply { Files.newInputStream(storeFile).use { load(it, "test-fixture-only".toCharArray()) } }
            val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, "test-fixture-only".toCharArray()) }
            return TlsIdentity(SSLContext.getInstance("TLS").apply { init(keys.keyManagers, null, null) },
                ServerTrust.fingerprint(store.getCertificate("fixture") as X509Certificate))
        } finally {
            Files.deleteIfExists(directory.resolve("fixture.p12"))
            Files.deleteIfExists(directory)
        }
    }
}

/** Returns at most one byte per read, so every decoder path is exercised with partial reads. */
class ChoppyInputStream(private val bytes: ByteArray) : InputStream() {
    private var index = 0
    override fun read(): Int = if (index < bytes.size) bytes[index++].toInt() and 0xff else -1
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (index >= bytes.size) return -1
        buffer[offset] = bytes[index++]
        return 1
    }
}

/** Builds the bytes an RFB server sends: a handshake, then framebuffer updates made of rectangles. */
class RfbScript(val width: Int = 16, val height: Int = 16, version: String = "RFB 003.008\n", security: Int = 1,
    securityResult: Int = 0, name: String = "test") {
    private val out = ByteArrayOutputStream()
    private val data = DataOutputStream(out)
    private val deflater = Deflater()

    init {
        data.writeBytes(version)
        // The client answers 3.3 to servers older than 3.8, and those then send one four-byte security type.
        if (version == "RFB 003.008\n") { data.writeByte(1); data.writeByte(security); if (security == 1) data.writeInt(securityResult) }
        else data.writeInt(security)
        data.writeShort(width); data.writeShort(height); data.write(ByteArray(16)); data.writeInt(name.length); data.writeBytes(name)
    }

    fun update(vararg rectangles: ByteArray): RfbScript {
        data.writeByte(0); data.writeByte(0); data.writeShort(rectangles.size)
        rectangles.forEach { data.write(it) }
        return this
    }
    fun raw(vararg bytes: ByteArray): RfbScript { bytes.forEach { data.write(it) }; return this }
    fun bell() = raw(byteArrayOf(2))
    fun serverCutText(text: ByteArray) = raw(byteArrayOf(3, 0, 0, 0) + int(text.size) + text)
    fun bytes(): ByteArray = out.toByteArray()

    /** Compresses with the script's single zlib stream, as a real server does for one connection. */
    fun zlib(raw: ByteArray): ByteArray {
        deflater.setInput(raw)
        val buffer = ByteArray(raw.size + 1024)
        val count = deflater.deflate(buffer, 0, buffer.size, Deflater.SYNC_FLUSH)
        return buffer.copyOf(count)
    }

    companion object {
        fun int(value: Int) = byteArrayOf((value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte())
        fun short(value: Int) = byteArrayOf((value ushr 8).toByte(), value.toByte())
        fun rect(x: Int, y: Int, w: Int, h: Int, encoding: Int, body: ByteArray = ByteArray(0)) =
            short(x) + short(y) + short(w) + short(h) + int(encoding) + body
        /** A 32 bpp little-endian pixel as sent by Raw and Hextile: blue, green, red, padding. */
        fun pixel(rgb: Int) = byteArrayOf(rgb.toByte(), (rgb ushr 8).toByte(), (rgb ushr 16).toByte(), 0)
        /** A ZRLE compressed pixel: blue, green, red. */
        fun cpixel(rgb: Int) = byteArrayOf(rgb.toByte(), (rgb ushr 8).toByte(), (rgb ushr 16).toByte())
        fun argb(rgb: Int) = 0xff000000.toInt() or rgb
    }
}
