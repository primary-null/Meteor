package com.meteor.app.data

import android.view.KeyEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** X11 keysyms used by the console. Printable Latin-1 characters use their own code point. */
object KeySyms {
    const val BACKSPACE = 0xff08
    const val TAB = 0xff09
    const val RETURN = 0xff0d
    const val PAUSE = 0xff13
    const val SCROLL_LOCK = 0xff14
    const val ESCAPE = 0xff1b
    const val HOME = 0xff50
    const val LEFT = 0xff51
    const val UP = 0xff52
    const val RIGHT = 0xff53
    const val DOWN = 0xff54
    const val PAGE_UP = 0xff55
    const val PAGE_DOWN = 0xff56
    const val END = 0xff57
    const val PRINT = 0xff61
    const val INSERT = 0xff63
    const val MENU = 0xff67
    const val NUM_LOCK = 0xff7f
    const val KP_ENTER = 0xff8d
    const val KP_DELETE = 0xff9f
    const val KP_MULTIPLY = 0xffaa
    const val KP_ADD = 0xffab
    const val KP_SUBTRACT = 0xffad
    const val KP_DECIMAL = 0xffae
    const val KP_DIVIDE = 0xffaf
    const val KP_0 = 0xffb0
    const val F1 = 0xffbe
    const val SHIFT_L = 0xffe1
    const val SHIFT_R = 0xffe2
    const val CONTROL_L = 0xffe3
    const val CONTROL_R = 0xffe4
    const val CAPS_LOCK = 0xffe5
    const val ALT_L = 0xffe9
    const val ALT_R = 0xffea
    const val SUPER_L = 0xffeb
    const val SUPER_R = 0xffec
    const val DELETE = 0xffff

    fun function(number: Int): Int {
        require(number in 1..12) { "Function keys F1 to F12 are supported." }
        return F1 + number - 1
    }

    fun isModifier(keysym: Int) = keysym in SHIFT_L..CONTROL_R || keysym in ALT_L..SUPER_R
}

/** A key plus whether the character needs Shift on a US-layout guest. */
data class KeyChord(val keysym: Int, val shift: Boolean)

object ConsoleKeys {
    private const val SHIFTED = "~!@#$%^&*()_+{}|:\"<>?"
    private const val BASE = "`1234567890-=[]\\;',./"

    /**
     * Maps one typed character to a key. Letters and punctuation follow the US layout (the guest's own
     * layout decides what it types). Control characters become their keys; characters beyond Latin-1 use
     * Unicode keysyms (0x01000000 + code point), which not every guest keyboard layer understands.
     */
    fun chord(codePoint: Int): KeyChord? = when {
        codePoint == '\n'.code || codePoint == '\r'.code -> KeyChord(KeySyms.RETURN, false)
        codePoint == '\t'.code -> KeyChord(KeySyms.TAB, false)
        codePoint == 8 -> KeyChord(KeySyms.BACKSPACE, false)
        codePoint == 0x7f -> KeyChord(KeySyms.DELETE, false)
        codePoint == 0x1b -> KeyChord(KeySyms.ESCAPE, false)
        codePoint < 0x20 -> null
        codePoint in 'A'.code..'Z'.code -> KeyChord(codePoint + 32, true)
        codePoint < 0x80 -> SHIFTED.indexOf(codePoint.toChar()).let { index ->
            if (index >= 0) KeyChord(BASE[index].code, true) else KeyChord(codePoint, false)
        }
        codePoint in 0xa0..0xff -> KeyChord(codePoint, false)
        codePoint in 0x80..0x9f || codePoint > 0x10ffff || codePoint in 0xd800..0xdfff -> null
        else -> KeyChord(0x01000000 or codePoint, false)
    }

