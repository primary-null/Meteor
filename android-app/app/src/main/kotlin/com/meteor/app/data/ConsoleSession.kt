package com.meteor.app.data

import com.meteor.app.BuildConfig
import java.io.Closeable
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.URISyntaxException
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLSocket

/** The CONNECT request was answered with something other than 200. */
internal class ConnectRefused(val status: Int, message: String, kind: ConsoleFailureKind) : ConsoleError(message, kind)

/**
 * One open console: a pinned TLS socket past `CONNECT`, an [RfbProtocol] over it, and the XAPI login that
 * authorised it. Closing closes the socket and logs that XAPI session out exactly once. The session
 * reference is only ever placed in the CONNECT request; it is not stored in messages or logs.
 */
class ConsoleSession internal constructor(private val socket: Socket, encodings: List<Int> = RfbEncoding.DEFAULT,
    private val logout: () -> Unit) : Closeable {
    val rfb = RfbProtocol(socket.getInputStream(), socket.getOutputStream(), encodings)
    private val closed = AtomicBoolean()

    /** Runs the RFB handshake. Afterwards reads time out after [READ_TIMEOUT_MILLIS] without any server data. */
    fun start(): RfbServerInfo = rfb.handshake().also { socket.soTimeout = READ_TIMEOUT_MILLIS }

    override fun close() {
        if (closed.compareAndSet(false, true)) try { rfb.close(); socket.close() } finally { logout() }
    }

    companion object {
        const val CONNECT_TIMEOUT_MILLIS = 8_000
        const val HANDSHAKE_TIMEOUT_MILLIS = 15_000
        /** The client probes a quiet screen about every 20 s, so a silent minute means the link is gone. */
        const val READ_TIMEOUT_MILLIS = 60_000

        internal fun location(value: String): URI {
            val uri = try { URI(value) } catch (_: URISyntaxException) { throw ConsoleError("Invalid HTTPS console location.", ConsoleFailureKind.PROTOCOL) }
            if (uri.scheme != "https" || uri.host.isNullOrBlank() || uri.rawUserInfo != null || uri.rawFragment != null ||
                uri.rawPath != "/console" || uri.port != -1 && uri.port !in 1..65535 ||
                uri.rawQuery.isNullOrEmpty() || !uri.rawQuery.matches(Regex("(?:ref|uuid)=[A-Za-z0-9:._%~-]+")))
                throw ConsoleError("Invalid HTTPS console location.", ConsoleFailureKind.PROTOCOL)
            return uri
        }

        /**
         * Sends `CONNECT` with the XAPI session as a cookie and waits for the tunnel. When [sessionQuery] is
         * set the session is also added as a `session_id` query parameter, a form some XAPI builds expect.
         */
        internal fun connect(socket: Socket, uri: URI, token: String, sessionQuery: Boolean = false, verify: () -> Boolean) {
            if (!verify()) throw ConsoleError("Console certificate does not match the saved pin.", ConsoleFailureKind.CERTIFICATE)
            if (!token.matches(Regex("[A-Za-z0-9:._-]+"))) throw ConsoleError("Invalid console session reference.", ConsoleFailureKind.AUTH)
            val host = uri.host + if (uri.port == -1) "" else ":${uri.port}"
            val query = uri.rawQuery + if (sessionQuery) "&session_id=$token" else ""
            val request = "CONNECT ${uri.rawPath}?$query HTTP/1.1\r\nHost: $host\r\nCookie: session_id=$token\r\n" +
                "User-Agent: Meteor-Spark/${BuildConfig.VERSION_NAME}\r\n\r\n"
            socket.getOutputStream().apply { write(request.toByteArray(Charsets.US_ASCII)); flush() }
            val input = socket.getInputStream()
            val header = StringBuilder()
            while (!header.endsWith("\r\n\r\n")) {
                val byte = input.read()
                if (byte < 0 || header.length >= 16384) throw ConsoleError("Invalid console CONNECT response.", ConsoleFailureKind.PROTOCOL)
                header.append(byte.toChar())
            }
            val status = header.toString().substringBefore("\r\n")
            if (status.matches(Regex("HTTP/1\\.[01] 200(?: .*)?"))) return
            val code = Regex("HTTP/1\\.[01] (\\d{3})").find(status)?.groupValues?.get(1)?.toInt() ?: 0
            throw when (code) {
                401, 403 -> ConnectRefused(code, "This account is not permitted to open this VM's console (HTTP $code).", ConsoleFailureKind.AUTH)
                404 -> ConnectRefused(code, "The server has no console for this VM right now (HTTP 404).", ConsoleFailureKind.NO_CONSOLE)
                else -> ConnectRefused(code, "Console CONNECT was refused${if (code != 0) " (HTTP $code)" else ""}.", ConsoleFailureKind.FAILED)
            }
        }

        /**
         * Opens a TLS connection strictly pinned to [pin] (the certificate must match before any session data is
         * sent) and runs `CONNECT`. [logout] runs once when the returned session closes; it is not called when
         * opening fails, so the caller must end the XAPI session in that case.
         */
        fun open(uri: URI, pin: String, token: String, logout: () -> Unit, encodings: List<Int> = RfbEncoding.DEFAULT): ConsoleSession {
            try { return openOnce(uri, pin, token, logout, encodings, sessionQuery = false) }
            catch (refused: ConnectRefused) {
                if (refused.status != 401 && refused.status != 403) throw refused
            }
            return openOnce(uri, pin, token, logout, encodings, sessionQuery = true)
        }

        private fun openOnce(uri: URI, pin: String, token: String, logout: () -> Unit, encodings: List<Int>, sessionQuery: Boolean): ConsoleSession {
            val socket = ServerTrust.context(pin).socketFactory.createSocket() as SSLSocket
            try {
                socket.soTimeout = HANDSHAKE_TIMEOUT_MILLIS
                socket.enabledProtocols = socket.supportedProtocols.filter { it == "TLSv1.2" || it == "TLSv1.3" }.toTypedArray()
                val host = uri.host.removeSurrounding("[", "]")
                socket.connect(InetSocketAddress(host, if (uri.port == -1) 443 else uri.port), CONNECT_TIMEOUT_MILLIS)
                socket.startHandshake()
                val verifier = HostnameVerifier { connectedHost, session ->
                    connectedHost == host && (session.peerCertificates.firstOrNull() as? X509Certificate)?.let {
                        ServerTrust.fingerprint(it) == pin
                    } == true
                }
                connect(socket, uri, token, sessionQuery) { verifier.verify(host, socket.session) }
                return ConsoleSession(socket, encodings, logout)
            } catch (error: Exception) {
                runCatching { socket.close() }
                throw ConsoleFailures.classify(error)
            }
        }
    }
}

