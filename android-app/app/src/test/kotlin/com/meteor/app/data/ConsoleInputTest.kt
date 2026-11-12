package com.meteor.app.data

import android.view.KeyEvent
import org.junit.Assert.*
import org.junit.Test

/** Keysym mapping, sticky modifiers, pointer and wheel events, view-only and the soft-keyboard diff. */
class ConsoleInputTest {
    private sealed interface Event {
        data class Key(val keysym: Int, val down: Boolean) : Event
        data class Pointer(val x: Int, val y: Int, val buttons: Int) : Event
    }
    private class Recorder : ConsoleSink {
        val events = mutableListOf<Event>()
        override fun key(keysym: Int, down: Boolean) { events += Event.Key(keysym, down) }
        override fun pointer(x: Int, y: Int, buttons: Int) { events += Event.Pointer(x, y, buttons) }
        fun keys() = events.filterIsInstance<Event.Key>().map { it.keysym to it.down }
        fun clear() = events.clear()
    }
    private fun input() = Recorder().let { it to ConsoleInput(it) }
    private fun tap(vararg keysyms: Int) = keysyms.flatMap { listOf(it to true, it to false) }

    // ---- character mapping ----

    @Test fun printableCharactersUseTheirOwnKeysym() {
        assertEquals(KeyChord('a'.code, false), ConsoleKeys.chord('a'.code))
        assertEquals(KeyChord('7'.code, false), ConsoleKeys.chord('7'.code))
        assertEquals(KeyChord(' '.code, false), ConsoleKeys.chord(' '.code))
        assertEquals(KeyChord('/'.code, false), ConsoleKeys.chord('/'.code))
        assertEquals(KeyChord(0xe9, false), ConsoleKeys.chord('é'.code))
        assertEquals(KeyChord(0x01000000 or 0x20ac, false), ConsoleKeys.chord('€'.code))
    }

    @Test fun uppercaseAndShiftedSymbolsUseTheBaseKeyWithShift() {
        assertEquals(KeyChord('a'.code, true), ConsoleKeys.chord('A'.code))
        assertEquals(KeyChord('z'.code, true), ConsoleKeys.chord('Z'.code))
        val shifted = "~!@#$%^&*()_+{}|:\"<>?"; val base = "`1234567890-=[]\\;',./"
        shifted.indices.forEach { assertEquals("${shifted[it]}", KeyChord(base[it].code, true), ConsoleKeys.chord(shifted[it].code)) }
    }

    @Test fun controlCharactersBecomeTheirKeysAndOthersAreDropped() {
        assertEquals(KeySyms.RETURN, ConsoleKeys.chord('\n'.code)!!.keysym)
        assertEquals(KeySyms.RETURN, ConsoleKeys.chord('\r'.code)!!.keysym)
        assertEquals(KeySyms.TAB, ConsoleKeys.chord('\t'.code)!!.keysym)
        assertEquals(KeySyms.BACKSPACE, ConsoleKeys.chord(8)!!.keysym)
        assertEquals(KeySyms.DELETE, ConsoleKeys.chord(0x7f)!!.keysym)
        assertEquals(KeySyms.ESCAPE, ConsoleKeys.chord(0x1b)!!.keysym)
        assertNull(ConsoleKeys.chord(1)); assertNull(ConsoleKeys.chord(0x85)); assertNull(ConsoleKeys.chord(0xd800))
    }

    @Test fun typingUppercaseWrapsTheKeyInShiftAndLowercaseDoesNot() {
        val (sink, input) = input()
        input.type("Ab!")
        assertEquals(listOf(KeySyms.SHIFT_L to true) + tap('a'.code).let { it } + (KeySyms.SHIFT_L to false) +
            tap('b'.code) + (KeySyms.SHIFT_L to true) + tap('1'.code) + (KeySyms.SHIFT_L to false), sink.keys())
    }

