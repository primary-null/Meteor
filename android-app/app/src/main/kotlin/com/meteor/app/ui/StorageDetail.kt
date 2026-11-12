package com.meteor.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.meteor.app.data.*
import java.util.Locale

/**
 * Expandable read-only detail for one storage repository, rendered under its row in the storage list. The read starts
 * only when the user opens it: connections per host, space versus allocation, and a bounded list of disks.
 */
@Composable
fun StorageDetail(srUuid: String) {
    val depth = LocalDepthUi.current ?: return
    val authority = depth.authority ?: return
    val key = "$authority:$srUuid"
    var open by remember(srUuid) { mutableStateOf(false) }
    val slot = depth.state.storage
    val info = slot.valueFor(key)
    TextButton(onClick = { open = !open }) { Text(if (open) "Hide repository detail" else "Show repository detail") }
    // The read starts only once the user has opened the detail.
    LaunchedEffect(key, open, depth.connected, depth.idle) {
        if (open && depth.canRead && slot.valueFor(key) == null && !slot.busyFor(key) && slot.problemFor(key) == null) depth.actions.loadStorage(srUuid)
    }
    if (!open) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { depth.actions.loadStorage(srUuid) }, enabled = depth.canRead && !slot.busyFor(key)) { Text("Refresh") }
            if (slot.busyFor(key)) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }
        slot.problemFor(key)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        if (info == null) return@Column
        info.warnings.forEach { Caution(it) }
        InfoRow("Sharing", if (info.shared) "Shared across the pool" else "Local to one host")
        InfoRow("Space", spaceText(info))
        info.overProvisioning?.let { ratio ->
            InfoRow("Virtual allocation", "${info.virtualAllocation?.let { HistoryFormat.size(it) } ?: "?"} promised · " +
                String.format(Locale.getDefault(), "%.2f×", ratio) + " of physical size")
            if (info.overProvisioned) Text("Over-provisioned: the disks promise more space than the repository physically has, which is normal for thin provisioning but needs watching.",
                style = MaterialTheme.typography.bodySmall)
        }
        SectionLabel("Host connections")
        if (info.hostLinks.isEmpty()) Text("No host connections reported", style = MaterialTheme.typography.bodySmall)
        info.hostLinks.forEach { link ->
            Text("${link.hostName} · ${when (link.attached) { true -> "attached"; false -> "not attached"; null -> "no connection" }}", style = MaterialTheme.typography.bodySmall)
        }
        SectionLabel("Disks (${info.totalVdis})")
        if (info.vdis.isEmpty()) Text("No disks", style = MaterialTheme.typography.bodySmall)
        info.vdis.forEach { vdi ->
            Text(buildString {
                append(vdi.name)
                when (vdi.kind) { VdiKind.SNAPSHOT -> append(" · snapshot"); VdiKind.BASE_COPY -> append(" · base copy"); VdiKind.DISK -> Unit }
                if (vdi.readOnly) append(" · read-only")
                append("\n").append(vdi.virtualSize?.let { "${HistoryFormat.size(it)} virtual" } ?: "size unknown")
                append(" · ").append(vdi.physicalUtilisation?.let { "${HistoryFormat.size(it)} used" } ?: "usage unknown")
                append("\n").append(if (vdi.attachedVms.isEmpty()) "Not attached to a VM" else "Used by ${vdi.attachedVms.joinToString(", ")}")
            }, style = MaterialTheme.typography.bodySmall)
        }
        if (info.totalVdis > info.vdis.size) Text("Showing ${info.vdis.size} of ${info.totalVdis} disks, largest first within that sample.", style = MaterialTheme.typography.labelSmall)
        Text("Read ${time(info.observedAt)}. Meteor does not change storage.", style = MaterialTheme.typography.labelSmall)
    }
}

private fun spaceText(info: StorageInfo): String {
    val size = info.physicalSize
    val used = info.physicalUtilisation
    return if (size != null && used != null && size > 0) {
        "${HistoryFormat.size(used)} used of ${HistoryFormat.size(size)} (${String.format(Locale.getDefault(), "%.0f", used * 100.0 / size)}%)"
    } else "Capacity not reported"
}
