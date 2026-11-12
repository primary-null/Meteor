package com.meteor.app.data

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URL
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext

/**
 * Read-only HTTPS GET with the same trust rules as the XML-RPC transport: the exact leaf SHA-256 pin
 * is enforced by [ServerTrust.context] and again by the hostname verifier, the host must match the URL,
 * redirects are refused, connect/read time are bounded and the decoded body is capped. Failures are
 * typed [GatewayError] reasons that never contain the URL, because the URL carries a session reference.
 */
internal object PinnedHttp {
    const val CONNECT_TIMEOUT_MS = 8_000

    fun get(url: URL, pin: String, tls: SSLContext, accept: String, userAgent: String, readTimeoutMs: Int, maxBytes: Int): ByteArray {
        require(url.protocol == "https") { "HTTPS is required." }
        require(pin.matches(Regex("[0-9a-f]{64}"))) { "Invalid certificate pin." }
        require(maxBytes > 0 && readTimeoutMs > 0) { "Invalid limits." }
        val connection = url.openConnection() as HttpsURLConnection
        try {
            connection.sslSocketFactory = tls.socketFactory
            connection.hostnameVerifier = HostnameVerifier { host, session ->
                // The exact user-approved leaf pin identifies private and self-signed endpoints.
                host == url.host && (session.peerCertificates.firstOrNull() as? X509Certificate)?.let {
                    ServerTrust.fingerprint(it) == pin
                } == true
            }
            connection.instanceFollowRedirects = false
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = readTimeoutMs
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", accept)
            connection.setRequestProperty("User-Agent", userAgent)
            // HttpURLConnection throws instead of reporting a 401 that carries no WWW-Authenticate challenge.
            val code = try { connection.responseCode } catch (error: IOException) {
                if (error.message?.contains("authentication challenge", ignoreCase = true) == true) 401 else throw error
            }
            if (code in 300..399) throw GatewayError("redirect-refused")
            when (code) {
                200 -> Unit
                401, 403 -> throw GatewayError("http-denied")
                404 -> throw GatewayError("http-unsupported")
                else -> throw GatewayError("http-error")
            }
            if (connection.contentLengthLong > maxBytes) throw GatewayError("response-limit")
            val output = ByteArrayOutputStream()
            connection.inputStream.use { stream ->
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val count = stream.read(buffer)
                    if (count == -1) break
                    if (output.size() + count > maxBytes) throw GatewayError("response-limit")
                    output.write(buffer, 0, count)
                }
            }
            return output.toByteArray()
        } finally {
            connection.disconnect()
        }
    }
}
