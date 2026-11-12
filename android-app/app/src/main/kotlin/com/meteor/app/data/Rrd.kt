package com.meteor.app.data

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

/** One column of an `rrd_updates` legend, for example `AVERAGE:vm:<uuid>:cpu0`. */
data class RrdLegend(val consolidation: String, val kind: String, val uuid: String, val name: String) {
    companion object {
        /** Returns null for anything that is not `<cf>:<kind>:<uuid>:<name>`; the name may itself contain colons. */
        fun parse(text: String): RrdLegend? {
            val parts = text.trim().split(':', limit = 4)
            if (parts.size != 4 || parts.any { it.isBlank() } || text.length > 400) return null
            if (!parts[0].matches(Regex("[A-Z]{3,8}")) || !parts[2].matches(Regex("[A-Za-z0-9-]{1,64}"))) return null
            return RrdLegend(parts[0], parts[1].lowercase(), parts[2].lowercase(), parts[3])
        }
    }
}

/** A selected column. Missing, NaN and infinite samples are stored as [Double.NaN]; they are never zero. */
data class RrdColumn(val legend: RrdLegend, val values: List<Double>)

/**
 * Parsed `rrd_updates` body. [timestamps] are Unix seconds in ascending order and unique; every column has
 * exactly one value per timestamp. Only columns chosen by the caller are retained.
 */
data class RrdUpdates(val start: Long, val step: Long, val end: Long, val timestamps: List<Long>,
    val columns: List<RrdColumn>, val legendCount: Int)

/**
 * Parses both bodies that XAPI can return from `/rrd_updates`. XCP-ng 8.2 answers XML unless `json` is
 * requested, 8.3 may negotiate JSON from `Accept`, so the first byte decides. Input is untrusted: DTDs are
 * refused, sizes, rows, columns, depth and node counts are bounded, and anything inconsistent is rejected.
 */
object RrdParser {
    const val MAX_BYTES = 4 * 1024 * 1024
    const val MAX_ROWS = 1_000
    const val MAX_COLUMNS = 20_000
    private const val MAX_EVENTS = 3_000_000

    fun parse(body: ByteArray, wanted: (RrdLegend) -> Boolean): RrdUpdates {
        require(body.size in 1..MAX_BYTES) { "RRD response size is not supported." }
        val text = String(body, Charsets.UTF_8).removePrefix("\uFEFF").trimStart()
        require(text.isNotEmpty()) { "Empty RRD response." }
        return when (text[0]) {
            '<' -> parseXml(text, wanted)
            '{' -> parseJson(text, wanted)
            else -> throw IllegalArgumentException("Unrecognized RRD format.")
        }
    }

    private class Meta(val start: Long, val step: Long, val end: Long, val legend: List<String>, val declaredColumns: Int?)

    /** Collects rows for the selected columns only, so unrelated VMs on a busy host cost no memory. */
    private class Rows(val legend: List<String>, wanted: (RrdLegend) -> Boolean) {
        val parsed = legend.map { RrdLegend.parse(it) }
        val selected = parsed.mapIndexedNotNull { index, entry -> index.takeIf { entry != null && wanted(entry) } }
        val times = ArrayList<Long>()
        val values = ArrayList<DoubleArray>()
        fun add(time: Long, row: DoubleArray) {
            require(time > 0) { "Invalid RRD timestamp." }
            require(times.size < MAX_ROWS) { "Too many RRD rows." }
            times += time; values += row
        }
    }

    private fun meta(start: Long?, step: Long?, end: Long?, legend: List<String>, columns: Int?): Meta {
        require(start != null && step != null && end != null) { "Incomplete RRD metadata." }
        require(step in 1..86_400 && start >= 0 && end >= 0) { "Invalid RRD time range." }
        require(legend.isNotEmpty() && legend.size <= MAX_COLUMNS) { "Unsupported RRD legend size." }
        require(columns == null || columns == legend.size) { "RRD column count does not match its legend." }
        return Meta(start, step, end, legend, columns)
    }

    private fun number(token: String): Double {
        val text = token.trim().lowercase()
        if (text.isEmpty() || text in setOf("nan", "-nan", "+nan", "null", "inf", "+inf", "-inf", "infinity", "+infinity", "-infinity")) return Double.NaN
        val value = text.toDoubleOrNull() ?: throw IllegalArgumentException("Invalid RRD value.")
        return if (value.isFinite()) value else Double.NaN
    }

    private fun finish(meta: Meta, rows: Rows): RrdUpdates {
        val order = rows.times.indices.sortedBy { rows.times[it] }
        val times = order.map { rows.times[it] }
        require(times.zipWithNext().all { (a, b) -> a < b }) { "Ambiguous RRD timestamps." }
        val columns = rows.selected.mapIndexed { slot, index ->
            RrdColumn(requireNotNull(rows.parsed[index]), order.map { rows.values[it][slot] })
        }
        return RrdUpdates(meta.start, meta.step, meta.end, times, columns, meta.legend.size)
    }

    // ---- XML ----

