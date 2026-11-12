package com.meteor.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp

/**
 * A2 operations: confirmation with an optional typed phrase. When [phrase] is non-null the confirm
 * button stays disabled until the typed text matches it exactly (case-sensitive, no trimming).
 * [key] resets the typed text whenever a different confirmation is shown.
 */
@Composable
fun TypedConfirmDialog(
    key: String, title: String, phrase: String?, confirmLabel: String, enabled: Boolean,
    onConfirm: (typed: String?) -> Unit, onDismiss: () -> Unit,
    // --- B2 storage/host --- What the phrase names, for example "snapshot name" or "host name".
    subject: String = "VM name",
    body: @Composable ColumnScope.() -> Unit,
) {
    var typed by rememberSaveable(key) { mutableStateOf("") }
    val matches = phrase == null || typed == phrase
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            body()
            if (phrase != null) {
                Text("Type the $subject exactly to confirm: $phrase", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(typed, { typed = it }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text(subject.replaceFirstChar { it.uppercase() }) },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                    isError = typed.isNotEmpty() && !matches,
                    supportingText = if (typed.isNotEmpty() && !matches) { { Text("Does not match. Names are case-sensitive.") } } else null)
            }
        }
    }, confirmButton = {
        TextButton(onClick = { if (matches) onConfirm(if (phrase == null) null else typed) }, enabled = enabled && matches) { Text(confirmLabel) }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}
