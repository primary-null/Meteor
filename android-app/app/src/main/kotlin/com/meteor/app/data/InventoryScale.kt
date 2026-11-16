package com.meteor.app.data

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream
import java.util.PriorityQueue

// --- inventory scale ---
//
// Pool-sized reads without holding the answer as one tree. XAPI has no field projection, so a `*.get_all_records(_where)`
// answer always carries whole records (a real VM record is about 10 KB and ~400 elements). This reader parses the answer
// straight from the socket and keeps, per record, only the fields the caller names; everything else is skipped without
// building strings or maps. Memory is then proportional to what the app shows, not to what the server sends.
//
// Measured on a real XCP-ng 8.3.0 host (METEOR-LIVE-FINDINGS.md, B and finding 3): a VM record is 7.8-10.6 KB, a
// template ~9.3 KB, and the tree reader spends about 675 node ticks per VM record, i.e. ~14 bytes of XML per tick.

/**
 * Limits of one streamed answer. Bytes and nodes bound bandwidth and CPU. Memory: [maxRecordChars] bounds what one record may
 * keep, [maxRecords] how many records are kept and [maxKeptChars] the text all kept records hold together.
 */
data class StreamLimits(val maxBytes: Long, val maxNodes: Long, val maxRecordChars: Int, val maxRecords: Int = XmlRpcRecords.MAX_RECORDS,
    val maxKeptChars: Long = 8_000_000, val maxDepth: Int = 32) {
    companion object {
        /**
         * 64 MiB on the wire is about 6,000 real VM records (10.6 KB each) in one answer, so the projected inventory read of a
         * 1,000-VM pool (~10.6 MiB) uses a sixth of it. At ~14 B per element a legitimate 64 MiB answer has ~4.8 M elements;
         * 6 M leaves room for denser records while bounding the parse work of a hostile answer (~1-2 s on a phone).
         * Nothing of the skipped part is retained, so these two limits bound time, not memory.
         */
        /*
         * Kept text: a real VM keeps ~91 characters with the inventory projection (~1 KB of heap with its map), so 10,000
         * VMs keep ~0.9 M characters and 1,000 alert messages at most ~4.3 M. 8 M characters (16 MB as UTF-16 on the JVM,
         * ~8 MB as Latin-1 strings on Android) is the most a hostile answer can make one read keep, plus ~6 MB of maps
         * for 10,000 records.
         */
        val DEFAULT = StreamLimits(maxBytes = 64L * 1024 * 1024, maxNodes = 6_000_000, maxRecordChars = 64 * 1024)
    }
}

/** Which fields of each record are kept. [clip] truncates long text fields at the stated length (display caps the app already applies). */
data class Projection(val fields: Set<String>, val clip: Map<String, Int> = emptyMap())

/** What a streamed read cost: bytes received, element ticks, records seen and characters kept. Exposed for tests and diagnostics. */
data class StreamStats(val bytes: Long, val nodes: Long, val records: Int, val keptChars: Long)

/**
 * The records a projected read kept, and how many the server sent. [total] > [records.size] means the caller's record cap
 * was reached: the rest were parsed (identity only) and counted but not kept.
 */
data class RecordsRead(val records: Map<String, Map<String, Any?>>, val total: Int, val stats: StreamStats) {
    val complete: Boolean get() = total == records.size
}

/** Thrown for every streamed-read limit; an [IllegalArgumentException] like the tree reader's "Response is too …" errors. */
class ResponseLimitExceeded(message: String) : IllegalArgumentException(message)

/** True for any size or complexity limit of either reader or of the transport: the answer was too big, not wrong. */
fun Throwable.isResponseLimit(): Boolean = this is ResponseLimitExceeded ||
    this is IllegalArgumentException && message.orEmpty().startsWith("Response is too ") ||
    this is GatewayError && reason == "response-limit"

/** Counts the bytes the parser pulls and stops at [maxBytes]. */
internal class LimitedInput(private val input: InputStream, private val maxBytes: Long) : InputStream() {
    var count = 0L
        private set
    private fun add(n: Int) { if (n > 0) { count += n; if (count > maxBytes) throw GatewayError("response-limit") } }
    override fun read(): Int = input.read().also { if (it >= 0) add(1) }
    override fun read(b: ByteArray, off: Int, len: Int): Int = input.read(b, off, len).also(::add)
    override fun close() = input.close()
}

