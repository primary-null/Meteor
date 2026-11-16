package com.meteor.app.data

import com.meteor.app.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.net.URL
import java.security.cert.X509Certificate
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory

// --- session reuse ---
//
// Ownership. [XapiGateway] owns at most one shared foreground XAPI session. ConnectionController names the profile it
// belongs to ([MeteorGateway.keepSession]) when it starts a connection, and ends it on disconnect or forget; a new
// connection with any other identity (profile switch, credential/endpoint edit, a re-trusted certificate, following
// the pool master) replaces it. Only the gateway's foreground methods for exactly that identity use it; every other
// call (another profile, the background worker, the All-pools summary, the console, the event long-poll) keeps its
// own session as before. The session is opened lazily by the first call that needs it and ends with a best-effort
// logout when it is replaced, released, after [SessionReusePolicy.BACKGROUND_GRACE_MILLIS] in the background, or
// when the pin check fails. A call that already holds it finishes with it: the logout waits for the last holder.

object SessionReusePolicy {
    /** How long the app may stay in the background before the shared session is logged out. */
    const val BACKGROUND_GRACE_MILLIS = 2 * 60_000L
    /** A kept-alive connection is reused only if the shared session made a call this recently ... */
    const val IDLE_REUSE_MILLIS = 2_000L
    /** ... and only within this age of its group of connections, so no pooled socket sits idle for long. */
    const val MAX_REUSE_AGE_MILLIS = 10_000L
}

/**
 * The identity a shared session is bound to: profile id, normalized endpoint, username, the password it was opened
 * with and the pinned certificate. Any difference means a different session. Never printed.
 */
internal class XapiIdentity private constructor(
    val profileId: String, private val endpoint: String, private val username: String, private val password: String, private val pin: String,
) {
    override fun equals(other: Any?) = other is XapiIdentity && profileId == other.profileId && endpoint == other.endpoint &&
        username == other.username && password == other.password && pin == other.pin
    override fun hashCode() = listOf(profileId, endpoint, username, pin).hashCode()
    override fun toString() = "XapiIdentity(profile=$profileId, credentials=redacted)"
    companion object {
        /** Null for a profile no session can be opened for (malformed endpoint or pin). */
        fun of(profile: Profile): XapiIdentity? = runCatching {
            require(profile.certificateSha256.matches(Regex("[0-9a-f]{64}")))
            XapiIdentity(profile.id, Endpoints.normalize(profile.endpoint), profile.username, profile.password, profile.certificateSha256)
        }.getOrNull()
    }
}

/**
 * Which XML-RPC calls are reads. Only a method whose name part starts with `get_` (plus `event.from` and the live
 * data-source read `query_data_source`, which does not start with `get_` but changes nothing) is a read; every
 * other method, including `host.call_plugin`, `message.destroy`, every `Async.*` call and login/logout, is treated as a
 * write: it is never repeated, never sent on a kept-alive connection and never sent after the caller was cancelled.
 */
internal object XapiMethods {
    fun isRead(method: String): Boolean = method == "event.from" ||
        method.substringAfterLast('.').let { it.startsWith("get_") || it == "query_data_source" }
}

/**
 * The pinned XML-RPC transport. The request bytes, headers, timeouts, redirect refusal, size cap and pin checks are the
 * ones the gateway always used. With [keepAlive], reads may reuse a recent connection (see [Lane]); writes always use
 * a brand-new connection that is closed afterwards, exactly as before.
 */
internal class XapiTransport(private val profile: Profile, private val keepAlive: Boolean) {
    val endpoint: String = Endpoints.normalize(profile.endpoint)
    private val url = URL(endpoint)
    val tls: SSLContext = ServerTrust.context(profile.certificateSha256)
    // One verifier instance, so kept-alive connections match; it checks the exact pin on every new connection.
    private val verifier = HostnameVerifier { host, session ->
        // Exact user-approved leaf pin identifies private/self-signed endpoints.
        host == url.host &&
            (session.peerCertificates.firstOrNull() as? X509Certificate)?.let {
                ServerTrust.fingerprint(it) == profile.certificateSha256
            } == true
    }
    private var lane: Lane? = null
    private val now get() = System.nanoTime() / 1_000_000

