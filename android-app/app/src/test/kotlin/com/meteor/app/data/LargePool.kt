package com.meteor.app.data

import com.sun.net.httpserver.HttpExchange
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.SequenceInputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

// --- inventory scale ---
/**
 * A large pool built from the real XCP-ng 8.3.0 records (`live-xcpng-8.3/VM.get_all_records`): every guest is the recorded
 * guest record (10.6 KB, all its real fields), every snapshot is that record turned into a snapshot the way XAPI marks one
 * (`is_a_snapshot`, `snapshot_of`, halted, not resident), and the 39 templates and the control domain are the recorded
 * ones, byte for byte. Only identities change: UUIDs and references are synthetic but have the real lengths, so sizes and
 * element counts stay realistic. Answers are produced as a stream, so even a 5,000-VM answer (~53 MB) is never held.
 */
class LargePool(val guests: Int, val snapshots: Int, val templates: Boolean = true, val runningEvery: Int = 2) {
    enum class Kind { GUEST, SNAPSHOT, TEMPLATE, CONTROL_DOMAIN }
    class Item(val ref: String, val kind: Kind, val fields: Map<String, Any?>, val xml: () -> String)

    companion object {
        private val recorded by lazy { Live83.splitRecords(Live83.response("VM.get_all_records")) }
        private val parsed by lazy { Live83.records("VM") }
        val guestXml: String by lazy { recorded.getValue(parsed.entries.single { normal(it.value) }.key) }
        private fun normal(vm: Map<String, Any?>) = vm["is_a_template"] != true && vm["is_control_domain"] != true && vm["is_a_snapshot"] != true
        val hostRef: String by lazy { Live83.records("host").keys.single() }
        fun uuid(kind: Int, i: Int) = "%08x-0000-4000-8000-%012x".format(kind, i)
        fun ref(kind: Int, i: Int) = "OpaqueRef:" + uuid(kind, i)
        const val HEAD = "<?xml version=\"1.0\"?><methodResponse><params><param><value><struct><member><name>Status</name><value>Success</value>" +
            "</member><member><name>Value</name><value><struct>"
        const val TAIL = "</struct></value></member></struct></value></param></params></methodResponse>"

        /** Replaces the first top-level `field`'s simple value (bare text or `<boolean>`). */
        fun set(xml: String, field: String, value: String): String {
            val open = "<member><name>$field</name><value>"
            val start = xml.indexOf(open).also { require(it >= 0) { "no field $field" } } + open.length
            val end = xml.indexOf("</value></member>", start)
            return xml.substring(0, start) + value + xml.substring(end)
        }
    }

    private fun guestName(i: Int) = "vm-%05d".format(i)
    private fun guest(i: Int): String {
        var xml = set(guestXml, "uuid", uuid(1, i))
        xml = set(xml, "name_label", guestName(i))
        if (i % runningEvery != 0) { xml = set(xml, "power_state", "Halted"); xml = set(xml, "resident_on", "OpaqueRef:NULL") }
        return xml
    }
    private fun snapshot(i: Int): String {
        var xml = set(guestXml, "uuid", uuid(2, i))
        xml = set(xml, "name_label", "snap-%05d".format(i))
        xml = set(xml, "is_a_snapshot", "<boolean>1</boolean>")
        xml = set(xml, "snapshot_of", ref(1, i % guests.coerceAtLeast(1)))
        xml = set(xml, "power_state", "Halted")
        return set(xml, "resident_on", "OpaqueRef:NULL")
    }

    /** Server order: control domain, templates, then guests with their snapshots interleaved. */
    val items: List<Item> by lazy {
        val out = ArrayList<Item>(guests + snapshots + 40)
        parsed.filterValues { it["is_control_domain"] == true }.forEach { (ref, vm) -> out += Item(ref, Kind.CONTROL_DOMAIN, flags(vm)) { recorded.getValue(ref) } }
        if (templates) parsed.filterValues { it["is_a_template"] == true }.forEach { (ref, vm) -> out += Item(ref, Kind.TEMPLATE, flags(vm)) { recorded.getValue(ref) } }
        val perGuest = if (guests == 0) 0 else snapshots / guests
        var s = 0
        for (i in 0 until guests) {
            out += Item(ref(1, i), Kind.GUEST, mapOf("is_a_template" to false, "is_control_domain" to false, "is_a_snapshot" to false,
                "snapshot_of" to "OpaqueRef:NULL")) { guest(i) }
            repeat(perGuest) { if (s < snapshots) { val k = s++; out += snapshotItem(k) } }
        }
        while (s < snapshots) out += snapshotItem(s++)
        out
    }
    private fun snapshotItem(k: Int) = Item(ref(2, k), Kind.SNAPSHOT, mapOf("is_a_template" to false, "is_control_domain" to false,
        "is_a_snapshot" to true, "snapshot_of" to ref(1, k % guests.coerceAtLeast(1)))) { snapshot(k) }
    private fun flags(vm: Map<String, Any?>) = mapOf("is_a_template" to vm["is_a_template"], "is_control_domain" to vm["is_control_domain"],
        "is_a_snapshot" to vm["is_a_snapshot"], "snapshot_of" to vm["snapshot_of"])

