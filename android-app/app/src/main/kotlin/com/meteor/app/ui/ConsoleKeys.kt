package com.meteor.app.ui

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.meteor.app.data.KeySyms
import com.meteor.app.data.Modifiers
import com.meteor.app.data.SoftKeyboard
import com.meteor.app.data.StickyMode

/** Special keys on the key bar, in display order. Modifiers and function keys are added by [ConsoleKeyBar]. */
private val KEY_BAR_KEYS = listOf(
    "Esc" to KeySyms.ESCAPE, "Tab" to KeySyms.TAB,
)
private val NAVIGATION_KEYS = listOf(
    "←" to KeySyms.LEFT, "↑" to KeySyms.UP, "↓" to KeySyms.DOWN, "→" to KeySyms.RIGHT,
    "Home" to KeySyms.HOME, "End" to KeySyms.END, "PgUp" to KeySyms.PAGE_UP, "PgDn" to KeySyms.PAGE_DOWN,
    "Ins" to KeySyms.INSERT, "Del" to KeySyms.DELETE,
)
private val NAVIGATION_DESCRIPTIONS = mapOf("←" to "Left arrow", "↑" to "Up arrow", "↓" to "Down arrow", "→" to "Right arrow")

/**
 * The special-key bar: Esc, Tab, sticky Ctrl/Alt/Shift/Super, arrows, Home/End/PgUp/PgDn/Ins/Del, F1–F12 and
 * Ctrl+Alt+Del (which asks for confirmation through [onCtrlAltDel]). Tapping a modifier arms it for the next
 * key or click, tapping again locks it, a third tap releases it.
 */
@Composable
internal fun ConsoleKeyBar(enabled: Boolean, modifiers: Modifiers, onModifier: (ConsoleModifier) -> Unit,
    onKey: (Int) -> Unit, onCtrlAltDel: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        KEY_BAR_KEYS.forEach { (label, keysym) -> KeyChip(label, enabled) { onKey(keysym) } }
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
        NAVIGATION_KEYS.forEach { (label, keysym) -> KeyChip(label, enabled, NAVIGATION_DESCRIPTIONS[label]) { onKey(keysym) } }
        (1..12).forEach { number -> KeyChip("F$number", enabled) { onKey(KeySyms.function(number)) } }
        KeyChip("Ctrl+Alt+Del", enabled, onClick = onCtrlAltDel)
    }
}

@Composable
private fun KeyChip(label: String, enabled: Boolean, description: String? = null, onClick: () -> Unit) {
    AssistChip(onClick = onClick, enabled = enabled, label = { Text(label) },
        modifier = if (description != null) Modifier.semantics { contentDescription = description } else Modifier)
}

/**
 * An invisible one-character text field that receives the soft keyboard. Every change is converted to key
 * events (typed text, or Backspace when the sentinel is deleted) and the field is reset, so characters, shifted
 * symbols and uppercase letters all reach the guest as key presses rather than as edited text.
 */
@Composable
internal fun SoftKeyboardField(focus: FocusRequester, onEdit: (backspaces: Int, text: String) -> Unit, modifier: Modifier = Modifier) {
    var value by remember { mutableStateOf(TextFieldValue(SoftKeyboard.SENTINEL, TextRange(SoftKeyboard.SENTINEL.length))) }
    BasicTextField(value = value, onValueChange = { next ->
        val edit = SoftKeyboard.diff(next.text)
        if (edit.backspaces > 0 || edit.text.isNotEmpty()) onEdit(edit.backspaces, edit.text)
        value = TextFieldValue(SoftKeyboard.SENTINEL, TextRange(SoftKeyboard.SENTINEL.length))
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
 * Hardware keyboard (and IME-synthesized key) events. Back, volume and other system keys are left alone.
 * Returns true when the event was forwarded to the guest.
 */
internal fun forwardKeyEvent(event: KeyEvent, input: ConsoleInput): Boolean {
    val native = event.nativeKeyEvent
    val code = event.key.nativeKeyCode
    if (code == AndroidKeyEvent.KEYCODE_BACK || code == AndroidKeyEvent.KEYCODE_HOME || code == AndroidKeyEvent.KEYCODE_APP_SWITCH ||
        code == AndroidKeyEvent.KEYCODE_VOLUME_UP || code == AndroidKeyEvent.KEYCODE_VOLUME_DOWN || code == AndroidKeyEvent.KEYCODE_POWER) return false
    val keysym = ConsoleKeys.fromAndroid(code, native.getUnicodeChar(0)) ?: return false
    return when (event.type) {
        // The guest repeats a held key itself, so repeated downs from the keyboard are swallowed.
        KeyEventType.KeyDown -> { if (native.repeatCount == 0) input.keyEvent(keysym, true); true }
        KeyEventType.KeyUp -> { input.keyEvent(keysym, false); true }
        else -> false
    }
}