/**
 * Streams one XAPI answer whose `Value` is a struct of `reference -> record struct` (`*.get_all_records`,
 * `*.get_all_records_where`, `message.get_since`). The envelope is checked exactly as [XmlRpc.response] checks it:
 * `Failure` becomes an [RpcFailure] with the same details, an XML-RPC fault is `RPC_FAULT` (not an explicit rejection),
 * DTDs are refused. Kept field values have the tree reader's types (String, Boolean, Long, Double, List, Map); a skipped
 * field is only checked for well-formed XML.
 */
internal class XmlRpcRecordStream(input: InputStream, private val limits: StreamLimits = StreamLimits.DEFAULT) {
    private val counted = LimitedInput(input, limits.maxBytes)
    private val parser: XmlPullParser = XmlPullParserFactory.newInstance().newPullParser().apply {
        setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        setInput(counted, "UTF-8")
    }
    // Field names repeat in every record: one String per distinct name.
    private val names = HashMap<String, String>()
    private var nodes = 0L
    private var records = 0
    private var keptChars = 0L
    private var recordChars = 0

    val stats get() = StreamStats(counted.count, nodes, records, keptChars)

    private fun tick() { if (++nodes > limits.maxNodes) throw ResponseLimitExceeded("Response is too complex.") }
    private fun nextTag(): Int { tick(); return parser.nextTag() }
    private fun depthCheck() { if (parser.depth > limits.maxDepth + 8) throw ResponseLimitExceeded("Response nesting is too deep.") }
    private fun keep(text: String, clip: Int = Int.MAX_VALUE): String {
        val value = if (text.length > clip) text.substring(0, clip) else text
        recordChars += value.length
        if (recordChars > limits.maxRecordChars) throw ResponseLimitExceeded("Response is too large.")
        keptChars += value.length
        return value
    }
    private fun name(text: String): String = names[text] ?: if (names.size < 4096) text.also { names[it] = it } else text

