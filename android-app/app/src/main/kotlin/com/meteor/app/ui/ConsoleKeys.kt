package com.meteor.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.meteor.app.data.ConsoleInput
import com.meteor.app.data.ConsoleKeys
import com.meteor.app.data.ConsoleModifier
import com.meteor.app.data.HoldRepeat
import com.meteor.app.data.KeyRepeat
import com.meteor.app.data.KeySyms
import com.meteor.app.data.Modifiers
import com.meteor.app.data.SoftKeyboard
import com.meteor.app.data.StickyMode
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Special keys on the key bar, in display order. Modifiers and function keys are added by [ConsoleKeyBar]. */
private val KEY_BAR_KEYS = listOf(
    "Esc" to KeySyms.ESCAPE, "Tab" to KeySyms.TAB, "⌫" to KeySyms.BACKSPACE,
)
private val NAVIGATION_KEYS = listOf(
    "←" to KeySyms.LEFT, "↑" to KeySyms.UP, "↓" to KeySyms.DOWN, "→" to KeySyms.RIGHT,
    "Home" to KeySyms.HOME, "End" to KeySyms.END, "PgUp" to KeySyms.PAGE_UP, "PgDn" to KeySyms.PAGE_DOWN,
    "Ins" to KeySyms.INSERT, "Del" to KeySyms.DELETE,
)
private val NAVIGATION_DESCRIPTIONS = mapOf("←" to "Left arrow", "↑" to "Up arrow", "↓" to "Down arrow", "→" to "Right arrow",
    "⌫" to "Backspace")

/**
 * The special-key bar: Esc, Tab, sticky Ctrl/Alt/Shift/Super, arrows, Home/End/PgUp/PgDn/Ins/Del, F1–F12 and
 * Ctrl+Alt+Del (which asks for confirmation through [onCtrlAltDel]; null leaves it out, as on a host console). Tapping a modifier arms it for the next
 * key or click, tapping again locks it, a third tap releases it. "Middle click" arms the next tap on the screen
 * as a middle-button click. Arrows, Backspace, Delete, Tab and Page Up/Down repeat while held.
 */
@Composable
internal fun ConsoleKeyBar(enabled: Boolean, modifiers: Modifiers, onModifier: (ConsoleModifier) -> Unit,
    onKey: (Int) -> Unit, onCtrlAltDel: (() -> Unit)?, middleArmed: Boolean, onMiddle: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        KEY_BAR_KEYS.forEach { (label, keysym) -> KeyChip(label, enabled, NAVIGATION_DESCRIPTIONS[label], keysym, onKey) }
        ConsoleModifier.entries.forEach { modifierKey ->
            val mode = modifiers[modifierKey]
            FilterChip(selected = mode != StickyMode.OFF, onClick = { onModifier(modifierKey) }, enabled = enabled,
                label = { Text(if (mode == StickyMode.LOCKED) "${modifierKey.label} lock" else modifierKey.label) },
                modifier = Modifier.semantics {
                    stateDescription = when (mode) {
                        StickyMode.OFF -> "Off"
                        StickyMode.ARMED -> "On for the next key or click"
                        StickyMode.LOCKED -> "Locked on"
                    }
                })
        }
        FilterChip(selected = middleArmed, onClick = onMiddle, enabled = enabled, label = { Text("Middle click") },
            modifier = Modifier.semantics { stateDescription = if (middleArmed) "On for the next tap" else "Off" })
        NAVIGATION_KEYS.forEach { (label, keysym) -> KeyChip(label, enabled, NAVIGATION_DESCRIPTIONS[label], keysym, onKey) }
        (1..12).forEach { number -> KeyChip("F$number", enabled, null, KeySyms.function(number), onKey) }
        if (onCtrlAltDel != null) AssistChip(onClick = onCtrlAltDel, enabled = enabled, label = { Text("Ctrl+Alt+Del") })
    }
}

/**
 * A key button. A tap presses the key once. For keys that [KeyRepeat.repeats], holding the button presses it again
 * after a short delay and then at a steady rate until the finger lifts; the click that ends such a hold is ignored.
 */
@Composable
private fun KeyChip(label: String, enabled: Boolean, description: String?, keysym: Int, onKey: (Int) -> Unit) {
    val repeating = KeyRepeat.repeats(keysym)
    val source = remember { MutableInteractionSource() }
    val repeat = remember { HoldRepeat() }
    val send by rememberUpdatedState(onKey)
    if (repeating) LaunchedEffect(source, enabled) {
        if (!enabled) return@LaunchedEffect
        var holding: Job? = null
        source.interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Press -> { holding?.cancel(); repeat.reset(); holding = launch { repeat.run { send(keysym) } } }
                is PressInteraction.Release, is PressInteraction.Cancel -> { holding?.cancel(); holding = null }
            }
        }
    }
    AssistChip(onClick = { if (!repeating || !repeat.consumeTap()) onKey(keysym) }, enabled = enabled, label = { Text(label) },
        interactionSource = source,
        modifier = if (description != null) Modifier.semantics { contentDescription = description } else Modifier)
}

/**
 * An invisible text field that receives the soft keyboard. Each change it reports is converted to key events by
 * [SoftKeyboard.step] (typed text, or Backspace for deleted text), so characters, shifted symbols and uppercase letters
 * reach the guest as key presses. The field keeps what the keyboard wrote, because replacing it after every edit makes
 * the keyboard treat it as a new field and drop back to its letters layout. The cursor is kept at the end.
 */
@Composable
internal fun SoftKeyboardField(focus: FocusRequester, onEdit: (backspaces: Int, text: String) -> Unit, modifier: Modifier = Modifier) {
    var value by remember { mutableStateOf(TextFieldValue(SoftKeyboard.PAD, TextRange(SoftKeyboard.PAD.length))) }
    BasicTextField(value = value, onValueChange = { next ->
        val step = SoftKeyboard.step(value.text, next.text, next.composition != null)
        if (step.edit.backspaces > 0 || step.edit.text.isNotEmpty()) onEdit(step.edit.backspaces, step.edit.text)
        value = if (step.field == next.text) next.copy(selection = TextRange(next.text.length)) else TextFieldValue(step.field, TextRange(step.field.length))
    }, modifier = modifier.size(1.dp).alpha(0f).focusRequester(focus),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false,
            keyboardType = KeyboardType.Password, imeAction = ImeAction.None))
}

/** Sends a soft-keyboard edit to the guest as key presses. */
internal fun applySoftKeyboardEdit(input: ConsoleInput, backspaces: Int, text: String) {
    repeat(backspaces) { input.press(KeySyms.BACKSPACE) }
    if (text.isNotEmpty()) input.type(text)
}

/**
 * Hardware keyboard (and IME-synthesized key) events. Back, volume and other system keys are left alone, and so is
 * everything in view-only mode. Auto-repeat key-downs are forwarded: the guest's emulated keyboard does not repeat
 * a held key by itself. Returns true when the event was forwarded to the guest.
 */
internal fun forwardKeyEvent(event: KeyEvent, input: ConsoleInput): Boolean {
    val down = when (event.type) { KeyEventType.KeyDown -> true; KeyEventType.KeyUp -> false; else -> return false }
    val native = event.nativeKeyEvent
    return ConsoleKeys.forward(input, event.key.nativeKeyCode, native.getUnicodeChar(0), down, repeat = native.repeatCount > 0)
}
