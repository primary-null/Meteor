package com.meteor.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meteor.app.data.Inventory
import com.meteor.app.data.TargetAction
import com.meteor.app.data.TargetKind
import com.meteor.app.data.TargetRules
import com.meteor.app.storageView

/**
 * B2 storage/host: each SR's storage connections (PBDs) per host, with Plug for an unattached one
 * (repair). One confirmation; the PBD is read again before sending. Unplug and PBD destroy are not offered.
 */
@Composable internal fun ColumnScope.StorageRepairSection(inventory: Inventory) {
    val model = LocalMeteorModel.current ?: return
    val state by model.state.collectAsStateWithLifecycle()
    val controller = model.storageHost
    LaunchedEffect(inventory.authority, state.connected) { if (state.connected) controller.loadPbds(inventory.authority) }
    DisposableEffect(inventory.authority) { onDispose { controller.closePbds() } }
    val view = state.storageView
    val pbds = view.pbds?.takeIf { view.pbdAuthority == inventory.authority }
    val ready = controller.canWrite(state)
    Text("Storage connections", style = MaterialTheme.typography.titleMedium)
    if (view.pbdBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
    view.pbdProblem?.let { Text("Storage connections could not be read: $it", style = MaterialTheme.typography.bodySmall) }
    if (pbds != null) {
        if (pbds.isEmpty()) Text("No storage connections reported.", style = MaterialTheme.typography.bodySmall)
        val broken = pbds.count { !it.attached }
        Text(if (broken == 0) "Every storage repository is attached on every host." else "$broken connection${if (broken == 1) " is" else "s are"} not attached.",
            style = MaterialTheme.typography.bodySmall)
        pbds.groupBy { it.srUuid }.values.forEach { group ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(group.first().srName, style = MaterialTheme.typography.labelLarge)
                group.forEach { pbd ->
                    Text("${pbd.hostName} · ${if (pbd.attached) "Attached" else "Not attached"}${if (pbd.hostLive) "" else " · host not live"}",
                        style = MaterialTheme.typography.bodySmall)
                    if (!pbd.attached) TargetActionButton(TargetAction.PBD_PLUG, "Plug (repair)", TargetRules.unavailableReason(pbd, TargetAction.PBD_PLUG),
                        ready, state) { controller.request(TargetAction.PBD_PLUG, pbd.authority, TargetKind.PBD, pbd.uuid) }
                }
            }
        }
    } else if (!view.pbdBusy && view.pbdProblem == null) Text(if (state.connected) "Storage connections are not loaded." else "Connect to repair storage.",
        style = MaterialTheme.typography.bodySmall)
    storageWriteBlocker(state)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    TextButton(onClick = { controller.loadPbds(inventory.authority) }, enabled = state.connected && !view.pbdBusy) { Text("Reload storage connections") }
}
