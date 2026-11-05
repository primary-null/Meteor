package com.meteor.app.data

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

class RpcFailure(val code: String) : Exception("XAPI request failed ($code)")

object XmlRpc {
    private fun escape(value: String): String = buildString {
        value.forEach { char ->
            require(char == '\n' || char == '\r' || char == '\t' || char.code >= 32) { "Invalid XML character." }
            append(when (char) { '&' -> "&amp;"; '<' -> "&lt;"; '>' -> "&gt;"; '"' -> "&quot;"; '\'' -> "&apos;"; else -> char.toString() })
        }
    }
    private fun value(input: Any?): String = when (input) {
        is Boolean -> "<boolean>${if (input) 1 else 0}</boolean>"
        is List<*> -> "<array><data>" + input.joinToString("") { "<value>${value(it)}</value>" } + "</data></array>"
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
        if (parser.name == "fault") throw RpcFailure("RPC_FAULT")
        parser.require(XmlPullParser.START_TAG, null, "params")
        nextTag(); parser.require(XmlPullParser.START_TAG, null, "param")
        nextTag()
        val result = parseValue(0)
        nextTag(); parser.require(XmlPullParser.END_TAG, null, "param")
        nextTag(); parser.require(XmlPullParser.END_TAG, null, "params")
        nextTag(); parser.require(XmlPullParser.END_TAG, null, "methodResponse")
        val envelope = result as? Map<*, *> ?: throw IllegalArgumentException("Missing XAPI envelope.")
        if (envelope["Status"] != "Success") {
            val code = (envelope["ErrorDescription"] as? List<*>)?.firstOrNull()?.toString().orEmpty()
            throw RpcFailure(code.takeIf { it.matches(Regex("[A-Z0-9_]+")) } ?: "UNKNOWN")
        }
        return envelope["Value"]
    }
}

