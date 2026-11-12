package com.meteor.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.meteor.app.data.*
import java.text.DateFormat
import java.util.Date

/** Host details as a dialog (compact width). The dialog keeps a height cap; its content scrolls. */
@Composable
internal fun HostDetails(host: Host, inventory: Inventory, connected: Boolean, dismiss: () -> Unit) {
    AlertDialog(onDismissRequest = dismiss, title = { Text(host.name) }, text = {
        Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            HostDetailsContent(host, inventory, connected)
        }
    }, confirmButton = { TextButton(onClick = dismiss) { Text("Done") } })
}

/** Host details as a pane beside Overview (medium/expanded width). */
@Composable
internal fun HostDetailsPane(host: Host, inventory: Inventory, connected: Boolean, close: () -> Unit) {
    DetailPane(host.name, close) { HostDetailsContent(host, inventory, connected) }
}

@Composable
private fun ColumnScope.HostDetailsContent(host: Host, inventory: Inventory, connected: Boolean) {
    SnapshotTime(inventory, connected)
    Text(if (host.online) "Host online" else "Host offline")
    host.softwareVersion?.let { Text("Software version: $it") }
    if (host.description.isNotBlank()) Text(host.description)
    Text("Memory", style = MaterialTheme.typography.titleMedium)
    val total = host.totalBytes
    val free = host.freeBytes
    Text(if (total != null && free != null && total > 0 && free in 0..total)
        "${bytes(total - free)} used / ${bytes(total)} total\n${bytes(free)} available" else "Not available")
    Text("Resident VMs", style = MaterialTheme.typography.titleMedium)
    val residents = inventory.guests.filter { it.hostUuid == host.uuid }
    if (residents.isEmpty()) Text("No resident VMs")
    residents.forEach { Text("${it.name} · ${it.power}") }
    HostDetailSectionsSlot(host, inventory, connected)
    Text("Host information from the current pool snapshot. Maintenance actions read the host again before any request.", style = MaterialTheme.typography.bodySmall) // B2 storage/host
}

/** Extension point (A3): extra host detail sections, rendered after resident VMs. Content scrolls with its parent. */
@Composable
internal fun ColumnScope.HostDetailSectionsSlot(host: Host, inventory: Inventory, connected: Boolean) {
    HostDetailSections(host.uuid) // A3 observability: CPU, software, NICs, clock
    // --- B2 storage/host --- maintenance, reboot and shutdown (ui/HostOperationsSection.kt).
    HostOperationsSection(host, inventory)
    // --- end B2 storage/host ---
    HostUpdateSection(host.uuid) // B3 background/summary: manual, read-only update availability
}

/** Storage repositories as a dialog (compact width). */
@Composable
internal fun StorageDetails(inventory: Inventory, connected: Boolean, dismiss: () -> Unit) {
    AlertDialog(onDismissRequest = dismiss, title = { Text("Storage repositories") }, text = {
        Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            StorageDetailsContent(inventory, connected)
        }
    }, confirmButton = { TextButton(onClick = dismiss) { Text("Done") } })
}

/** Storage repositories as a pane beside Overview (medium/expanded width). */
@Composable
internal fun StorageDetailsPane(inventory: Inventory, connected: Boolean, close: () -> Unit) {
    DetailPane("Storage repositories", close) { StorageDetailsContent(inventory, connected) }
}

@Composable
private fun ColumnScope.StorageDetailsContent(inventory: Inventory, connected: Boolean) {
    SnapshotTime(inventory, connected)
    if (inventory.repositories.isEmpty()) Text("No storage repositories reported.")
    inventory.repositories.forEach { repository ->
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(repository.name, style = MaterialTheme.typography.titleMedium)
            Text(listOf(repository.type, repository.contentType).filter { it.isNotBlank() }.joinToString(" · "))
            if (repository.description.isNotBlank()) Text(repository.description)
            val total = repository.totalBytes
            val used = repository.usedBytes
            Text(if (total != null && used != null && total > 0) "${bytes(used)} used / ${bytes(total)} total" else "Capacity not available")
            if (repository.contentType == "iso") Text("ISO library · excluded from pool disk-capacity totals", style = MaterialTheme.typography.bodySmall)
            StorageRepositorySectionsSlot(repository)
        }
    }
    StorageDetailSectionsSlot(inventory, connected)
    Text("Inventory from the current pool snapshot. Only an explicit Plug changes storage; disks are never modified here.", style = MaterialTheme.typography.bodySmall) // B2 storage/host
}

/** Extension point: sections for one repository, rendered inside its entry (A3: PBDs, over-provisioning, disks). */
@Composable
internal fun ColumnScope.StorageRepositorySectionsSlot(repository: StorageRepository) {
    StorageDetail(repository.uuid) // A3 observability
}

/** Extension point: extra storage sections rendered after the repository list. */
@Composable
internal fun ColumnScope.StorageDetailSectionsSlot(inventory: Inventory, connected: Boolean) {
    // --- B2 storage/host --- PBD repair (ui/StorageRepairSection.kt).
    StorageRepairSection(inventory)
    // --- end B2 storage/host ---
}

@Composable
private fun SnapshotTime(inventory: Inventory, connected: Boolean) {
    Text("${if (connected) "Pool snapshot" else "Last-known snapshot"} · ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(inventory.observedAtMillis))}",
        style = MaterialTheme.typography.bodySmall)
}