    /**
     * Maps a hardware key to a keysym from its Android key code and its unmodified character (`KeyEvent`
     * `getUnicodeChar(0)`). Returns null for keys that must not reach the guest (Back, volume, ...).
     * Shift/Ctrl/Alt/Meta are forwarded as keys themselves; letters carry their unshifted keysym.
     */
    fun fromAndroid(keyCode: Int, unicode: Int): Int? = when (keyCode) {
        KeyEvent.KEYCODE_DEL -> KeySyms.BACKSPACE
        KeyEvent.KEYCODE_FORWARD_DEL -> KeySyms.DELETE
        KeyEvent.KEYCODE_ENTER -> KeySyms.RETURN
        KeyEvent.KEYCODE_NUMPAD_ENTER -> KeySyms.KP_ENTER
        KeyEvent.KEYCODE_TAB -> KeySyms.TAB
        KeyEvent.KEYCODE_ESCAPE -> KeySyms.ESCAPE
        KeyEvent.KEYCODE_DPAD_LEFT -> KeySyms.LEFT
        KeyEvent.KEYCODE_DPAD_UP -> KeySyms.UP
        KeyEvent.KEYCODE_DPAD_RIGHT -> KeySyms.RIGHT
        KeyEvent.KEYCODE_DPAD_DOWN -> KeySyms.DOWN
        KeyEvent.KEYCODE_MOVE_HOME -> KeySyms.HOME
        KeyEvent.KEYCODE_MOVE_END -> KeySyms.END
        KeyEvent.KEYCODE_PAGE_UP -> KeySyms.PAGE_UP
        KeyEvent.KEYCODE_PAGE_DOWN -> KeySyms.PAGE_DOWN
        KeyEvent.KEYCODE_INSERT -> KeySyms.INSERT
        KeyEvent.KEYCODE_MENU -> KeySyms.MENU
        KeyEvent.KEYCODE_SYSRQ -> KeySyms.PRINT
        KeyEvent.KEYCODE_BREAK -> KeySyms.PAUSE
        KeyEvent.KEYCODE_SCROLL_LOCK -> KeySyms.SCROLL_LOCK
        KeyEvent.KEYCODE_NUM_LOCK -> KeySyms.NUM_LOCK
        KeyEvent.KEYCODE_CAPS_LOCK -> KeySyms.CAPS_LOCK
        KeyEvent.KEYCODE_SHIFT_LEFT -> KeySyms.SHIFT_L
        KeyEvent.KEYCODE_SHIFT_RIGHT -> KeySyms.SHIFT_R
        KeyEvent.KEYCODE_CTRL_LEFT -> KeySyms.CONTROL_L
        KeyEvent.KEYCODE_CTRL_RIGHT -> KeySyms.CONTROL_R
        KeyEvent.KEYCODE_ALT_LEFT -> KeySyms.ALT_L
        KeyEvent.KEYCODE_ALT_RIGHT -> KeySyms.ALT_R
        KeyEvent.KEYCODE_META_LEFT -> KeySyms.SUPER_L
        KeyEvent.KEYCODE_META_RIGHT -> KeySyms.SUPER_R
        in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 -> KeySyms.function(keyCode - KeyEvent.KEYCODE_F1 + 1)
        in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> KeySyms.KP_0 + (keyCode - KeyEvent.KEYCODE_NUMPAD_0)
        KeyEvent.KEYCODE_NUMPAD_DOT -> KeySyms.KP_DECIMAL
        KeyEvent.KEYCODE_NUMPAD_ADD -> KeySyms.KP_ADD
        KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> KeySyms.KP_SUBTRACT
        KeyEvent.KEYCODE_NUMPAD_MULTIPLY -> KeySyms.KP_MULTIPLY
        KeyEvent.KEYCODE_NUMPAD_DIVIDE -> KeySyms.KP_DIVIDE
        else -> unicode.takeIf { it >= 0x20 && it != 0x7f }?.let { chord(it)?.keysym }
    }

    /** Android system keys that always stay with the system, never reaching the guest. */
    private fun isSystemKey(keyCode: Int) = keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_HOME ||
        keyCode == KeyEvent.KEYCODE_APP_SWITCH || keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
        keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_POWER

    /**
     * Passes one hardware key event to [input]. Returns true when the guest took it; false leaves the event to the
     * system, which is what happens for system keys, keys with no mapping, and every key in view-only mode.
     * [repeat] marks an auto-repeat key-down (a held key), which is forwarded as another key-down.
     */
    fun forward(input: ConsoleInput, keyCode: Int, unicode: Int, down: Boolean, repeat: Boolean): Boolean {
        if (input.viewOnly || isSystemKey(keyCode)) return false
        val keysym = fromAndroid(keyCode, unicode) ?: return false
        input.keyEvent(keysym, down, repeat && down)
        return true
    }
}

