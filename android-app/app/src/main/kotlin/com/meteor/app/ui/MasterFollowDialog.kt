package com.meteor.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.meteor.app.ControlGates
import com.meteor.app.MeteorState
import com.meteor.app.MeteorViewModel

// --- A4 alerts/events ---

/**
 * Shown when the saved endpoint answered HOST_IS_SLAVE. It explains and offers; it never switches by itself.
 * "Connect to master" starts the normal certificate review for the master's own certificate, and the profile is
 * updated only after the user trusts it and the login succeeds. The profile id, name, username and saved
 * password are kept.
 */
@Composable
fun MasterFollowDialog(state: MeteorState, model: MeteorViewModel) {
    val offer = state.masterOffer ?: return
    if (state.pendingTrust != null) return
    val pendingAction = state.operation?.let { it.unresolved && it.profileId == offer.profileId } == true
    AlertDialog(onDismissRequest = model::declineMaster, title = { Text("This address is a pool member") }, text = {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("${offer.memberEndpoint} answered HOST_IS_SLAVE: it is a member of a pool, not the pool master. The pool master reports its address as ${offer.masterAddress}.")
            Text("Meteor will not switch on its own. Connecting to the master first shows the master's certificate, which differs from this member's saved pin, and sends your credentials only after you trust it.",
                style = MaterialTheme.typography.bodySmall)
            Text("This connection keeps its name, username and saved password; only its address and certificate pin change.", style = MaterialTheme.typography.bodySmall)
            if (pendingAction) Text("A pending power action was recorded for this connection's current address and certificate. After switching, review that action manually; Meteor will not resend it.",
                style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = {
        TextButton(onClick = model::followMaster, enabled = !state.loading && !state.profileBusy && !ControlGates.operationExclusive(state)) {
            Text("Connect to master ${offer.masterAddress}")
        }
    }, dismissButton = { TextButton(onClick = model::declineMaster) { Text("Not now") } })
}
// --- end A4 ---
