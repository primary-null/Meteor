package com.meteor.app.data

import com.sun.net.httpserver.HttpExchange
import java.io.ByteArrayInputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.GZIPInputStream

/**
 * Sanitized answers recorded from a real XCP-ng 8.3.0 host (see `live-xcpng-8.3/README.md`). Every UUID, reference,
 * address and name in them is a deterministic fake; structure, order, types and spelling are the server's own.
 */
object Live83 {
    private const val DIR = "live-xcpng-8.3/"

    fun bytes(name: String): ByteArray = (Live83::class.java.classLoader.getResourceAsStream(DIR + name)
        ?: error("missing fixture $name")).use { it.readBytes() }
    fun exists(name: String) = Live83::class.java.classLoader.getResource(DIR + name) != null
    fun text(name: String) = String(bytes(name), Charsets.UTF_8)
    fun gunzip(name: String): ByteArray = GZIPInputStream(ByteArrayInputStream(bytes(name))).use { it.readBytes() }

    @Suppress("UNCHECKED_CAST")
    val index: List<Map<String, Any?>> by lazy { (JsonLite.parse(text("index.json")) as Map<String, Any?>)["entries"] as List<Map<String, Any?>> }
    fun entry(name: String): Map<String, Any?> = index.single { it["name"] == name }
    fun entries(prefix: String) = index.filter { (it["name"] as String).startsWith(prefix) }

    fun response(name: String) = text("$name.response.xml")
    fun requestBody(name: String) = bytes("$name.request.xml")
    /** The answer as the app's own XML-RPC reader sees it. */
    fun value(name: String): Any? = XmlRpc.response(response(name))

    @Suppress("UNCHECKED_CAST")
    fun records(cls: String): Map<String, Map<String, Any?>> =
        (value("$cls.get_all_records") as Map<String, Any?>).mapValues { it.value as Map<String, Any?> }

    fun guest(): Map<String, Any?> = records("VM").values.single { it["is_a_template"] != true && it["is_control_domain"] != true && it["is_a_snapshot"] != true }
    fun dom0(): Map<String, Any?> = records("VM").values.single { it["is_control_domain"] == true }
    fun host(): Map<String, Any?> = records("host").values.single()
    fun pool(): Map<String, Any?> = records("pool").values.single()
    fun session(): String = Regex("<string>(OpaqueRef:[^<]+)</string>").find(String(requestBody("VM.get_all_records")))!!.groupValues[1]

    /** Splits a `*.get_all_records` answer into one `<value>…</value>` per reference, byte for byte. */
    fun splitRecords(xml: String): Map<String, String> {
        val marker = Regex("<member><name>([^<]*)</name>|<struct>|</struct>")
        var depth = 0
        var close = -1
        val starts = ArrayList<Triple<String, Int, Int>>()
        for (match in marker.findAll(xml)) when (match.value) {
            "<struct>" -> depth++
            "</struct>" -> { depth--; if (depth == 1 && close < 0 && starts.isNotEmpty()) close = match.range.first }
            else -> if (depth == 2) starts += Triple(match.groupValues[1], match.range.last + 1, match.range.first)
        }
        return starts.mapIndexed { i, (ref, valueStart, _) ->
            val end = if (i + 1 < starts.size) starts[i + 1].third else close
            ref to xml.substring(valueStart, xml.lastIndexOf("</member>", end))
        }.toMap()
    }
}

/**
 * A loopback XAPI that answers from the recorded fixtures: records by class (so `get_record` and `get_by_uuid` work for
 * every recorded object), the recorded `event.from` batches in order, and `/rrd_updates` by range. It allows reads only.
 */
class Live83Server : AutoCloseable {
    val fixture = TlsFixture()
    val calls = CopyOnWriteArrayList<String>()
    val rrdQueries = CopyOnWriteArrayList<String>()
    var eventScript = mutableListOf("event.from.begin", "event.from.backlog", "event.from.quiet-1")
    /** The recorded answer for the app's own history request: since the archive fix every range gets the step it asks for. */
    var rrdFor: (host: Boolean, label: String) -> String = { host, label -> "rrd-fixed-${if (host) "host" else "vm"}-$label-r1" }
    /** `*.query_data_source` calls in order, as `VM cpu_usage`; the live view reads each current value this way. */
    val queried = CopyOnWriteArrayList<String>()

    private val recordXml = HashMap<String, Map<String, String>>()
    private fun records(cls: String) = recordXml.getOrPut(cls) { Live83.splitRecords(Live83.response("$cls.get_all_records")) }
    private fun failureXml(code: String) = "<?xml version=\"1.0\"?><methodResponse><params><param><value><struct><member><name>Status</name>" +
        "<value>Failure</value></member><member><name>ErrorDescription</name><value><array><data><value>$code</value></data></array></value></member></struct></value></param></params></methodResponse>"
    private fun success(valueXml: String) = "<?xml version=\"1.0\"?><methodResponse><params><param><value><struct><member><name>Status</name>" +
        "<value>Success</value></member><member><name>Value</name>$valueXml</member></struct></value></param></params></methodResponse>"