/** What the soft keyboard changed in the hidden field: [backspaces] deleted characters, then [text] typed. */
data class SoftKeyboardEdit(val backspaces: Int, val text: String)

/**
 * The rule behind the hidden soft-keyboard field. The field is never reset after an edit: the keyboard keeps the
 * text it wrote, so it sees one continuous field and keeps its layout (symbols stay until the person presses ABC).
 * Resetting the text after every edit made the toolkit restart the input connection each time, and the keyboard
 * then fell back to letters.
 *
 * The field starts as [PAD], a run of spaces. The pad exists so that Backspace has something to delete (a keyboard sends
 * Backspace on an empty field as a key event, or not at all). Each change the keyboard reports is compared with the text
 * the field held: everything after the common start is deleted (Backspace presses) and the rest is typed. That covers a
 * typed or committed character, a deleted one, an autocorrect that replaces the end of a word, and a swiped word. Only
 * when the pad is nearly used up, or the text has grown long, is the field replaced by a fresh pad ([Step.field]); the
 * keyboard may then fall back to letters once, which is rare and never in the middle of a symbol run.
 */
object SoftKeyboard {
    const val PAD_LENGTH = 16
    val PAD = " ".repeat(PAD_LENGTH)
    /** Fewer characters than this left (the pad is nearly deleted): start over with a fresh pad. */
    const val MIN_LENGTH = 4
    /** More characters than this (about 128 typed): start over, unless the keyboard is composing a word. */
    const val MAX_LENGTH = PAD_LENGTH + 128
    /** Even a composing field starts over beyond this. */
    const val HARD_LIMIT = 512

    /** [edit] is what to send; [field] is the text the hidden field must hold from now on. */
    data class Step(val edit: SoftKeyboardEdit, val field: String)

    /** [previous] is the field's text before the change, [next] what the keyboard changed it to, [composing] whether [next] has a composing region. */
    fun step(previous: String, next: String, composing: Boolean = false): Step {
        var common = 0
        val limit = minOf(previous.length, next.length)
        while (common < limit && previous[common] == next[common]) common++
        // Never split a surrogate pair: the whole character is deleted and typed again.
        if (common > 0 && common < previous.length && Character.isHighSurrogate(previous[common - 1])) common--
        val edit = SoftKeyboardEdit(previous.codePointCount(common, previous.length), next.substring(common))
        val startOver = next.length < MIN_LENGTH || next.length > HARD_LIMIT || (!composing && next.length > MAX_LENGTH)
        return Step(edit, if (startOver) PAD else next)
    }
}

/** The sticky state of one modifier button: one-shot (applies to the next key or click) or locked. */
enum class StickyMode { OFF, ARMED, LOCKED }

enum class ConsoleModifier(val keysym: Int, val label: String) {
    SHIFT(KeySyms.SHIFT_L, "Shift"), CTRL(KeySyms.CONTROL_L, "Ctrl"), ALT(KeySyms.ALT_L, "Alt"), SUPER(KeySyms.SUPER_L, "Super")
}

data class Modifiers(val shift: StickyMode = StickyMode.OFF, val ctrl: StickyMode = StickyMode.OFF,
    val alt: StickyMode = StickyMode.OFF, val superKey: StickyMode = StickyMode.OFF) {
    operator fun get(modifier: ConsoleModifier) = when (modifier) {
        ConsoleModifier.SHIFT -> shift; ConsoleModifier.CTRL -> ctrl; ConsoleModifier.ALT -> alt; ConsoleModifier.SUPER -> superKey
    }
    fun with(modifier: ConsoleModifier, mode: StickyMode) = when (modifier) {
        ConsoleModifier.SHIFT -> copy(shift = mode); ConsoleModifier.CTRL -> copy(ctrl = mode)
        ConsoleModifier.ALT -> copy(alt = mode); ConsoleModifier.SUPER -> copy(superKey = mode)
    }
    val any: Boolean get() = ConsoleModifier.entries.any { get(it) != StickyMode.OFF }
}

