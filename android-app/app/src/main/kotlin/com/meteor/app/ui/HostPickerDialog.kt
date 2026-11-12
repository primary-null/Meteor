package com.meteor.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.meteor.app.data.HostChoice
import com.meteor.app.data.HostChoiceRequest
import com.meteor.app.data.HostTarget
import com.meteor.app.data.PowerRules

/**
 * A2 operations: host picker for start-on-host and live migration. Offers only enabled, live hosts
 * of the same pool (and not the current host for migration). Choosing a host re-reads the VM and
 * opens the normal confirmation; the host is read again before the request is sent.
 */
@Composable
fun HostPickerDialog(request: HostChoiceRequest, vmName: String, enabled: Boolean, onPick: (HostChoice) -> Unit, onDismiss: () -> Unit) {
    val candidates = PowerRules.hostCandidates(request.action, request.hosts, request.residentHostUuid)
    val excluded = request.hosts.size - candidates.size
    AlertDialog(onDismissRequest = onDismiss, title = { Text("${request.action.title}: $vmName") }, text = {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(if (request.action.hostTarget == HostTarget.OTHER_LIVE_HOST) "Choose another host in this pool." else "Choose a host in this pool.",
                style = MaterialTheme.typography.bodySmall)
            if (candidates.isEmpty()) Text("No eligible host is available. Hosts must be enabled and live.", style = MaterialTheme.typography.bodySmall)
            candidates.forEach { host ->
                ActionButton(host.name, { onPick(host) }, enabled = enabled)
            }
            if (excluded > 0) Text("$excluded host${if (excluded == 1) " is" else "s are"} not offered: current host, disabled or not live.",
                style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}
