package com.meteor.app.data

import org.junit.Assert.*
import org.junit.Test

/** What a paste types and what it leaves out. */
class ConsolePasteTest {
    private fun typed(text: String) = ConsolePaste.plan(text).typed.map { it.toChar() }.joinToString("")

    @Test fun printableAsciiNewlinesAndTabsAreTyped() {
        val text = "Hello, World! ~`@#\t\nok"
        assertEquals(0, ConsolePaste.plan(text).skipped)
        assertEquals(text, typed(text))
    }

    @Test fun lineBreaksCountOnceWhateverTheirStyle() {
        assertEquals("a\nb\nc\nd", typed("a\r\nb\rc\nd"))
        assertEquals(0, ConsolePaste.plan("a\r\nb\rc\nd").skipped)
        assertEquals("a\n\nb", typed("a\r\n\r\nb"))
    }

    @Test fun charactersAUsKeyboardCannotTypeAreSkippedAndCounted() {
        val text = "café €5 😀 x\u0007y\u001b\u007f"
        val plan = ConsolePaste.plan(text)
        assertEquals("A US keyboard has no key for é, €, the emoji, BEL, ESC or DEL", 6, plan.skipped)
        assertEquals("caf 5  xy", typed(text))
    }

    @Test fun anEmojiIsOneSkippedCharacterNotTwo() {
        assertEquals(1, ConsolePaste.plan("😀").skipped)
        assertEquals(1, ConsolePaste.plan("\uD800").skipped)
    }

    @Test fun anAllUnsupportedPasteTypesNothing() {
        val plan = ConsolePaste.plan("éè")
        assertEquals(0, plan.typed.size); assertEquals(2, plan.skipped)
        assertEquals(0, ConsolePaste.plan("").skipped)
    }

    @Test fun everythingPlannedForTypingMapsToAKey() {
        val plan = ConsolePaste.plan((0..0x7f).map { it.toChar() }.joinToString(""))
        assertTrue(plan.typed.all { ConsoleKeys.chord(it) != null })
        assertEquals(95 + 3, plan.typed.size) // 0x20..0x7e, tab, newline and carriage return (typed as a newline)
    }
}