/** Receives the protocol-level events produced by [ConsoleInput]. */
interface ConsoleSink {
    fun key(keysym: Int, down: Boolean)
    fun pointer(x: Int, y: Int, buttons: Int)
}

/**
 * Turns UI actions (special keys, typed text, hardware keys, touch gestures) into key and pointer events.
 *
 * Sticky modifiers are pressed on the guest as soon as they are armed, so they also apply to pointer
 * clicks. A one-shot (armed) modifier is released after the next key press or click; a locked one stays
 * down until it is toggled off. [viewOnly] drops every event, and turning it on first releases anything
 * held. [releaseAll] is called when the console closes so no key or button stays stuck in the guest.
 * Methods are synchronized because gestures, the soft keyboard and lifecycle callbacks may overlap.
 */
class ConsoleInput(private val sink: ConsoleSink) {
    private val lock = Any()
    private val state = MutableStateFlow(Modifiers())
    val modifiers: StateFlow<Modifiers> = state.asStateFlow()
    private val held = linkedSetOf<Int>()
    private var buttons = 0
    private var x = 0
    private var y = 0
    private var viewOnlyFlag = false
    private var noCtrlAltDel = false

    /**
     * Set for a host console: Ctrl+Alt+Del is never sent, neither as [ctrlAltDel] nor as Delete pressed while Control and
     * Alt are down (sticky or held). On the hypervisor's own console it could restart the host outside Meteor's guarded,
     * journaled host reboot.
     */
    var blockCtrlAltDel: Boolean
        get() = synchronized(lock) { noCtrlAltDel }
        set(value) = synchronized(lock) { noCtrlAltDel = value }

    var viewOnly: Boolean
        get() = synchronized(lock) { viewOnlyFlag }
        set(value) = synchronized(lock) {
            if (value && !viewOnlyFlag) releaseLocked()
            viewOnlyFlag = value
        }

    /** True while a key or pointer button is down in the guest, including sticky modifiers. */
    val anyHeld: Boolean get() = synchronized(lock) { held.isNotEmpty() || buttons != 0 }

    private inline fun locked(block: () -> Unit) = synchronized(lock) { block() }

    /** Delete, keypad Delete and keypad decimal (the same physical key with Num Lock off). */
    private val DELETE_KEYS = setOf(KeySyms.DELETE, KeySyms.KP_DELETE, KeySyms.KP_DECIMAL)
    private fun ctrlAltChord(keysym: Int) = noCtrlAltDel && keysym in DELETE_KEYS &&
        (KeySyms.CONTROL_L in held || KeySyms.CONTROL_R in held) && (KeySyms.ALT_L in held || KeySyms.ALT_R in held)

    private fun send(keysym: Int, down: Boolean) {
        // A blocked Delete is dropped on the way down, and so is its release (it never reached the console).
        if (ctrlAltChord(keysym) && (down || keysym !in held)) return
        if (down) held += keysym else held -= keysym
        sink.key(keysym, down)
    }

    private fun shiftActive() = state.value.shift != StickyMode.OFF || KeySyms.SHIFT_L in held || KeySyms.SHIFT_R in held

    /** Off → one-shot → locked → off. */
    fun toggle(modifier: ConsoleModifier) = locked {
        if (viewOnlyFlag) return@locked
        when (state.value[modifier]) {
            StickyMode.OFF -> { send(modifier.keysym, true); state.value = state.value.with(modifier, StickyMode.ARMED) }
            StickyMode.ARMED -> state.value = state.value.with(modifier, StickyMode.LOCKED)
            StickyMode.LOCKED -> { send(modifier.keysym, false); state.value = state.value.with(modifier, StickyMode.OFF) }
        }
    }

    private fun consumeOneShots() {
        for (modifier in ConsoleModifier.entries) if (state.value[modifier] == StickyMode.ARMED) {
            send(modifier.keysym, false); state.value = state.value.with(modifier, StickyMode.OFF)
        }
    }

    /** Presses and releases a key such as Esc, Tab, an arrow or F5, then releases one-shot modifiers. */
    fun press(keysym: Int) = locked {
        if (viewOnlyFlag) return@locked
        send(keysym, true); send(keysym, false); consumeOneShots()
    }