    @Test fun surrogatePairsAreOneUnicodeKey() {
        val (sink, input) = input()
        input.type("😀")
        assertEquals(tap(0x01000000 or 0x1f600), sink.keys())
    }

    // ---- special keys ----

    @Test fun functionKeysAreContiguousXKeysyms() {
        assertEquals(0xffbe, KeySyms.function(1)); assertEquals(0xffc9, KeySyms.function(12))
        assertThrows(IllegalArgumentException::class.java) { KeySyms.function(13) }
        val (sink, input) = input()
        input.press(KeySyms.function(5)); input.press(KeySyms.PAGE_DOWN)
        assertEquals(tap(0xffc2, 0xff56), sink.keys())
    }

    @Test fun hardwareKeyCodesMapToKeysyms() {
        assertEquals(KeySyms.BACKSPACE, ConsoleKeys.fromAndroid(KeyEvent.KEYCODE_DEL, 0))
        assertEquals(KeySyms.DELETE, ConsoleKeys.fromAndroid(KeyEvent.KEYCODE_FORWARD_DEL, 0))
        assertEquals(KeySyms.RETURN, ConsoleKeys.fromAndroid(KeyEvent.KEYCODE_ENTER, '\n'.code))
        assertEquals(KeySyms.LEFT, ConsoleKeys.fromAndroid(KeyEvent.KEYCODE_DPAD_LEFT, 0))
        assertEquals(KeySyms.HOME, ConsoleKeys.fromAndroid(KeyEvent.KEYCODE_MOVE_HOME, 0))
        assertEquals(KeySyms.function(1), ConsoleKeys.fromAndroid(KeyEvent.KEYCODE_F1, 0))
        assertEquals(KeySyms.function(12), ConsoleKeys.fromAndroid(KeyEvent.KEYCODE_F12, 0))
        assertEquals(KeySyms.KP_0 + 7, ConsoleKeys.fromAndroid(KeyEvent.KEYCODE_NUMPAD_7, '7'.code))
        assertEquals(KeySyms.SHIFT_L, ConsoleKeys.fromAndroid(KeyEvent.KEYCODE_SHIFT_LEFT, 0))
        assertEquals(KeySyms.CONTROL_R, ConsoleKeys.fromAndroid(KeyEvent.KEYCODE_CTRL_RIGHT, 0))
        assertEquals(KeySyms.SUPER_L, ConsoleKeys.fromAndroid(KeyEvent.KEYCODE_META_LEFT, 0))
        assertEquals('q'.code, ConsoleKeys.fromAndroid(KeyEvent.KEYCODE_Q, 'q'.code))
        assertEquals(' '.code, ConsoleKeys.fromAndroid(KeyEvent.KEYCODE_SPACE, ' '.code))
        assertNull("A key with no character and no mapping", ConsoleKeys.fromAndroid(KeyEvent.KEYCODE_VOLUME_UP, 0))
    }

    @Test fun hardwareKeysPassThroughWithOneDownAndOneUpAndIgnoreUnmatchedReleases() {
        val (sink, input) = input()
        input.keyEvent(KeySyms.SHIFT_L, true); input.keyEvent('a'.code, true); input.keyEvent('a'.code, true)
        input.keyEvent('a'.code, false); input.keyEvent('a'.code, false); input.keyEvent(KeySyms.SHIFT_L, false)
        assertEquals(listOf(KeySyms.SHIFT_L to true, 'a'.code to true, 'a'.code to false, KeySyms.SHIFT_L to false), sink.keys())
        assertFalse(input.anyHeld)
    }

    // ---- sticky modifiers ----

    @Test fun modifierCyclesOffArmedLockedOffAndPressesTheKeyOnTheGuest() {
        val (sink, input) = input()
        input.toggle(ConsoleModifier.CTRL)
        assertEquals(StickyMode.ARMED, input.modifiers.value.ctrl); assertEquals(listOf(KeySyms.CONTROL_L to true), sink.keys())
        input.toggle(ConsoleModifier.CTRL)
        assertEquals(StickyMode.LOCKED, input.modifiers.value.ctrl); assertEquals("Locking sends nothing new", 1, sink.keys().size)
        input.toggle(ConsoleModifier.CTRL)
        assertEquals(StickyMode.OFF, input.modifiers.value.ctrl); assertEquals(KeySyms.CONTROL_L to false, sink.keys().last())
        assertFalse(input.anyHeld)
    }

