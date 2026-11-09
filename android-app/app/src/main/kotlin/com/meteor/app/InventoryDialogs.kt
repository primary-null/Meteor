package com.meteor.app

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

@Composable
internal fun HostDetails(host: Host, inventory: Inventory, connected: Boolean, dismiss: () -> Unit) {
    AlertDialog(onDismissRequest = dismiss, title = { Text(host.name) }, text = {
        Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
            Text("Read-only host information from the current pool snapshot.", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(onClick = dismiss) { Text("Done") } })
}

@Composable
internal fun StorageDetails(inventory: Inventory, connected: Boolean, dismiss: () -> Unit) {
    AlertDialog(onDismissRequest = dismiss, title = { Text("Storage repositories") }, text = {
        Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
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
                }
            }
            Text("Read-only inventory. This view does not modify storage or disks.", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(onClick = dismiss) { Text("Done") } })
}

@Composable
private fun SnapshotTime(inventory: Inventory, connected: Boolean) {
    Text("${if (connected) "Pool snapshot" else "Last-known snapshot"} · ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(inventory.observedAtMillis))}",
        style = MaterialTheme.typography.bodySmall)
}