    /** At START_TAG `value`; consumes through its END_TAG without keeping anything. */
    private fun skipValue() {
        parser.require(XmlPullParser.START_TAG, null, "value")
        tick()
        var open = 1
        while (open > 0) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> { tick(); depthCheck(); open++ }
                XmlPullParser.END_TAG -> open--
                XmlPullParser.END_DOCUMENT -> throw IllegalArgumentException("Truncated XML-RPC response.")
            }
        }
    }

    /** At START_TAG `value`; the same value the tree reader builds (bare text is a string, `<value/>` is ""). */
    private fun keptValue(depth: Int, clip: Int = Int.MAX_VALUE): Any? {
        require(depth <= limits.maxDepth) { "Response nesting is too deep." }
        parser.require(XmlPullParser.START_TAG, null, "value")
        tick()
        var event = parser.next()
        if (event == XmlPullParser.END_TAG) return ""
        if (event == XmlPullParser.TEXT) {
            val text = parser.text
            event = parser.nextTag()
            if (event == XmlPullParser.END_TAG) {
                parser.require(XmlPullParser.END_TAG, null, "value")
                return keep(text, clip)
            }
            require(text.isBlank()) { "Unexpected mixed XML-RPC content." }
        }
        parser.require(XmlPullParser.START_TAG, null, null)
        val result: Any? = when (parser.name) {
            "struct" -> {
                val map = LinkedHashMap<String, Any?>()
                while (nextTag() == XmlPullParser.START_TAG) {
                    parser.require(XmlPullParser.START_TAG, null, "member")
                    nextTag(); parser.require(XmlPullParser.START_TAG, null, "name")
                    val key = name(keep(parser.nextText()))
                    require(!map.containsKey(key)) { "Duplicate XML-RPC field." }
                    nextTag()
                    map[key] = keptValue(depth + 1)
                    nextTag(); parser.require(XmlPullParser.END_TAG, null, "member")
                }
                parser.require(XmlPullParser.END_TAG, null, "struct")
                map
            }
            "array" -> {
                val list = ArrayList<Any?>()
                nextTag(); parser.require(XmlPullParser.START_TAG, null, "data")
                while (nextTag() == XmlPullParser.START_TAG) list += keptValue(depth + 1)
                parser.require(XmlPullParser.END_TAG, null, "data")
                nextTag(); parser.require(XmlPullParser.END_TAG, null, "array")
                list
            }
            "boolean" -> parser.nextText().trim().let { require(it == "0" || it == "1") { "Invalid XML-RPC boolean." }; it == "1" }
            "int", "i4", "i8" -> parser.nextText().trim().toLongOrNull() ?: throw IllegalArgumentException("Invalid XML-RPC integer.")
            // The same reading as XmlRpc.response (including XAPI's inf, -inf and nan).
            "double" -> XmlRpc.parseDouble(parser.nextText())
            "string", "dateTime.iso8601", "base64" -> keep(parser.nextText(), clip)
            else -> throw IllegalArgumentException("Unsupported XML-RPC type.")
        }
        nextTag(); parser.require(XmlPullParser.END_TAG, null, "value")
        return result
    }

    /** At START_TAG `value` of one record: the projected fields, or a protocol error if it is not a struct. */
    private fun record(projection: Projection): Map<String, Any?> {
        parser.require(XmlPullParser.START_TAG, null, "value")
        tick()
        if (nextTag() != XmlPullParser.START_TAG || parser.name != "struct") throw GatewayError("invalid-record")
        recordChars = 0
        val kept = LinkedHashMap<String, Any?>()
        val seen = HashSet<String>()
        while (nextTag() == XmlPullParser.START_TAG) {
            parser.require(XmlPullParser.START_TAG, null, "member")
            nextTag(); parser.require(XmlPullParser.START_TAG, null, "name")
            val field = name(parser.nextText())
            require(seen.add(field)) { "Duplicate XML-RPC field." }
            nextTag()
            if (field in projection.fields) kept[field] = keptValue(1, projection.clip[field] ?: Int.MAX_VALUE) else skipValue()
            nextTag(); parser.require(XmlPullParser.END_TAG, null, "member")
        }
        parser.require(XmlPullParser.END_TAG, null, "struct")
        nextTag(); parser.require(XmlPullParser.END_TAG, null, "value")
        return kept
    }

    /** At START_TAG `value` of the envelope's `Value`: every record goes to [onRecord] (with the characters it keeps) as it is read. */
    private fun recordMap(projection: Projection, onRecord: (String, Map<String, Any?>, Int) -> Unit) {
        parser.require(XmlPullParser.START_TAG, null, "value")
        tick()
        if (nextTag() != XmlPullParser.START_TAG || parser.name != "struct") throw GatewayError("invalid-records")
        while (nextTag() == XmlPullParser.START_TAG) {
            parser.require(XmlPullParser.START_TAG, null, "member")
            nextTag(); parser.require(XmlPullParser.START_TAG, null, "name")
            val ref = parser.nextText()
            nextTag()
            val fields = record(projection)
            records++
            onRecord(ref, fields, recordChars)
            nextTag(); parser.require(XmlPullParser.END_TAG, null, "member")
        }
        parser.require(XmlPullParser.END_TAG, null, "struct")
        nextTag(); parser.require(XmlPullParser.END_TAG, null, "value")
    }

    /** Reads the whole answer. Returns normally only for `Status: Success` with a record map. */
    fun read(projection: Projection, onRecord: (String, Map<String, Any?>, Int) -> Unit) {
        // Before the root element only the XML declaration, comments and whitespace may appear; a DTD is refused.
        var token = parser.nextToken()
        while (token != XmlPullParser.START_TAG) {
            when (token) {
                XmlPullParser.DOCDECL, XmlPullParser.ENTITY_REF -> throw IllegalArgumentException("DTD and entities are not supported.")
                XmlPullParser.END_DOCUMENT -> throw IllegalArgumentException("Truncated XML-RPC response.")
                XmlPullParser.TEXT -> require(parser.isWhitespace) { "Unexpected XML-RPC content." }
            }
            token = parser.nextToken()
        }
        parser.require(XmlPullParser.START_TAG, null, "methodResponse")
        nextTag()
        if (parser.name == "fault") throw RpcFailure("RPC_FAULT", explicitRejection = false)
        parser.require(XmlPullParser.START_TAG, null, "params")
        nextTag(); parser.require(XmlPullParser.START_TAG, null, "param")
        nextTag(); parser.require(XmlPullParser.START_TAG, null, "value")
        tick()
        nextTag(); parser.require(XmlPullParser.START_TAG, null, "struct")
        var status: Any? = null
        var description: Any? = null
        var hasValue = false
        while (nextTag() == XmlPullParser.START_TAG) {
            parser.require(XmlPullParser.START_TAG, null, "member")
            nextTag(); parser.require(XmlPullParser.START_TAG, null, "name")
            val key = parser.nextText()
            nextTag()
            when (key) {
                "Status" -> { require(status == null) { "Duplicate XML-RPC field." }; recordChars = 0; status = keptValue(1) }
                "ErrorDescription" -> { require(description == null) { "Duplicate XML-RPC field." }; recordChars = 0; description = keptValue(1) }
                "Value" -> {
                    require(!hasValue) { "Duplicate XML-RPC field." }
                    hasValue = true
                    // XAPI sends Status first. A Value that follows Failure is not records and is not read as such.
                    if (status == null || status == "Success") recordMap(projection, onRecord) else skipValue()
                }
                else -> skipValue()
            }
            nextTag(); parser.require(XmlPullParser.END_TAG, null, "member")
        }
        parser.require(XmlPullParser.END_TAG, null, "struct")
        nextTag(); parser.require(XmlPullParser.END_TAG, null, "value")
        nextTag(); parser.require(XmlPullParser.END_TAG, null, "param")
        nextTag(); parser.require(XmlPullParser.END_TAG, null, "params")
        nextTag(); parser.require(XmlPullParser.END_TAG, null, "methodResponse")
        when (status) {
            "Success" -> require(hasValue) { "Missing XAPI value." }
            "Failure" -> {
                val list = description as? List<*>
                val code = list?.firstOrNull() as? String
                require(code != null && code.matches(Regex("[A-Z0-9_]+"))) { "Invalid XAPI failure." }
                throw RpcFailure(code, details = list.drop(1).filterIsInstance<String>().take(8).map { it.take(200) })
            }
            null -> throw IllegalArgumentException("Missing XAPI envelope.")
            else -> throw IllegalArgumentException("Invalid XAPI status.")
        }
    }
}

