package com.meteor.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.meteor.app.InfoSlot
import com.meteor.app.data.*

/**
 * Loads once per object when the section appears, and again when the connection becomes readable after a skipped read.
 * A slot marked stale (the VM moved, restarted or changed power state, an operation on it closed, or its details were
 * opened or refreshed) is read again the same way, as soon as reads are allowed. A failed read is not retried by itself.
 */
@Composable
private fun <T> LoadOnce(depth: DepthUi, slot: InfoSlot<T>, key: String, load: () -> Unit) {
    val stale = slot.staleFor(key)
    LaunchedEffect(key, depth.connected, depth.idle, stale) {
        if (depth.canRead && (slot.valueFor(key) == null || stale) && !slot.busyFor(key) && slot.problemFor(key) == null) load()
    }
}

@Composable
private fun Loading(slot: InfoSlot<*>, key: String, refresh: () -> Unit, canRead: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = refresh, enabled = canRead && !slot.busyFor(key)) { Text("Refresh") }
        if (slot.busyFor(key)) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
    }
    slot.problemFor(key)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    if (slot.valueFor(key) == null && !slot.busyFor(key) && slot.problemFor(key) == null) Text("Details have not been read yet.", style = MaterialTheme.typography.bodySmall)
}

/** Read-only VM facts from the server record: nothing here changes the VM. Rendered inside the VM detail. */
@Composable
fun VmDetailSections(vmUuid: String) {
    val depth = LocalDepthUi.current ?: return
    val authority = depth.authority ?: return
    val key = "$authority:$vmUuid"
    val slot = depth.state.vm
    val info = slot.valueFor(key)
    LoadOnce(depth, slot, key) { depth.actions.loadVm(vmUuid) }
    val context = LocalContext.current
    var copied by remember(vmUuid) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel("Identity and configuration")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { InfoRow("UUID", vmUuid, selectable = true) }
            TextButton(onClick = {
                context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("VM UUID", vmUuid))
                copied = true
            }) { Text(if (copied) "Copied" else "Copy") }
        }
        Loading(slot, key, { depth.actions.loadVm(vmUuid) }, depth.canRead)
        if (info == null) return@Column
        if (info.description.isNotBlank()) InfoRow("Description", info.description)
        InfoRow("Firmware", when (info.firmware) { "UEFI" -> "UEFI"; "BIOS" -> "BIOS"; "PV" -> "Paravirtualized (no firmware)"; else -> "Not reported" })
        InfoRow("Secure Boot", when (info.secureBoot) {
            SecureBoot.ON -> "On"; SecureBoot.OFF -> "Off"; SecureBoot.OTHER -> "Custom setting"; SecureBoot.NOT_UEFI -> "Not applicable (not UEFI)"
        })
        InfoRow("Virtual TPM", when (info.vtpm) { true -> "Present"; false -> "None"; null -> "Not reported by this server" })
        InfoRow("HA restart priority", VmInfoMapper.haLabel(info.haRestartPriority))
        InfoRow("Auto power-on", if (info.autoPowerOn) "Enabled for this VM" else "Not enabled")
        if (info.autoPowerOn && info.poolAutoPowerOn == false) Caution("The pool-wide auto power-on flag is off, so this VM will not start automatically.")
        InfoRow("vCPUs", "${info.vcpusMax ?: "?"} maximum · ${info.vcpusAtStartup ?: "?"} at startup")
        InfoRow("Memory", listOf(
            if (info.memoryDynamicMin != null && info.memoryDynamicMax != null) "Dynamic ${HistoryFormat.size(info.memoryDynamicMin)} – ${HistoryFormat.size(info.memoryDynamicMax)}" else null,
            info.memoryStaticMax?.let { "static maximum ${HistoryFormat.size(it)}" },
        ).filterNotNull().joinToString(" · ").ifBlank { "Not reported" })
        InfoRow("Tags", info.tags.joinToString(", ").ifBlank { "None" } +
            if (info.tagTotal > info.tags.size) " (first ${info.tags.size} of ${info.tagTotal})" else "") // inventory scale
        // CD drives and snapshots are shown by B2's CdDriveSection and SnapshotsSection, which add the actions.
        Text("Read from the server ${time(info.observedAt)}. Meteor does not change these settings.", style = MaterialTheme.typography.labelSmall)
    }
}

