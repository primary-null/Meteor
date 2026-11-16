package com.meteor.app.data

import android.annotation.SuppressLint
import com.meteor.app.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.net.InetSocketAddress
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

data class ServerCertificate(val sha256: String, val subject: String, val validFrom: Long, val validUntil: Long)
class CertificateChanged : Exception("The server certificate changed.")
class GatewayError(val reason: String) : Exception(reason)

fun interface CertificateInspector {
    suspend fun inspect(endpoint: String): ServerCertificate
}

object ServerTrust : CertificateInspector {
    fun fingerprint(certificate: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString("") { "%02x".format(it) }

    // Deliberately replaces CA trust: XCP-ng hosts commonly use self-signed certificates, so the
    // user approves an exact leaf SHA-256 pin out of band. Validity is still checked, and a
    // pinned context rejects every other certificate.
    @SuppressLint("CustomX509TrustManager")
    fun context(expectedPin: String? = null, observed: ((X509Certificate) -> Unit)? = null): SSLContext {
        val manager = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                throw java.security.cert.CertificateException("Client trust is not used.")
            }
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                val leaf = chain?.firstOrNull() ?: throw java.security.cert.CertificateException("Missing certificate.")
                leaf.checkValidity()
                if (expectedPin != null && fingerprint(leaf) != expectedPin) throw CertificateChanged()
                if (expectedPin == null && observed == null) throw java.security.cert.CertificateException("No trust policy.")
                observed?.invoke(leaf)
            }
        }
        return SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(manager), SecureRandom()) }
    }

    override suspend fun inspect(endpoint: String): ServerCertificate = withContext(Dispatchers.IO) {
        val address = URI(Endpoints.normalize(endpoint))
        var certificate: X509Certificate? = null
        val context = context(observed = { certificate = it })
        val host = address.host.removeSurrounding("[", "]")
        (context.socketFactory.createSocket() as SSLSocket).use { socket ->
            socket.soTimeout = 8_000
            socket.enabledProtocols = socket.supportedProtocols.filter { it == "TLSv1.2" || it == "TLSv1.3" }.toTypedArray()
            socket.connect(InetSocketAddress(host, if (address.port == -1) 443 else address.port), 8_000)
            socket.startHandshake()
        }
        val leaf = certificate ?: throw SSLPeerUnverifiedException("No certificate.")
        ServerCertificate(fingerprint(leaf), leaf.subjectX500Principal.name, leaf.notBefore.time, leaf.notAfter.time)
    }
}

interface MeteorGateway : PowerBackend {
    suspend fun inventory(profile: Profile): Inventory
    suspend fun details(profile: Profile, authority: String, uuid: String): VmDetails
    suspend fun pollDetails(profile: Profile, authority: String, uuid: String, previous: VmDetails): VmDetails =
        details(profile, authority, uuid)
    suspend fun metrics(profile: Profile, target: MetricTarget): MetricSnapshot = throw UnsupportedOperationException()
    suspend fun activity(profile: Profile, authority: String): PoolActivity = throw UnsupportedOperationException()
    // --- A2 operations ---
    /** Reads what this login is known to hold. Never throws for missing methods or restricted roles. */
    suspend fun permissions(profile: Profile): SessionPermissions = SessionPermissions.unavailable("not supported")
    /** Fresh hosts of the VM's pool for a host picker, with the VM's resident host marked. */
    suspend fun hostChoices(profile: Profile, authority: String, vmUuid: String): List<HostChoice> = throw UnsupportedOperationException()
    // --- end A2 operations ---
    // --- A3 observability --- read-only history (HTTP /rrd_updates) and detail reads
    suspend fun history(profile: Profile, target: MetricTarget, range: HistoryRange, sinceSeconds: Long? = null): HistorySnapshot =
        throw UnsupportedOperationException()
    suspend fun vmInfo(profile: Profile, authority: String, uuid: String): VmInfo = throw UnsupportedOperationException()
    suspend fun hostInfo(profile: Profile, authority: String, uuid: String): HostInfo = throw UnsupportedOperationException()
    suspend fun networks(profile: Profile, authority: String): NetworkOverview = throw UnsupportedOperationException()
    suspend fun storageInfo(profile: Profile, authority: String, uuid: String): StorageInfo = throw UnsupportedOperationException()
    // --- end A3 observability ---
    // --- A4 alerts/events ---
    /** [sinceMillis] null = full bounded read (`get_all_records`); otherwise incremental `message.get_since`. */
    suspend fun alerts(profile: Profile, authority: String, sinceMillis: Long?): AlertFetch = throw UnsupportedOperationException()
    /** The only alert write: `message.destroy`, each followed by a read-back. Never touches the VM power journal. */
    suspend fun dismissAlerts(profile: Profile, authority: String, targets: List<AlertTarget>): DismissOutcome = throw UnsupportedOperationException()
    /** Opens a separate long-lived session for `event.from`. The caller must close it. */
    suspend fun openEvents(profile: Profile, authority: String): EventSession = throw UnsupportedOperationException()
    // --- end A4 ---
    // --- B2 storage/host ---
    /** Fresh read of one storage/host target in the pool. */
    suspend fun targetState(profile: Profile, authority: String, kind: TargetKind, uuid: String): TargetState = throw UnsupportedOperationException()
    /** Every storage connection (PBD) of the pool with its host's liveness. Bounded. */
    suspend fun poolPbds(profile: Profile, authority: String): List<PbdState> = throw UnsupportedOperationException()
    /** ISO images on ISO storage repositories of the pool. Bounded. */
    suspend fun isoCatalog(profile: Profile, authority: String): IsoCatalog = throw UnsupportedOperationException()
    // --- end B2 storage/host ---
    // --- B1 console ---
    /**
     * Opens a VM console on its own XAPI session. That session is logged out when the returned
     * [ConsoleSession] closes, or immediately when opening fails. Not a VM operation: nothing is journaled.
     */
    suspend fun console(profile: Profile, authority: String, uuid: String, encodings: List<Int> = RfbEncoding.DEFAULT): ConsoleSession =
        throw UnsupportedOperationException()
    /**
     * Opens the console of the XCP-ng host [uuid] itself (its control domain's `rfb` console) on its own XAPI session, with
     * the same pinning and member-trust review as a VM console. Not a host operation: nothing is journaled or sent.
     */
    suspend fun hostConsole(profile: Profile, authority: String, uuid: String, encodings: List<Int> = RfbEncoding.DEFAULT): ConsoleSession =
        throw UnsupportedOperationException()
    /** Read-only: whether the host offers a console Meteor can show, for the host details' button. */
    suspend fun hostConsoleOffer(profile: Profile, authority: String, uuid: String): HostConsoleOffer = throw UnsupportedOperationException()
    // --- end B1 console ---
    // --- B3 background/summary --- read-only; none of these sends a write
    /**
     * Background alert check in one short-lived session: the pool, the server's clock (`host.get_servertime`), A4's
     * `message.get_since` read from what [since] chooses once those are known, and names for at most
     * [BackgroundAlerts.MAX_NAMED_OBJECTS] objects of messages that match [wanted].
     */
    suspend fun backgroundAlerts(profile: Profile, since: BackgroundSince, wanted: (AlertMessage) -> Boolean): BackgroundAlertFetch =
        throw UnsupportedOperationException()
    /** All-pools summary counts in one short-lived session. [lastSeen] is the A4 in-app "seen" value, read-only here. */
    suspend fun poolSummary(profile: Profile, lastSeen: Long?): PoolSummaryRead = throw UnsupportedOperationException()
    /** `host.call_plugin(host, "updater.py", "check_update", {})`, only on an explicit tap. Never `update`. */
    suspend fun checkHostUpdates(profile: Profile, authority: String, hostUuid: String): HostUpdateResult = throw UnsupportedOperationException()
    // --- end B3 ---
    // --- inventory scale ---
    /** On demand, bounded: the pool's VM templates with only what a picker needs. Read-only; no UI uses it yet. */
    suspend fun templates(profile: Profile, authority: String): TemplateCatalog = throw UnsupportedOperationException()
    // --- end inventory scale ---
    // --- session reuse --- see SessionReuse.kt for ownership and lifetime
    /** Names the one profile whose XAPI session the foreground methods keep open. Another identity replaces it. */
    fun keepSession(profile: Profile) {}
    /** Ends the shared session (best-effort logout); with [profileId], only if it belongs to that profile. */
    fun releaseSession(profileId: String? = null) {}
    /** The app went to the background (true) or came back (false); see [SessionReusePolicy.BACKGROUND_GRACE_MILLIS]. */
    fun backgrounded(value: Boolean) {}
    // --- end session reuse ---
    // --- VM settings ---
    /** Read-only: everything the settings editor shows for one VM. Writes go only through [PowerService.executeSettings]. */
    suspend fun vmSettings(profile: Profile, authority: String, uuid: String): VmSettings = throw UnsupportedOperationException()
    // --- end VM settings ---
}