object XmlRpcRecords {
    /** Default record cap of one projected read: far above any pool XCP-ng supports, and ~15 MB kept at most. */
    const val MAX_RECORDS = 10_000

    /**
     * Keeps the projected fields of at most [maxRecords] records, in server order, and counts the rest. Duplicate
     * references are a protocol error, as in the tree reader.
     */
    fun collect(input: InputStream, projection: Projection, maxRecords: Int = MAX_RECORDS,
        limits: StreamLimits = StreamLimits.DEFAULT, accept: (Map<String, Any?>) -> Boolean = { true }): RecordsRead {
        val kept = LinkedHashMap<String, Map<String, Any?>>()
        var total = 0
        var keptChars = 0L
        val reader = XmlRpcRecordStream(input, limits)
        reader.read(projection) { ref, fields, chars ->
            if (!accept(fields)) return@read
            total++
            if (kept.size < maxRecords) {
                require(!kept.containsKey(ref)) { "Duplicate XML-RPC field." }
                keptChars += chars
                if (keptChars > limits.maxKeptChars) throw ResponseLimitExceeded("Response is too large.")
                kept[ref] = fields
            }
        }
        return RecordsRead(kept, total, reader.stats)
    }

    /** Keeps the [keep] newest records by [time] (ties by reference) and counts all of them. Memory is O([keep]). */
    fun newest(input: InputStream, projection: Projection, keep: Int, time: (Map<String, Any?>) -> Long?,
        limits: StreamLimits = StreamLimits.DEFAULT): RecordsRead {
        data class Item(val ref: String, val at: Long, val fields: Map<String, Any?>, val chars: Int)
        // Oldest first, so the head is the one to drop.
        val heap = PriorityQueue<Item>(keep.coerceAtLeast(1), compareBy<Item> { it.at }.thenByDescending { it.ref })
        val refs = HashSet<String>()
        var total = 0
        var keptChars = 0L
        val reader = XmlRpcRecordStream(input, limits)
        reader.read(projection) { ref, fields, chars ->
            total++
            val item = Item(ref, time(fields) ?: Long.MIN_VALUE, fields, chars)
            require(ref !in refs) { "Duplicate XML-RPC field." }
            val added = if (heap.size < keep) true
                else if (keep > 0 && heap.comparator().compare(item, heap.peek()) > 0) { val out = heap.poll(); refs -= out.ref; keptChars -= out.chars; true }
                else false
            if (added) {
                heap += item; refs += ref; keptChars += chars
                if (keptChars > limits.maxKeptChars) throw ResponseLimitExceeded("Response is too large.")
            }
        }
        val ordered = heap.sortedWith(compareByDescending<Item> { it.at }.thenBy { it.ref })
        return RecordsRead(ordered.associateTo(LinkedHashMap()) { it.ref to it.fields }, total, reader.stats)
    }
}

