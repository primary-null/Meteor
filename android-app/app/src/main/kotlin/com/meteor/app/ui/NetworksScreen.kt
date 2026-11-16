package com.meteor.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.meteor.app.data.*

/** Entry point for the Networks view. Renders nothing unless the shell provided [LocalDepthUi]. */
@Composable
fun NetworksEntry() {
    val depth = LocalDepthUi.current ?: return
    OutlinedButton(onClick = depth.actions.openNetworks, enabled = depth.canRead) { Text("Networks") }
}

/** Read-only networks: bridge, MTU, VIF count and, per host, the physical, VLAN and bonded interfaces attached. */
@Composable
fun NetworksScreen(back: () -> Unit) {
    val depth = LocalDepthUi.current
    if (depth == null) { TextButton(onClick = back) { Text("Back") }; return }
    val authority = depth.authority
    val key = "$authority:networks"
    val slot = depth.state.networks
    val overview = slot.valueFor(key)
    LaunchedEffect(key, depth.connected, depth.idle) {
        if (authority != null && depth.canRead && slot.valueFor(key) == null && !slot.busyFor(key) && slot.problemFor(key) == null) depth.actions.loadNetworks()
    }
    LazyColumn(Modifier.fillMaxSize().testTag("networks-list"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Text("Networks", style = MaterialTheme.typography.headlineMedium)
            Text("Read-only view of the pool's networks and the interfaces attached to them.", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = back) { Text("Back") }
                OutlinedButton(onClick = depth.actions.loadNetworks, enabled = depth.canRead && !slot.busyFor(key)) { Text("Refresh networks") }
            }
        }
        if (slot.busyFor(key) && overview == null) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        slot.problemFor(key)?.let { problem -> item { Text(problem) } }
        if (overview != null) {
            item {
                Text("${overview.total} network${if (overview.total == 1) "" else "s"} · read ${time(overview.observedAt)}", style = MaterialTheme.typography.labelMedium)
                if (overview.total > overview.networks.size) Text("Showing the first ${overview.networks.size}.", style = MaterialTheme.typography.bodySmall)
            }
            if (overview.networks.isEmpty()) item { Text("No networks reported.") }
            items(overview.networks, key = { it.uuid }) { network -> NetworkCard(network) }
        } else if (!slot.busyFor(key) && slot.problemFor(key) == null) item { Text("Reading networks…") }
    }
}

@Composable
private fun NetworkCard(network: NetworkInfo) {
    DepthPanel {
        Text(network.name, style = MaterialTheme.typography.titleMedium)
        if (network.description.isNotBlank()) Text(network.description, style = MaterialTheme.typography.bodySmall)
        InfoRow("Bridge · MTU", listOf(network.bridge.ifBlank { "unknown bridge" }, network.mtu?.let { "MTU $it" } ?: "MTU unknown").joinToString(" · "))
        InfoRow("Virtual interfaces", "${network.vifCount} VIF${if (network.vifCount == 1) "" else "s"} attached")
        if (network.internal) Text("Internal network · no physical interface, so it does not leave the host.", style = MaterialTheme.typography.bodySmall)
        network.pifs.forEach { pif ->
            Text(buildString {
                append(pif.hostName).append(" · ").append(pif.device)
                pif.vlan?.let { append(" · VLAN $it") }
                when (pif.bond) { BondRole.MASTER -> append(" · bond"); BondRole.MEMBER -> append(" · bond member"); BondRole.NONE -> Unit }
                append("\n")
                append(when (pif.carrier) { true -> "Link up"; false -> "Link down"; null -> "Link unknown" })
                if (!pif.attached) append(" · not plugged")
                if (pif.ip.isNotBlank()) append(" · ${pif.ip}")
            }, style = MaterialTheme.typography.bodySmall)
        }
        if (network.pifTotal > network.pifs.size) Text("Showing ${network.pifs.size} of ${network.pifTotal} host interfaces.", style = MaterialTheme.typography.bodySmall) // inventory scale
    }
}
