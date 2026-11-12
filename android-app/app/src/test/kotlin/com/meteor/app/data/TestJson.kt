package com.meteor.app.data

/** Minimal strict JSON reader for JVM tests, where Android's org.json is only a stub. */
object TestJson {
    fun parse(text: String): Any? {
        val reader = Reader(text)
        val value = reader.value()
        reader.space()
        require(reader.index == text.length) { "Trailing JSON content." }
        return value
    }

    fun render(value: Any?): String = when (value) {
        null -> "null"
        is String -> "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
        is Number, is Boolean -> value.toString()
        is Map<*, *> -> value.entries.joinToString(",", "{", "}") { render(it.key.toString()) + ":" + render(it.value) }
        is List<*> -> value.joinToString(",", "[", "]") { render(it) }
        else -> throw IllegalArgumentException("Unsupported JSON value.")
    }

    @Suppress("UNCHECKED_CAST")
    fun objects(text: String, key: String = "operations"): List<Map<String, Any?>> =
        ((parse(text) as Map<String, Any?>)[key] as List<Any?>).map { it as Map<String, Any?> }

    private class Reader(val text: String) {
        var index = 0
        fun space() { while (index < text.length && text[index].isWhitespace()) index++ }
        fun value(): Any? {
            space()
            return when (val c = text[index]) {
                '{' -> obj()
                '[' -> array()
                '"' -> string()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c.isDigit()) number() else throw IllegalArgumentException("Unexpected JSON at $index.")
            }
        }
        private fun literal(word: String, result: Any?): Any? {
            require(text.startsWith(word, index)); index += word.length; return result
        }
        private fun number(): Any {
            val start = index
            while (index < text.length && (text[index].isDigit() || text[index] in "+-.eE")) index++
            val raw = text.substring(start, index)
            return raw.toLongOrNull() ?: raw.toDouble()
        }
        private fun string(): String {
            require(text[index++] == '"')
            val out = StringBuilder()
            while (true) {
                when (val c = text[index++]) {
                    '"' -> return out.toString()
                    '\\' -> when (val e = text[index++]) {
                        'n' -> out.append('\n'); 't' -> out.append('\t'); 'r' -> out.append('\r')
                        'b' -> out.append('\b'); 'f' -> out.append('\u000c')
                        'u' -> { out.append(text.substring(index, index + 4).toInt(16).toChar()); index += 4 }
                        else -> out.append(e)
                    }
                    else -> out.append(c)
                }
            }
        }
        private fun array(): List<Any?> {
            index++; val list = mutableListOf<Any?>(); space()
            if (text[index] == ']') { index++; return list }
            while (true) {
                list += value(); space()
                when (text[index++]) { ',' -> continue; ']' -> return list; else -> throw IllegalArgumentException("Bad array.") }
            }
        }
        private fun obj(): Map<String, Any?> {
            index++; val map = linkedMapOf<String, Any?>(); space()
            if (text[index] == '}') { index++; return map }
            while (true) {
                space(); val key = string(); space()
                require(text[index++] == ':')
                require(!map.containsKey(key)) { "Duplicate JSON key." }
                map[key] = value(); space()
                when (text[index++]) { ',' -> continue; '}' -> return map; else -> throw IllegalArgumentException("Bad object.") }
            }
        }
    }
}
