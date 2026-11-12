package com.meteor.app.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/** Material 3's disabled content opacity. */
internal const val DISABLED_CONTENT_ALPHA = 0.38f

/**
 * The outlined button used for every VM, snapshot, CD, host and storage action and for the pickers that start one.
 *
 * A locked button (an uncertain operation blocks writes, a refresh is in progress, the server does not allow the action)
 * must look locked. Material 3 dims the outline to 12% and the label to 38% of `onSurface`; the label colour is set here
 * explicitly on both the button's colours and the text, so the dimmed label can never be overridden by an inherited
 * content colour while only the outline dims.
 */
@Composable
internal fun ActionButton(label: String, onClick: () -> Unit, enabled: Boolean, modifier: Modifier = Modifier) {
    val disabledLabel = MaterialTheme.colorScheme.onSurface.copy(alpha = DISABLED_CONTENT_ALPHA)
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth().then(modifier), enabled = enabled,
        colors = ButtonDefaults.outlinedButtonColors(disabledContainerColor = Color.Transparent, disabledContentColor = disabledLabel)) {
        Text(label, color = if (enabled) Color.Unspecified else disabledLabel)
    }
}