/** Chooses the certificate pin for the console host and turns an unknown member host into a trust review. */
internal object ConsoleOpener {
    private fun endpoint(uri: URI) = "https://" + uri.host + if (uri.port == -1) "" else ":${uri.port}"

    suspend fun open(profile: Profile, uri: URI, token: String, logout: () -> Unit, encodings: List<Int>): ConsoleSession {
        val decision = ConsoleTrust.decide(profile, uri)
        val pin = when (decision) {
            is ConsoleTrust.Decision.Pinned -> decision.pin
            is ConsoleTrust.Decision.Review -> {
                // A credential-free probe: only the TLS handshake runs, so no session data reaches an unreviewed host.
                val certificate = try { ServerTrust.inspect(endpoint(uri)) } catch (error: Exception) { throw ConsoleFailures.classify(error) }
                // The coordinator may be reachable by another name or address; the same certificate is the same trust.
                if (certificate.sha256 == profile.certificateSha256) profile.certificateSha256
                else throw MemberTrustRequired(decision.authority, certificate, null)
            }
        }
        try {
            return ConsoleSession.open(uri, pin, token, logout, encodings)
        } catch (error: ConsoleError) {
            if (error.kind == ConsoleFailureKind.CERTIFICATE && decision is ConsoleTrust.Decision.Pinned && decision.member) {
                // An approved member now presents another certificate: show it for a fresh review.
                val certificate = runCatching { ServerTrust.inspect(endpoint(uri)) }.getOrNull()
                if (certificate != null && certificate.sha256 != pin) throw MemberTrustRequired(MemberPins.authority(uri), certificate, pin)
            }
            throw error
        }
    }
}