    fun expectedGuestUuids(): Set<String> = (0 until guests).map { uuid(1, it) }.toSet()
    fun guestNameOf(i: Int) = guestName(i)

    /** `VM.get_all_records` ([filter] null) or `VM.get_all_records_where` as a lazily produced byte stream. */
    fun answer(filter: String?): InputStream {
        val chosen = if (filter == null) items else XapiWhere.filter(items.associateBy { it.ref }.mapValues { it.value.fields }, filter).keys
            .let { keys -> items.filter { it.ref in keys } }
        val parts = sequence {
            yield(HEAD.toByteArray())
            for (item in chosen) yield("<member><name>${item.ref}</name>${item.xml()}</member>".toByteArray())
            yield(TAIL.toByteArray())
        }.map { ByteArrayInputStream(it) as InputStream }.iterator()
        return SequenceInputStream(object : java.util.Enumeration<InputStream> {
            override fun hasMoreElements() = parts.hasNext()
            override fun nextElement() = parts.next()
        })
    }

    /** Size of an answer without keeping it. */
    fun answerBytes(filter: String?): Long {
        var total = 0L
        answer(filter).use { stream -> val buffer = ByteArray(64 * 1024); while (true) { val n = stream.read(buffer); if (n < 0) break; total += n } }
        return total
    }
}

/**
 * A loopback XAPI for scale tests: the real recorded pool, host, host metrics and SRs, and the [LargePool]'s VMs, streamed
 * (chunked) so neither side ever holds a whole answer. Reads only.
 */
class LargePoolServer(val pool: LargePool) : AutoCloseable {
    val fixture = TlsFixture()
    val calls = CopyOnWriteArrayList<String>()
    val vmBytes = AtomicLong()
    init {
        fixture.server.createContext("/") { exchange ->
            try { post(exchange) } catch (error: Throwable) { runCatching { exchange.sendResponseHeaders(500, -1) } } finally { exchange.close() }
        }
        fixture.start()
    }
    val profile get() = fixture.profile()

    private fun post(exchange: HttpExchange) {
        val body = String(exchange.requestBody.readBytes(), Charsets.UTF_8)
        val method = Regex("<methodName>([^<]+)</methodName>").find(body)!!.groupValues[1]
        calls += method
        val strings = Regex("<param><value><string>([^<]*)</string></value></param>").findAll(body).map { it.groupValues[1] }.toList()
        exchange.responseHeaders.add("Content-Type", "text/xml")
        val stream: InputStream = when (method) {
            "session.login_with_password" -> Live83.response("login").byteInputStream()
            "session.logout" -> Live83.response("logout").byteInputStream()
            "VM.get_all_records" -> pool.answer(null)
            "VM.get_all_records_where" -> pool.answer(XapiWhere.unescape(strings[1]))
            "pool.get_all_records", "host.get_all_records", "host_metrics.get_all_records", "SR.get_all_records" -> Live83.response(method).byteInputStream()
            else -> Live83.response("error-method-unknown").byteInputStream()
        }
        exchange.sendResponseHeaders(200, 0) // chunked
        exchange.responseBody.use { out ->
            val buffer = ByteArray(64 * 1024)
            stream.use { input ->
                while (true) {
                    val n = input.read(buffer); if (n < 0) break
                    if (method.startsWith("VM.")) vmBytes.addAndGet(n.toLong())
                    out.write(buffer, 0, n)
                }
            }
        }
    }
    override fun close() = fixture.close()
}
// --- end inventory scale ---