    @Test fun armedModifierAppliesToTheNextKeyOnlyAndLockedStays() {
        val (sink, input) = input()
        input.toggle(ConsoleModifier.CTRL)
        input.type("c")
        assertEquals(listOf(KeySyms.CONTROL_L to true) + tap('c'.code) + (KeySyms.CONTROL_L to false), sink.keys())
        assertEquals(StickyMode.OFF, input.modifiers.value.ctrl)
        sink.clear()
        input.toggle(ConsoleModifier.ALT); input.toggle(ConsoleModifier.ALT)
        input.press(KeySyms.TAB); input.press(KeySyms.TAB)
        assertEquals(StickyMode.LOCKED, input.modifiers.value.alt)
        assertEquals(listOf(KeySyms.ALT_L to true) + tap(KeySyms.TAB, KeySyms.TAB), sink.keys())
        assertTrue(input.anyHeld)
    }

    @Test fun armedShiftSuppliesTheShiftForUppercaseWithoutAddingAnother() {
        val (sink, input) = input()
        input.toggle(ConsoleModifier.SHIFT)
        input.type("Q")
        assertEquals(listOf(KeySyms.SHIFT_L to true) + tap('q'.code) + (KeySyms.SHIFT_L to false), sink.keys())
    }

    @Test fun armedModifiersAlsoApplyToClicksAndThenRelease() {
        val (sink, input) = input()
        input.toggle(ConsoleModifier.CTRL); sink.clear()
        input.click(RfbProtocol.LEFT, 10, 20)
        assertEquals(listOf(Event.Pointer(10, 20, 0), Event.Pointer(10, 20, 1), Event.Pointer(10, 20, 0), Event.Key(KeySyms.CONTROL_L, false)), sink.events)
    }

    @Test fun ctrlAltDelIsAKeySequenceThatLeavesNothingHeld() {
        val (sink, input) = input()
        input.ctrlAltDel()
        assertEquals(listOf(KeySyms.CONTROL_L to true, KeySyms.ALT_L to true, KeySyms.DELETE to true,
            KeySyms.DELETE to false, KeySyms.ALT_L to false, KeySyms.CONTROL_L to false), sink.keys())
        assertFalse(input.anyHeld)
        sink.clear(); input.toggle(ConsoleModifier.CTRL); sink.clear()
        input.ctrlAltDel()
        assertEquals("An already-held Ctrl is reused, then released as the one-shot it was",
            listOf(KeySyms.ALT_L to true, KeySyms.DELETE to true, KeySyms.DELETE to false, KeySyms.ALT_L to false, KeySyms.CONTROL_L to false), sink.keys())
    }

    // ---- pointer ----

    @Test fun tapAndLongPressAndDragProduceButtonSequences() {
        val (sink, input) = input()
        input.click(RfbProtocol.LEFT, 5, 6)
        assertEquals(listOf(Event.Pointer(5, 6, 0), Event.Pointer(5, 6, 1), Event.Pointer(5, 6, 0)), sink.events)
        sink.clear()
        input.buttonDown(RfbProtocol.RIGHT, 7, 8)
        assertTrue(input.anyHeld)
        input.pointerMove(9, 10)
        input.buttonUp(RfbProtocol.RIGHT)
        assertEquals(listOf(Event.Pointer(7, 8, 4), Event.Pointer(9, 10, 4), Event.Pointer(9, 10, 0)), sink.events)
        assertFalse(input.anyHeld)
    }

