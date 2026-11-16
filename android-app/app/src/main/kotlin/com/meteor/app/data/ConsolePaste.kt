package com.meteor.app.data

/** What a paste will type: [typed] code points in order, and how many characters were left out. */
class PastePlan(val typed: IntArray, val skipped: Int)

object ConsolePaste {
    /**
     * A character can be typed on a US-layout guest when it is printable ASCII, a newline or a tab. Windows (CRLF) and old
     * Mac (CR) line breaks count as one Enter. Everything else (other control characters, accented letters, symbols
     * and emoji, which a US keyboard has no key for) is skipped and counted, never sent.
     */
    fun typable(codePoint: Int): Boolean = codePoint in 0x20..0x7e || codePoint == '\n'.code || codePoint == '\t'.code

    fun plan(text: String): PastePlan {
        val typed = ArrayList<Int>(text.length)
        var skipped = 0
        var index = 0
        while (index < text.length) {
            var codePoint = text.codePointAt(index)
            index += Character.charCount(codePoint)
            if (codePoint == '\r'.code) {
                if (index < text.length && text[index] == '\n') index++
                codePoint = '\n'.code
            }
            if (typable(codePoint)) typed += codePoint else skipped++
        }
        return PastePlan(typed.toIntArray(), skipped)
    }
}
