package com.meteor.app

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.core.view.WindowCompat
import com.meteor.app.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

@Composable
fun MeteorApp(model: MeteorViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    val dark = when (state.appearance.mode) { "light" -> false; "system" -> isSystemInDarkTheme(); else -> true }
    val accent = when (state.appearance.accent) {
        "slate" -> if (dark) Color(0xFFA6BBCB) else Color(0xFF496579)
        "green" -> if (dark) Color(0xFF9EC7AC) else Color(0xFF356E4A)
        "silver" -> if (dark) Color(0xFFD1D6DE) else Color(0xFF525C6B)
        else -> if (dark) Color(0xFFF1A18F) else Color(0xFF9D4535)
    }
    val colors = if (dark) darkColorScheme(primary = accent, secondary = accent, tertiary = accent,
        primaryContainer = lerp(Color(0xFF15191F), accent, .22f), onPrimaryContainer = accent,
        secondaryContainer = lerp(Color(0xFF15191F), accent, .18f), onSecondaryContainer = accent,
        background = Color(0xFF0B0D12), surface = Color(0xFF15191F), surfaceVariant = Color(0xFF303841),
        surfaceContainer = Color(0xFF1B2027), surfaceContainerHigh = Color(0xFF242A32), surfaceTint = accent,
        onPrimary = Color(0xFF171B20))
    else lightColorScheme(primary = accent, secondary = accent, tertiary = accent,
        primaryContainer = lerp(Color(0xFFFAF9F6), accent, .15f), onPrimaryContainer = accent,
        secondaryContainer = lerp(Color(0xFFFAF9F6), accent, .13f), onSecondaryContainer = accent,
        background = Color(0xFFF2F1ED), surface = Color(0xFFFAF9F6),
        surfaceVariant = Color(0xFFE1E3DF), surfaceContainer = Color(0xFFE9E9E5),
        surfaceContainerHigh = Color(0xFFE9E9E5), surfaceTint = accent, onPrimary = Color.White)
    val view = LocalView.current
    SideEffect {
        (view.context as? android.app.Activity)?.window?.let { window ->
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    MaterialTheme(colorScheme = colors) {
        var page by rememberSaveable { mutableIntStateOf(0) }
        var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
        LaunchedEffect(state.inventory?.observedAtMillis, state.metricSamples.lastOrNull()?.observedAt, state.poolActivity?.observedAt) {
            // A receipt can arrive between five-second ticks; compare it with the current clock immediately.
            now = System.currentTimeMillis()
        }
        val owner = LocalLifecycleOwner.current
        LaunchedEffect(owner, page) {
            owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                var elapsed = 0
                while (true) {
                    now = System.currentTimeMillis()
                    if (elapsed % 30 == 0) model.refreshIfStale()
                    if (elapsed % 5 == 0) model.refreshDetails()
                    if (page == 5) model.refreshMetrics()
                    if (page == 6 && elapsed % 15 == 0) model.refreshActivity()
                    delay(5000); elapsed += 5
                }
            }
        }
        LaunchedEffect(state.manualConnectionEvent) { if (state.manualConnectionEvent > 0) page = 0 }
        Scaffold(containerColor = colors.background, bottomBar = {
            NavigationBar(containerColor = colors.surface) {
                listOf("Overview", "VMs", "Connect", "Settings").forEachIndexed { index, label ->
                    val parent = when (page) {
                        4 -> 3
                        5 -> if (state.metricTarget?.kind == MetricKind.VM) 1 else 0
                        6 -> 0
                        else -> page
                    }
                    NavigationBarItem(selected = parent == index, onClick = { page = index },
                        icon = { SpaceIcon(listOf("planet", "meteor", "orbit", "spark")[index], Modifier.size(23.dp)) },
                        label = { Text(label) })
                }
            }
        }) { insets ->
            Box(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets).imePadding()) {
                if (state.appearance.texture) Canvas(Modifier.fillMaxSize().alpha(if (dark) .12f else .07f)) {
                    val ink = accent
                    repeat(64) { index ->
                        val x = ((index * 137 + 23) % 997) / 997f * size.width
                        val y = ((index * 211 + 79) % 991) / 991f * size.height
                        drawCircle(ink, if (index % 7 == 0) 2f else 1f, Offset(x, y))
                    }
                    drawCircle(ink, size.width * .72f, Offset(size.width * .9f, size.height * .35f), style = Stroke(1f))
                    drawCircle(ink, size.width * .9f, Offset(size.width * .9f, size.height * .35f), style = Stroke(1f))
                }
                Column(Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Image(painterResource(R.drawable.meteor_mark), "Meteor", Modifier.size(52.dp).clip(RoundedCornerShape(14.dp)), contentScale = ContentScale.Crop)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("meteor", fontSize = 28.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-1).sp)
                            Text("SPARK  /  XCP-ng", style = MaterialTheme.typography.labelSmall, color = colors.primary)
                        }
                        if (state.selectedProfile != null) TextButton(onClick = model::refresh,
                            enabled = !state.loading && !state.profileBusy && !state.operationBusy && state.editingProfileId == null && state.pendingTrust == null) { Text("Refresh") }
                    }
                    AmbientMessage(canRotate = page == 0 &&
                        !state.operationBusy && state.operation?.unresolved != true && state.message == null &&
                        state.pendingTrust == null && state.editingProfileId == null && state.selectedGuest == null &&
                        state.pendingPower == null && state.pendingReview == null,
                        modifier = Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = 2.dp, bottom = 14.dp))
                    HorizontalDivider(color = colors.outlineVariant.copy(alpha = .35f))
                    if (state.loading || state.profileBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    state.notice?.let { NoticeBanner(it, model::dismissMessage) }
                    state.operation?.let { operation ->
                        Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), color = colors.surfaceContainer, shape = RoundedCornerShape(12.dp)) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("${operation.vmName} · ${operation.action.title}", style = MaterialTheme.typography.labelMedium)
                                Text(if (operation.unresolved) operation.message.ifBlank { operation.phase.name } else OperationHistory.status(operation),
                                    style = MaterialTheme.typography.bodySmall)
                                TextButton(onClick = { page = 4; model.reloadHistory() }) { Text("View history") }
                                if (operation.unresolved && !state.operationBusy) TextButton(onClick = model::checkOperation, enabled = !state.profileBusy) { Text("Check status") }
                                if (operation.unresolved) {
                                    TextButton(onClick = model::reviewOperation,
                                        enabled = state.connected && !state.loading && !state.profileBusy && !state.operationBusy && !state.journalProblem) { Text("Review pending action") }
                                }
                            }
                        }
                    }
                    when (page) {
                        0 -> Overview(state, now, { page = 2 }, { target -> model.openMetrics(target); page = 5 }, { page = 6; model.refreshActivity() })
                        1 -> VmList(state, model, model::openDetails)
                        2 -> Connections(state, model)
                        3 -> Settings(state.appearance, model) { page = 4; model.reloadHistory() }
                        5 -> PerformanceScreen(state, now, model::refreshMetrics) { page = if (state.metricTarget?.kind == MetricKind.VM) 1 else 0 }
                        6 -> PoolActivityScreen(state, now, model::refreshActivity) { page = 0 }
                        else -> HistoryScreen(state, model::reloadHistory, { page = 3 }, { page = 0 })
                    }
                }
            }
        }
        state.pendingTrust?.let { pending ->
            var verified by remember(pending) { mutableStateOf(false) }
            AlertDialog(onDismissRequest = model::cancelTrust, title = { Text("Verify this server") }, text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Compare this SHA-256 fingerprint with a trusted source for your XCP-ng server. Credentials are sent only after you accept.")
                    Text(pending.profile.endpoint, style = MaterialTheme.typography.bodySmall)
                    Text(pending.certificate.subject, style = MaterialTheme.typography.bodySmall)
                    Text("Valid from ${DateFormat.getDateInstance().format(Date(pending.certificate.validFrom))}\nExpires ${DateFormat.getDateInstance().format(Date(pending.certificate.validUntil))}", style = MaterialTheme.typography.bodySmall)
                    Fingerprint("Observed SHA-256", pending.certificate.sha256)
                    pending.previous?.let { previous ->
                        if (previous.endpoint != pending.profile.endpoint || previous.certificateSha256 != pending.certificate.sha256) {
                            Text("This replaces the saved server identity. Verify the change independently before accepting.")
                            Text("Previously saved: ${previous.endpoint}", style = MaterialTheme.typography.bodySmall)
                            Fingerprint("Saved SHA-256", previous.certificateSha256)
                        } else Text("The observed certificate matches the saved pin.", style = MaterialTheme.typography.bodySmall)
                    }
                    Text("Accepting pins this certificate for future connections.", style = MaterialTheme.typography.bodySmall)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(verified, { verified = it })
                        Text("I compared the fingerprint with a trusted source.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }, confirmButton = { TextButton(onClick = model::acceptTrust, enabled = verified && !state.profileBusy && !state.operationBusy) { Text("Trust and connect") } },
                dismissButton = { TextButton(onClick = model::cancelTrust) { Text("Cancel") } })
        }
        state.selectedGuest?.let { guest ->
            GuestDialog(guest, state, model, { model.openMetrics(MetricTarget(guest.poolUuid, guest.uuid, MetricKind.VM, guest.name)); page = 5 }, model::closeDetails)
        }
        state.pendingPower?.let { confirmation ->
            AlertDialog(onDismissRequest = model::cancelPower, title = { Text("${confirmation.action.title} ${confirmation.details.name}?") }, text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Connection: ${state.selectedProfile?.label.orEmpty()}\nCurrent state: ${confirmation.details.power}")
                    Text(when (confirmation.action) {
                        PowerAction.START -> "Boot this virtual machine. Meteor will track the server task and verify its state."
                        PowerAction.SHUTDOWN -> "Ask the guest to shut down gracefully. Running services will be interrupted."
                        PowerAction.REBOOT -> "Ask the guest to reboot gracefully. Running services will be interrupted."
                    })
                    Text("No forced power action or automatic resubmission will occur.", style = MaterialTheme.typography.bodySmall)
                }
            }, confirmButton = { TextButton(onClick = model::confirmPower) { Text("Confirm ${confirmation.action.title.lowercase()}") } },
                dismissButton = { TextButton(onClick = model::cancelPower) { Text("Cancel") } })
        }
        state.pendingReview?.let { review ->
            var historyChecked by remember(review) { mutableStateOf(false) }
            AlertDialog(onDismissRequest = model::cancelReview, title = { Text("Review ${review.operation.vmName}") }, text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Original action: ${review.operation.action.title}\nCurrent state: ${review.details.power}\nConnection: ${state.selectedProfile?.label.orEmpty()}")
                    Text("This fresh VM read does not prove the old task finished. Check this VM's task history on the server and make sure the action is no longer pending before closing the record.")
                    Text("The original action stays recorded with an unverified outcome. Closing this record sends no power command and permits new actions after a fresh confirmation.")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = historyChecked, onCheckedChange = { historyChecked = it })
                        Text("I checked the server task history and confirmed this action is no longer pending.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }, confirmButton = { TextButton(onClick = { model.confirmReview(historyChecked) }, enabled = historyChecked && !state.operationBusy) { Text("Close reviewed record") } },
                dismissButton = { TextButton(onClick = model::cancelReview) { Text("Keep pending") } })
        }
    }
}

