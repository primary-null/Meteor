package com.meteor.app.ui

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meteor.app.data.Host
import com.meteor.app.data.Inventory
import com.meteor.app.data.RoleRules
import com.meteor.app.data.TargetAction
import com.meteor.app.data.TargetKind
import com.meteor.app.data.TargetRules
import com.meteor.app.storageView

/**
 * B2 storage/host: host maintenance (disable, evacuate with blockers, enable) and reboot/shutdown,
 * which are offered only for a disabled host without resident VMs. Each is a separate confirmed
 * step; nothing is chained. Content scrolls with the host dialog or pane.
 */
@Composable internal fun ColumnScope.HostOperationsSection(host: Host, inventory: Inventory) {
    val model = LocalMeteorModel.current ?: return
    val state by model.state.collectAsStateWithLifecycle()
    val controller = model.storageHost
    LaunchedEffect(inventory.authority, host.uuid, state.connected) { if (state.connected) controller.loadHost(inventory.authority, host.uuid) }
    DisposableEffect(host.uuid) { onDispose { controller.closeHost(host.uuid) } }
    val view = state.storageView
    val current = view.host?.takeIf { it.uuid == host.uuid && it.authority == inventory.authority }
    val ready = controller.canWrite(state)
    Text("Maintenance", style = MaterialTheme.typography.titleMedium)
    if (view.hostBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
    view.hostProblem?.let { Text("Host state could not be read: $it", style = MaterialTheme.typography.bodySmall) }
    if (current == null) {
        if (!view.hostBusy && view.hostProblem == null) Text(if (state.connected) "Host state is not loaded." else "Connect to manage this host.",
            style = MaterialTheme.typography.bodySmall)
    } else {
        Text("${current.summary}${if (current.master) " · pool coordinator" else ""}", style = MaterialTheme.typography.bodySmall)
        if (current.residentVms.isNotEmpty()) Text("Running here: ${current.residentVms.joinToString()}", style = MaterialTheme.typography.bodySmall)
        current.blockers?.takeIf { it.isNotEmpty() }?.let { blockers ->
            Text("Prevent evacuation:", style = MaterialTheme.typography.labelLarge)
            blockers.forEach { Text("• ${it.vmName}: ${it.code}", style = MaterialTheme.typography.bodySmall) }
        }
        current.blockersProblem?.let { Text("Evacuation blockers could not be read ($it). The server still checks them.", style = MaterialTheme.typography.bodySmall) }
        val send = { action: TargetAction -> controller.request(action, current.authority, TargetKind.HOST, current.uuid) }
        listOf(TargetAction.HOST_DISABLE to "Disable host", TargetAction.HOST_EVACUATE to "Evacuate host…", TargetAction.HOST_ENABLE to "Enable host")
            .forEach { (action, label) -> TargetActionButton(action, label, TargetRules.unavailableReason(current, action), ready, state) { send(action) } }
        Text("Restart", style = MaterialTheme.typography.titleMedium)
        if (current.master) Text("This host is the pool coordinator. Rebooting or shutting it down drops Meteor's connection and the result stays uncertain until it is checked again.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        listOf(TargetAction.HOST_REBOOT to "Reboot host…", TargetAction.HOST_SHUTDOWN to "Shut down host…")
            .forEach { (action, label) -> TargetActionButton(action, label, TargetRules.unavailableReason(current, action), ready, state) { send(action) } }
    }
    storageWriteBlocker(state)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    TextButton(onClick = { controller.loadHost(inventory.authority, host.uuid) }, enabled = state.connected && !view.hostBusy) { Text("Reload host state") }
    Text("Maintenance is separate steps: disable, evacuate, then reboot or shut down; enable the host afterwards. ${RoleRules.summary(state.selectedPermissions)}",
        style = MaterialTheme.typography.bodySmall)
}