    /** Types one character, adding Shift when the character needs it and Shift is not already held. */
    fun typeCodePoint(codePoint: Int) = locked {
        if (viewOnlyFlag) return@locked
        val chord = ConsoleKeys.chord(codePoint) ?: return@locked
        val addShift = chord.shift && !shiftActive()
        if (addShift) send(KeySyms.SHIFT_L, true)
        send(chord.keysym, true); send(chord.keysym, false)
        if (addShift) send(KeySyms.SHIFT_L, false)
        consumeOneShots()
    }

    fun type(text: String) {
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            typeCodePoint(codePoint)
            index += Character.charCount(codePoint)
        }
    }

    /**
     * A hardware key event passed through unchanged. One-shot modifiers end after a non-modifier key goes up.
     * With [repeat] set, a key-down for a key that is already down is sent again, as a VNC client does for
     * auto-repeat: the guest's emulated keyboard does not repeat held keys by itself. Modifiers never repeat,
     * and a key-down without [repeat] for a key already down is still dropped as a duplicate.
     */
    fun keyEvent(keysym: Int, down: Boolean, repeat: Boolean = false) = locked {
        if (viewOnlyFlag) return@locked
        if (down && repeat && keysym in held && !KeySyms.isModifier(keysym)) { sink.key(keysym, true); return@locked }
        if (down == (keysym in held)) return@locked
        send(keysym, down)
        if (!down && !KeySyms.isModifier(keysym)) consumeOneShots()
    }

    /** Control, Alt and Delete as one key sequence. It is not an XAPI reboot. */
    fun ctrlAltDel() = locked {
        if (viewOnlyFlag || noCtrlAltDel) return@locked
        val chord = listOf(KeySyms.CONTROL_L, KeySyms.ALT_L, KeySyms.DELETE)
        val pressed = chord.filter { it !in held }
        pressed.forEach { send(it, true) }
        chord.reversed().filter { it in pressed }.forEach { send(it, false) }
        consumeOneShots()
    }

    fun pointerMove(px: Int, py: Int) = locked {
        if (viewOnlyFlag) return@locked
        x = px; y = py
        sink.pointer(x, y, buttons)
    }

    fun buttonDown(button: Int, px: Int = x, py: Int = y) = locked {
        if (viewOnlyFlag) return@locked
        x = px; y = py; buttons = buttons or button
        sink.pointer(x, y, buttons)
    }

    fun buttonUp(button: Int, px: Int = x, py: Int = y) = locked {
        if (buttons and button == 0) return@locked
        x = px; y = py; buttons = buttons and button.inv()
        sink.pointer(x, y, buttons)
        consumeOneShots()
    }

    fun click(button: Int, px: Int, py: Int) = locked {
        if (viewOnlyFlag) return@locked
        x = px; y = py
        sink.pointer(x, y, buttons)
        buttons = buttons or button; sink.pointer(x, y, buttons)
        buttons = buttons and button.inv(); sink.pointer(x, y, buttons)
        consumeOneShots()
    }

    /** One or more wheel notches. [direction] is [RfbProtocol.WHEEL_UP], `WHEEL_DOWN`, `WHEEL_LEFT` or `WHEEL_RIGHT`. */
    fun wheel(direction: Int, ticks: Int, px: Int, py: Int) = locked {
        if (viewOnlyFlag || ticks <= 0) return@locked
        x = px; y = py
        sink.pointer(x, y, buttons)
        repeat(ticks.coerceAtMost(20)) {
            sink.pointer(x, y, buttons or direction)
            sink.pointer(x, y, buttons)
        }
    }

    fun releaseAll() = locked { releaseLocked() }

    private fun releaseLocked() {
        for (keysym in held.toList()) send(keysym, false)
        if (buttons != 0) { buttons = 0; sink.pointer(x, y, 0) }
        state.value = Modifiers()
    }
}

/** Converts accumulated finger travel into wheel notches, carrying the remainder between events. */
class ScrollAccumulator(private val stepPx: Float) {
    private var remainder = 0f
    /** Positive travel (finger down) yields positive notches. */
    fun add(delta: Float): Int {
        remainder += delta
        val ticks = (remainder / stepPx).toInt()
        remainder -= ticks * stepPx
        return ticks
    }
    fun reset() { remainder = 0f }
}