    /**
     * A group of kept-alive connections. The platform pools connections per socket factory, so a lane's own factory
     * decides which pooled connections a call may get. A lane is replaced when the session has been idle for
     * [SessionReusePolicy.IDLE_REUSE_MILLIS] or the lane is [SessionReusePolicy.MAX_REUSE_AGE_MILLIS] old; its sockets
     * are closed once no call uses them, so they are never handed out again.
     */
    private class Lane(base: SSLSocketFactory, val createdAt: Long) {
        val factory = TrackingSocketFactory(base)
        var inFlight = 0
        var lastUsed = createdAt
        var retired = false
    }

    private fun checkout(): Lane {
        val closing: Lane?
        val chosen: Lane
        synchronized(this) {
            val time = now
            val current = lane
            if (current != null && time - current.lastUsed <= SessionReusePolicy.IDLE_REUSE_MILLIS &&
                time - current.createdAt <= SessionReusePolicy.MAX_REUSE_AGE_MILLIS) { chosen = current; closing = null }
            else { closing = current?.let(::retire); chosen = Lane(tls.socketFactory, time); lane = chosen }
            chosen.inFlight++; chosen.lastUsed = time
        }
        closing?.factory?.closeAll()
        return chosen
    }
    private fun checkin(used: Lane) {
        val closing = synchronized(this) {
            used.inFlight--; used.lastUsed = now
            used.takeIf { it.retired && it.inFlight == 0 }
        }
        closing?.factory?.closeAll()
    }
    /** Marks [old] retired and returns it if its sockets can be closed now. Caller holds the lock. */
    private fun retire(old: Lane): Lane? { old.retired = true; return old.takeIf { it.inFlight == 0 } }

    /** Closes every idle kept-alive connection. Used when the session ends. */
    fun close() {
        val closing = synchronized(this) { lane?.let(::retire).also { lane = null } }
        closing?.factory?.closeAll()
    }

    fun post(method: String, args: List<Any?>, readTimeoutMillis: Int, abort: CallAbort?): Any? =
        exchange(method, args, readTimeoutMillis, abort) { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val count = stream.read(buffer)
                if (count == -1) break
                if (output.size() + count > 8 * 1024 * 1024) throw GatewayError("response-limit")
                output.write(buffer, 0, count)
            }
            XmlRpc.response(output.toString(Charsets.UTF_8.name()))
        }

    // --- inventory scale ---
    /**
     * A read whose answer is parsed while it arrives ([read] gets the body, at most [maxBytes]); nothing else differs from
     * [post]. Whatever [read] leaves unread is drained (within the same limit), so a pooled connection is always clean.
     */
    fun <T> postStream(method: String, args: List<Any?>, readTimeoutMillis: Int, abort: CallAbort?, maxBytes: Long,
        read: (java.io.InputStream) -> T): T {
        require(XapiMethods.isRead(method)) { "Only reads are streamed." }
        return exchange(method, args, readTimeoutMillis, abort) { stream ->
            val limited = LimitedInput(stream, maxBytes)
            fun drain() { val rest = ByteArray(4096); while (limited.read(rest) != -1) Unit }
            val result = try { read(limited) } catch (failure: RpcFailure) { drain(); throw failure }
            drain()
            result
        }
    }
    // --- end inventory scale ---

    private fun <T> exchange(method: String, args: List<Any?>, readTimeoutMillis: Int, abort: CallAbort?, consume: (java.io.InputStream) -> T): T {
        val reuse = keepAlive && XapiMethods.isRead(method)
        val used = if (reuse) checkout() else null
        // On a keep-alive transport a write gets a factory of its own: no pooled connection can match it, and its socket
        // is closed afterwards, so it is never pooled either.
        val single = if (keepAlive && !reuse) TrackingSocketFactory(tls.socketFactory) else null
        val connection = url.openConnection() as HttpsURLConnection
        var keep = false
        try {
            abort?.attach(connection)
            connection.sslSocketFactory = used?.factory ?: single ?: tls.socketFactory
            connection.hostnameVerifier = verifier
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 8_000
            connection.readTimeout = readTimeoutMillis
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "text/xml; charset=utf-8")
            connection.setRequestProperty("User-Agent", "Meteor-Spark/${BuildConfig.VERSION_NAME}")
            val bytes = XmlRpc.request(method, args)
            // A fixed-length body cannot be replayed by the platform, so it never resends a request on its own.
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }
            if (connection.responseCode in 300..399) throw GatewayError("redirect-refused")
            if (connection.responseCode != 200) throw GatewayError("http-error")
            val result = try { connection.inputStream.use(consume) } catch (failure: RpcFailure) {
                // An XAPI failure envelope is a complete answer: the connection is as clean as after a success.
                keep = reuse; throw failure
            }
            // The whole response was read, so the connection is in a clean state and may be pooled.
            keep = reuse
            return result
        } catch (error: IllegalArgumentException) {
            keep = false; throw error
        } finally {
            abort?.detach(connection)
            // Anything that did not end in a fully read response closes its connection; so does every write.
            if (!keep) connection.disconnect()
            used?.let(::checkin)
            single?.closeAll()
        }
    }

    fun login(): String = post("session.login_with_password",
        listOf(profile.username, profile.password, "1.0", "MeteorAndroidSpark"), 12_000, null) as? String
        ?: throw GatewayError("session-missing")

    fun logout(token: String) { post("session.logout", listOf(token), 12_000, null) }
}