    private fun parseXml(text: String, wanted: (RrdLegend) -> Boolean): RrdUpdates {
        require(!text.contains("<!DOCTYPE", true) && !text.contains("<!ENTITY", true)) { "DTD and entities are not supported." }
        val parser = XmlPullParserFactory.newInstance().newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setInput(StringReader(text))
        }
        var events = 0
        fun tick() { require(++events <= MAX_EVENTS) { "RRD response is too complex." } }
        fun tag(): Int { tick(); return parser.nextTag() }
        fun skip() {
            var depth = 1
            while (depth > 0) {
                tick()
                when (parser.next()) {
                    XmlPullParser.START_TAG -> depth++
                    XmlPullParser.END_TAG -> depth--
                    XmlPullParser.END_DOCUMENT -> throw IllegalArgumentException("Unexpected end of RRD document.")
                }
            }
        }
        fun text(): String { tick(); return parser.nextText() }
        fun readMeta(): Meta {
            var start: Long? = null; var step: Long? = null; var end: Long? = null; var columns: Int? = null
            val legend = ArrayList<String>()
            while (tag() == XmlPullParser.START_TAG) when (parser.name) {
                "start" -> start = text().trim().toLongOrNull()
                "step" -> step = text().trim().toLongOrNull()
                "end" -> end = text().trim().toLongOrNull()
                "columns" -> columns = text().trim().toIntOrNull()
                "legend" -> while (tag() == XmlPullParser.START_TAG) {
                    require(parser.name == "entry") { "Unexpected RRD legend content." }
                    require(legend.size < MAX_COLUMNS) { "Too many RRD columns." }
                    legend += text()
                }
                else -> skip()
            }
            return meta(start, step, end, legend, columns)
        }
        fun readRows(meta: Meta): Rows {
            val rows = Rows(meta.legend, wanted)
            while (tag() == XmlPullParser.START_TAG) {
                require(parser.name == "row") { "Unexpected RRD data content." }
                var time: Long? = null
                val picked = DoubleArray(rows.selected.size)
                var index = 0
                var slot = 0
                while (tag() == XmlPullParser.START_TAG) when (parser.name) {
                    "t" -> time = text().trim().toLongOrNull()
                    "v" -> {
                        require(index < meta.legend.size) { "RRD row has more values than legend entries." }
                        val raw = text()
                        if (slot < picked.size && rows.selected[slot] == index) picked[slot++] = number(raw)
                        index++
                    }
                    else -> throw IllegalArgumentException("Unexpected RRD row content.")
                }
                require(index == meta.legend.size) { "RRD row does not match its legend." }
                rows.add(requireNotNull(time) { "RRD row has no timestamp." }, picked)
            }
            return rows
        }
        tag(); parser.require(XmlPullParser.START_TAG, null, "xport")
        var meta: Meta? = null
        var rows: Rows? = null
        while (tag() == XmlPullParser.START_TAG) when (parser.name) {
            "meta" -> { require(meta == null) { "Duplicate RRD metadata." }; meta = readMeta() }
            "data" -> { require(rows == null) { "Duplicate RRD data." }; rows = readRows(requireNotNull(meta) { "RRD data precedes its metadata." }) }
            else -> skip()
        }
        return finish(requireNotNull(meta) { "Missing RRD metadata." }, requireNotNull(rows) { "Missing RRD data." })
    }

    // ---- JSON ----

    private fun long(value: Any?): Long? = when (value) {
        is Long -> value
        is Double -> value.takeIf { it.isFinite() && it == Math.rint(it) && Math.abs(it) < 9.0e15 }?.toLong()
        is String -> value.trim().toLongOrNull()
        else -> null
    }

    private fun parseJson(text: String, wanted: (RrdLegend) -> Boolean): RrdUpdates {
        val root = JsonLite.parse(text) as? Map<*, *> ?: throw IllegalArgumentException("RRD JSON must be an object.")
        val header = root["meta"] as? Map<*, *> ?: throw IllegalArgumentException("Missing RRD metadata.")
        val legend = (header["legend"] as? List<*>)?.map { it as? String ?: throw IllegalArgumentException("Invalid RRD legend.") }
            ?: throw IllegalArgumentException("Missing RRD legend.")
        val meta = meta(long(header["start"]), long(header["step"]), long(header["end"]), legend, header["columns"]?.let { long(it)?.toInt() ?: throw IllegalArgumentException("Invalid RRD column count.") })
        // XAPI writes `data` as a list of {"t", "values"}. A `{ "row": [ {"t", "v"} ] }` wrapper mirrors the XML shape.
        val data = (root["data"] as? Map<*, *>)?.get("row") ?: root["data"]
        val list = data as? List<*> ?: throw IllegalArgumentException("Missing RRD data.")
        val rows = Rows(meta.legend, wanted)
        for (entry in list) {
            val row = entry as? Map<*, *> ?: throw IllegalArgumentException("Invalid RRD row.")
            val values = (row["values"] ?: row["v"]) as? List<*> ?: throw IllegalArgumentException("Invalid RRD row values.")
            require(values.size == meta.legend.size) { "RRD row does not match its legend." }
            val picked = DoubleArray(rows.selected.size) { slot -> jsonNumber(values[rows.selected[slot]]) }
            rows.add(requireNotNull(long(row["t"])) { "RRD row has no timestamp." }, picked)
        }
        return finish(meta, rows)
    }

    private fun jsonNumber(value: Any?): Double = when (value) {
        null -> Double.NaN
        is Long -> value.toDouble()
        is Double -> if (value.isFinite()) value else Double.NaN
        is String -> number(value)
        else -> throw IllegalArgumentException("Invalid RRD value.")
    }
}