    @Test fun wheelNotchesPressAndReleaseButtons4And5() {
        val (sink, input) = input()
        input.wheel(RfbProtocol.WHEEL_DOWN, 2, 3, 4)
        assertEquals(listOf(Event.Pointer(3, 4, 0), Event.Pointer(3, 4, 16), Event.Pointer(3, 4, 0), Event.Pointer(3, 4, 16), Event.Pointer(3, 4, 0)), sink.events)
        sink.clear(); input.wheel(RfbProtocol.WHEEL_UP, 0, 0, 0)
        assertTrue(sink.events.isEmpty())
        input.wheel(RfbProtocol.WHEEL_UP, 100, 0, 0)
        assertEquals("A runaway gesture is capped", 1 + 40, sink.events.size)
    }

    @Test fun scrollAccumulatorCarriesRemaindersBetweenEvents() {
        val acc = ScrollAccumulator(10f)
        assertEquals(0, acc.add(4f)); assertEquals(1, acc.add(7f)); assertEquals(0, acc.add(2f)); assertEquals(-1, acc.add(-14f)); assertEquals(2, acc.add(30f))
        acc.reset(); assertEquals(0, acc.add(9f))
    }

    // ---- view only and release ----

    @Test fun viewOnlySuppressesEveryInputAndReleasesWhatWasHeld() {
        val (sink, input) = input()
        input.toggle(ConsoleModifier.SHIFT); input.buttonDown(RfbProtocol.LEFT, 1, 2); sink.clear()
        input.viewOnly = true
        assertEquals(listOf<Event>(Event.Key(KeySyms.SHIFT_L, false), Event.Pointer(1, 2, 0)), sink.events)
        assertFalse(input.anyHeld); assertEquals(Modifiers(), input.modifiers.value)
        sink.clear()
        input.type("hello"); input.press(KeySyms.TAB); input.toggle(ConsoleModifier.CTRL); input.ctrlAltDel()
        input.keyEvent('a'.code, true); input.click(RfbProtocol.LEFT, 1, 1); input.pointerMove(3, 3)
        input.buttonDown(RfbProtocol.LEFT); input.wheel(RfbProtocol.WHEEL_UP, 1, 0, 0)
        assertTrue(sink.events.isEmpty())
        input.viewOnly = false
        input.press(KeySyms.ESCAPE)
        assertEquals(tap(KeySyms.ESCAPE), sink.keys())
    }

    @Test fun releaseAllFreesEveryHeldKeyAndButton() {
        val (sink, input) = input()
        input.toggle(ConsoleModifier.CTRL); input.toggle(ConsoleModifier.ALT); input.toggle(ConsoleModifier.ALT)
        input.keyEvent('x'.code, true); input.buttonDown(RfbProtocol.LEFT, 4, 4); sink.clear()
        input.releaseAll()
        assertEquals(setOf(KeySyms.CONTROL_L, KeySyms.ALT_L, 'x'.code), sink.keys().filter { !it.second }.map { it.first }.toSet())
        assertEquals(Event.Pointer(4, 4, 0), sink.events.last())
        assertFalse(input.anyHeld); assertEquals(Modifiers(), input.modifiers.value)
    }

    // ---- soft keyboard bridge ----

    @Test fun softKeyboardDiffTypesAppendedTextAndTurnsADeletedSentinelIntoBackspace() {
        assertEquals(SoftKeyboardEdit(0, ""), SoftKeyboard.diff(SoftKeyboard.SENTINEL))
        assertEquals(SoftKeyboardEdit(0, "a"), SoftKeyboard.diff(SoftKeyboard.SENTINEL + "a"))
        assertEquals(SoftKeyboardEdit(0, "Hi!"), SoftKeyboard.diff(SoftKeyboard.SENTINEL + "Hi!"))
        assertEquals(SoftKeyboardEdit(1, ""), SoftKeyboard.diff(""))
        assertEquals("A replaced text is typed in full", SoftKeyboardEdit(1, "xyz"), SoftKeyboard.diff("xyz"))
    }
}
