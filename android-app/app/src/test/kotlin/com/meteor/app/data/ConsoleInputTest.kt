package com.meteor.app.data

import android.view.KeyEvent
import com.meteor.app.ui.applySoftKeyboardEdit
import org.junit.Assert.*
import org.junit.Test

/** Keysym mapping, sticky modifiers, pointer and wheel events, view-only and the soft-keyboard field rule. */
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

    // ---- hardware key repeat ----

    @Test fun aHeldHardwareKeyRepeatsAsFurtherKeyDownsWithoutAnUpInBetween() {
        val (sink, input) = input()
        input.keyEvent(KeySyms.BACKSPACE, true)
        input.keyEvent(KeySyms.BACKSPACE, true, repeat = true)
        input.keyEvent(KeySyms.BACKSPACE, true, repeat = true)
        input.keyEvent(KeySyms.BACKSPACE, false)
        assertEquals(listOf(KeySyms.BACKSPACE to true, KeySyms.BACKSPACE to true, KeySyms.BACKSPACE to true, KeySyms.BACKSPACE to false), sink.keys())
        assertFalse(input.anyHeld)
    }

    @Test fun repeatsNeverRepeatModifiersAndAnUnrepeatedDuplicateIsStillDropped() {
        val (sink, input) = input()
        input.keyEvent(KeySyms.SHIFT_L, true); input.keyEvent(KeySyms.SHIFT_L, true, repeat = true)
        input.keyEvent(KeySyms.CONTROL_R, true); input.keyEvent(KeySyms.CONTROL_R, true, repeat = true)
        input.keyEvent(KeySyms.LEFT, true); input.keyEvent(KeySyms.LEFT, true)
        assertEquals(listOf(KeySyms.SHIFT_L to true, KeySyms.CONTROL_R to true, KeySyms.LEFT to true), sink.keys())
    }

    @Test fun aRepeatForAKeyThatIsNotDownStartsItInsteadOfRepeating() {
        val (sink, input) = input()
        input.keyEvent('a'.code, true, repeat = true)
        assertEquals(listOf('a'.code to true), sink.keys())
        assertTrue(input.anyHeld)
    }

    @Test fun repeatsDoNotEndOneShotModifiersUntilTheKeyGoesUp() {
        val (sink, input) = input()
        input.toggle(ConsoleModifier.CTRL); sink.clear()
        input.keyEvent(KeySyms.RIGHT, true); input.keyEvent(KeySyms.RIGHT, true, repeat = true)
        assertEquals(listOf(KeySyms.RIGHT to true, KeySyms.RIGHT to true), sink.keys())
        assertEquals(StickyMode.ARMED, input.modifiers.value.ctrl)
        input.keyEvent(KeySyms.RIGHT, false)
        assertEquals(KeySyms.CONTROL_L to false, sink.keys().last())
    }

    @Test fun viewOnlySendsNoRepeatsAndLeavesHardwareKeysToTheSystem() {
        val (sink, input) = input()
        input.keyEvent(KeySyms.DOWN, true)
        input.viewOnly = true; sink.clear()
        input.keyEvent(KeySyms.DOWN, true, repeat = true)
        assertTrue(sink.events.isEmpty())
        assertFalse("A view-only console does not consume the key", ConsoleKeys.forward(input, KeyEvent.KEYCODE_DPAD_DOWN, 0, down = true, repeat = false))
        assertFalse(ConsoleKeys.forward(input, KeyEvent.KEYCODE_A, 'a'.code, down = true, repeat = true))
        assertTrue(sink.events.isEmpty())
    }

    @Test fun forwardingPassesRepeatsAndUpsAndKeepsSystemKeysAndUnmappedKeysForTheSystem() {
        val (sink, input) = input()
        assertTrue(ConsoleKeys.forward(input, KeyEvent.KEYCODE_DEL, 0, down = true, repeat = false))
        assertTrue(ConsoleKeys.forward(input, KeyEvent.KEYCODE_DEL, 0, down = true, repeat = true))
        assertTrue(ConsoleKeys.forward(input, KeyEvent.KEYCODE_DEL, 0, down = false, repeat = false))
        assertEquals(listOf(KeySyms.BACKSPACE to true, KeySyms.BACKSPACE to true, KeySyms.BACKSPACE to false), sink.keys())
        sink.clear()
        for (code in listOf(KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_APP_SWITCH, KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_CAMERA)) {
            assertFalse("key $code", ConsoleKeys.forward(input, code, 0, down = true, repeat = false))
        }
        assertTrue(sink.events.isEmpty())
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

    /**
     * Plays what a keyboard reports, as successive field texts, through [SoftKeyboard.step] the way the hidden field does.
     * [resets] counts the times the field was replaced, which is what makes a real keyboard fall back to its letters.
     */
    private class Field {
        var text = SoftKeyboard.PAD
        var resets = 0
        val edits = mutableListOf<SoftKeyboardEdit>()
        fun change(next: String, composing: Boolean = false): SoftKeyboardEdit {
            val step = SoftKeyboard.step(text, next, composing)
            if (step.field != next) resets++
            edits += step.edit; text = step.field
            return step.edit
        }
        fun type(typed: String, composing: Boolean = false) = change(text + typed, composing)
        fun backspace(count: Int = 1) = change(text.dropLast(count))
        /** What the guest sees, with `<` for a Backspace press. */
        fun sent() = edits.joinToString("") { "<".repeat(it.backspaces) + it.text }
    }

    @Test fun aRunOfSymbolsNeverReplacesTheFieldSoTheKeyboardKeepsItsSymbolLayout() {
        val field = Field()
        for (c in "@#$!?") assertEquals(SoftKeyboardEdit(0, c.toString()), field.type(c.toString()))
        assertEquals("@#$!?", field.sent())
        assertEquals(0, field.resets)
        assertEquals(SoftKeyboard.PAD + "@#$!?", field.text)
    }

    @Test fun backspaceDeletesTypedTextThenPadAndTheFieldStartsOverOnlyWhenThePadIsNearlyGone() {
        val field = Field()
        field.type("ab")
        assertEquals(SoftKeyboardEdit(1, ""), field.backspace()); assertEquals(SoftKeyboardEdit(1, ""), field.backspace())
        assertEquals(0, field.resets); assertEquals(SoftKeyboard.PAD, field.text)
        // Backspace with nothing typed still reaches the guest.
        assertEquals(SoftKeyboardEdit(1, ""), field.backspace()); assertEquals(0, field.resets)
        // A held Backspace: every press is sent; the field starts over once, when fewer than MIN_LENGTH characters remain.
        val before = field.edits.size
        repeat(SoftKeyboard.PAD_LENGTH - 1 - (SoftKeyboard.MIN_LENGTH - 1)) { field.backspace() }
        assertEquals(1, field.resets); assertEquals(SoftKeyboard.PAD, field.text)
        assertTrue(field.edits.drop(before).all { it == SoftKeyboardEdit(1, "") })
        // Typing goes on normally afterwards.
        assertEquals(SoftKeyboardEdit(0, "x"), field.type("x"))
    }

    @Test fun spaceAndEnterAreTypedAndReachTheGuest() {
        val field = Field()
        assertEquals(SoftKeyboardEdit(0, " "), field.type(" "))
        assertEquals(SoftKeyboardEdit(0, "\n"), field.type("\n"))
        val (sink, input) = input()
        field.edits.forEach { applySoftKeyboardEdit(input, it.backspaces, it.text) }
        assertEquals(tap(' '.code, KeySyms.RETURN), sink.keys())
        // A keyboard that sends Space as a key event instead of text reaches the guest as the same key.
        assertEquals(' '.code, ConsoleKeys.fromAndroid(KeyEvent.KEYCODE_SPACE, ' '.code))
    }

    @Test fun anAutocorrectThatRewritesTheEndOfAWordDeletesAndRetypesOnlyTheChangedTail() {
        val field = Field()
        field.type("t"); field.type("e"); field.type("h")
        assertEquals(SoftKeyboardEdit(2, "he"), field.change(SoftKeyboard.PAD + "the"))
        assertEquals("teh<<he", field.sent())
        assertEquals(0, field.resets)
    }

    @Test fun aSwipedWordCommittedAtOnceAndComposedLetterByLetterBothEndAsTheSameKeys() {
        val committed = Field(); committed.type("hello ")
        val composed = Field()
        listOf("h", "he", "hel", "hell", "hello").forEach { composed.change(SoftKeyboard.PAD + it, composing = true) }
        composed.change(SoftKeyboard.PAD + "hello ")
        assertEquals("hello ", committed.sent()); assertEquals("hello ", composed.sent())
        assertEquals(0, composed.resets)
        // A composing word replaced by the keyboard's choice: only the differing tail is retyped.
        val replaced = Field(); replaced.type("helo", composing = true)
        assertEquals(SoftKeyboardEdit(1, "lo"), replaced.change(SoftKeyboard.PAD + "hello", composing = true))
    }

    @Test fun anEmojiIsOneCharacterToDeleteAndTheCursorMovingWithoutTextSendsNothing() {
        val field = Field()
        field.type("a😀")
        assertEquals(SoftKeyboardEdit(1, ""), field.backspace(2))
        assertEquals(SoftKeyboardEdit(0, ""), field.change(field.text))
        assertEquals(0, field.resets)
    }

    @Test fun longTypingStartsOverOnceAndNothingTypedIsLost() {
        val field = Field(); val typed = "0123456789".repeat(15)
        typed.forEach { field.type(it.toString()) }
        assertEquals(typed, field.sent())
        assertEquals(1, field.resets)
        assertTrue(field.text.length < SoftKeyboard.MAX_LENGTH)
        // A composing keyboard is not interrupted by the soft limit, only by the hard one.
        val composing = Field(); repeat(SoftKeyboard.MAX_LENGTH) { composing.type("a", composing = true) }
        assertEquals(0, composing.resets)
        repeat(SoftKeyboard.HARD_LIMIT) { composing.type("a", composing = true) }
        assertTrue(composing.resets >= 1)
    }

    @Test fun softKeyboardEditsAreDroppedInViewOnlyMode() {
        val (sink, input) = input()
        input.viewOnly = true
        applySoftKeyboardEdit(input, 2, "ab")
        assertTrue(sink.events.isEmpty())
        input.viewOnly = false
        applySoftKeyboardEdit(input, 1, "a")
        assertEquals(tap(KeySyms.BACKSPACE, 'a'.code), sink.keys())
    }
}
