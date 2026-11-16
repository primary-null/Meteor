package com.meteor.app.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.meteor.app.ControlGates
import com.meteor.app.MeteorState
import com.meteor.app.MeteorViewModel
import com.meteor.app.data.*
import java.text.DateFormat
import java.util.Date

/**
 * VM details on compact width: a full-screen dialog (no height cap; content scrolls).
 * On medium/expanded width `FleetScreen` shows [VmDetailsPane] beside the list instead.
 */
@Composable internal fun VmDetailsDialog(guest: Guest, state: MeteorState, model: MeteorViewModel, performance: () -> Unit, dismiss: () -> Unit) {
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            VmDetailsPane(guest, state, model, performance, dismiss, Modifier.safeDrawingPadding().imePadding())
        }
    }
}

/** VM details as a titled pane. Used directly as the Fleet detail pane and inside [VmDetailsDialog]. */
@Composable internal fun VmDetailsPane(guest: Guest, state: MeteorState, model: MeteorViewModel, performance: () -> Unit, close: () -> Unit,
    modifier: Modifier = Modifier) {
    // --- VM settings --- "Edit settings" replaces the details in this same pane or full-screen dialog; Back returns to them.
    var editing by rememberSaveable(guest.uuid) { mutableStateOf(false) }
    val detail = state.details?.takeIf { it.uuid == guest.uuid && it.authority == guest.poolUuid }
    if (editing && detail != null) {
        BackHandler { editing = false }
        DetailPane("Edit ${guest.name}", { editing = false }, modifier) { VmSettingsEditor(detail, state, model) }
        return
    }
    // --- end VM settings ---
    DetailPane(guest.name, close, modifier) {
        // --- VM settings ---
        OutlinedButton(onClick = { editing = true }, enabled = detail != null && state.connected && !ControlGates.operationExclusive(state)) { Text("Edit settings") }
        // --- end VM settings ---
        VmDetailsContent(guest, state, model, performance)
    }
}

@Composable private fun ColumnScope.VmDetailsContent(guest: Guest, state: MeteorState, model: MeteorViewModel, performance: () -> Unit) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> if (uri != null) model.importIcon(guest, uri) }
    var showAddresses by remember(guest.uuid) { mutableStateOf(false) }
    val detail = state.details?.takeIf { it.uuid == guest.uuid && it.authority == guest.poolUuid }
    // B1 console: opens the full-screen console destination (this view closes while it is open); Back returns here.
    OutlinedButton(onClick = { model.openConsole(guest, fromDetails = true) },
        enabled = (detail?.power ?: guest.power) == "Running" && state.connected && state.selectedProfile != null) { Text("Open console") }
    if (state.detailsBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
    Text("${detail?.power ?: guest.power} · ${detail?.vcpus ?: guest.vcpus} vCPU · ${bytes(detail?.memoryBytes ?: guest.memoryBytes)}")
    OutlinedButton(onClick = performance, enabled = state.connected && !state.profileBusy && !ControlGates.operationExclusive(state)) { Text("VM performance") }
    if (detail != null) {
        Text("Host: ${detail.host ?: if (detail.power == "Halted") "Unassigned" else "Unknown"}", style = MaterialTheme.typography.bodySmall)
        Text(if (detail.guestToolsLive) "Guest tools reporting${detail.os?.let { " · $it" }.orEmpty()}" else "Guest tools not reporting", style = MaterialTheme.typography.bodySmall)
        // Display only: the raw marker (compared for reboot proof) is untouched; see XapiTime.
        detail.bootTime?.takeIf { detail.power == "Running" && PowerRules.validBoot(it) }?.let { Text("Last boot: ${XapiTime.display(it)}", style = MaterialTheme.typography.bodySmall) }
        if (detail.addresses.isNotEmpty()) {
            TextButton(onClick = { showAddresses = !showAddresses }) { Text(if (showAddresses) "Hide guest addresses" else "Show guest addresses (${detail.addresses.size})") }
            if (showAddresses) Text(detail.addresses.joinToString("\n"), style = MaterialTheme.typography.bodySmall)
        }
        Text("Disks", fontWeight = FontWeight.Medium)
        Text("Disk/network snapshot: ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(detail.topologyObservedAt))}. Refresh details to reload it.",
            style = MaterialTheme.typography.bodySmall)
        if (detail.disks.isEmpty()) Text("No virtual disks", style = MaterialTheme.typography.bodySmall)
        detail.disks.forEach { disk -> Text("${disk.name} · ${bytes(disk.size)}\nDevice ${disk.device} · ${if (disk.attached) "Attached" else "Detached"}${if (disk.readOnly) " · Read-only" else ""}", style = MaterialTheme.typography.bodySmall) }
        Text("Network interfaces", fontWeight = FontWeight.Medium)
        if (detail.networks.isEmpty()) Text("No network interfaces", style = MaterialTheme.typography.bodySmall)
        detail.networks.forEach { nic -> Text("${nic.name}\nInterface ${nic.device} · ${nic.mac}\n${if (nic.attached) "Connected" else "Disconnected"}", style = MaterialTheme.typography.bodySmall) }
        VmDetailSectionsSlot(guest, detail, state, model)
        VmActionsSlot(detail, state, model)
    }
    if (detail == null && !state.detailsBusy) Text("Details are unavailable. Load them again when connected.", style = MaterialTheme.typography.bodySmall)
    OutlinedButton(onClick = { model.openDetails(guest) },
        enabled = state.connected && !state.loading && !state.detailsBusy && !ControlGates.operationExclusive(state)) {
        Text(if (detail == null) "Load details" else "Refresh details")
    }
    Text("VM icon", fontWeight = FontWeight.Medium)
    val chosen = state.appearance.icons[guest.identity(state.inventory?.authority.orEmpty())] ?: "meteor"
    Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.SpaceEvenly) {
        listOf("meteor", "planet", "moon", "spark").forEach { icon ->
            Column(Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                .selectable(selected = chosen == icon, role = Role.RadioButton, onClick = { model.icon(guest, icon) }).padding(6.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                SpaceIcon(icon, Modifier.size(30.dp)); Text(icon.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
    OutlinedButton(onClick = { picker.launch("image/*") }) { Text("Choose custom image") }
    Text("Icons follow the VM's identity when it moves between hosts.", style = MaterialTheme.typography.bodySmall)
}

/**
 * Extension point (A3): extra read-only VM detail sections, rendered after network interfaces and
 * before the actions. Only called once [detail] matches the selected VM and pool. Content scrolls
 * with the pane or dialog; do not add fixed heights or nested vertical scrolling.
 */
@Composable internal fun ColumnScope.VmDetailSectionsSlot(guest: Guest, detail: VmDetails, state: MeteorState, model: MeteorViewModel) {
    VmDetailSections(guest.uuid) // A3 observability: UUID, firmware, snapshots, CD drive, ...
}

/**
 * Extension point (A2): the VM actions panel, rendered by `VmActionsPanel` (ui/VmActionsPanel.kt).
 * Requests go through the view model (`requestPower` → `VmOperationDialogs` in `MeteorApp.kt`).
 */
@Composable internal fun ColumnScope.VmActionsSlot(detail: VmDetails, state: MeteorState, model: MeteorViewModel) {
    // A2 operations: every eligible VM operation, disabled reasons and the host picker entry.
    VmActionsPanel(detail, state, onRequest = model::requestPower, onChooseHost = model::requestHostChoice)
    // --- B2 storage/host --- snapshots and CD drives (ui/SnapshotsSection.kt, ui/CdDriveSection.kt).
    VmStorageSections(detail, state, model)
    // --- end B2 storage/host ---
}
