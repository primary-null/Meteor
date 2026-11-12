package com.meteor.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meteor.app.MeteorState
import com.meteor.app.data.*
import java.text.DateFormat
import java.util.Date

/**
 * Pool overview. Host and storage details open as dialogs on compact width and as a pane beside
 * the overview on medium/expanded width. [listState] is owned by the shell.
 */
@Composable internal fun OverviewScreen(state: MeteorState, now: Long, listState: LazyListState, connect: () -> Unit,
    metrics: (MetricTarget) -> Unit, activity: () -> Unit) {
    val inventory = state.inventory
    val panes = LocalWidthClass.current.usesPanes
    // Saved so an open host or storage view survives rotation and fold/unfold.
    var hostId by rememberSaveable(inventory?.authority) { mutableStateOf<String?>(null) }
    var showStorage by rememberSaveable(inventory?.authority) { mutableStateOf(false) }
    val host = inventory?.hosts?.firstOrNull { it.uuid == hostId }
    val paneOpen = inventory != null && (host != null || showStorage)
    BackHandler(enabled = panes && paneOpen) { hostId = null; showStorage = false }
    ListDetailLayout(showDetail = paneOpen,
        list = { OverviewList(state, now, listState, connect, metrics, activity,
            hostDetails = { hostId = it; showStorage = false }, storageDetails = { showStorage = true; hostId = null }) },
        detail = {
            if (inventory != null) {
                if (host != null) HostDetailsPane(host, inventory, state.connected) { hostId = null }
                else if (showStorage) StorageDetailsPane(inventory, state.connected) { showStorage = false }
            }
        })
    if (inventory != null && !panes) {
        host?.let { HostDetails(it, inventory, state.connected) { hostId = null } }
        if (showStorage) StorageDetails(inventory, state.connected) { showStorage = false }
    }
}

@Composable private fun OverviewList(state: MeteorState, now: Long, listState: LazyListState, connect: () -> Unit, metrics: (MetricTarget) -> Unit,
    activity: () -> Unit, hostDetails: (String) -> Unit, storageDetails: () -> Unit) {
    val inventory = state.inventory
    LazyColumn(Modifier.fillMaxSize().testTag("overview-list"), state = listState, contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { PageTitle("Your universe", state.selectedProfile?.label ?: "A clear view of every spark.") }
        if (state.profiles.size >= 2) item { AllPoolsCard(state) } // B3 background/summary
        if (inventory == null) item {
            Panel {
                SpaceIcon("orbit", Modifier.size(52.dp))
                Text("Bring your first server into orbit", style = MaterialTheme.typography.titleLarge)
                Text("Connect directly to XCP-ng to see hosts, virtual machines, and storage.", style = MaterialTheme.typography.bodyMedium)
                Button(onClick = connect) { Text("Add connection") }
            }
        } else {
            val fresh = state.connected && now - inventory.observedAtMillis in 0..state.inventoryFreshMillis /* A4: longer while live events are healthy */
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Surface(color = if (fresh) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(50)) {
                        Text(if (state.loading) "Refreshing…" else if (fresh) "● Connected" else "○ Last known",
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            color = if (fresh) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelMedium)
                    }
                    Text("Updated ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(inventory.observedAtMillis))}",
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Stat("HOSTS", "${inventory.hosts.size}", "${inventory.hosts.count { it.online }} online", Modifier.weight(1f))
                    Stat("VIRTUAL MACHINES", "${inventory.guests.size}", "${inventory.guests.count { it.power == "Running" }} running", Modifier.weight(1f))
                }
            }
            item {
                Panel {
                    Text("Capacity", style = MaterialTheme.typography.titleMedium)
                    val complete = inventory.hosts.isNotEmpty() && inventory.hosts.all { it.totalBytes != null && it.freeBytes != null }
                    val total = inventory.hosts.sumOf { it.totalBytes ?: 0 }
                    val free = inventory.hosts.sumOf { it.freeBytes ?: 0 }
                    Capacity("Host memory", if (complete && total > 0) total - free else null, if (complete) total else null)
                    Capacity("Storage repositories", inventory.storageUsed.takeIf { inventory.storageTotal > 0 }, inventory.storageTotal.takeIf { it > 0 })
                    TextButton(onClick = storageDetails) { Text("Storage details") }
                }
            }
            item { Text("CONSTELLATION", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) }
            item { Panel {
                Text(inventory.poolName, style = MaterialTheme.typography.titleLarge)
                Text("Pool · ${inventory.hosts.size} ${if (inventory.hosts.size == 1) "host" else "hosts"} · ${inventory.guests.size} ${if (inventory.guests.size == 1) "VM" else "VMs"}", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = activity, enabled = state.connected) { Text("Pool activity") }
                NetworksEntry() // A3 observability
            } }
            items(inventory.hosts, key = { it.uuid }) { host ->
                Panel {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        SpaceIcon("planet", Modifier.size(40.dp))
                        // The Overview list is also a narrow pane beside host details: never break a host name mid-word.
                        Column(Modifier.weight(1f)) {
                            Text(host.name, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(if (host.online) "Host online" else "Host offline", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    TextButton(onClick = { hostDetails(host.uuid) }) { Text("Host details") }
                    OutlinedButton(onClick = { metrics(MetricTarget(inventory.authority, host.uuid, MetricKind.HOST, host.name)) },
                        enabled = state.connected && host.online) { Text("Host performance") }
                }
            }
            item { Text("Inventory refreshes while Meteor is visible. Open a VM for details and available power actions.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable private fun Stat(label: String, number: String, detail: String, modifier: Modifier) {
    Surface(modifier, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(number, fontSize = 34.sp, fontWeight = FontWeight.Light)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable private fun Capacity(label: String, used: Long?, total: Long?) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        if (used != null && total != null && total > 0) {
            LinearProgressIndicator(progress = { (used.toFloat() / total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            Text("${bytes(used)} / ${bytes(total)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else Text("Not available", style = MaterialTheme.typography.bodySmall)
    }
}