/** Read-only host facts: hardware, software, management address, NICs and clock. Rendered inside the host detail. */
@Composable
fun HostDetailSections(hostUuid: String) {
    val depth = LocalDepthUi.current ?: return
    val authority = depth.authority ?: return
    val key = "$authority:$hostUuid"
    val slot = depth.state.host
    val info = slot.valueFor(key)
    LoadOnce(depth, slot, key) { depth.actions.loadHost(hostUuid) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel("Hardware and software")
        Loading(slot, key, { depth.actions.loadHost(hostUuid) }, depth.canRead)
        if (info == null) return@Column
        InfoRow("Role", if (info.coordinator) "Pool coordinator" else "Pool member")
        InfoRow("State", if (info.enabled) "Enabled" else "Disabled (maintenance mode)")
        if (!info.enabled) Caution("This host is disabled. It will not start or accept VMs until it is enabled.")
        InfoRow("CPU", listOfNotNull(info.cpuModel.ifBlank { null },
            info.cpuCount?.let { "$it logical CPUs" }, info.socketCount?.let { "$it sockets" }, info.cpuSpeedMhz?.let { "$it MHz" })
            .joinToString(" · ").ifBlank { "Not reported" })
        InfoRow("Software", listOfNotNull(info.productBrand, info.productVersion, info.buildNumber?.let { "build $it" }).joinToString(" ").ifBlank { "Not reported" })
        InfoRow("xapi / Xen", listOfNotNull(info.xapiVersion?.let { "xapi $it" }, info.xenVersion?.let { "Xen $it" }).joinToString(" · ").ifBlank { "Not reported" })
        InfoRow("Management address", info.managementIp ?: "Not reported", selectable = true)
        InfoRow("Server clock", info.serverTimeMillis?.let { dateTime(it) } ?: "Not reported")
        info.clockSkewMillis?.let { skew ->
            val seconds = Math.round(skew / 1000.0)
            InfoRow("Clock vs this phone", if (Math.abs(seconds) <= 1) "In step (within a second)" else "${if (seconds > 0) "Server ahead" else "Server behind"} by ${Math.abs(seconds)} s")
            if (Math.abs(seconds) > 60) Caution("The server and phone clocks differ by more than a minute. Compare timestamps with care.")
        }
        SectionLabel("Network interfaces")
        if (info.nics.isEmpty()) Text("No network interfaces reported", style = MaterialTheme.typography.bodySmall)
        info.nics.forEach { nic -> Text(nicText(nic), style = MaterialTheme.typography.bodySmall) }
        if (info.nicTotal > info.nics.size) Text("Showing ${info.nics.size} of ${info.nicTotal} interfaces.", style = MaterialTheme.typography.bodySmall) // inventory scale
        Text("Read from the server ${time(info.observedAt)}. Meteor does not change host settings.", style = MaterialTheme.typography.labelSmall)
    }
}

internal fun nicText(nic: HostNic): String = buildString {
    append(nic.device)
    nic.vlan?.let { append(" · VLAN $it") }
    when (nic.bond) { BondRole.MASTER -> append(" · bond"); BondRole.MEMBER -> append(" · bond member"); BondRole.NONE -> Unit }
    if (nic.management) append(" · management")
    append("\n")
    append(when (nic.carrier) { true -> "Link up"; false -> "Link down"; null -> "Link unknown" })
    nic.speedMbit?.let { append(" · $it Mbit/s") }
    nic.duplex?.let { append(" · $it duplex") }
    if (!nic.attached) append(" · not plugged")
    if (nic.ip.isNotBlank()) append("\n${nic.ip}")
}