    init {
        fixture.server.createContext("/") { exchange ->
            try {
                if (exchange.requestMethod == "GET") get(exchange) else post(exchange)
            } catch (error: Throwable) {
                runCatching { exchange.sendResponseHeaders(500, -1) }
            } finally { exchange.close() }
        }
        fixture.start()
    }
    val profile get() = fixture.profile()

    private fun send(exchange: HttpExchange, body: ByteArray, type: String) {
        exchange.responseHeaders.add("Content-Type", type)
        exchange.sendResponseHeaders(200, body.size.toLong())
        exchange.responseBody.write(body)
    }

    private fun get(exchange: HttpExchange) {
        val query = exchange.requestURI.rawQuery.orEmpty()
        calls += "GET ${exchange.requestURI.path}"
        rrdQueries += query
        val params = query.split('&').associate { it.substringBefore('=') to it.substringAfter('=', "") }
        val label = when (params["interval"]) { "5" -> "10m"; "60" -> "2h"; else -> "1w" }
        send(exchange, Live83.bytes(rrdFor(params["host"] == "true", label) + ".response.json"), "application/json")
    }

    private fun post(exchange: HttpExchange) {
        val body = String(exchange.requestBody.readBytes(), Charsets.UTF_8)
        val method = Regex("<methodName>([^<]+)</methodName>").find(body)!!.groupValues[1]
        calls += method
        val strings = Regex("<param><value><string>([^<]*)</string></value></param>").findAll(body).map { it.groupValues[1] }.toList()
        val cls = method.substringBefore('.')
        val xml = when {
            method == "session.login_with_password" -> Live83.response("login")
            method == "session.logout" -> Live83.response("logout")
            method.endsWith(".get_all_records") -> Live83.response(method)
            // inventory scale: filtered reads are answered from the recorded records, byte for byte, as the server filters them
            method.endsWith(".get_all_records_where") -> {
                val expression = XapiWhere.unescape(strings.getOrNull(1).orEmpty())
                val matching = runCatching { XapiWhere.filter(Live83.records(cls), expression).keys }.getOrNull()
                if (matching == null) failureXml("INVALID_VALUE")
                else success("<value><struct>" + matching.joinToString("") { "<member><name>$it</name>${records(cls).getValue(it)}</member>" } + "</struct></value>")
            }
            method.endsWith(".get_by_uuid") -> {
                val uuid = strings.getOrNull(1).orEmpty()
                val ref = records(cls).entries.firstOrNull { Regex("<name>uuid</name><value>([^<]*)</value>").find(it.value)?.groupValues?.get(1) == uuid }?.key
                if (ref == null) Live83.response("error-uuid-invalid") else success("<value>$ref</value>")
            }
            method.endsWith(".get_record") && cls in setOf("pool", "host", "host_metrics", "VM", "VM_metrics", "VM_guest_metrics", "SR", "PBD", "VBD", "VDI", "VIF", "PIF", "PIF_metrics", "network", "task", "console") ->
                records(cls)[strings.getOrNull(1).orEmpty()]?.let { success(it) } ?: Live83.response("error-handle-invalid")
            method == "event.from" -> Live83.response(if (eventScript.isEmpty()) "event.from.quiet-1" else eventScript.removeAt(0))
            method == "message.get_since" -> Live83.response("message.get_since.30d")
            method == "message.get_record" -> Live83.response("error-message-handle")
            method in setOf("host.get_servertime", "VM.get_data_sources", "host.get_data_sources", "VM.get_consoles", "console.get_record",
                "session.get_is_local_superuser", "session.get_subject", "host.get_vms_which_prevent_evacuation") -> Live83.response(method)
            method == "host.call_plugin" -> Live83.response("host.call_plugin.check_update")
            method == "VM.query_data_source" || method == "host.query_data_source" -> {
                val name = strings.getOrNull(2).orEmpty()
                queried += "$cls $name"
                // A source nobody recorded is answered the way the real server answered an unknown one.
                if (Live83.exists("query-$cls-$name.response.xml")) Live83.response("query-$cls-$name") else Live83.response("query-VM-no-such-source")
            }
            else -> Live83.response("error-method-unknown")
        }
        send(exchange, xml.toByteArray(Charsets.UTF_8), "text/xml")
    }

    /** Nothing in this class may ever send a write: only session calls, reads (getters, `event.from`, `query_data_source`) and the one update check. */
    fun assertReadOnly() {
        val bad = calls.filter { call ->
            !(call.startsWith("GET ") || call.startsWith("session.") || XapiMethods.isRead(call) || call == "host.call_plugin")
        }
        check(bad.isEmpty()) { "unexpected writes: $bad" }
    }

    override fun close() = fixture.close()
}
