package com.meteor.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.meteor.app.ControlGates
import com.meteor.app.MeteorState
import com.meteor.app.MeteorViewModel
import com.meteor.app.PendingTrust
import com.meteor.app.data.*
import java.text.DateFormat
import java.util.Date

// App-wide modal dialogs. `MeteorApp` shows them over every destination and layout, so a pending
// trust decision, power confirmation or review survives navigation, rotation and fold/unfold
// (their state lives in the view model).

@Composable internal fun TrustDialog(pending: PendingTrust, state: MeteorState, model: MeteorViewModel) {
    var verified by remember(pending) { mutableStateOf(false) }
    AlertDialog(onDismissRequest = model::cancelTrust, title = { Text("Verify this server") }, text = {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Compare this SHA-256 fingerprint with a trusted source for your XCP-ng server. Credentials are sent only after you accept.")
            pending.followedFrom?.let { member -> // A4: master follow
                Text("$member is a pool member and reported this address as the pool master. The master has its own certificate, so it differs from the member's saved pin.")
            }
            Text(pending.profile.endpoint, style = MaterialTheme.typography.bodySmall)
            Text(pending.certificate.subject, style = MaterialTheme.typography.bodySmall)
            Text("Valid from ${DateFormat.getDateInstance().format(Date(pending.certificate.validFrom))}\nExpires ${DateFormat.getDateInstance().format(Date(pending.certificate.validUntil))}", style = MaterialTheme.typography.bodySmall)
            Fingerprint("Observed SHA-256", pending.certificate.sha256)
            pending.previous?.let { previous ->
                if (previous.endpoint != pending.profile.endpoint || previous.certificateSha256 != pending.certificate.sha256) {
                    Text("This replaces the saved server identity. Verify the change independently before accepting.")
                    Text("Previously saved: ${previous.endpoint}", style = MaterialTheme.typography.bodySmall)
                    Fingerprint("Saved SHA-256", previous.certificateSha256)
                } else Text("The observed certificate matches the saved pin.", style = MaterialTheme.typography.bodySmall)
            }
            // --- vault recovery --- Adding a server and username that is already saved updates that connection instead of adding one.
            pending.previous?.takeIf { ConnectionProfiles.replacesOnAdd(it, state.editingProfileId, pending.followedFrom) }?.let { saved ->
                Text("A saved connection for this server and username already exists (${saved.label}). Trusting it replaces that connection's name and password with what you entered.")
            }
            // --- end vault recovery ---
            Text("Accepting pins this certificate for future connections.", style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(verified, { verified = it })
                Text("I compared the fingerprint with a trusted source.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }, confirmButton = { TextButton(onClick = model::acceptTrust, enabled = verified && !state.profileBusy && !ControlGates.operationExclusive(state)) { Text("Trust and connect") } },
        dismissButton = { TextButton(onClick = model::cancelTrust) { Text("Cancel") } })
}

@Composable internal fun ReviewDialog(review: PowerReview, state: MeteorState, model: MeteorViewModel) {
    var historyChecked by remember(review) { mutableStateOf(false) }
    AlertDialog(onDismissRequest = model::cancelReview, title = { Text("Review ${review.operation.vmName}") }, text = {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // Journaled operation hardening: a missing VM or an uninterpretable record has its own wording.
            Text("Original action: ${ReviewText.originalAction(review.operation)}\nCurrent state: ${ReviewText.currentState(review)}\nConnection: ${state.selectedProfile?.label.orEmpty()}")
            ReviewText.explanation(review).forEach { Text(it) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = historyChecked, onCheckedChange = { historyChecked = it })
                Text("I checked the server task history and confirmed this action is no longer pending.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }, confirmButton = { TextButton(onClick = { model.confirmReview(historyChecked) }, enabled = historyChecked && !state.operationBusy) { Text("Close reviewed record") } },
        dismissButton = { TextButton(onClick = model::cancelReview) { Text("Keep pending") } })
}