@Composable private fun PageTitle(title: String, subtitle: String) {
    Column(Modifier.padding(bottom = 8.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable private fun Overview(state: MeteorState, now: Long, connect: () -> Unit, metrics: (MetricTarget) -> Unit, activity: () -> Unit) {
    val inventory = state.inventory
    var hostId by remember(inventory?.authority) { mutableStateOf<String?>(null) }
    var showStorage by remember(inventory?.authority) { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize().testTag("overview-list"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { PageTitle("Your universe", state.selectedProfile?.label ?: "A clear view of every spark.") }
        if (inventory == null) item {
            Panel {
                SpaceIcon("orbit", Modifier.size(52.dp))
                Text("Bring your first server into orbit", style = MaterialTheme.typography.titleLarge)
                Text("Connect directly to XCP-ng to see hosts, virtual machines, and storage.", style = MaterialTheme.typography.bodyMedium)
                Button(onClick = connect) { Text("Add connection") }
            }
        } else {
            val fresh = state.connected && now - inventory.observedAtMillis in 0..45_000
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
                    TextButton(onClick = { showStorage = true }) { Text("Storage details") }
                }
            }
            item { Text("CONSTELLATION", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) }
            item { Panel {
                Text(inventory.poolName, style = MaterialTheme.typography.titleLarge)
                Text("Pool · ${inventory.hosts.size} ${if (inventory.hosts.size == 1) "host" else "hosts"} · ${inventory.guests.size} ${if (inventory.guests.size == 1) "VM" else "VMs"}", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = activity, enabled = state.connected) { Text("Pool activity") }
            } }
            items(inventory.hosts, key = { it.uuid }) { host ->
                Panel {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        SpaceIcon("planet", Modifier.size(40.dp)); Column { Text(host.name, fontWeight = FontWeight.Medium); Text(if (host.online) "Host online" else "Host offline", style = MaterialTheme.typography.bodySmall) }
                    }
                    TextButton(onClick = { hostId = host.uuid }) { Text("Host details") }
                    OutlinedButton(onClick = { metrics(MetricTarget(inventory.authority, host.uuid, MetricKind.HOST, host.name)) },
                        enabled = state.connected && host.online) { Text("Host performance") }
                }
            }
            item { Text("Inventory refreshes while Meteor is visible. Open a VM for details and available power actions.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
    if (inventory != null) {
        inventory.hosts.firstOrNull { it.uuid == hostId }?.let { HostDetails(it, inventory, state.connected) { hostId = null } }
        if (showStorage) StorageDetails(inventory, state.connected) { showStorage = false }
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

@Composable private fun Panel(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .35f)), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable private fun VmList(state: MeteorState, model: MeteorViewModel, select: (Guest) -> Unit) {
    var search by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(FleetFilter.ALL) }
    val inventory = state.inventory
    val filtered = remember(inventory, search, filter) { inventory?.filterFleet(search, filter) }
    LazyColumn(Modifier.fillMaxSize().testTag("fleet-list"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                            SpaceIcon("planet", Modifier.size(28.dp)); Column { Text(group.name, fontWeight = FontWeight.Medium); Text(group.subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                    if (guests.isEmpty()) item { Text("No resident VMs", style = MaterialTheme.typography.bodySmall) }
                    items(guests, key = { "vm:${it.uuid}" }) { guest ->
                        Surface(Modifier.fillMaxWidth().clickable { select(guest) }, shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface) {
                            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                GuestIcon(guest, state, model, Modifier.size(42.dp))
                                Column(Modifier.weight(1f)) { Text(guest.name, fontWeight = FontWeight.Medium); Text("${guest.vcpus} vCPU · ${bytes(guest.memoryBytes)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                Text(guest.power, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
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

@Composable private fun GuestIcon(guest: Guest, state: MeteorState, model: MeteorViewModel, modifier: Modifier) {
    val token = state.appearance.icons[guest.identity(state.inventory?.authority.orEmpty())] ?: "meteor"
    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, token) {
        value = if (token.startsWith("file:")) withContext(Dispatchers.IO) {
            runCatching { model.customIcons.file(token)?.let { BitmapFactory.decodeFile(it.absolutePath)?.asImageBitmap() } }.getOrNull()
        } else null
    }
    if (bitmap != null) Image(bitmap!!, "Custom VM icon", modifier.clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.Crop)
    else SpaceIcon(token, modifier)
}

@Composable private fun GuestDialog(guest: Guest, state: MeteorState, model: MeteorViewModel, performance: () -> Unit, dismiss: () -> Unit) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> if (uri != null) model.importIcon(guest, uri) }
    var showAddresses by remember(guest.uuid) { mutableStateOf(false) }
    AlertDialog(onDismissRequest = dismiss, title = { Text(guest.name) }, text = {
        Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            val detail = state.details?.takeIf { it.uuid == guest.uuid && it.authority == guest.poolUuid }
            if (state.detailsBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("${detail?.power ?: guest.power} · ${detail?.vcpus ?: guest.vcpus} vCPU · ${bytes(detail?.memoryBytes ?: guest.memoryBytes)}")
            OutlinedButton(onClick = performance, enabled = state.connected && !state.profileBusy && !state.operationBusy) { Text("VM performance") }
            if (detail != null) {
                Text("Host: ${detail.host ?: if (detail.power == "Halted") "Unassigned" else "Unknown"}", style = MaterialTheme.typography.bodySmall)
                Text(if (detail.guestToolsLive) "Guest tools reporting${detail.os?.let { " · $it" }.orEmpty()}" else "Guest tools not reporting", style = MaterialTheme.typography.bodySmall)
                detail.bootTime?.takeIf { detail.power == "Running" && PowerRules.validBoot(it) }?.let { Text("Last boot: $it", style = MaterialTheme.typography.bodySmall) }
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
                Text("Power", fontWeight = FontWeight.Medium)
                PowerAction.entries.forEach { action ->
                    OutlinedButton(onClick = { model.requestPower(action) }, modifier = Modifier.fillMaxWidth(),
                        enabled = state.connected && !state.loading && !state.profileBusy && !state.detailsBusy && !state.journalProblem && !state.operationBusy &&
                            state.operation?.unresolved != true && PowerRules.eligible(detail, action)) { Text(action.title) }
                    PowerRules.unavailableReason(detail, action)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
                if (state.operation?.unresolved == true) Text("A pending operation must be reconciled before another action.", style = MaterialTheme.typography.bodySmall)
                else if (state.journalProblem) Text("Operation history must be readable before a power action can be sent.", style = MaterialTheme.typography.bodySmall)
                else if (!state.connected) Text("Reconnect before choosing a power action.", style = MaterialTheme.typography.bodySmall)
                Text("Availability comes from the server's current VM operations. Graceful actions may require working guest tools.", style = MaterialTheme.typography.bodySmall)
            }
            if (detail == null && !state.detailsBusy) Text("Details are unavailable. Load them again when connected.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { model.openDetails(guest) },
                enabled = state.connected && !state.loading && !state.detailsBusy && !state.operationBusy) {
                Text(if (detail == null) "Load details" else "Refresh details")
            }
            Text("VM icon", fontWeight = FontWeight.Medium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                listOf("meteor", "planet", "moon", "spark").forEach { icon ->
                    Column(Modifier.clickable { model.icon(guest, icon) }.padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        SpaceIcon(icon, Modifier.size(30.dp)); Text(icon.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            OutlinedButton(onClick = { picker.launch("image/*") }) { Text("Choose custom image") }
            Text("Icons follow the VM's identity when it moves between hosts.", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(onClick = dismiss) { Text("Done") } })
}

@Composable private fun Connections(state: MeteorState, model: MeteorViewModel) {
    var forgetId by rememberSaveable { mutableStateOf<String?>(null) }
    var certificateId by rememberSaveable { mutableStateOf<String?>(null) }
    val editable = !state.loading && !state.profileBusy && !state.operationBusy && state.pendingTrust == null
    val editorKey = "editor:${state.editingProfileId ?: "new"}:${state.profileEditSession}"
    val listState = rememberLazyListState()
    LaunchedEffect(state.editingProfileId) { if (state.editingProfileId != null) listState.animateScrollToItem(1) }
    LazyColumn(Modifier.fillMaxSize().testTag("connections-list"), state = listState, contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { PageTitle("Connections", "Direct XAPI · HTTPS · certificate pinning") }
        if (state.editingProfileId != null) item(key = editorKey) { ConnectionForm(state, model) }
        if (state.selectedProfile != null || state.loading) item {
            Panel {
                Text(state.selectedProfile?.label ?: "Connecting", style = MaterialTheme.typography.titleMedium)
                Text(if (state.connected) "Connected" else if (state.loading) "Connecting…" else "Last connection unavailable", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = model::disconnect, enabled = !state.profileBusy && !state.operationBusy) { Text("Disconnect") }
                Text("Disconnect stops refreshes and clears the active selection. Saved profiles and operation history are kept.", style = MaterialTheme.typography.bodySmall)
            }
        }
        items(state.profiles, key = { it.id }) { profile ->
            val pending = state.operation?.let { it.unresolved && it.profileId == profile.id } == true
            Panel {
                Text(profile.label, style = MaterialTheme.typography.titleMedium)
                Text("${profile.endpoint}\n${profile.username}", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { model.switchProfile(profile) }, enabled = editable) { Text(if (profile.id == state.selectedId) "Reconnect" else "Connect") }
                    OutlinedButton(onClick = { model.editProfile(profile.id) }, enabled = editable) { Text("Edit") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { certificateId = profile.id }) { Text("Certificate") }
                    TextButton(onClick = { forgetId = profile.id }, enabled = editable && !state.journalProblem && !pending) { Text("Forget") }
                }
                if (pending) Text("Resolve this connection's pending action before forgetting it. You can still edit credentials or review a renewed certificate.", style = MaterialTheme.typography.bodySmall)
                else if (state.journalProblem) Text("Forgetting is unavailable until operation history can be read.", style = MaterialTheme.typography.bodySmall)
            }
        }
        if (state.editingProfileId == null) item(key = editorKey) { ConnectionForm(state, model) }
        item { Text("Connect to the XCP-ng pool coordinator using direct XAPI.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    state.profiles.firstOrNull { it.id == certificateId }?.let { profile ->
        AlertDialog(onDismissRequest = { certificateId = null }, title = { Text("Saved server certificate") }, text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(profile.label)
                Text(profile.endpoint, style = MaterialTheme.typography.bodySmall)
                Fingerprint("Saved SHA-256", profile.certificateSha256)
                Text("To see the current certificate and its expiry, edit this connection and choose Review server certificate. A changed certificate always needs independent verification.", style = MaterialTheme.typography.bodySmall)
            }
        }, confirmButton = { TextButton(onClick = { certificateId = null }) { Text("Done") } })
    }
    state.profiles.firstOrNull { it.id == forgetId }?.let { profile ->
        AlertDialog(onDismissRequest = { forgetId = null }, title = { Text("Forget ${profile.label}?") }, text = {
            Text("Remove this saved connection and its credentials from Meteor on this device. Operation history is kept. This does not revoke the credentials on the server.")
        }, confirmButton = { TextButton(onClick = { model.forgetProfile(profile.id); forgetId = null }, enabled = editable) { Text("Forget connection") } },
            dismissButton = { TextButton(onClick = { forgetId = null }) { Text("Cancel") } })
    }
}

@Composable private fun Fingerprint(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        SelectionContainer { Text(value.chunked(2).joinToString(":"), style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable private fun ConnectionForm(state: MeteorState, model: MeteorViewModel) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    fun submit(action: () -> Unit) { focus.clearFocus(); keyboard?.hide(); action() }
    val editing = state.profiles.firstOrNull { it.id == state.editingProfileId }
    val key = "${editing?.id ?: "new"}:${state.profileEditSession}"
    var label by rememberSaveable(key) { mutableStateOf(editing?.label.orEmpty()) }
    var endpoint by rememberSaveable(key) { mutableStateOf(editing?.endpoint.orEmpty()) }
    var username by rememberSaveable(key) { mutableStateOf(editing?.username.orEmpty()) }
    // Password drafts never enter saved instance state, including during activity recreation.
    var password by remember(key) { mutableStateOf("") }
    LaunchedEffect(state.manualConnectionEvent) { if (state.manualConnectionEvent > 0) password = "" }
    val enabled = !state.loading && !state.profileBusy && !state.operationBusy && state.pendingTrust == null
    val valid = enabled && endpoint.isNotBlank() && username.isNotBlank() && (editing != null || password.isNotBlank())
    Panel {
        Text(if (editing == null) "Add XCP-ng server" else "Edit connection", style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(label, { label = it }, label = { Text("Connection name") }, enabled = enabled, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(endpoint, { endpoint = it }, label = { Text("Server address") }, placeholder = { Text("https://your-server") }, enabled = enabled, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(username, { username = it }, label = { Text("Username") }, enabled = enabled, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(password, { password = it }, label = { Text(if (editing == null) "Password" else "New password") }, enabled = enabled, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        if (editing != null) {
            Text("Leave the new password empty to keep the saved password. Changing credentials keeps the saved certificate pin; a different server requires certificate review.", style = MaterialTheme.typography.bodySmall)
            Button(onClick = { submit { model.saveConnection(label, endpoint, username, password, editing.id) } }, enabled = valid) { Text("Save changes") }
            OutlinedButton(onClick = { submit { model.inspect(label, endpoint, username, password, editing.id) } }, enabled = valid) { Text("Review server certificate") }
            TextButton(onClick = { submit(model::cancelEdit) }, enabled = !state.profileBusy && !state.operationBusy) { Text("Cancel editing") }
            if (state.operation?.let { it.unresolved && it.profileId == editing.id } == true)
                Text("Pending actions retain their original connection identity. Changing the server, username or certificate requires manual review of the pending action.", style = MaterialTheme.typography.bodySmall)
        } else Button(onClick = { submit { model.inspect(label, endpoint, username, password) } }, enabled = valid) { Text("Review certificate") }
        Text("Credentials are encrypted with Android Keystore after a successful connection. Use your private network or VPN.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable private fun Settings(appearance: Appearance, model: MeteorViewModel, history: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { PageTitle("Make it yours", "One universe. Your atmosphere.") }
        item { Panel {
            Text("Activity", style = MaterialTheme.typography.titleMedium)
            Text("Review verified, failed and unresolved power actions saved on this device.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = history) { Text("Action history") }
        } }
        item { Panel {
            Text("Appearance", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("light", "dark", "system").forEach { mode ->
                FilterChip(selected = appearance.mode == mode, onClick = { model.mode(mode) }, label = { Text(mode.replaceFirstChar { it.uppercase() }) })
            } }
            Text("Accent", style = MaterialTheme.typography.titleMedium)
            listOf(listOf("coral", "slate"), listOf("green", "silver")).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { row.forEach { accent ->
                    FilterChip(selected = appearance.accent == accent, onClick = { model.accent(accent) }, label = { Text(accent.replaceFirstChar { it.uppercase() }) }, modifier = Modifier.weight(1f))
                } }
            }
            Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("Orbital texture"); Text("Subtle stars and orbit lines", style = MaterialTheme.typography.bodySmall) }; Switch(appearance.texture, model::texture) }
        } }
        item { Panel { Text("Meteor · Spark", style = MaterialTheme.typography.titleMedium); Text("${BuildConfig.VERSION_NAME} · Android 12+\n${if (BuildConfig.BUILD_TYPE == "candidate") "Release candidate" else if (BuildConfig.DEBUG) "Development build" else "Release build"} · Direct XAPI", style = MaterialTheme.typography.bodyMedium); Text("Your universe. Under control.", color = MaterialTheme.colorScheme.primary) } }
    }
}

@Composable private fun SpaceIcon(kind: String, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.primary
    Canvas(modifier) {
        val w = size.width; val h = size.height; val stroke = Stroke(1.6.dp.toPx())
        when (kind) {
            "planet", "orbit" -> { drawCircle(color, w * .28f, Offset(w * .5f, h * .5f), style = stroke); drawOval(color.copy(alpha = .7f), Offset(0f, h * .3f), androidx.compose.ui.geometry.Size(w, h * .4f), style = stroke) }
            "moon" -> { drawCircle(color, w * .34f, center, style = stroke); drawCircle(color.copy(alpha = .4f), w * .07f, Offset(w * .4f, h * .43f), style = stroke); drawCircle(color.copy(alpha = .4f), w * .04f, Offset(w * .61f, h * .61f), style = stroke) }
            "spark" -> { drawLine(color, Offset(w*.5f,h*.08f),Offset(w*.5f,h*.92f),stroke.width); drawLine(color,Offset(w*.08f,h*.5f),Offset(w*.92f,h*.5f),stroke.width); drawLine(color,Offset(w*.23f,h*.23f),Offset(w*.77f,h*.77f),stroke.width); drawLine(color,Offset(w*.23f,h*.77f),Offset(w*.77f,h*.23f),stroke.width) }
            else -> { drawCircle(color, w*.2f,Offset(w*.31f,h*.69f),style=stroke); drawLine(color,Offset(w*.35f,h*.46f),Offset(w*.86f,h*.12f),stroke.width); drawLine(color,Offset(w*.54f,h*.7f),Offset(w*.92f,h*.18f),stroke.width); drawLine(color.copy(alpha=.5f),Offset(w*.48f,h*.5f),Offset(w*.7f,h*.25f),stroke.width) }
        }
    }
}

internal fun bytes(value: Long): String = when {
    value >= 1L shl 40 -> "%.1f TiB".format(value / (1L shl 40).toDouble())
    value >= 1L shl 30 -> "%.1f GiB".format(value / (1L shl 30).toDouble())
    else -> "%.0f MiB".format(value / (1L shl 20).toDouble())
}