class XapiGateway(private val clock: () -> Long = System::currentTimeMillis) : MeteorGateway {
    // --- A4 alerts/events ---
    /** Added to the event timeout to form the HTTP read timeout. Settable so tests can shrink it. */
    internal var eventReadMarginMillis: Long = EventPolicy.READ_MARGIN_MILLIS
    override suspend fun alerts(profile: Profile, authority: String, sinceMillis: Long?): AlertFetch =
        session(profile) { alerts(authority, sinceMillis) }
    override suspend fun dismissAlerts(profile: Profile, authority: String, targets: List<AlertTarget>): DismissOutcome =
        session(profile) { dismissAlerts(authority, targets) }
    override suspend fun openEvents(profile: Profile, authority: String): EventSession = withContext(Dispatchers.IO) {
        val connection = XapiSession(profile, clock)
        try { connection.startEvents(authority) } catch (error: Exception) { connection.close(); throw error }
        XapiEventSession(connection, eventReadMarginMillis)
    }
    // --- end A4 ---
    // --- inventory scale --- limits of streamed pool-sized reads; settable so tests can shrink them
    internal var streamLimits: StreamLimits = StreamLimits.DEFAULT
    override suspend fun templates(profile: Profile, authority: String): TemplateCatalog = session(profile) { templates(authority) }
    // --- end inventory scale ---
    // --- session reuse --- foreground calls share the kept session of their exact identity; anything else logs in per call.
    internal val reuse = SessionReuse()
    /** The live view's source choices and a VM's disk devices, kept for a minute (see [LiveCache]). */
    private val liveCache = LiveCache()
    override fun keepSession(profile: Profile) = reuse.keep(profile)
    override fun releaseSession(profileId: String?) = reuse.release(profileId)
    override fun backgrounded(value: Boolean) = reuse.background(value)
    private suspend fun <T> session(profile: Profile, action: suspend XapiSession.() -> T): T = abortableXapi { abort ->
        val shared = reuse.hold(profile)
        if (shared == null) ephemeralSession(profile, abort, action)
        else try { XapiSession(profile, clock, shared, abort, liveCache, streamLimits).action() } catch (error: Exception) {
            // A changed certificate ends the shared session; its logout can only reach a server that passes the pin.
            if (error.pinFailure()) reuse.drop(shared)
            throw error
        } finally { reuse.leave(shared) }
    }
    /** One session for this call only, logged out at the end: the per-run designs (background check, All pools) keep this. */
    private suspend fun <T> ephemeral(profile: Profile, action: suspend XapiSession.() -> T): T =
        abortableXapi { abort -> ephemeralSession(profile, abort, action) }
    private suspend fun <T> ephemeralSession(profile: Profile, abort: CallAbort, action: suspend XapiSession.() -> T): T {
        val connection = XapiSession(profile, clock, stop = abort, live = liveCache, limits = streamLimits)
        try { connection.login(); return connection.action() } finally { connection.close() }
    }
    // --- end session reuse ---
    // --- B1 console ---
    override suspend fun console(profile: Profile, authority: String, uuid: String, encodings: List<Int>): ConsoleSession =
        openConsole(profile, encodings, ConsoleSubject.VM) { ConsoleSession.location(consoleLocation(authority, uuid)) }
    override suspend fun hostConsole(profile: Profile, authority: String, uuid: String, encodings: List<Int>): ConsoleSession =
        openConsole(profile, encodings, ConsoleSubject.HOST) { hostConsoleUri(profile, authority, uuid) }
    override suspend fun hostConsoleOffer(profile: Profile, authority: String, uuid: String): HostConsoleOffer = session(profile) {
        try { hostConsoleUri(profile, authority, uuid); HostConsoleOffer(null) }
        catch (error: ConsoleError) { HostConsoleOffer(error.message) }
    }
    private fun XapiSession.hostConsoleUri(profile: Profile, authority: String, uuid: String): java.net.URI {
        val found = hostConsoleLocation(authority, uuid)
        return ConsoleSession.location(found.location).also { HostConsoleRules.checkLocation(it, found, profile) }
    }
    private suspend fun openConsole(profile: Profile, encodings: List<Int>, subject: ConsoleSubject,
        locate: XapiSession.() -> java.net.URI): ConsoleSession =
        withContext(Dispatchers.IO) {
            val connection = XapiSession(profile, clock)
            try {
                connection.login()
                val uri = connection.locate()
                val opened = ConsoleOpener.open(profile, uri, connection.consoleToken, { connection.close() }, encodings, subject)
                // A cancelled caller must not leave a live tunnel or XAPI login behind.
                try { ensureActive() } catch (cancelled: CancellationException) { opened.close(); throw cancelled }
                opened
            } catch (error: Exception) {
                if (error is CancellationException) { connection.close(); throw error }
                connection.close()
                throw ConsoleFailures.classify(error)
            }
        }
    // --- end B1 console ---
    override suspend fun inventory(profile: Profile): Inventory = session(profile) { inventory() }
    override suspend fun details(profile: Profile, authority: String, uuid: String): VmDetails = session(profile) { details(authority, uuid) }
    override suspend fun pollDetails(profile: Profile, authority: String, uuid: String, previous: VmDetails): VmDetails =
        session(profile) { readDetails(authority, uuid, previous) }
    override suspend fun metrics(profile: Profile, target: MetricTarget): MetricSnapshot = session(profile) { metrics(target) }
    override suspend fun activity(profile: Profile, authority: String): PoolActivity = session(profile) { activity(authority) }
    // --- A3 observability ---
    override suspend fun history(profile: Profile, target: MetricTarget, range: HistoryRange, sinceSeconds: Long?): HistorySnapshot =
        session(profile) { history(target, range, sinceSeconds) }
    override suspend fun vmInfo(profile: Profile, authority: String, uuid: String): VmInfo = session(profile) { vmInfo(authority, uuid) }
    override suspend fun hostInfo(profile: Profile, authority: String, uuid: String): HostInfo = session(profile) { hostInfo(authority, uuid) }
    override suspend fun networks(profile: Profile, authority: String): NetworkOverview = session(profile) { networks(authority) }
    override suspend fun storageInfo(profile: Profile, authority: String, uuid: String): StorageInfo = session(profile) { storageInfo(authority, uuid) }
    // --- end A3 observability ---
    override suspend fun <T> powerConnection(profile: Profile, action: suspend PowerConnection.() -> T): T = session(profile) { action(this) }
    // --- A2 operations ---
    override suspend fun permissions(profile: Profile): SessionPermissions = session(profile) { permissions() }
    override suspend fun hostChoices(profile: Profile, authority: String, vmUuid: String): List<HostChoice> =
        session(profile) { hostChoices(authority, vmUuid) }
    // --- end A2 operations ---
    // --- B2 storage/host ---
    override suspend fun targetState(profile: Profile, authority: String, kind: TargetKind, uuid: String): TargetState =
        session(profile) { target(authority, kind, uuid) }
    override suspend fun poolPbds(profile: Profile, authority: String): List<PbdState> = session(profile) { poolPbds(authority) }
    override suspend fun isoCatalog(profile: Profile, authority: String): IsoCatalog = session(profile) { isoCatalog(authority) }
    // --- end B2 storage/host ---
    // --- B3 background/summary ---
    override suspend fun backgroundAlerts(profile: Profile, since: BackgroundSince, wanted: (AlertMessage) -> Boolean): BackgroundAlertFetch =
        ephemeral(profile) { backgroundAlerts(since, wanted) } // session reuse: one session per run, as before
    override suspend fun poolSummary(profile: Profile, lastSeen: Long?): PoolSummaryRead = ephemeral(profile) { poolSummary(lastSeen) }
    override suspend fun checkHostUpdates(profile: Profile, authority: String, hostUuid: String): HostUpdateResult =
        session(profile) { checkHostUpdates(authority, hostUuid) }
    // --- end B3 ---
    // --- VM settings ---
    override suspend fun vmSettings(profile: Profile, authority: String, uuid: String): VmSettings =
        session(profile) { settings(authority, uuid) ?: throw IllegalArgumentException("This VM no longer exists.") }
    // --- end VM settings ---
}