/**
 * A small strict JSON reader. `org.json` is only a stub in JVM unit tests, so one implementation serves both.
 * XAPI's OCaml JSON writer can emit bare `NaN`, `Infinity` and `-Infinity`; they are accepted as numbers.
 */
internal object JsonLite {
    private const val MAX_DEPTH = 16
    private const val MAX_VALUES = 2_000_000
    private const val MAX_STRING = 4_096

    fun parse(text: String): Any? {
        val reader = Reader(text)
        val value = reader.value(0)
        reader.skipWhitespace()
        require(reader.position == text.length) { "Unexpected content after JSON value." }
        return value
    }

    private class Reader(val text: String) {
        var position = 0
        private var values = 0
        fun skipWhitespace() { while (position < text.length && text[position] in " \t\r\n") position++ }
        fun value(depth: Int): Any? {
            require(depth <= MAX_DEPTH) { "JSON nesting is too deep." }
            require(++values <= MAX_VALUES) { "JSON is too large." }
            skipWhitespace()
            require(position < text.length) { "Unexpected end of JSON." }
            return when (text[position]) {
                '{' -> objectValue(depth)
                '[' -> arrayValue(depth)
                '"' -> string()
                else -> literal()
            }
        }
        private fun expect(char: Char) {
            skipWhitespace()
            require(position < text.length && text[position] == char) { "Malformed JSON." }
            position++
        }
        private fun objectValue(depth: Int): Map<String, Any?> {
            position++
            val map = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (position < text.length && text[position] == '}') { position++; return map }
            while (true) {
                skipWhitespace()
                require(position < text.length && text[position] == '"') { "Malformed JSON object." }
                val key = string()
                require(!map.containsKey(key)) { "Duplicate JSON field." }
                expect(':')
                map[key] = value(depth + 1)
                skipWhitespace()
                require(position < text.length) { "Unexpected end of JSON." }
                when (text[position++]) { ',' -> continue; '}' -> return map; else -> throw IllegalArgumentException("Malformed JSON object.") }
            }
        }
        private fun arrayValue(depth: Int): List<Any?> {
            position++
            val list = ArrayList<Any?>()
            skipWhitespace()
            if (position < text.length && text[position] == ']') { position++; return list }
            while (true) {
                list += value(depth + 1)
                skipWhitespace()
                require(position < text.length) { "Unexpected end of JSON." }
                when (text[position++]) { ',' -> continue; ']' -> return list; else -> throw IllegalArgumentException("Malformed JSON array.") }
            }
        }
        private fun string(): String {
            position++
            val out = StringBuilder()
            while (true) {
                require(position < text.length) { "Unterminated JSON string." }
                val char = text[position++]
                require(out.length <= MAX_STRING) { "JSON string is too long." }
                when {
                    char == '"' -> return out.toString()
                    char == '\\' -> {
                        require(position < text.length) { "Unterminated JSON escape." }
                        when (val escape = text[position++]) {
                            '"', '\\', '/' -> out.append(escape)
                            'b' -> out.append('\b'); 'f' -> out.append('\u000C'); 'n' -> out.append('\n')
                            'r' -> out.append('\r'); 't' -> out.append('\t')
                            'u' -> {
                                require(position + 4 <= text.length) { "Invalid JSON escape." }
                                out.append(text.substring(position, position + 4).toIntOrNull(16)?.toChar()
                                    ?: throw IllegalArgumentException("Invalid JSON escape."))
                                position += 4
                            }
                            else -> throw IllegalArgumentException("Invalid JSON escape.")
                        }
                    }
                    char < ' ' -> throw IllegalArgumentException("Control character in JSON string.")
                    else -> out.append(char)
                }
            }
        }
        private fun literal(): Any? {
            val start = position
            while (position < text.length && text[position] !in ",]} \t\r\n") position++
            val token = text.substring(start, position)
            return when (token) {
                "null" -> null
                "true" -> true
                "false" -> false
                "NaN" -> Double.NaN
                "Infinity" -> Double.POSITIVE_INFINITY
                "-Infinity" -> Double.NEGATIVE_INFINITY
                else -> {
                    require(token.matches(Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?"))) { "Invalid JSON literal." }
                    if (token.any { it == '.' || it == 'e' || it == 'E' }) token.toDouble() else token.toLongOrNull() ?: token.toDouble()
                }
            }
        }
    }
}