/** Delegates to the pinned factory and remembers its sockets, so a retired lane can close its pooled connections. */
private class TrackingSocketFactory(private val base: SSLSocketFactory) : SSLSocketFactory() {
    private val sockets = ArrayList<Socket>()
    private fun track(socket: Socket): Socket {
        synchronized(sockets) { sockets.removeAll { it.isClosed }; sockets += socket }
        return socket
    }
    fun closeAll() {
        val open = synchronized(sockets) { sockets.toList().also { sockets.clear() } }
        open.forEach { runCatching { it.close() } }
    }
    override fun getDefaultCipherSuites(): Array<String> = base.defaultCipherSuites
    override fun getSupportedCipherSuites(): Array<String> = base.supportedCipherSuites
    override fun createSocket(): Socket = track(base.createSocket())
    override fun createSocket(socket: Socket?, host: String?, port: Int, autoClose: Boolean): Socket = track(base.createSocket(socket, host, port, autoClose))
    override fun createSocket(host: String?, port: Int): Socket = track(base.createSocket(host, port))
    override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
        track(base.createSocket(host, port, localHost, localPort))
    override fun createSocket(host: InetAddress?, port: Int): Socket = track(base.createSocket(host, port))
    override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
        track(base.createSocket(address, port, localAddress, localPort))
}

/**
 * One XAPI session shared by concurrent foreground calls for one [identity]. Login is single-flight: racing callers
 * wait for the same attempt and share its result or its failure. [relogin] replaces a token the server answered
 * SESSION_INVALID for, once per stale token. Retiring it stops new holders; the token is logged out when the last
 * holder leaves.
 */
internal class SharedXapiSession(val identity: XapiIdentity, val transport: XapiTransport) {
    private val lock = Any()
    private var token: String? = null
    private var pending: CompletableFuture<String>? = null
    private var holders = 0
    private var retired = false

    fun token(): String = obtain(null)
    /** A valid token after [stale] was refused: a fresh login, unless another caller already replaced [stale]. */
    fun relogin(stale: String): String = obtain(stale)

    private fun obtain(stale: String?): String {
        val attempt: CompletableFuture<String>
        val owner: Boolean
        synchronized(lock) {
            val current = token
            if (current != null && current != stale) return current
            token = null
            val running = pending
            if (running != null) { attempt = running; owner = false }
            else { attempt = CompletableFuture(); pending = attempt; owner = true }
        }
        if (owner) {
            try {
                val fresh = transport.login()
                synchronized(lock) { token = fresh; pending = null }
                attempt.complete(fresh)
            } catch (error: Throwable) {
                synchronized(lock) { pending = null }
                attempt.completeExceptionally(error)
                throw error
            }
        }
        return try { attempt.get() } catch (error: ExecutionException) { throw error.cause ?: error }
    }