/**
 * Server-side filters for `*.get_all_records_where`. Verified read-only against XCP-ng 8.3.0: `field "x"="y"`,
 * `and`, `or`, `not`, parentheses and the literal `true` are accepted; `field "_ref"="OpaqueRef:…"` selects one object;
 * a `"` inside a literal is rejected (INVALID_VALUE, even escaped), an unknown field is INTERNAL_ERROR. Literals here
 * are therefore restricted to a reference/UUID alphabet and never carry user text.
 */
object XapiQuery {
    /** References per `or` chain: one chain of 100 is ~6 KB of request. */
    const val BATCH = 100
    private val safe = Regex("[A-Za-z0-9:._-]{1,200}")
    private val fieldName = Regex("[A-Za-z_]{1,64}")
    fun isSafe(value: String) = value.matches(safe)

    fun eq(field: String, value: String): String {
        require(field.matches(fieldName) && value.matches(safe)) { "Unsafe XAPI query literal." }
        return "field \"$field\"=\"$value\""
    }
    /** `field "f"="v1" or field "f"="v2" …` for every chunk of [BATCH] values. */
    fun anyOf(field: String, values: Collection<String>): List<String> =
        values.distinct().chunked(BATCH).map { chunk -> chunk.joinToString(" or ") { eq(field, it) } }

    /** Normal VMs only (same expression the pool summary sends). */
    const val GUESTS = PoolSummaries.VM_FILTER
    /** Templates a VM can be created from: real templates, not snapshots of VMs. */
    const val TEMPLATES = "field \"is_a_template\"=\"true\" and field \"is_a_snapshot\"=\"false\""
    fun snapshotsOf(vmRef: String) = "${eq("snapshot_of", vmRef)} and field \"is_a_snapshot\"=\"true\""
}

/** The fields each pool-sized read keeps. */
object Projections {
    val POOL = Projection(setOf("uuid", "name_label", "master"))
    val HOST = Projection(setOf("uuid", "name_label", "name_description", "metrics", "software_version", "enabled"))
    val HOST_METRICS = Projection(setOf("live", "memory_total", "memory_free"))
    val GUEST = Projection(setOf("uuid", "name_label", "power_state", "resident_on", "VCPUs_max", "memory_static_max",
        "is_a_template", "is_control_domain", "is_a_snapshot"))
    val SR = Projection(setOf("uuid", "name_label", "name_description", "type", "content_type", "physical_size", "physical_utilisation",
        "current_operations", "shared"))
    val SNAPSHOT = Projection(setOf("uuid", "name_label", "snapshot_time", "power_state", "suspend_VDI", "allowed_operations",
        "current_operations", "is_a_snapshot", "is_control_domain", "snapshot_of"))
    val VM_NAME = Projection(setOf("uuid", "name_label", "is_a_snapshot", "is_a_template", "is_control_domain"))
    val VBD = Projection(setOf("uuid", "VM", "VDI", "type", "empty", "userdevice", "currently_attached", "mode"))
    val PBD = Projection(setOf("uuid", "SR", "host", "currently_attached"))
    val ISO = Projection(setOf("uuid", "name_label", "SR", "is_a_snapshot", "missing"))
    val PIF = Projection(setOf("uuid", "host", "device", "MAC", "IP", "management", "currently_attached", "metrics", "VLAN",
        "bond_master_of", "bond_slave_of", "physical", "network"))
    /** Message bodies are clipped at the 4096 characters [AlertMapper] and [ActivityMapper] keep anyway. */
    val MESSAGE = Projection(setOf("uuid", "name", "priority", "cls", "obj_uuid", "timestamp", "body"), clip = mapOf("body" to 4096, "name" to 160))
    val TEMPLATE = Projection(setOf("uuid", "name_label", "name_description", "is_default_template", "reference_label", "VCPUs_max",
        "memory_static_max", "domain_type", "allowed_operations", "is_a_template", "is_a_snapshot"),
        clip = mapOf("name_description" to 300))
}