private class XapiSession(private val profile: Profile, private val clock: () -> Long,
    // --- session reuse --- [shared]: the kept foreground session; [stop]: cancellation of the gateway call
    private val shared: SharedXapiSession? = null, private val stop: CallAbort? = null,
    private val live: LiveCache = LiveCache(),
    // --- end session reuse ---
    private val limits: StreamLimits = StreamLimits.DEFAULT, // inventory scale
) : PowerConnection {
    private val endpoint = Endpoints.normalize(profile.endpoint)
    // --- session reuse ---
    private val transport = shared?.transport ?: XapiTransport(profile, keepAlive = false)
    private val tls = transport.tls
    // --- end session reuse ---
    private var session: String? = null
    init { require(profile.certificateSha256.matches(Regex("[0-9a-f]{64}"))) }
    private val token get() = shared?.token() ?: requireNotNull(session)
    // A4: [readTimeoutMillis] and [abort] let the long-lived event session wait past the default 12 s and be cancelled.
    private fun call(method: String, args: List<Any?>, readTimeoutMillis: Int = 12_000, abort: CallAbort? = null): Any? {
        // --- session reuse ---
        val read = XapiMethods.isRead(method)
        val cancel = abort ?: stop
        // A write is never started for a caller that was already cancelled; nothing has been sent, so it is NotSent.
        if (!read && cancel?.isAborted == true) throw NotSent("The request was cancelled before sending.")
        val link = shared
        return try { transport.post(method, args, readTimeoutMillis, cancel.takeIf { read }) } catch (error: RpcFailure) {
            // A read refused with SESSION_INVALID logs in again once (shared with racing callers) and is repeated once.
            // A write is never repeated: its SESSION_INVALID reaches the caller as the explicit rejection it is.
            if (link == null || !read || error.code != "SESSION_INVALID") throw error
            val stale = args.firstOrNull() as? String ?: throw error
            val fresh = link.relogin(stale)
            transport.post(method, args.map { if (it == stale) fresh else it }, readTimeoutMillis, cancel)
        }
        // --- end session reuse ---
    }
    private fun callForText(method: String, args: List<Any?>): String =
        call(method, args) as? String ?: throw GatewayError("invalid-reference")
    fun login() {
        session = transport.login() // session reuse: same request; login and logout are never cut off by cancellation
    }
    fun close() { session?.let { runCatching { transport.logout(it) } }; session = null }
    private fun record(type: String, ref: String): Map<String, Any?> = map(call("$type.get_record", listOf(token, ref)))
    private fun map(value: Any?): Map<String, Any?> {
        val data = value as? Map<*, *> ?: throw GatewayError("invalid-record")
        return data.entries.associate { (key, item) -> (key as? String ?: throw GatewayError("invalid-field")) to item }
    }
    private fun records(type: String): Map<String, Map<String, Any?>> {
        val data = call("$type.get_all_records", listOf(token)) as? Map<*, *> ?: throw GatewayError("invalid-records")
        return data.entries.associate { entry ->
            val key = entry.key as? String ?: throw GatewayError("invalid-record-key")
            key to map(entry.value)
        }
    }
    // --- inventory scale --- streamed, projected reads (InventoryScale.kt). Every method here is a `get_*` read, so a
    // shared session's SESSION_INVALID is answered by one re-login and one repeat, exactly as in [call].
    private fun <T> streamCall(method: String, args: List<Any?>, read: (java.io.InputStream) -> T): T {
        val link = shared
        return try { transport.postStream(method, args, 12_000, stop, limits.maxBytes, read) } catch (error: RpcFailure) {
            if (link == null || error.code != "SESSION_INVALID") throw error
            val stale = args.firstOrNull() as? String ?: throw error
            val fresh = link.relogin(stale)
            transport.postStream(method, args.map { if (it == stale) fresh else it }, 12_000, stop, limits.maxBytes, read)
        }
    }
    private fun projected(type: String, projection: Projection, maxRecords: Int = limits.maxRecords,
        keep: (Map<String, Any?>) -> Boolean = { true }): RecordsRead =
        streamCall("$type.get_all_records", listOf(token)) { XmlRpcRecords.collect(it, projection, maxRecords, limits, keep) }
    private fun projectedWhere(type: String, filter: String, projection: Projection, maxRecords: Int = limits.maxRecords): RecordsRead =
        streamCall("$type.get_all_records_where", listOf(token, filter)) { XmlRpcRecords.collect(it, projection, maxRecords, limits) }
    /** Every record whose [field] is one of [values], in `or` chains of [XapiQuery.BATCH]. An unsafe literal cannot name a real object. */
    private fun whereAny(type: String, field: String, values: Collection<String>, projection: Projection): Map<String, Map<String, Any?>> {
        val out = LinkedHashMap<String, Map<String, Any?>>()
        XapiQuery.anyOf(field, values.filter { !isNull(it) && XapiQuery.isSafe(it) }).forEach { filter ->
            val read = projectedWhere(type, filter, projection)
            if (!read.complete) throw GatewayError("invalid-records")
            out.putAll(read.records)
        }
        return out
    }
    /** Records of known references; one that no longer exists is simply absent (as `HANDLE_INVALID` was per record). */
    private fun byRefs(type: String, refs: Collection<String>, projection: Projection) = whereAny(type, "_ref", refs, projection)

    /** Pool, hosts, host metrics, normal VMs (filtered by the server) and storage. A VM list beyond the limits is reported, not hidden. */
    fun inventory(): Inventory {
        val pools = projected("pool", Projections.POOL).records
        val hosts = projected("host", Projections.HOST).records
        val metrics = projected("host_metrics", Projections.HOST_METRICS).records
        val notices = ArrayList<String>()
        val vms = try { guests() } catch (error: Exception) {
            if (!error.isResponseLimit()) throw error
            notices += InventoryNotices.guestsUnreadable(error); null
        }
        if (vms != null && !vms.complete) notices += InventoryNotices.guestsCapped(vms.records.size, vms.total)
        val srs = projected("SR", Projections.SR).records
        return InventoryMapper.normalize(pools, hosts, metrics, vms?.records.orEmpty(), srs, clock()).copy(notLoaded = notices)
    }
    /** Normal VMs only. A server without `get_all_records_where` sends every VM record; templates and snapshots are then skipped here. */
    private fun guests(): RecordsRead = try { projectedWhere("VM", XapiQuery.GUESTS, Projections.GUEST) } catch (error: RpcFailure) {
        if (error.code != "MESSAGE_METHOD_UNKNOWN") throw error
        projected("VM", Projections.GUEST) { !it.flag("is_a_template") && !it.flag("is_control_domain") && !it.flag("is_a_snapshot") }
    }
    /** Templates for a future picker: one filtered read, at most [Templates.MAX] kept. */
    fun templates(authority: String): TemplateCatalog {
        verifyPool(authority)
        val read = projectedWhere("VM", XapiQuery.TEMPLATES, Projections.TEMPLATE, Templates.MAX)
        verifyPool(authority)
        return Templates.map(authority, read, clock())
    }
    /**
     * The one message read the Alerts inbox and pool activity share. Full: every message is parsed as it arrives and only
     * the [AlertMerge.MAX_KEPT] newest are kept, with the true count. If even that answer exceeds the stream limits, only
     * the recent window is requested and [MessageWindow.fallback] says so. Incremental: `message.get_since`.
     */
    private fun messageWindow(sinceMillis: Long?): MessageWindow {
        fun since(millis: Long) = streamCall("message.get_since", listOf(token, XmlRpcDateTime(millis))) {
            XmlRpcRecords.newest(it, Projections.MESSAGE, AlertMerge.MAX_KEPT, MessageWindow::time, limits)
        }
        if (sinceMillis != null) return MessageWindow(since(sinceMillis), full = false, fallback = false)
        return try {
            MessageWindow(streamCall("message.get_all_records", listOf(token)) {
                XmlRpcRecords.newest(it, Projections.MESSAGE, AlertMerge.MAX_KEPT, MessageWindow::time, limits)
            }, full = true, fallback = false)
        } catch (error: Exception) {
            if (!error.isResponseLimit()) throw error
            MessageWindow(since(clock() - AlertMerge.FALLBACK_WINDOW_MILLIS), full = false, fallback = true)
        }
    }
    // --- end inventory scale ---
    private fun liveKey(target: MetricTarget) = LiveCache.Key(profile.id, endpoint, target.kind, target.uuid)
    /** Non-CD disk devices of a VM record, for example `xvda`. CD drives also have `vbd_*` series but are not disks. */
    private fun diskDevices(target: MetricTarget, vm: Map<String, Any?>): Set<String> {
        val key = liveKey(target)
        live.disks(key, clock())?.let { return it }
        val devices = vm.strings("VBDs").take(16).mapNotNull { optionalRecord("VBD", it) }
            .filter { it.text("type") == "Disk" }.map { it.text("device") }.filter { it.isNotBlank() }.toSet()
        live.putDisks(key, devices, clock())
        return devices
    }
    /**
     * One live tick: identity checks, then the current value of each planned source with `query_data_source`
     * (`get_data_sources` reports 0.0 for every value on a real server). The plan comes from the cache when it is
     * under a minute old, so a warm tick is the checks plus one small read per source and no catalogue download.
     */
    fun metrics(target: MetricTarget): MetricSnapshot {
        val type = if (target.kind == MetricKind.VM) "VM" else "host"
        var vmRecord: Map<String, Any?>? = null
        val reference = if (target.kind == MetricKind.VM) {
            val (reference, vm) = readVm(target.authority, target.uuid)
            if (vm.text("power_state") != "Running") return MetricSnapshot(target, clock(), emptyList(), 0,
                "The VM is not running. Live metrics are unavailable.")
            vmRecord = vm
            reference
        } else {
            verifyPool(target.authority)
            callForText("host.get_by_uuid", listOf(token, target.uuid)).also {
                require(record("host", it)["uuid"] == target.uuid) { "Host identity changed." }
            }
        }
        val key = liveKey(target)
        val plan = live.plan(key, clock()) ?: run {
            val sources = call("$type.get_data_sources", listOf(token, reference)) as? List<*> ?: throw GatewayError("invalid-metrics")
            LiveMetrics.plan(target, sources.map(::map), vmRecord?.let { diskDevices(target, it) }).also { live.putPlan(key, it, clock()) }
        }
        val values = LinkedHashMap<String, Double?>()
        for (name in plan.queryNames) {
            values[name] = try { LiveMetrics.sample(call("$type.query_data_source", listOf(token, reference, name))) }
            catch (error: RpcFailure) {
                // A source that was listed and is gone (the server answers INTERNAL_ERROR) is a gap; choose again next tick.
                if (error.code != "INTERNAL_ERROR") throw error
                live.dropPlan(key); null
            }
        }
        val snapshot = LiveMetrics.snapshot(target, plan, values, clock())
        verifyPool(target.authority)
        return snapshot
    }
    private fun <T> section(read: () -> ReadSection<T>): ReadSection<T> = try { read() }
        catch (error: RpcFailure) {
            if (error.code == "SESSION_AUTHENTICATION_FAILED") throw error
            ReadSection(emptyList(), issue = when (error.code) {
                "MESSAGE_METHOD_UNKNOWN", "NOT_IMPLEMENTED" -> ReadIssue.UNSUPPORTED
                "RBAC_PERMISSION_DENIED", "PERMISSION_DENIED" -> ReadIssue.FORBIDDEN
                else -> ReadIssue.TEMPORARY
            })
        } catch (_: GatewayError) { ReadSection(emptyList(), issue = ReadIssue.TEMPORARY) }
        catch (_: IllegalArgumentException) { ReadSection(emptyList(), issue = ReadIssue.TEMPORARY) }
    fun activity(authority: String): PoolActivity {
        verifyPool(authority)
        val tasks = section { ActivityMapper.tasks(records("task")) }
        val alerts = section { MessageWindow.activity(messageWindow(null)) } // inventory scale: shared large-store read
        verifyPool(authority)
        return PoolActivity(authority, clock(), tasks, alerts)
    }
    private fun verifyPool(authority: String) {
        val pools = records("pool")
        require(pools.size == 1 && pools.values.single()["uuid"] == authority) { "Pool identity changed." }
    }
    private fun readVm(authority: String, uuid: String): Pair<String, Map<String, Any?>> {
        verifyPool(authority)
        val ref = callForText("VM.get_by_uuid", listOf(token, uuid))
        val vm = record("VM", ref)
        require(vm["uuid"] == uuid && !vm.flag("is_control_domain") && !vm.flag("is_a_template") && !vm.flag("is_a_snapshot")) { "This object cannot be managed as a VM." }
        return ref to vm
    }
    private fun vmRef(authority: String, uuid: String) = readVm(authority, uuid).first
    // --- A3 observability --- read-only reads; no XAPI write method, plugin call or SSH is used here
    private fun poolRecord(authority: String): Map<String, Any?> {
        val pools = records("pool")
        require(pools.size == 1 && pools.values.single()["uuid"] == authority) { "Pool identity changed." }
        return pools.values.single()
    }
    /** A record that disappeared between listing and reading is skipped; every other failure stays visible. */
    private fun optionalRecord(type: String, ref: String): Map<String, Any?>? =
        try { record(type, ref) } catch (error: RpcFailure) { if (error.code == "HANDLE_INVALID") null else throw error }
    private fun optionalText(method: String, args: List<Any?>): String? =
        try { callForText(method, args) } catch (error: RpcFailure) {
            if (error.code == "SESSION_AUTHENTICATION_FAILED") throw error else null
        } catch (_: GatewayError) { null }
    private fun liveRef(value: String) = value.takeIf { it.isNotBlank() && it != "OpaqueRef:NULL" }

    /**
     * Historical samples over the pinned HTTPS GET path. RRD data lives on the host that runs the object, and only
     * the pool coordinator is reachable under this profile's pin, so anything else is [HistoryUnavailable].
     */
    fun history(target: MetricTarget, range: HistoryRange, sinceSeconds: Long?): HistorySnapshot {
        val coordinator = poolRecord(target.authority).text("master")
        val isHost = target.kind == MetricKind.HOST
        var disks: Set<String>? = null
        if (isHost) {
            val ref = callForText("host.get_by_uuid", listOf(token, target.uuid))
            val host = record("host", ref)
            require(host["uuid"] == target.uuid) { "Host identity changed." }
            HistoryRouting.hostReason(host.text("name_label"), ref == coordinator)?.let { throw HistoryUnavailable(it) }
        } else {
            val vm = readVm(target.authority, target.uuid).second
            val resident = vm.text("resident_on")
            val residentName = liveRef(resident)?.takeIf { it != coordinator }?.let { optionalRecord("host", it)?.text("name_label") }
            HistoryRouting.vmReason(vm.text("power_state"), resident, coordinator, residentName)?.let { throw HistoryUnavailable(it) }
            disks = diskDevices(target, vm)
        }
        val serverText = optionalText("host.get_servertime", listOf(token, coordinator))
        val serverNow = serverText?.let { ActivityMapper.time(it) }?.div(1000)
        val query = range.query(serverNow ?: (clock() / 1000), sinceSeconds)
        // The session reference travels in the query, so it is validated and the URL is never logged or reported.
        if (!token.matches(Regex("[A-Za-z0-9:._-]{1,200}"))) throw GatewayError("invalid-session")
        val url = URL("${endpoint}rrd_updates?session_id=$token&start=${query.startSeconds}&cf=AVERAGE&interval=${query.intervalSeconds}" +
            (if (isHost) "&host=true" else "") + "&json=true" +
            // A host answer holds the host and every VM on it. Asking for one VM keeps a busy host's answer small (XCP-ng 8.3; others ignore it).
            (if (!isHost && target.uuid.matches(Regex("[A-Za-z0-9-]{1,64}"))) "&vm_uuid=${target.uuid}" else ""))
        val body = PinnedHttp.get(url, profile.certificateSha256, tls, "application/json, application/xml;q=0.8",
            "Meteor-Spark/${BuildConfig.VERSION_NAME}", readTimeoutMs = 20_000, maxBytes = RrdParser.MAX_BYTES)
        val kind = if (isHost) "host" else "vm"
        val update = RrdParser.parse(body) { it.consolidation == "AVERAGE" && it.kind == kind && it.uuid == target.uuid.lowercase() }
        val snapshot = HistoryMapper.build(target, range, update, clock(), serverNow, disks)
        verifyPool(target.authority)
        return snapshot
    }

    fun vmInfo(authority: String, uuid: String): VmInfo {
        val pool = poolRecord(authority)
        val (vmRef, vm) = readVm(authority, uuid)
        // inventory scale: every snapshot (so the newest are shown, not the first refs) and every drive, one read each
        val snapshots = projectedWhere("VM", XapiQuery.snapshotsOf(vmRef), Projections.SNAPSHOT).records.values.toList()
        val drives = projectedWhere("VBD", XapiQuery.eq("VM", vmRef), Projections.VBD).records.values.toList()
        val isoNames = drives.filter { it.text("type") == "CD" && !it.flag("empty") }.mapNotNull { drive ->
            liveRef(drive.text("VDI"))?.let { ref -> optionalRecord("VDI", ref)?.let { ref to it.text("name_label") } }
        }.toMap()
        verifyPool(authority)
        return VmInfoMapper.map(authority, vm, pool, snapshots, drives, isoNames, clock())
    }

    fun hostInfo(authority: String, uuid: String): HostInfo {
        val coordinator = poolRecord(authority).text("master")
        val ref = callForText("host.get_by_uuid", listOf(token, uuid))
        val host = record("host", ref)
        require(host["uuid"] == uuid) { "Host identity changed." }
        val pifs = projectedWhere("PIF", XapiQuery.eq("host", ref), Projections.PIF).records.values.filter { it.text("host") == ref } // inventory scale
        val metrics = records("PIF_metrics")
        val before = clock()
        val serverTime = optionalText("host.get_servertime", listOf(token, ref))
        val after = clock()
        verifyPool(authority)
        return HostInfoMapper.map(authority, host, ref == coordinator, pifs, metrics, serverTime, before, after, after)
    }

    fun networks(authority: String): NetworkOverview {
        poolRecord(authority)
        val hosts = projected("host", Projections.HOST).records // inventory scale
        val networks = records("network")
        val pifs = records("PIF")
        val metrics = records("PIF_metrics")
        verifyPool(authority)
        return NetworkMapper.map(authority, hosts, networks, pifs, metrics, clock())
    }

    fun storageInfo(authority: String, uuid: String): StorageInfo {
        poolRecord(authority)
        val ref = callForText("SR.get_by_uuid", listOf(token, uuid))
        val sr = record("SR", ref)
        require(sr["uuid"] == uuid) { "Storage identity changed." }
        val hosts = projected("host", Projections.HOST).records // inventory scale
        val pbds = projectedWhere("PBD", XapiQuery.eq("SR", ref), Projections.PBD).records.values.filter { it.text("SR") == ref }
        val allDisks = sr.strings("VDIs")
        // Bounded: at most MAX_VDIS individual reads, then only the VBDs and VMs of those disks (inventory scale: never the
        // pool-wide VBD and VM lists, which hold every snapshot and template).
        val disks = allDisks.take(StorageMapper.MAX_VDIS).mapNotNull { optionalRecord("VDI", it) }
        val vbds = byRefs("VBD", disks.flatMap { it.strings("VBDs") }, Projections.VBD)
        val vms = byRefs("VM", vbds.values.map { it.text("VM") }, Projections.VM_NAME)
        verifyPool(authority)
        return StorageMapper.map(authority, sr, hosts, pbds, disks, allDisks.size, vbds, vms, clock())
    }
    // --- end A3 observability ---
    val consoleToken get() = token
    // --- B1 console --- Reads the RFB console location. Pool and VM identity are verified by readVm.
    fun consoleLocation(authority: String, uuid: String): String {
        val (ref, vm) = readVm(authority, uuid)
        if (vm.text("power_state") in listOf("Halted", "Suspended")) throw ConsoleError(
            "This VM is not running, so it has no console. Start it from VM details, then reconnect.", ConsoleFailureKind.NOT_RUNNING)
        val consoles = call("VM.get_consoles", listOf(token, ref)) as? List<*> ?: throw ConsoleError("Invalid console list.", ConsoleFailureKind.PROTOCOL)
        val records = consoles.map { record("console", it as? String ?: throw ConsoleError("Invalid console reference.", ConsoleFailureKind.PROTOCOL)) }
        records.firstOrNull { it.text("protocol").equals("rfb", true) && it.text("location").isNotBlank() }?.let { return it.text("location") }
        throw if (records.any { it.text("protocol").equals("vt100", true) }) ConsoleError(
            "This VM offers only a text (vt100) console, which Meteor cannot display.", ConsoleFailureKind.NO_CONSOLE)
        else ConsoleError("This VM has no graphical console yet. It may still be starting; try again shortly.", ConsoleFailureKind.NO_CONSOLE)
    }
    /**
     * Host console: reads only. Pool identity, then the host by UUID, its liveness, its control domain (the host record's
     * `control_domain`, or for servers without that field the single control domain resident on the host), that VM's
     * consoles, and the pool identity again. The control domain is refused by every VM path ([readVm], [normalVm]); this is
     * the only place it is read, and only to find its `rfb` console.
     */
    fun hostConsoleLocation(authority: String, uuid: String): HostConsoleLocation {
        val pool = poolRecord(authority)
        val ref = callForText("host.get_by_uuid", listOf(token, uuid))
        val host = record("host", ref)
        require(host["uuid"] == uuid) { "Host identity changed." }
        if (recordOrNull("host_metrics", host.text("metrics"))?.flag("live") != true)
            throw ConsoleError(HostConsoleRules.NOT_LIVE, ConsoleFailureKind.NOT_RUNNING)
        val dom0Ref = HostConsoleRules.namedControlDomain(host) ?: HostConsoleRules.fallbackControlDomain(ref,
            projectedWhere("VM", "${XapiQuery.eq("is_control_domain", "true")} and ${XapiQuery.eq("resident_on", ref)}",
                Projection(setOf("uuid", "is_control_domain", "resident_on", "is_a_template", "is_a_snapshot")), 4).records)
            ?: throw ConsoleError(HostConsoleRules.NO_CONTROL_DOMAIN, ConsoleFailureKind.NO_CONSOLE)
        val dom0 = record("VM", dom0Ref)
        if (!HostConsoleRules.isControlDomainOf(ref, dom0)) throw ConsoleError(HostConsoleRules.NO_CONTROL_DOMAIN, ConsoleFailureKind.NO_CONSOLE)
        val consoles = dom0.strings("consoles").take(HostConsoleRules.MAX_CONSOLES).mapNotNull { recordOrNull("console", it) }
        val location = HostConsoleRules.chooseConsole(dom0Ref, consoles)
        verifyPool(authority)
        return HostConsoleLocation(location, host.text("address"), pool.text("master") == ref)
    }
    // --- end B1 console ---
    override suspend fun details(authority: String, uuid: String): VmDetails = readDetails(authority, uuid, null)
    fun readDetails(authority: String, uuid: String, previous: VmDetails?): VmDetails {
        val vm = readVm(authority, uuid).second
        val power = vm.text("power_state")
        val now = clock()
        val topology = VmTopology(profile.origin(), vm.strings("VBDs"), vm.strings("VIFs"))
        val operations = (vm["current_operations"] as? Map<*, *>)?.isNotEmpty() ?: false
        val metrics = vm.text("metrics").takeIf { it != "OpaqueRef:NULL" && it.isNotBlank() }?.let { record("VM_metrics", it) }.orEmpty()
        val boot = metrics.text("start_time").takeIf { it.isNotBlank() }
        val cached = previous?.takeIf { DetailPolling.canReuseTopology(it, authority, uuid, topology, power, boot, operations, now) }
        val residentHost = vm.text("resident_on").takeIf { power != "Halted" && it != "OpaqueRef:NULL" && it.isNotBlank() }
            ?.let { record("host", it) }
        val host = residentHost?.text("name_label")
        val disks = cached?.disks ?: topology.disks.map { record("VBD", it) }.filter { it.text("type") == "Disk" && !it.flag("empty") }.map { vbd ->
            val disk = record("VDI", vbd.text("VDI"))
            VmDisk(disk.text("name_label").ifBlank { "Disk" }, disk.number("virtual_size"), vbd.text("userdevice"), vbd.flag("currently_attached"), vbd.text("mode") == "RO")
        }
        val networks = cached?.networks ?: topology.networks.map { record("VIF", it) }.map { vif ->
            VmNetwork(record("network", vif.text("network")).text("name_label"), vif.text("device"), vif.text("MAC"), vif.flag("currently_attached"))
        }
        val guest = vm.text("guest_metrics").takeIf { it != "OpaqueRef:NULL" && it.isNotBlank() }?.let { record("VM_guest_metrics", it) }.orEmpty()
        val agentReported = (guest["os_version"] as? Map<*, *>)?.isNotEmpty() == true ||
            (guest["PV_drivers_version"] as? Map<*, *>)?.isNotEmpty() == true
        val live = power == "Running" && guest.flag("live") && agentReported
        val os = (guest["os_version"] as? Map<*, *>)?.get("name")?.toString()?.takeIf { live && it.isNotBlank() }
        val addresses = if (live) (guest["networks"] as? Map<*, *>)?.entries.orEmpty()
            .filter { it.key.toString().contains("/ip") }.map { it.value.toString() }.filter { it.isNotBlank() }.distinct().sorted() else emptyList()
        return VmDetails(authority, uuid, vm.text("name_label"), vm.text("name_description"), power, host,
            vm.number("VCPUs_max").toInt(), vm.number("memory_static_max"), disks, networks, live, os, addresses,
            boot, vm.strings("allowed_operations").toSet(), operations, now,
            cached?.topologyObservedAt ?: now, topology,
            residentHostUuid = residentHost?.text("uuid")?.takeIf { it.isNotBlank() })
    }
    override suspend fun submit(authority: String, uuid: String, action: PowerAction): String = submitOperation(authority, uuid, action, null)
    // --- A2 operations ---
    /** One async call per kind, built from the kind's declaration. Callers journal SUBMITTING first. */
    override suspend fun submitOperation(authority: String, uuid: String, action: PowerAction, targetHost: String?): String {
        // Journaled operation hardening: every read and argument check before the one write call fails as NotSent.
        val arguments = beforeSend("VM") {
            require(action.requestable) { "This action cannot be sent." }
            val ref = vmRef(authority, uuid)
            val hostRef = targetHost?.let { target ->
                callForText("host.get_by_uuid", listOf(token, target)).also {
                    require(record("host", it)["uuid"] == target) { "Host identity changed." }
                }
            }
            listOf(token) + action.xapiArguments(ref, hostRef)
        }
        return callForText(action.xapiMethod, arguments)
    }
    private fun hostChoice(host: Map<String, Any?>, metrics: Map<String, Any?>, resident: Boolean) =
        HostChoice(host.text("uuid").also { if (it.isBlank()) throw GatewayError("invalid-host") }, host.text("name_label").ifBlank { "Unnamed host" },
            host.flag("enabled"), metrics.flag("live"), resident)
    override suspend fun host(authority: String, uuid: String): HostChoice {
        verifyPool(authority)
        val ref = callForText("host.get_by_uuid", listOf(token, uuid))
        val host = record("host", ref)
        require(host["uuid"] == uuid) { "Host identity changed." }
        val metrics = host.text("metrics").takeIf { it != "OpaqueRef:NULL" && it.isNotBlank() }?.let { record("host_metrics", it) }.orEmpty()
        return hostChoice(host, metrics, resident = false)
    }
    fun hostChoices(authority: String, vmUuid: String): List<HostChoice> {
        val (_, vm) = readVm(authority, vmUuid)
        val resident = vm.text("resident_on").takeIf { vm.text("power_state") != "Halted" }
        val metrics = projected("host_metrics", Projections.HOST_METRICS).records // inventory scale
        val hosts = projected("host", Projections.HOST).records.map { (ref, host) -> hostChoice(host, metrics[host.text("metrics")].orEmpty(), ref == resident) }
        verifyPool(authority)
        return hosts.sortedBy { it.name.lowercase() }
    }
    /**
     * Advisory permissions. Local superuser is pool-admin. Otherwise the session's subject roles are
     * read by name. Missing methods, restricted reads or malformed replies return "unavailable".
     */
    fun permissions(): SessionPermissions = try {
        val superuser = call("session.get_is_local_superuser", listOf(token, token)).let { it == true || it == "true" || it == "1" }
        if (superuser) SessionPermissions(superuser = true) else {
            val subject = callForText("session.get_subject", listOf(token, token))
            if (subject.isBlank() || subject == "OpaqueRef:NULL") SessionPermissions.unavailable("no subject")
            else {
                val roles = call("subject.get_roles", listOf(token, subject)) as? List<*> ?: throw GatewayError("invalid-roles")
                SessionPermissions(roles = roles.map { role ->
                    callForText("role.get_name_label", listOf(token, role as? String ?: throw GatewayError("invalid-role")))
                }.sorted())
            }
        }
    } catch (error: RpcFailure) { SessionPermissions.unavailable(error.code) }
    catch (error: GatewayError) { SessionPermissions.unavailable(error.reason) }
    catch (_: IllegalArgumentException) { SessionPermissions.unavailable("invalid reply") }
    // --- end A2 operations ---
    // --- B2 storage/host ---
    private fun isNull(ref: String) = ref.isBlank() || ref == "OpaqueRef:NULL"
    private fun busy(record: Map<String, Any?>) = (record["current_operations"] as? Map<*, *>)?.isNotEmpty() ?: false
    /** Null only when the server explicitly answers UUID_INVALID. */
    private fun refByUuid(type: String, uuid: String): String? = try { callForText("$type.get_by_uuid", listOf(token, uuid)) }
        catch (error: RpcFailure) { if (error.code == "UUID_INVALID") null else throw error }
    /** Null only when the server explicitly answers HANDLE_INVALID (the object was removed meanwhile). */
    private fun recordOrNull(type: String, ref: String): Map<String, Any?>? = if (isNull(ref)) null else try { record(type, ref) }
        catch (error: RpcFailure) { if (error.code == "HANDLE_INVALID") null else throw error }
    private fun normalVm(vm: Map<String, Any?>) = !vm.flag("is_control_domain") && !vm.flag("is_a_template") && !vm.flag("is_a_snapshot")
    private fun withMemory(snapshot: Map<String, Any?>) = snapshot.text("power_state") == "Suspended" || !isNull(snapshot.text("suspend_VDI"))
    private fun hostLive(host: Map<String, Any?>) = recordOrNull("host_metrics", host.text("metrics"))?.flag("live") == true

    override suspend fun target(authority: String, kind: TargetKind, uuid: String): TargetState {
        verifyPool(authority)
        val ref = refByUuid(kind.xapiClass, uuid) ?: return MissingTarget(authority, kind, uuid, clock())
        val record = record(kind.xapiClass, ref)
        require(record["uuid"] == uuid) { "Object identity changed." }
        return when (kind) {
            TargetKind.VM -> vmTarget(authority, ref, record)
            TargetKind.SNAPSHOT -> snapshotTarget(authority, record)
            TargetKind.VBD -> cdDrive(authority, record)
            TargetKind.HOST -> hostState(authority, ref, record)
            TargetKind.PBD -> pbdState(authority, record)
        }
    }
    private fun vmTarget(authority: String, ref: String, vm: Map<String, Any?>): VmTarget {
        require(normalVm(vm)) { "This object cannot be managed as a VM." }
        // inventory scale: every snapshot of the VM in one filtered read, so snapshot proofs never rest on a cut list
        val read = projectedWhere("VM", XapiQuery.snapshotsOf(ref), Projections.SNAPSHOT)
        val snapshots = read.records.values.filter { it.flag("is_a_snapshot") && it.text("snapshot_of") == ref }.map { snap ->
            SnapshotInfo(snap.text("uuid"), snap.text("name_label"), snap.text("snapshot_time").ifBlank { null }, withMemory(snap),
                snap.strings("allowed_operations").toSet(), busy(snap))
        }.sortedByDescending { it.time.orEmpty() }
        val parent = recordOrNull("VM", vm.text("parent"))?.text("uuid")?.takeIf { it.isNotBlank() }
        val vbds = vm.strings("VBDs").mapNotNull { recordOrNull("VBD", it) }
        val disks = vbds.filter { it.text("type") == "Disk" && !it.flag("empty") }.mapNotNull { recordOrNull("VDI", it.text("VDI"))?.text("uuid") }
        val name = vm.text("name_label")
        val power = vm.text("power_state")
        val drives = vbds.filter { it.text("type") == "CD" }.map { cdDriveFrom(authority, it, vm.text("uuid"), name, power) }.sortedBy { it.device }
        return VmTarget(authority, vm.text("uuid"), name, power, vm.strings("allowed_operations").toSet(), busy(vm), clock(),
            snapshots, !read.complete, parent, disks, drives)
    }
    private fun snapshotTarget(authority: String, snap: Map<String, Any?>): SnapshotTarget {
        require(snap.flag("is_a_snapshot") && !snap.flag("is_control_domain")) { "This object is not a VM snapshot." }
        val live = recordOrNull("VM", snap.text("snapshot_of"))?.takeIf(::normalVm) ?: throw IllegalArgumentException("The snapshot's VM is not available.")
        val liveVbds = live.strings("VBDs").mapNotNull { recordOrNull("VBD", it) }
        val liveAll = liveVbds.filterNot { it.flag("empty") }.mapNotNull { recordOrNull("VDI", it.text("VDI"))?.text("uuid") }.toSet()
        val liveDisks = liveVbds.filter { it.text("type") == "Disk" && !it.flag("empty") }.mapNotNull { recordOrNull("VDI", it.text("VDI"))?.text("uuid") }
        val ownVbds = snap.strings("VBDs").toSet()
        val disks = ownVbds.mapNotNull { ref -> recordOrNull("VBD", ref) }.map { vbd ->
            val empty = vbd.flag("empty")
            val vdi = if (empty) null else recordOrNull("VDI", vbd.text("VDI"))
            SnapshotDisk(vbd.text("uuid"), vbd.text("type"), empty || vdi == null, vdi?.text("uuid")?.takeIf { it.isNotBlank() },
                vdi?.text("name_label").orEmpty(), vdi?.flag("is_a_snapshot") == true,
                onlyThisSnapshot = vdi != null && vdi.strings("VBDs").all { it in ownVbds },
                inLiveVm = vdi != null && vdi.text("uuid") in liveAll)
        }.sortedBy { it.vbdUuid }
        return SnapshotTarget(authority, snap.text("uuid"), snap.text("name_label"), live.text("uuid"), live.text("name_label"),
            snap.text("snapshot_time").ifBlank { null }, withMemory(snap), snap.text("power_state"), snap.strings("allowed_operations").toSet(),
            busy(snap), clock(), disks, live.text("power_state"),
            recordOrNull("VM", live.text("parent"))?.text("uuid")?.takeIf { it.isNotBlank() }, liveDisks, busy(live))
    }
    private fun cdDrive(authority: String, vbd: Map<String, Any?>): CdDrive {
        require(vbd.text("type") == "CD") { "This drive is not a CD drive." }
        val vm = recordOrNull("VM", vbd.text("VM"))?.takeIf(::normalVm) ?: throw IllegalArgumentException("The drive's VM cannot be managed.")
        return cdDriveFrom(authority, vbd, vm.text("uuid"), vm.text("name_label"), vm.text("power_state"))
    }
    private fun cdDriveFrom(authority: String, vbd: Map<String, Any?>, vmUuid: String, vmName: String, power: String): CdDrive {
        val vdi = if (vbd.flag("empty")) null else recordOrNull("VDI", vbd.text("VDI"))
        return CdDrive(authority, vbd.text("uuid"), vbd.text("userdevice"), vmUuid, vmName, power, vbd.flag("empty") || vdi == null,
            vdi?.text("uuid")?.takeIf { it.isNotBlank() }, vdi?.text("name_label"), vbd.strings("allowed_operations").toSet(), busy(vbd), clock())
    }
    private fun hostState(authority: String, ref: String, host: Map<String, Any?>): HostState {
        val master = records("pool").values.single()["master"] == ref
        val boot = (host["other_config"] as? Map<*, *>)?.get("boot_time")?.toString()?.takeIf { it.isNotBlank() }
        // inventory scale: resident VMs and blockers are named in batches, not one record read per VM
        val resident = byRefs("VM", host.strings("resident_VMs"), Projections.VM_NAME).values.filterNot { it.flag("is_control_domain") }
            .map { it.text("name_label").ifBlank { "Unnamed VM" } }.sorted()
        var problem: String? = null
        val blockers = if (resident.isEmpty()) emptyList() else try {
            val map = call("host.get_vms_which_prevent_evacuation", listOf(token, ref)) as? Map<*, *> ?: throw GatewayError("invalid-blockers")
            val names = byRefs("VM", map.keys.map { it.toString() }, Projections.VM_NAME)
            map.entries.map { (vmRef, reasons) ->
                val code = (reasons as? List<*>)?.firstOrNull()?.toString()?.takeIf { it.matches(Regex("[A-Z0-9_]{1,64}")) } ?: "UNKNOWN_REASON"
                EvacuationBlocker(names[vmRef.toString()]?.text("name_label")?.ifBlank { null } ?: "Unnamed VM", code)
            }.sortedBy { it.vmName.lowercase() }
        } catch (error: RpcFailure) {
            if (error.code == "SESSION_AUTHENTICATION_FAILED") throw error
            problem = error.code; null
        } catch (error: GatewayError) { problem = error.reason; null }
        return HostState(authority, host.text("uuid"), host.text("name_label").ifBlank { "Unnamed host" }, host.flag("enabled"), hostLive(host),
            master, boot, resident, host.strings("allowed_operations").toSet(), busy(host), clock(), blockers, problem)
    }
    private fun pbdState(authority: String, pbd: Map<String, Any?>): PbdState {
        val sr = record("SR", pbd.text("SR"))
        val host = record("host", pbd.text("host"))
        return PbdState(authority, pbd.text("uuid"), sr.text("uuid"), sr.text("name_label").ifBlank { "Storage repository" },
            host.text("uuid"), host.text("name_label").ifBlank { "Unnamed host" }, hostLive(host), pbd.flag("currently_attached"), busy(sr), clock())
    }
    fun poolPbds(authority: String): List<PbdState> {
        verifyPool(authority)
        // inventory scale: projected reads and no cut; every PBD of the pool is returned
        val srs = projected("SR", Projections.SR).records; val hosts = projected("host", Projections.HOST).records
        val metrics = projected("host_metrics", Projections.HOST_METRICS).records
        val now = clock()
        return projected("PBD", Projections.PBD).records.values.mapNotNull { pbd ->
            val sr = srs[pbd.text("SR")] ?: return@mapNotNull null
            val host = hosts[pbd.text("host")] ?: return@mapNotNull null
            PbdState(authority, pbd.text("uuid"), sr.text("uuid"), sr.text("name_label").ifBlank { "Storage repository" }, host.text("uuid"),
                host.text("name_label").ifBlank { "Unnamed host" }, metrics[host.text("metrics")]?.flag("live") == true,
                pbd.flag("currently_attached"), busy(sr), now)
        }.sortedWith(compareBy({ it.srName.lowercase() }, { it.hostName.lowercase() }))
    }
    fun isoCatalog(authority: String): IsoCatalog {
        verifyPool(authority)
        // inventory scale: only the VDIs of ISO repositories, never every disk and snapshot disk of the pool
        val isoSrs = projectedWhere("SR", XapiQuery.eq("content_type", "iso"), Projections.SR).records.filterValues { it.text("content_type") == "iso" }
        val isos = whereAny("VDI", "SR", isoSrs.keys, Projections.ISO).values
            .filter { it.text("SR") in isoSrs && !it.flag("is_a_snapshot") && !it.flag("missing") }.map { vdi ->
            IsoChoice(vdi.text("uuid"), vdi.text("name_label").ifBlank { "Unnamed ISO" }, isoSrs.getValue(vdi.text("SR")).text("name_label"))
        }.filter { it.uuid.isNotBlank() }.sortedBy { it.name.lowercase() }
        return IsoCatalog(isos.take(MAX_ISOS), isos.size > MAX_ISOS, isos.size)
    }
    override suspend fun iso(authority: String, uuid: String): IsoChoice {
        verifyPool(authority)
        val ref = refByUuid("VDI", uuid) ?: throw IllegalArgumentException("The ISO no longer exists.")
        val vdi = record("VDI", ref)
        val sr = record("SR", vdi.text("SR"))
        require(vdi["uuid"] == uuid && sr.text("content_type") == "iso" && !vdi.flag("missing")) { "This image is not an available ISO." }
        return IsoChoice(uuid, vdi.text("name_label").ifBlank { "Unnamed ISO" }, sr.text("name_label"))
    }
    /** One async call per kind. Identity is checked again at send time; a failed check sends nothing ([NotSent]). */
    override suspend fun submitTarget(authority: String, action: TargetAction, target: String, argument: String?): String {
        val (primary, alternate) = beforeSend(action.targetKind.label) { // journaled operation hardening
            val ref = refByUuid(action.targetKind.xapiClass, target) ?: throw NotSent("The ${action.targetKind.label} no longer exists.")
            val record = record(action.targetKind.xapiClass, ref)
            if (record["uuid"] != target) throw NotSent("Object identity changed.")
            when (action.targetKind) {
                TargetKind.VM -> if (!normalVm(record)) throw NotSent("This object cannot be managed as a VM.")
                TargetKind.SNAPSHOT -> if (!record.flag("is_a_snapshot")) throw NotSent("This object is not a VM snapshot.")
                TargetKind.VBD -> if (record.text("type") != "CD") throw NotSent("This drive is not a CD drive.")
                TargetKind.HOST, TargetKind.PBD -> Unit
            }
            val value = when (action) {
                TargetAction.CD_INSERT -> refByUuid("VDI", requireNotNull(argument)) ?: throw NotSent("The ISO no longer exists.")
                else -> argument
            }
            action.xapiArguments(ref, value) to action.alternateArguments(ref, value)
        }
        return XapiArity.send(primary, alternate) { args ->
            callForText(action.xapiMethod, listOf(token) + args)
        }
    }
    override suspend fun absent(authority: String, step: OperationStep): Boolean {
        verifyPool(authority)
        return refByUuid(step.kind.xapiClass, step.uuid) == null
    }
    /** Destroys only snapshot objects: a snapshot VDI used by snapshots alone, or a snapshot VM record. */
    override suspend fun submitStep(authority: String, step: OperationStep): String {
        val ref = beforeSend("“${step.name}”") { // journaled operation hardening
            val ref = refByUuid(step.kind.xapiClass, step.uuid) ?: throw NotSent("“${step.name}” no longer exists.")
            val record = record(step.kind.xapiClass, ref)
            if (record["uuid"] != step.uuid) throw NotSent("Object identity changed.")
            when (step.kind) {
                StepKind.VDI_DESTROY -> {
                    if (!record.flag("is_a_snapshot")) throw NotSent("“${step.name}” is not a snapshot disk.")
                    val users = record.strings("VBDs").mapNotNull { recordOrNull("VBD", it) }.map { recordOrNull("VM", it.text("VM")) }
                    if (users.any { it == null || !it.flag("is_a_snapshot") }) throw NotSent("“${step.name}” is used by a VM that is not a snapshot.")
                }
                StepKind.VM_DESTROY -> if (!record.flag("is_a_snapshot") || record.flag("is_control_domain")) throw NotSent("Refusing to destroy a VM that is not a snapshot.")
            }
            ref
        }
        return callForText(step.kind.xapiMethod, listOf(token, ref))
    }
    // --- end B2 storage/host ---
    // --- journaled operation hardening ---
    /**
     * Runs the read-only checks that precede exactly one write call. Nothing in [checks] writes, so any failure
     * here provably happens before the write request is built or put on the wire, and is reported as [NotSent].
     * The write call itself always stays outside, so its failures remain uncertain.
     */
    private inline fun <T> beforeSend(subject: String, checks: () -> T): T = try { checks() }
        catch (error: NotSent) { throw error }
        catch (error: CancellationException) { throw error }
        catch (error: IllegalArgumentException) {
            throw NotSent(error.message?.takeIf { it.isNotBlank() && it != "Failed requirement." } ?: "The $subject changed before sending.")
        } catch (error: Exception) {
            val reason = when (error) {
                is RpcFailure -> error.code; is GatewayError -> error.reason; is java.io.IOException -> "network error"; else -> "unexpected reply"
            }
            throw NotSent("Could not read the $subject again just before sending ($reason).")
        }
    override suspend fun verifyPoolIdentity(authority: String) = verifyPool(authority)
    /** Pool identity, then the VM record, its metrics (boot marker) and resident host. No disk, network or guest reads. */
    override suspend fun vmState(authority: String, uuid: String): VmDetails? {
        verifyPool(authority)
        val ref = refByUuid("VM", uuid) ?: return null
        val vm = record("VM", ref)
        require(vm["uuid"] == uuid && normalVm(vm)) { "This object cannot be managed as a VM." }
        val power = vm.text("power_state")
        val boot = recordOrNull("VM_metrics", vm.text("metrics"))?.text("start_time")?.takeIf { it.isNotBlank() }
        val resident = vm.text("resident_on").takeIf { power != "Halted" && !isNull(it) }?.let { record("host", it) }
        return VmDetails(authority, uuid, vm.text("name_label"), vm.text("name_description"), power, resident?.text("name_label"),
            vm.number("VCPUs_max").toInt(), vm.number("memory_static_max"), emptyList(), emptyList(), false, null, emptyList(),
            boot, vm.strings("allowed_operations").toSet(), busy(vm), clock(),
            residentHostUuid = resident?.text("uuid")?.takeIf { it.isNotBlank() })
    }
    // --- end journaled operation hardening ---
    // --- VM settings ---
    /**
     * The pool record (identity, HA, pool auto power-on), then the VM record, its metrics (live vCPU count), its guest
     * metrics (vCPU hot-plug report) and host memory. Only `*.get_*` reads. Null only on UUID_INVALID for the VM.
     */
    override suspend fun settings(authority: String, uuid: String): VmSettings? {
        val pool = poolRecord(authority)
        val ref = refByUuid("VM", uuid) ?: return null
        val vm = record("VM", ref)
        require(vm["uuid"] == uuid && normalVm(vm)) { "This object cannot be managed as a VM." }
        fun count(key: String) = vm.text(key).toLongOrNull() ?: throw GatewayError("invalid-record")
        fun stringMap(value: Any?) = (value as? Map<*, *>)?.entries?.associate { (key, item) -> key.toString() to item?.toString().orEmpty() }.orEmpty()
        val power = vm.text("power_state")
        val metrics = recordOrNull("VM_metrics", vm.text("metrics"))
        val guest = recordOrNull("VM_guest_metrics", vm.text("guest_metrics"))
        val resident = vm.text("resident_on").takeIf { power != "Halted" && !isNull(it) }
        val free = if (resident != null) recordOrNull("host", resident)?.let { recordOrNull("host_metrics", it.text("metrics")) }?.text("memory_free")?.toLongOrNull()
            else records("host_metrics").values.filter { it.flag("live") }.mapNotNull { it.text("memory_free").toLongOrNull() }.maxOrNull()
        val hotplug = stringMap(guest?.get("other"))["feature-vcpu-hotplug"]?.let { it == "1" }
        return VmSettings(authority, uuid, vm.text("name_label"), vm.text("name_description"), vm.strings("tags"), power,
            vm.strings("allowed_operations").toSet(), busy(vm), count("VCPUs_max"), count("VCPUs_at_startup"),
            metrics?.text("VCPUs_number")?.toLongOrNull()?.takeIf { power == "Running" },
            count("memory_static_min"), count("memory_dynamic_min"), count("memory_dynamic_max"), count("memory_static_max"),
            stringMap(vm["other_config"])["auto_poweron"], stringMap(pool["other_config"])["auto_poweron"] == "true",
            vm.text("HVM_boot_policy").isNotBlank(), VmInfoMapper.firmware(vm), stringMap(vm["HVM_boot_params"]),
            pool.flag("ha_enabled"), vm.text("ha_restart_priority"), free, resident != null, hotplug, clock())
    }
    /** One setter, once. Pool and VM identity are read first ([NotSent] on any failure); the call itself is a write. */
    override suspend fun submitSetting(authority: String, uuid: String, step: SettingStep) {
        val ref = beforeSend("VM") {
            verifyPool(authority)
            val ref = refByUuid("VM", uuid) ?: throw NotSent("The VM no longer exists.")
            val vm = record("VM", ref)
            if (vm["uuid"] != uuid || !normalVm(vm)) throw NotSent("This object cannot be managed as a VM.")
            ref
        }
        call(step.kind.xapiMethod, listOf(token) + SettingsRules.xapiArguments(step, ref))
    }
    // --- end VM settings ---
    override suspend fun taskUuid(reference: String): String = callForText("task.get_uuid", listOf(token, reference))
    override suspend fun taskReference(uuid: String): String = callForText("task.get_by_uuid", listOf(token, uuid))
    override suspend fun observeTask(reference: String): TaskObservation {
        val task = record("task", reference)
        // --- A2 operations --- A failed task's first error_info element is shown only if it is a well-formed code.
        val code = (task["error_info"] as? List<*>)?.firstOrNull()?.toString()?.takeIf { it.matches(Regex("[A-Z0-9_]{1,64}")) }
        return TaskObservation(task.text("status"), task["progress"]?.toString()?.toDoubleOrNull()?.takeIf { it.isFinite() } ?: 0.0, code)
    }

    // --- A4 alerts/events ---
    /**
     * Full read: `message.get_all_records`. A very large message store can exceed the parser's size/complexity
     * limits; then only the recent window is requested and the result is marked truncated. Incremental read:
     * `message.get_since`. The pool identity is verified before and after, as for every other read.
     */
    fun alerts(authority: String, sinceMillis: Long?): AlertFetch {
        verifyPool(authority)
        val window = messageWindow(sinceMillis) // inventory scale: shared with pool activity
        verifyPool(authority)
        return AlertFetch(authority, clock(), AlertMapper.parse(window.read.records), window.full, window.truncated)
    }
    /**
     * `message.destroy` for each target, each followed by `message.get_record`: HANDLE_INVALID is the only proof
     * of removal. An explicit XAPI refusal is reported with its code; a permission refusal stops the batch.
     * An ambiguous failure (transport error, RPC fault) is never assumed to have worked; the read-back decides.
     */
    fun dismissAlerts(authority: String, targets: List<AlertTarget>): DismissOutcome {
        require(targets.size in 1..AlertDismissal.MAX_BATCH) { "Choose between 1 and ${AlertDismissal.MAX_BATCH} alerts." }
        require(targets.map { it.ref }.distinct().size == targets.size) { "Duplicate alert." }
        verifyPool(authority)
        val results = ArrayList<DismissResult>(targets.size)
        var denied: DismissResult? = null
        for (target in targets) {
            val blocked = denied
            if (blocked != null) { results += DismissResult(target.ref, DismissStatus.NOT_SENT, blocked.code, blocked.permission); continue }
            var refusal: RpcFailure? = null
            try { call("message.destroy", listOf(token, target.ref)) }
            catch (error: RpcFailure) { if (error.code != "HANDLE_INVALID" && error.explicitRejection) refusal = error }
            catch (_: Exception) { /* ambiguous: the read-back below decides */ }
            if (refusal != null) {
                val result = DismissResult(target.ref, DismissStatus.REFUSED, refusal.code,
                    refusal.details.firstOrNull()?.takeIf { it.matches(Regex("[A-Za-z0-9_.:-]{1,80}")) })
                if (result.code == "RBAC_PERMISSION_DENIED" || result.code == "PERMISSION_DENIED") denied = result
                results += result
                continue
            }
            val status = try { call("message.get_record", listOf(token, target.ref)); DismissStatus.STILL_PRESENT }
                catch (error: RpcFailure) { if (error.code == "HANDLE_INVALID") DismissStatus.REMOVED else DismissStatus.UNKNOWN }
                catch (_: Exception) { DismissStatus.UNKNOWN }
            results += DismissResult(target.ref, status)
        }
        return DismissOutcome(results)
    }
    /** Logs in and checks the pool; the caller keeps this session open for the event loop. */
    fun startEvents(authority: String) { login(); verifyPool(authority) }
    fun eventToken(marginMillis: Long): String {
        val timeout = 5.0
        return EventParser.batch(call("event.from", listOf(token, listOf("pool"), "", timeout),
            EventPolicy.readTimeoutMillis(timeout, marginMillis).toInt())).token
    }
    fun eventFrom(eventToken: String, timeoutSeconds: Double, marginMillis: Long, abort: CallAbort): EventBatch =
        EventParser.batch(call("event.from", listOf(token, EventPolicy.CLASSES, eventToken, timeoutSeconds),
            EventPolicy.readTimeoutMillis(timeoutSeconds, marginMillis).toInt(), abort))
    // --- end A4 ---

    // --- B3 background/summary --- read-only: session, *.get_* and message.get_since, plus one explicit plugin check
    private fun singlePool(pools: Map<String, Map<String, Any?>> = records("pool")): Pair<String, Map<String, Any?>> {
        require(pools.size == 1) { "Direct XAPI must identify exactly one pool." }
        val pool = pools.values.single()
        return pool.text("uuid").also { require(it.isNotBlank()) { "Missing pool identity." } } to pool
    }
    /**
     * The server's clock from `host.get_servertime` on the pool master, paired with the phone's clock halfway through the call.
     * Null when the server does not answer it (the read is optional: only a first check or another pool needs it).
     */
    private fun serverClock(master: String): ServerClock? {
        val ref = liveRef(master) ?: return null
        val before = clock()
        val text = optionalText("host.get_servertime", listOf(token, ref)) ?: return null
        val after = clock()
        return ActivityMapper.time(text)?.let { ServerClock(it, before + (after - before) / 2) }
    }
    /** Pool, the server's clock, then A4's incremental alert read (pool checked before and after), then a bounded set of object names. */
    fun backgroundAlerts(since: BackgroundSince, wanted: (AlertMessage) -> Boolean): BackgroundAlertFetch {
        val (authority, pool) = singlePool()
        val serverClock = serverClock(pool.text("master"))
        val sinceMillis = since.since(authority, serverClock) ?: throw ServerTimeUnavailable()
        val fetch = alerts(authority, sinceMillis)
        val names = LinkedHashMap<String, String>()
        fetch.messages.filter(wanted).map { it.objectKind to it }.distinctBy { BackgroundAlerts.objectKey(it.second) }
            .filter { (kind, message) -> kind != AlertObjectKind.POOL && kind != AlertObjectKind.OTHER && message.objectUuid.matches(Regex("[A-Za-z0-9-]{1,64}")) }
            .take(BackgroundAlerts.MAX_NAMED_OBJECTS).forEach { (kind, message) ->
                val type = when (kind) { AlertObjectKind.VM -> "VM"; AlertObjectKind.HOST -> "host"; else -> "SR" }
                // A missing or restricted object only loses its name; the alert is still notified.
                val ref = optionalText("$type.get_by_uuid", listOf(token, message.objectUuid)) ?: return@forEach
                optionalText("$type.get_name_label", listOf(token, ref))?.let { names[BackgroundAlerts.objectKey(message)] = it.take(80) }
            }
        return BackgroundAlertFetch(authority, pool.text("name_label").ifBlank { "XCP-ng pool" }, fetch.messages, names, serverClock)
    }
    /** Pool, hosts, host metrics, non-template VMs and the unread urgent alerts count, in one session. */
    fun poolSummary(lastSeen: Long?): PoolSummaryRead {
        val pools = records("pool")
        val (authority, _) = singlePool(pools)
        // inventory scale: projected hosts, and the VMs are counted as they stream (nothing per VM is kept)
        val hosts = projected("host", Projections.HOST).records
        val metrics = projected("host_metrics", Projections.HOST_METRICS).records
        var vmsTotal = 0
        var vmsRunning = 0
        streamCall("VM.get_all_records_where", listOf(token, PoolSummaries.VM_FILTER)) { input ->
            XmlRpcRecordStream(input, limits).read(Projections.GUEST) { _, vm, _ ->
                if (!vm.flag("is_a_template") && !vm.flag("is_control_domain") && !vm.flag("is_a_snapshot")) {
                    vmsTotal++; if (vm.text("power_state") == "Running") vmsRunning++
                }
            }
        }
        var partial = false
        val unread = try {
            val fetch = alerts(authority, lastSeen)
            partial = fetch.truncated
            PoolSummaries.unread(fetch.messages, lastSeen)
        } catch (error: RpcFailure) { if (error.code == "SESSION_AUTHENTICATION_FAILED") throw error else null }
        catch (_: GatewayError) { null }
        return PoolSummaries.counts(pools, hosts, metrics, emptyMap(), unread, partial, clock()).copy(vmsRunning = vmsRunning, vmsTotal = vmsTotal)
    }
    /**
     * Read-only update availability. The plugin call can take a while (it queries the host's package repositories),
     * so it gets a longer read timeout. The host is resolved by UUID inside the verified pool.
     */
    fun checkHostUpdates(authority: String, hostUuid: String): HostUpdateResult {
        verifyPool(authority)
        val ref = callForText("host.get_by_uuid", listOf(token, hostUuid))
        require(record("host", ref)["uuid"] == hostUuid) { "Host identity changed." }
        val raw = call("host.call_plugin", listOf(token, ref, HostUpdates.PLUGIN, HostUpdates.FUNCTION, emptyMap<String, String>()),
            readTimeoutMillis = HostUpdates.READ_TIMEOUT_MILLIS)
        verifyPool(authority)
        return HostUpdates.parse(raw)
    }
    // --- end B3 ---
}