    fun hold(): Boolean = synchronized(lock) { if (retired) false else { holders++; true } }
    /** Returns true when the caller must end the session (it was retired and this was the last holder). */
    fun leave(): Boolean = synchronized(lock) { holders--; retired && holders == 0 }
    /** Returns true when the caller must end the session now (no holder left). */
    fun retire(): Boolean = synchronized(lock) { if (retired) false else { retired = true; holders == 0 } }
    /** The token to log out, taken once. */
    fun takeToken(): String? = synchronized(lock) { token.also { token = null } }
}

/** Owns the gateway's one shared foreground session: which identity it is for, its lifetime and its logout. */
internal class SessionReuse(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    @Volatile var backgroundGraceMillis: Long = SessionReusePolicy.BACKGROUND_GRACE_MILLIS
    private var designated: XapiIdentity? = null
    private var current: SharedXapiSession? = null
    private var background = false
    /** True after the background grace ended the session; calls use their own sessions until the app returns. */
    private var asleep = false
    private var grace: Job? = null
    private val endings = ArrayList<Job>()

    fun keep(profile: Profile?) {
        val identity = profile?.let(XapiIdentity::of)
        val ending = synchronized(this) {
            if (identity == designated) return
            designated = identity
            current.also { current = null }
        }
        ending?.let(::retire)
    }

    fun release(profileId: String?) {
        val ending = synchronized(this) {
            if (profileId != null && designated?.profileId != profileId) return
            designated = null
            current.also { current = null }
        }
        ending?.let(::retire)
    }

    fun background(value: Boolean) {
        synchronized(this) {
            if (background == value) return
            background = value
            grace?.cancel(); grace = null
            if (!value) { asleep = false; return }
            grace = scope.launch {
                delay(backgroundGraceMillis)
                val ending = synchronized(this@SessionReuse) {
                    if (!background) return@launch
                    asleep = true
                    current.also { current = null }
                }
                ending?.let(::retire)
            }
        }
    }

    /** The shared session for [profile], held for one gateway call, or null: then the call opens its own session. */
    fun hold(profile: Profile): SharedXapiSession? = synchronized(this) {
        if (asleep) return null
        val identity = XapiIdentity.of(profile) ?: return null
        if (identity != designated) return null
        val session = current ?: SharedXapiSession(identity, XapiTransport(profile, keepAlive = true)).also { current = it }
        session.takeIf { it.hold() }
    }

    fun leave(session: SharedXapiSession) { if (session.leave()) end(session) }

    /** Retires [session] if it is still the current one, e.g. after the pin check failed. */
    fun drop(session: SharedXapiSession) {
        val ending = synchronized(this) { if (current === session) { current = null; session } else null }
        ending?.let(::retire)
    }

    private fun retire(session: SharedXapiSession) { if (session.retire()) end(session) }

    /** Best-effort logout off the caller's thread. A transport failure is tried once more (logout is idempotent). */
    private fun end(session: SharedXapiSession) {
        val job = scope.launch {
            val token = session.takeToken()
            if (token != null) try { session.transport.logout(token) } catch (error: Exception) {
                if (error is IOException || error is GatewayError) runCatching { session.transport.logout(token) }
            }
            session.transport.close()
        }
        synchronized(endings) { endings.removeAll { it.isCompleted }; endings += job }
    }

    /** Test hook: waits for pending logouts. */
    suspend fun awaitEndings() { synchronized(endings) { endings.toList() }.joinAll() }
}

/**
 * Runs a blocking XAPI exchange on the IO dispatcher; cancelling the caller disconnects the read in flight instead of
 * waiting out its timeout (the A4 event session's mechanism). A failure after cancellation is rethrown as the
 * cancellation it is. Writes, login and logout ignore [CallAbort]: they are never cut off half-way.
 */
internal suspend fun <T> abortableXapi(block: suspend (CallAbort) -> T): T = coroutineScope {
    val abort = CallAbort()
    val guard = launch(start = CoroutineStart.UNDISPATCHED) { try { awaitCancellation() } finally { abort.abort() } }
    try { withContext(Dispatchers.IO) { block(abort) } }
    catch (error: Exception) { currentCoroutineContext().ensureActive(); throw error }
    finally { guard.cancel() }
}

internal fun Throwable.pinFailure(): Boolean = generateSequence(this) { it.cause }.take(8).any { it is CertificateChanged }
// --- end session reuse ---
