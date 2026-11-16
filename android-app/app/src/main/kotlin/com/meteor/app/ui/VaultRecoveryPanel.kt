package com.meteor.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.meteor.app.MeteorState
import com.meteor.app.MeteorViewModel
import com.meteor.app.VaultRecoveryText

// --- vault recovery ---
/**
 * Shown at the top of Connections while the saved connections cannot be opened: what happened in plain words, what it means for this
 * session, and the two ways out. The wording and the rules for which button is offered live in [VaultRecoveryText] (JVM-tested).
 */
@Composable internal fun VaultRecoveryPanel(state: MeteorState, model: MeteorViewModel) {
    val panel = VaultRecoveryText.panel(state) ?: return
    var confirming by rememberSaveable { mutableStateOf(false) }
    Panel {
        Column(Modifier.testTag("vault-recovery-panel"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(panel.title, style = MaterialTheme.typography.titleMedium)
            Text(panel.body, style = MaterialTheme.typography.bodyMedium)
            if (panel.stillFailing) Text("Tried again, and the saved connections still can't be opened.", style = MaterialTheme.typography.bodyMedium)
            Text(panel.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = model::retryVault, enabled = panel.retryEnabled, modifier = Modifier.testTag("vault-retry")) { Text("Try again") }
            if (panel.startOverShown) OutlinedButton(onClick = { confirming = true }, enabled = panel.startOverEnabled,
                modifier = Modifier.testTag("vault-start-over")) { Text("Start over with saved connections") }
        }
    }
    if (confirming && panel.startOverShown) {
        val confirmation = VaultRecoveryText.confirmation(state)
        AlertDialog(onDismissRequest = { confirming = false }, title = { Text(confirmation.title) }, text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                confirmation.paragraphs.forEach { Text(it) }
            }
        }, confirmButton = {
            TextButton(onClick = { confirming = false; model.startOverVault() }, enabled = panel.startOverEnabled,
                modifier = Modifier.testTag("vault-start-over-confirm")) { Text(confirmation.confirmLabel) }
        }, dismissButton = { TextButton(onClick = { confirming = false }) { Text(confirmation.cancelLabel) } })
    }
}
// --- end vault recovery ---
