package com.meteor.app.data

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

// --- A4 alerts/events ---
// `details` keeps the XAPI error parameters (ErrorDescription minus the code), capped. They can carry the
// pool master address (HOST_IS_SLAVE) or a permission name (RBAC_PERMISSION_DENIED). The exception message
// stays code-only, and callers must pick which details they show.
class RpcFailure(val code: String, val explicitRejection: Boolean = true, val details: List<String> = emptyList()) :
    Exception("XAPI request failed ($code)")

/** Encoded as `<dateTime.iso8601>` (UTC, `yyyyMMdd'T'HH:mm:ss'Z'`), the type XAPI expects for `datetime` arguments. */
class XmlRpcDateTime(val epochMillis: Long) {
    val text: String get() = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HH:mm:ss'Z'")
        .withZone(java.time.ZoneOffset.UTC).format(java.time.Instant.ofEpochMilli(epochMillis))
}
// --- end A4 ---

object XmlRpc {
    private fun escape(value: String): String = buildString {
        value.forEach { char ->
            require(char == '\n' || char == '\r' || char == '\t' || char.code >= 32) { "Invalid XML character." }
            append(when (char) { '&' -> "&amp;"; '<' -> "&lt;"; '>' -> "&gt;"; '"' -> "&quot;"; '\'' -> "&apos;"; else -> char.toString() })
        }
    }
    private fun value(input: Any?): String = when (input) {
        is Boolean -> "<boolean>${if (input) 1 else 0}</boolean>"
        // --- A4 alerts/events ---
        is Double -> { require(input.isFinite()) { "Invalid XML-RPC number." }; "<double>${String.format(java.util.Locale.ROOT, "%.3f", input)}</double>" }
        is XmlRpcDateTime -> "<dateTime.iso8601>${input.text}</dateTime.iso8601>"
        // --- end A4 ---
        is List<*> -> "<array><data>" + input.joinToString("") { "<value>${value(it)}</value>" } + "</data></array>"
        // A2 operations: string-keyed structs, e.g. the pool_migrate options map.
        is Map<*, *> -> "<struct>" + input.entries.joinToString("") { (key, item) ->
            "<member><name>${escape(key as? String ?: throw IllegalArgumentException("Struct keys must be strings."))}</name><value>${value(item)}</value></member>"
        } + "</struct>"
        else -> "<string>${escape(input?.toString().orEmpty())}</string>"
    }
    fun request(method: String, arguments: List<Any?>): ByteArray {
        require(method.matches(Regex("[A-Za-z][A-Za-z0-9_.]*")))
        return ("<?xml version=\"1.0\" encoding=\"UTF-8\"?><methodCall><methodName>$method</methodName><params>" +
            arguments.joinToString("") { "<param><value>${value(it)}</value></param>" } + "</params></methodCall>").toByteArray(Charsets.UTF_8)
    }

    fun response(xml: String): Any? {
        require(xml.toByteArray().size <= 8 * 1024 * 1024) { "Response is too large." }
        require(!xml.contains("<!DOCTYPE", true) && !xml.contains("<!ENTITY", true)) { "DTD and entities are not supported." }
        val parser = XmlPullParserFactory.newInstance().newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setInput(StringReader(xml))
        }
        var nodes = 0
        fun tick() { require(++nodes <= 150_000) { "Response is too complex." } }
        fun nextTag(): Int { tick(); return parser.nextTag() }
        fun parseValue(depth: Int): Any? {
            require(depth <= 32) { "Response nesting is too deep." }
            parser.require(XmlPullParser.START_TAG, null, "value")
            tick()
            var event = parser.next()
            if (event == XmlPullParser.END_TAG) return ""
            if (event == XmlPullParser.TEXT) {
                val text = parser.text
                event = parser.nextTag()
                if (event == XmlPullParser.END_TAG) {
                    parser.require(XmlPullParser.END_TAG, null, "value")
                    return text
                }
                require(text.isBlank()) { "Unexpected mixed XML-RPC content." }
            }
            parser.require(XmlPullParser.START_TAG, null, null)
            val result: Any? = when (parser.name) {
                "struct" -> {
                    val map = linkedMapOf<String, Any?>()
                    while (nextTag() == XmlPullParser.START_TAG) {
                        parser.require(XmlPullParser.START_TAG, null, "member")
                        nextTag(); parser.require(XmlPullParser.START_TAG, null, "name")
                        val key = parser.nextText()
                        require(!map.containsKey(key)) { "Duplicate XML-RPC field." }
                        nextTag()
                        map[key] = parseValue(depth + 1)
                        nextTag(); parser.require(XmlPullParser.END_TAG, null, "member")
                    }
                    parser.require(XmlPullParser.END_TAG, null, "struct")
                    map
                }
                "array" -> {
                    val list = mutableListOf<Any?>()
                    nextTag(); parser.require(XmlPullParser.START_TAG, null, "data")
                    while (nextTag() == XmlPullParser.START_TAG) list += parseValue(depth + 1)
                    parser.require(XmlPullParser.END_TAG, null, "data")
                    nextTag(); parser.require(XmlPullParser.END_TAG, null, "array")
                    list
                }
                "boolean" -> parser.nextText().trim().let { require(it in listOf("0", "1")) { "Invalid XML-RPC boolean." }; it == "1" }
                "int", "i4", "i8" -> parser.nextText().trim().toLongOrNull() ?: throw IllegalArgumentException("Invalid XML-RPC integer.")
                "double" -> parser.nextText().trim().toDoubleOrNull() ?: throw IllegalArgumentException("Invalid XML-RPC number.")
                "string", "dateTime.iso8601", "base64" -> parser.nextText()
                else -> throw IllegalArgumentException("Unsupported XML-RPC type.")
            }
            nextTag(); parser.require(XmlPullParser.END_TAG, null, "value")
            return result
        }
        parser.nextTag(); parser.require(XmlPullParser.START_TAG, null, "methodResponse")
        nextTag()
        // A generic RPC fault is not proof that XAPI rejected a mutation before accepting it.
        if (parser.name == "fault") throw RpcFailure("RPC_FAULT", explicitRejection = false)
        parser.require(XmlPullParser.START_TAG, null, "params")
        nextTag(); parser.require(XmlPullParser.START_TAG, null, "param")
        nextTag()
        val result = parseValue(0)
        nextTag(); parser.require(XmlPullParser.END_TAG, null, "param")
        nextTag(); parser.require(XmlPullParser.END_TAG, null, "params")
        nextTag(); parser.require(XmlPullParser.END_TAG, null, "methodResponse")
        val envelope = result as? Map<*, *> ?: throw IllegalArgumentException("Missing XAPI envelope.")
        when (envelope["Status"]) {
            "Success" -> require(envelope.containsKey("Value")) { "Missing XAPI value." }
            "Failure" -> {
                val description = envelope["ErrorDescription"] as? List<*>
                val code = description?.firstOrNull() as? String
                require(code != null && code.matches(Regex("[A-Z0-9_]+"))) { "Invalid XAPI failure." }
                throw RpcFailure(code, details = description.orEmpty().drop(1).filterIsInstance<String>().take(8).map { it.take(200) }) // A4
            }
            else -> throw IllegalArgumentException("Invalid XAPI status.")
        }
        return envelope["Value"]
    }
}

