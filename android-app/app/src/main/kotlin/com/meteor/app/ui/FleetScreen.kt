package com.meteor.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.meteor.app.MeteorState
import com.meteor.app.MeteorViewModel
import com.meteor.app.data.*

/**
 * The VM fleet. On medium/expanded width the selected VM's details sit beside the list; on
 * compact width `MeteorApp` shows them in [VmDetailsDialog]. [listState] is owned by the shell so
 * the scroll position survives fold/unfold and switching destinations.
 */
@Composable internal fun FleetScreen(state: MeteorState, model: MeteorViewModel, listState: LazyListState, performance: (Guest) -> Unit) {
    val panes = LocalWidthClass.current.usesPanes
    val selected = state.selectedGuest
    // On a wide screen Back first closes the detail pane, as it dismisses the dialog on a phone.
    BackHandler(enabled = panes && selected != null) { model.closeDetails() }
    ListDetailLayout(showDetail = true,
        list = { VmList(state, model, listState, selected?.uuid?.takeIf { panes }, model::openDetails) },
        detail = {
            if (selected != null) VmDetailsPane(selected, state, model, { performance(selected) }, model::closeDetails)
            else EmptyPane("Select a virtual machine", "Its details, performance and available actions appear here.")
        })
}

@Composable private fun VmList(state: MeteorState, model: MeteorViewModel, listState: LazyListState, selectedUuid: String?, select: (Guest) -> Unit) {
    var search by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(FleetFilter.ALL) }
    val inventory = state.inventory
    val filtered = remember(inventory, search, filter) { inventory?.filterFleet(search, filter) }
    LazyColumn(Modifier.fillMaxSize().testTag("fleet-list"), state = listState, contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { PageTitle("The fleet", "Pools → hosts → virtual machines") }
        item { OutlinedTextField(search, { search = it }, label = { Text("Find a VM") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FleetFilter.entries.forEach { choice ->
                    FilterChip(selected = filter == choice, onClick = { filter = choice }, label = { Text(choice.title) })
                }
            }
        }
        if (inventory == null) item { Text("Connect to a server to see your fleet.") }
        else {
            val matches = requireNotNull(filtered)
            item {
                Text(inventory.poolName, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                Text("${matches.guests.size} of ${inventory.guests.size} VMs · Search by VM or host name",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val groups = InventoryMapper.groups(matches)
            groups.forEach { group ->
                val guests = group.guests
                if (guests.isNotEmpty() || (search.isBlank() && filter == FleetFilter.ALL)) {
                    item(key = "group:${group.id}") {
                        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            SpaceIcon("planet", Modifier.size(28.dp))
                            // One line each: a long host name is cut with an ellipsis, never broken inside a word.
                            Column(Modifier.weight(1f)) {
                                Text(group.name, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(group.subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    if (guests.isEmpty()) item { Text("No resident VMs", style = MaterialTheme.typography.bodySmall) }
                    items(guests, key = { "vm:${it.uuid}" }) { guest ->
                        val isSelected = guest.uuid == selectedUuid
                        Surface(Modifier.fillMaxWidth().semantics { selected = isSelected }
                            .clickable(onClickLabel = "Open details", role = Role.Button) { select(guest) },
                            shape = RoundedCornerShape(18.dp),
                            color = if (isSelected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface) {
                            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                GuestIcon(guest, state, model, Modifier.size(42.dp))
                                // A narrow list pane beside the details must not break a name mid-word: one line, ellipsis. The full name
                                // stays in the row's accessibility text (Text keeps the whole string) and in the details pane title.
                                Column(Modifier.weight(1f)) {
                                    Text(guest.name, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("${guest.vcpus} vCPU · ${bytes(guest.memoryBytes)}", style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                Text(guest.power, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, maxLines = 1, softWrap = false)
                            }
                        }
                    }
                }
            }
            if (matches.guests.isEmpty()) item {
                Text(if (inventory.guests.isEmpty()) "This pool has no VMs." else "No VMs match this search and state filter.")
                if (search.isNotBlank() || filter != FleetFilter.ALL)
                    TextButton(onClick = { search = ""; filter = FleetFilter.ALL }) { Text("Clear filters") }
            }
        }
    }
}
