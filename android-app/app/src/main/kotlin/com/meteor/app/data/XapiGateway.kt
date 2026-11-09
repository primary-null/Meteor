package com.meteor.app.data

import android.annotation.SuppressLint
import com.meteor.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
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
}

class XapiGateway(private val clock: () -> Long = System::currentTimeMillis) : MeteorGateway {
    private suspend fun <T> session(profile: Profile, action: suspend XapiSession.() -> T): T = withContext(Dispatchers.IO) {
        val connection = XapiSession(profile, clock)
        try { connection.login(); connection.action() } finally { connection.close() }
    }
    override suspend fun inventory(profile: Profile): Inventory = session(profile) { inventory() }
    override suspend fun details(profile: Profile, authority: String, uuid: String): VmDetails = session(profile) { details(authority, uuid) }
    override suspend fun pollDetails(profile: Profile, authority: String, uuid: String, previous: VmDetails): VmDetails =
        session(profile) { readDetails(authority, uuid, previous) }
    override suspend fun metrics(profile: Profile, target: MetricTarget): MetricSnapshot = session(profile) { metrics(target) }
    override suspend fun activity(profile: Profile, authority: String): PoolActivity = session(profile) { activity(authority) }
    override suspend fun <T> powerConnection(profile: Profile, action: suspend PowerConnection.() -> T): T = session(profile) { action(this) }
}

private class XapiSession(private val profile: Profile, private val clock: () -> Long) : PowerConnection {
    private val endpoint = Endpoints.normalize(profile.endpoint)
    private val tls = ServerTrust.context(profile.certificateSha256)
    private var session: String? = null
    init { require(profile.certificateSha256.matches(Regex("[0-9a-f]{64}"))) }
    private val token get() = requireNotNull(session)
    private fun call(method: String, args: List<Any?>): Any? {
        val url = URL(endpoint)
        val connection = url.openConnection() as HttpsURLConnection
        try {
            connection.sslSocketFactory = tls.socketFactory
            connection.hostnameVerifier = javax.net.ssl.HostnameVerifier { host, session ->
                // Exact user-approved leaf pin identifies private/self-signed endpoints.
                host == url.host &&
                    (session.peerCertificates.firstOrNull() as? X509Certificate)?.let {
                        ServerTrust.fingerprint(it) == profile.certificateSha256
                    } == true
            }
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 8_000
            connection.readTimeout = 12_000
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "text/xml; charset=utf-8")
            connection.setRequestProperty("User-Agent", "Meteor-Spark/${BuildConfig.VERSION_NAME}")
            val bytes = XmlRpc.request(method, args)
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }
            if (connection.responseCode in 300..399) throw GatewayError("redirect-refused")
            if (connection.responseCode != 200) throw GatewayError("http-error")
            val output = ByteArrayOutputStream()
            connection.inputStream.use { stream ->
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val count = stream.read(buffer)
                    if (count == -1) break
                    if (output.size() + count > 8 * 1024 * 1024) throw GatewayError("response-limit")
                    output.write(buffer, 0, count)
                }
            }
            return XmlRpc.response(output.toString(Charsets.UTF_8.name()))
        } finally {
            connection.disconnect()
        }
    }
    private fun callForText(method: String, args: List<Any?>): String =
        call(method, args) as? String ?: throw GatewayError("invalid-reference")
    fun login() {
        session = call("session.login_with_password",
            listOf(profile.username, profile.password, "1.0", "MeteorAndroidSpark")) as? String
            ?: throw GatewayError("session-missing")
    }
    fun close() { session?.let { runCatching { call("session.logout", listOf(it)) } }; session = null }
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
    fun inventory(): Inventory = InventoryMapper.normalize(records("pool"), records("host"), records("host_metrics"),
        records("VM"), records("SR"), System.currentTimeMillis())
    fun metrics(target: MetricTarget): MetricSnapshot {
        val type = if (target.kind == MetricKind.VM) "VM" else "host"
        val reference = if (target.kind == MetricKind.VM) {
            val (reference, vm) = readVm(target.authority, target.uuid)
            if (vm.text("power_state") != "Running") return MetricSnapshot(target, clock(), emptyList(), 0,
                "The VM is not running. Live metrics are unavailable.")
            reference
        } else {
            verifyPool(target.authority)
            callForText("host.get_by_uuid", listOf(token, target.uuid)).also {
                require(record("host", it)["uuid"] == target.uuid) { "Host identity changed." }
            }
        }
        val sources = call("$type.get_data_sources", listOf(token, reference)) as? List<*> ?: throw GatewayError("invalid-metrics")
        val snapshot = MetricMapper.snapshot(target, sources.map(::map), clock())
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
        val alerts = section { ActivityMapper.alerts(records("message")) }
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
        val host = vm.text("resident_on").takeIf { power != "Halted" && it != "OpaqueRef:NULL" && it.isNotBlank() }
            ?.let { record("host", it).text("name_label") }
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
            cached?.topologyObservedAt ?: now, topology)
    }
    override suspend fun submit(authority: String, uuid: String, action: PowerAction): String {
        val ref = vmRef(authority, uuid)
        val args: List<Any?> = if (action == PowerAction.START) listOf(token, ref, false, false) else listOf(token, ref)
        return callForText("Async.VM.${action.operation}", args)
    }
    override suspend fun taskUuid(reference: String): String = callForText("task.get_uuid", listOf(token, reference))
    override suspend fun taskReference(uuid: String): String = callForText("task.get_by_uuid", listOf(token, uuid))
    override suspend fun observeTask(reference: String): TaskObservation {
        val task = record("task", reference)
        return TaskObservation(task.text("status"), task["progress"]?.toString()?.toDoubleOrNull() ?: 0.0)
    }
}

private fun Map<String, Any?>.text(key: String) = this[key]?.toString().orEmpty()
private fun Map<String, Any?>.number(key: String) = text(key).toLongOrNull() ?: 0L
private fun Map<String, Any?>.flag(key: String) = this[key] == true || text(key) == "true" || text(key) == "1"
private fun Map<String, Any?>.strings(key: String) = (this[key] as? List<*>)?.map { it as? String ?: throw GatewayError("invalid-list") }.orEmpty()

