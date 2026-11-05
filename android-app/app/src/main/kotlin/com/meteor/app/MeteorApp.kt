package com.meteor.app

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
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

private val taglines = listOf("Keep every spark in sight.", "Your universe. Under control.",
    "From a spark to a system.", "Small sparks. Stellar possibilities.")

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
        var paused by rememberSaveable { mutableStateOf(false) }
        var tagline by rememberSaveable { mutableIntStateOf(0) }
        var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
        val owner = LocalLifecycleOwner.current
        LaunchedEffect(owner, paused) {
            owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                var elapsed = 0
                while (true) {
                    now = System.currentTimeMillis()
                    if (elapsed % 30 == 0) model.refreshIfStale()
                    if (elapsed % 5 == 0) model.refreshDetails()
                    if (!paused && elapsed > 0 && elapsed % 8 == 0) tagline = (tagline + 1) % taglines.size
                    delay(1000); elapsed++
                }
            }
        }
        LaunchedEffect(state.manualConnectionEvent) { if (state.manualConnectionEvent > 0) page = 0 }
        Scaffold(containerColor = colors.background, bottomBar = {
            NavigationBar(containerColor = colors.surface) {
                listOf("Overview", "VMs", "Connect", "Settings").forEachIndexed { index, label ->
                    NavigationBarItem(selected = page == index, onClick = { page = index },
                        icon = { SpaceIcon(listOf("planet", "meteor", "orbit", "spark")[index], Modifier.size(23.dp)) },
                        label = { Text(label) })
                }
            }
        }) { insets ->
            Box(Modifier.fillMaxSize().padding(insets)) {
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
                        if (state.selectedProfile != null) TextButton(onClick = model::refresh, enabled = !state.loading) { Text("Refresh") }
                    }
                    Row(Modifier.padding(horizontal = 22.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(taglines[tagline], style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, modifier = Modifier.weight(1f))
                        TextButton(onClick = { paused = !paused }, contentPadding = PaddingValues(4.dp)) { Text(if (paused) "Resume" else "Pause", style = MaterialTheme.typography.labelSmall) }
                    }
                    if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    state.message?.let { message ->
                        Surface(color = colors.errorContainer, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp), shape = RoundedCornerShape(12.dp)) {
                            Column(Modifier.padding(12.dp)) { Text(message, style = MaterialTheme.typography.bodySmall); TextButton(onClick = model::dismissMessage) { Text("Dismiss") } }
                        }
                    }
                    state.operation?.let { operation ->
                        Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), color = colors.surfaceContainer, shape = RoundedCornerShape(12.dp)) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("${operation.vmName} · ${operation.action.title}", style = MaterialTheme.typography.labelMedium)
                                Text(operation.message.ifBlank { operation.phase.name }, style = MaterialTheme.typography.bodySmall)
                                if (operation.unresolved && !state.operationBusy) TextButton(onClick = model::checkOperation) { Text("Check status") }
                                if (operation.unresolved) {
                                    TextButton(onClick = model::reviewOperation,
                                        enabled = state.connected && !state.loading && !state.operationBusy && !state.journalProblem) { Text("Review pending action") }
                                }
                            }
                        }
                    }
                    when (page) {
                        0 -> Overview(state, now) { page = 2 }
                        1 -> VmList(state, model, model::openDetails)
                        2 -> Connections(state, model)
                        else -> Settings(state.appearance, model)
                    }
                }
            }
        }
        state.pendingTrust?.let { pending ->
            AlertDialog(onDismissRequest = model::cancelTrust, title = { Text("Verify this server") }, text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Compare this SHA-256 fingerprint with a trusted source for your XCP-ng server. Credentials are sent only after you accept.")
                    Text(pending.profile.endpoint, style = MaterialTheme.typography.bodySmall)
                    Text(pending.certificate.sha256.chunked(2).joinToString(":"), style = MaterialTheme.typography.bodySmall)
                    Text("Accepting pins this certificate for future connections.", style = MaterialTheme.typography.bodySmall)
                }
            }, confirmButton = { TextButton(onClick = model::acceptTrust) { Text("Trust and connect") } },
                dismissButton = { TextButton(onClick = model::cancelTrust) { Text("Cancel") } })
        }
        state.selectedGuest?.let { guest ->
            GuestDialog(guest, state, model, model::closeDetails)
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

@Composable private fun Overview(state: MeteorState, now: Long, connect: () -> Unit) {
    val inventory = state.inventory
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { PageTitle("Your universe", state.selectedProfile?.label ?: "A clear view of every spark.") }
        if (inventory == null) item {
            Panel {
                SpaceIcon("orbit", Modifier.size(52.dp))
                Text("Bring your first server into orbit", style = MaterialTheme.typography.titleLarge)
                Text("Connect directly to XCP-ng to see hosts, virtual machines, and storage.", style = MaterialTheme.typography.bodyMedium)
                Button(onClick = connect) { Text("Add connection") }
            }
        } else {
            val fresh = state.connected && now - inventory.observedAtMillis < 45_000
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(if (fresh) "● Connected" else "○ Last known", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                    Text(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(inventory.observedAtMillis)), style = MaterialTheme.typography.labelMedium)
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
                }
            }
            item { Text("CONSTELLATION", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) }
            item { Panel { Text(inventory.poolName, style = MaterialTheme.typography.titleLarge); Text("Pool · ${inventory.hosts.size} ${if (inventory.hosts.size == 1) "host" else "hosts"} · ${inventory.guests.size} ${if (inventory.guests.size == 1) "VM" else "VMs"}", style = MaterialTheme.typography.bodySmall) } }
            items(inventory.hosts, key = { it.uuid }) { host ->
                Panel {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        SpaceIcon("planet", Modifier.size(40.dp)); Column { Text(host.name, fontWeight = FontWeight.Medium); Text(if (host.online) "Host online" else "Host offline", style = MaterialTheme.typography.bodySmall) }
                    }
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

@Composable private fun Panel(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable private fun VmList(state: MeteorState, model: MeteorViewModel, select: (Guest) -> Unit) {
    var search by rememberSaveable { mutableStateOf("") }
    val inventory = state.inventory
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { PageTitle("The fleet", "Pools → hosts → virtual machines") }
        item { OutlinedTextField(search, { search = it }, label = { Text("Find a VM") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
        if (inventory == null) item { Text("Connect to a server to see your fleet.") }
        else {
            item { Text(inventory.poolName, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold) }
            val groups = InventoryMapper.groups(inventory)
            groups.forEach { group ->
                val guests = group.guests.filter { it.name.contains(search, ignoreCase = true) }
                if (guests.isNotEmpty() || search.isBlank()) {
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
            if (inventory.guests.none { it.name.contains(search, true) }) item { Text(if (search.isBlank()) "This pool has no VMs." else "No matching VMs.") }
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

@Composable private fun GuestDialog(guest: Guest, state: MeteorState, model: MeteorViewModel, dismiss: () -> Unit) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> if (uri != null) model.importIcon(guest, uri) }
    var showAddresses by remember(guest.uuid) { mutableStateOf(false) }
    AlertDialog(onDismissRequest = dismiss, title = { Text(guest.name) }, text = {
        Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            val detail = state.details?.takeIf { it.uuid == guest.uuid && it.authority == guest.poolUuid }
            if (state.detailsBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("${detail?.power ?: guest.power} · ${detail?.vcpus ?: guest.vcpus} vCPU · ${bytes(detail?.memoryBytes ?: guest.memoryBytes)}")
            if (detail != null) {
                Text("Host: ${detail.host ?: if (detail.power == "Halted") "Unassigned" else "Unknown"}", style = MaterialTheme.typography.bodySmall)
                Text(if (detail.guestToolsLive) "Guest tools reporting${detail.os?.let { " · $it" }.orEmpty()}" else "Guest tools not reporting", style = MaterialTheme.typography.bodySmall)
                detail.bootTime?.takeIf { detail.power == "Running" && PowerRules.validBoot(it) }?.let { Text("Last boot: $it", style = MaterialTheme.typography.bodySmall) }
                if (detail.addresses.isNotEmpty()) {
                    TextButton(onClick = { showAddresses = !showAddresses }) { Text(if (showAddresses) "Hide guest addresses" else "Show guest addresses (${detail.addresses.size})") }
                    if (showAddresses) Text(detail.addresses.joinToString("\n"), style = MaterialTheme.typography.bodySmall)
                }
                Text("Disks", fontWeight = FontWeight.Medium)
                if (detail.disks.isEmpty()) Text("No virtual disks", style = MaterialTheme.typography.bodySmall)
                detail.disks.forEach { disk -> Text("${disk.name} · ${bytes(disk.size)}\nDevice ${disk.device} · ${if (disk.attached) "Attached" else "Detached"}${if (disk.readOnly) " · Read-only" else ""}", style = MaterialTheme.typography.bodySmall) }
                Text("Network interfaces", fontWeight = FontWeight.Medium)
                if (detail.networks.isEmpty()) Text("No network interfaces", style = MaterialTheme.typography.bodySmall)
                detail.networks.forEach { nic -> Text("${nic.name}\nInterface ${nic.device} · ${nic.mac}\n${if (nic.attached) "Connected" else "Disconnected"}", style = MaterialTheme.typography.bodySmall) }
                Text("Power", fontWeight = FontWeight.Medium)
                PowerAction.entries.forEach { action ->
                    OutlinedButton(onClick = { model.requestPower(action) }, modifier = Modifier.fillMaxWidth(),
                        enabled = state.connected && !state.loading && !state.detailsBusy && !state.journalProblem && !state.operationBusy &&
                            state.operation?.unresolved != true && PowerRules.eligible(detail, action)) { Text(action.title) }
                }
                if (state.operation?.unresolved == true) Text("A pending operation must be reconciled before another action.", style = MaterialTheme.typography.bodySmall)
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
    var label by rememberSaveable { mutableStateOf("") }
    var endpoint by rememberSaveable { mutableStateOf("") }
    var username by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    LaunchedEffect(state.manualConnectionEvent) { if (state.manualConnectionEvent > 0) password = "" }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { PageTitle("Connections", "Direct XAPI · HTTPS · certificate pinning") }
        items(state.profiles, key = { it.id }) { profile ->
            Panel { Text(profile.label, style = MaterialTheme.typography.titleMedium); Text("Connection profile", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { model.switchProfile(profile) }, enabled = !state.loading) { Text(if (profile.id == state.selectedId) "Reconnect" else "Connect") } }
        }
        item {
            Panel {
                Text("Add XCP-ng server", style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(label, { label = it }, label = { Text("Connection name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(endpoint, { endpoint = it }, label = { Text("Server address") }, placeholder = { Text("https://your-server") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(username, { username = it }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(password, { password = it }, label = { Text("Password") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                Button(onClick = { model.inspect(label, endpoint, username, password) }, enabled = !state.loading && endpoint.isNotBlank() && username.isNotBlank() && password.isNotBlank()) { Text("Review certificate") }
                Text("Credentials are encrypted with Android Keystore after a successful connection. Use your private network or VPN.", style = MaterialTheme.typography.bodySmall)
            }
        }
        item { Text("Connect to the XCP-ng pool coordinator using direct XAPI.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable private fun Settings(appearance: Appearance, model: MeteorViewModel) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { PageTitle("Make it yours", "One universe. Your atmosphere.") }
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

private fun bytes(value: Long): String = when {
    value >= 1L shl 40 -> "%.1f TiB".format(value / (1L shl 40).toDouble())
    value >= 1L shl 30 -> "%.1f GiB".format(value / (1L shl 30).toDouble())
    else -> "%.0f MiB".format(value / (1L shl 20).toDouble())
}