/**
 * The shared result of the message read used by the Alerts inbox and by pool activity. [read] holds at most
 * [AlertMerge.MAX_KEPT] newest messages and the true count of what the server sent. [fallback]: the full store exceeded
 * the stream limits, so only the last [AlertMerge.FALLBACK_WINDOW_MILLIS] were requested.
 */
data class MessageWindow(val read: RecordsRead, val full: Boolean, val fallback: Boolean) {
    val truncated: Boolean get() = fallback || !read.complete

    companion object {
        /** [ReadSection.total] when only the recent window could be read: the size of the whole store is unknown. */
        const val TOTAL_UNKNOWN = -1
        fun time(record: Map<String, Any?>): Long? = ActivityMapper.time(record["timestamp"])
        /** Pool activity's alert section from the same read: the newest 100 as before, with the true total. */
        fun activity(window: MessageWindow): ReadSection<PoolAlert> {
            val section = ActivityMapper.alerts(window.read.records)
            return ReadSection(section.items, if (window.fallback) TOTAL_UNKNOWN else window.read.total, section.issue)
        }
    }
}

/** What the inventory could not load. Shown on the overview and the VM list; never silently dropped. */
object InventoryNotices {
    fun guestsUnreadable(error: Throwable): String = "VMs could not be loaded: " + when {
        error is GatewayError && error.reason == "response-limit" ->
            "the pool's VM list is larger than the ${StreamLimits.DEFAULT.maxBytes / (1024 * 1024)} MiB Meteor reads in one answer."
        error.message == "Response is too complex." -> "the pool's VM list has more elements than Meteor reads in one answer."
        else -> "one VM record is larger than Meteor accepts."
    } + " Hosts and storage are shown."
    fun guestsCapped(shown: Int, total: Int) =
        "Showing $shown of $total VMs (in server order): Meteor keeps at most $shown VMs in one inventory."
}

// ---- templates (read on demand; no UI yet) --------------------------------------------------------------------

/** One template for a future "create VM from template" picker. [vcpus] and [memoryBytes] are the template's defaults. */
data class TemplateChoice(val uuid: String, val name: String, val description: String, val builtIn: Boolean,
    val referenceLabel: String?, val vcpus: Int?, val memoryBytes: Long?, val domainType: String?, val canClone: Boolean)

/** [total] counts every template the server listed; [templates] holds at most [Templates.MAX] of them, sorted by name. */
data class TemplateCatalog(val authority: String, val templates: List<TemplateChoice>, val total: Int, val observedAt: Long) {
    val truncated: Boolean get() = total > templates.size
}

object Templates {
    /** A fresh XCP-ng 8.3 install has 39; custom templates add a few dozen in practice. */
    const val MAX = 2000

    fun map(authority: String, read: RecordsRead, now: Long): TemplateCatalog {
        fun Map<String, Any?>.text(key: String) = this[key]?.toString().orEmpty()
        fun Map<String, Any?>.flag(key: String) = this[key] == true || text(key) == "true" || text(key) == "1"
        val choices = read.records.values.filter { it.flag("is_a_template") && !it.flag("is_a_snapshot") && it.text("uuid").isNotBlank() }.map { t ->
            val allowed = (t["allowed_operations"] as? List<*>).orEmpty().map { it.toString() }.toSet()
            TemplateChoice(t.text("uuid"), t.text("name_label").trim().take(160).ifBlank { "Unnamed template" },
                t.text("name_description").trim().replace(Regex("\\s+"), " ").take(300), t.flag("is_default_template"),
                t.text("reference_label").trim().take(80).ifBlank { null }, t.text("VCPUs_max").toIntOrNull()?.takeIf { it > 0 },
                t.text("memory_static_max").toLongOrNull()?.takeIf { it > 0 }, t.text("domain_type").trim().take(16).ifBlank { null },
                "clone" in allowed || "copy" in allowed)
        }.sortedWith(compareBy<TemplateChoice> { !it.builtIn }.thenBy { it.name.lowercase() }.thenBy { it.uuid })
        require(choices.map { it.uuid }.distinct().size == choices.size) { "Duplicate template identity." }
        return TemplateCatalog(authority, choices, read.total, now)
    }
}
// --- end inventory scale ---