// --- A4 alerts/events ---
/** Lets cancellation disconnect an in-flight HTTP call that is blocked in a read. */
internal class CallAbort {
    @Volatile private var connection: HttpsURLConnection? = null
    @Volatile private var aborted = false
    fun attach(value: HttpsURLConnection) {
        connection = value
        if (aborted) { value.disconnect(); throw GatewayError("aborted") }
    }
    fun abort() { aborted = true; connection?.disconnect() }
    // --- session reuse --- a finished connection may be pooled, so a later abort must not touch it
    val isAborted get() = aborted
    fun detach(value: HttpsURLConnection) { if (connection === value) connection = null }
    // --- end session reuse ---
}

private class XapiEventSession(private val connection: XapiSession, private val marginMillis: Long) : EventSession {
    private val closed = AtomicBoolean(false)
    /**
     * Runs a blocking call on the IO dispatcher; cancelling the caller disconnects it instead of waiting out the poll.
     * The disconnect surfaces as a SocketException, which would otherwise replace the cancellation and fail the
     * parent job, so a failure after cancellation is rethrown as the cancellation it is.
     */
    private suspend fun <T> abortable(block: (CallAbort) -> T): T = coroutineScope {
        val abort = CallAbort()
        val guard = launch(start = CoroutineStart.UNDISPATCHED) { try { awaitCancellation() } finally { abort.abort() } }
        try { withContext(Dispatchers.IO) { block(abort) } }
        catch (error: Exception) { currentCoroutineContext().ensureActive(); throw error }
        finally { guard.cancel() }
    }
    override suspend fun begin(): String = abortable { connection.eventToken(marginMillis) }
    override suspend fun next(token: String, timeoutSeconds: Double): EventBatch =
        abortable { abort -> connection.eventFrom(token, timeoutSeconds, marginMillis, abort) }
    override suspend fun close() { if (closed.compareAndSet(false, true)) withContext(Dispatchers.IO) { connection.close() } }
}
// --- end A4 ---

// --- B2 storage/host --- read bounds
// inventory scale: snapshots, blockers and PBDs are no longer cut here (see METEOR-LIVE-FINDINGS.md, finding 3)
private const val MAX_ISOS = 200
// --- end B2 storage/host ---
private fun Map<String, Any?>.text(key: String) = this[key]?.toString().orEmpty()
private fun Map<String, Any?>.number(key: String) = text(key).toLongOrNull() ?: 0L
private fun Map<String, Any?>.flag(key: String) = this[key] == true || text(key) == "true" || text(key) == "1"
private fun Map<String, Any?>.strings(key: String) = (this[key] as? List<*>)?.map { it as? String ?: throw GatewayError("invalid-list") }.orEmpty()

