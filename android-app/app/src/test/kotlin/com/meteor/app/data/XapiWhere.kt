package com.meteor.app.data

// --- inventory scale ---
/**
 * Evaluates the `*.get_all_records_where` expressions Meteor sends, for the test servers. The grammar is the part the real
 * XCP-ng 8.3.0 host was seen to accept (see the `scale-*` fixtures): `field "name"="literal"`, `and`, `or`, `not`,
 * parentheses, `true`/`false`; `_ref` is the record's own reference. A `"` inside a literal is refused, as the server does.
 * `and` binds tighter than `or`.
 */
object XapiWhere {
    fun unescape(xml: String) = xml.replace("&quot;", "\"").replace("&apos;", "'").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")

    fun <V : Map<String, Any?>> filter(records: Map<String, V>, expression: String): Map<String, V> {
        val parsed = parse(expression)
        return records.filter { (ref, record) -> parsed(ref) { record[it] } }
    }

    private fun text(value: Any?): String = when (value) { null -> ""; is Boolean -> value.toString(); else -> value.toString() }

    private fun tokens(expression: String): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < expression.length) {
            val c = expression[i]
            when {
                c.isWhitespace() -> i++
                c == '(' || c == ')' || c == '=' -> { out += c.toString(); i++ }
                c == '"' -> {
                    val end = expression.indexOf('"', i + 1)
                    require(end > i) { "INVALID_VALUE: unterminated literal" }
                    out += expression.substring(i, end + 1); i = end + 1
                    require(i >= expression.length || expression[i].isWhitespace() || expression[i] == '=' || expression[i] == ')') { "INVALID_VALUE: quote in literal" }
                }
                else -> { var j = i; while (j < expression.length && expression[j].isLetter()) j++; require(j > i) { "INVALID_VALUE: $c" }; out += expression.substring(i, j); i = j }
            }
        }
        return out
    }

    private fun parse(expression: String): (String, (String) -> Any?) -> Boolean {
        val t = tokens(expression)
        require(t.isNotEmpty()) { "INVALID_VALUE: empty" }
        var p = 0
        fun peek() = t.getOrNull(p)
        fun take(expected: String? = null): String {
            val token = t.getOrNull(p++) ?: throw IllegalArgumentException("INVALID_VALUE: truncated")
            require(expected == null || token == expected) { "INVALID_VALUE: expected $expected" }
            return token
        }
        fun literal() = take().also { require(it.startsWith("\"")) { "INVALID_VALUE: literal" } }.removeSurrounding("\"")
        lateinit var expr: () -> (String, (String) -> Any?) -> Boolean
        fun factor(): (String, (String) -> Any?) -> Boolean = when (val token = take()) {
            "not" -> factor().let { inner -> { ref, field -> !inner(ref, field) } }
            "(" -> expr().also { take(")") }
            "true" -> { _, _ -> true }
            "false" -> { _, _ -> false }
            "field" -> {
                val name = literal(); take("="); val value = literal()
                if (name == "_ref") { ref, _ -> ref == value } else { _, field -> text(field(name)) == value }
            }
            else -> throw IllegalArgumentException("INVALID_VALUE: $token")
        }
        fun term(): (String, (String) -> Any?) -> Boolean {
            var left = factor()
            while (peek() == "and") { take(); val l = left; val r = factor(); left = { ref, field -> l(ref, field) && r(ref, field) } }
            return left
        }
        expr = {
            var left = term()
            while (peek() == "or") { take(); val l = left; val r = term(); left = { ref, field -> l(ref, field) || r(ref, field) } }
            left
        }
        val result = expr()
        require(p == t.size) { "INVALID_VALUE: trailing" }
        return result
    }
}
// --- end inventory scale ---
