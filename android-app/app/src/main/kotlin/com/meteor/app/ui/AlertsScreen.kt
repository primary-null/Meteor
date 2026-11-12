package com.meteor.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.meteor.app.MeteorState
import com.meteor.app.MeteorViewModel
import com.meteor.app.data.*
import java.text.DateFormat
import java.util.Date

// --- A4 alerts/events ---
// Alerts inbox. Reached from the "Alerts" navigation entry (page 7 in the current integer navigation; the
// integrator moves it into A1's destination registry). Severity is shown by shape and word as well as colour.

/** What the screen can ask for. Kept apart from the view model so the screen can be shown from a fixture. */
class AlertsActions(
    val refresh: () -> Unit, val markSeen: () -> Unit, val dismiss: (List<AlertMessage>) -> Unit,
    val openGuest: (Guest) -> Unit, val openOverview: () -> Unit,
)

@Composable
fun AlertsScreen(state: MeteorState, now: Long, model: MeteorViewModel, openOverview: () -> Unit) =
    AlertsScreen(state, now, AlertsActions(model::reloadAlerts, model::markAlertsSeen, model::dismissAlerts, model::openDetails, openOverview))

@Composable
fun AlertsScreen(state: MeteorState, now: Long, actions: AlertsActions) {
    val inbox = state.currentAlerts
    val inventory = state.inventory
    var query by rememberSaveable { mutableStateOf("") }
    var severities by rememberSaveable { mutableStateOf("") }
    var kinds by rememberSaveable { mutableStateOf("") }
    var newOnly by rememberSaveable { mutableStateOf(false) }
    var newestFirst by rememberSaveable { mutableStateOf(false) }
    var hostId by remember { mutableStateOf<String?>(null) }
    var showStorage by remember { mutableStateOf(false) }
    var dismissOne by remember { mutableStateOf<AlertMessage?>(null) }
    var dismissBatch by remember { mutableStateOf<List<AlertMessage>?>(null) }
    // What was new when this screen opened. It stays fixed while the screen is open, even as "seen" advances.
    val baseline = remember(inbox?.scopeKey) { inbox?.lastSeen }
    val filter = AlertFilter(csvSet(severities, AlertSeverity.entries), csvSet(kinds, AlertObjectKind.entries), query, newOnly,
        if (newestFirst) AlertSort.NEWEST else AlertSort.PRIORITY)
    val shown = remember(inbox?.messages, inventory, severities, kinds, query, newOnly, newestFirst, baseline) {
        inbox?.let { filter.apply(it.messages, inventory, baseline) }.orEmpty()
    }
    LaunchedEffect(inbox?.scopeKey, inbox?.syncedAt) { actions.markSeen() }
    val canDismiss = state.connected && !state.loading && !state.profileBusy && !state.alertDismissBusy && inbox != null

    LazyColumn(Modifier.fillMaxSize().testTag("alerts-list"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Text("Alerts", style = MaterialTheme.typography.headlineMedium)
            Text("Messages the server keeps for this pool, including ones raised by other clients. Dismissing removes a message for everyone on the pool.",
                style = MaterialTheme.typography.bodySmall)
        }
        item {
            Text(when (state.events) {
                EventStatus.LIVE -> "Live updates on. They use one extra server session while Meteor is open."
                EventStatus.CONNECTING -> "Live updates connecting…"
                EventStatus.RETRYING -> "Live updates paused; retrying. Alerts also refresh on a timer."
                EventStatus.OFF -> "Live updates off. Alerts refresh on a timer."
            }, style = MaterialTheme.typography.labelMedium, modifier = Modifier.testTag("alerts-live-status"))
            inbox?.let {
                val fresh = state.connected && state.alertsProblem == null && now - it.syncedAt in 0..120_000
                Text("${if (fresh) "Server read" else "Last-known read"} · ${alertTime(it.syncedAt)}", style = MaterialTheme.typography.labelMedium)
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = actions.refresh, enabled = state.connected && !state.alertsBusy) { Text("Refresh alerts") }
                OutlinedButton(onClick = { dismissBatch = shown.take(AlertDismissal.MAX_BATCH) }, enabled = canDismiss && shown.isNotEmpty()) {
                    Text("Dismiss shown (${shown.size.coerceAtMost(AlertDismissal.MAX_BATCH)})")
                }
            }
        }
        item { OutlinedTextField(query, { query = it }, label = { Text("Search alerts") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AlertSeverity.entries.filter { it != AlertSeverity.UNRATED }.forEach { severity ->
                    val selected = severity in filter.severities
                    FilterChip(selected = selected, onClick = { severities = csvEncode(csvSet(severities, AlertSeverity.entries).toggle(severity)) },
                        label = { Text(severity.label) }, leadingIcon = { SeverityMark(severity, Modifier.size(14.dp)) })
                }
            }
        }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AlertObjectKind.entries.forEach { kind ->
                    FilterChip(selected = kind in filter.kinds, onClick = { kinds = csvEncode(csvSet(kinds, AlertObjectKind.entries).toggle(kind)) },
                        label = { Text(kind.label) })
                }
                FilterChip(selected = newOnly, onClick = { newOnly = !newOnly }, label = { Text("New only") })
                FilterChip(selected = newestFirst, onClick = { newestFirst = !newestFirst }, label = { Text(if (newestFirst) "Newest first" else "Priority first") })
            }
        }
        if (state.alertsBusy && inbox == null) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        state.alertsProblem?.let { problem -> item { Text(problem, modifier = Modifier.testTag("alerts-problem")) } }
        if (inbox != null) {
            item {
                Text("Showing ${shown.size} of ${inbox.messages.size} alerts${if (inbox.truncated) " (older alerts are not loaded)" else ""}.",
                    style = MaterialTheme.typography.bodySmall)
            }
            if (shown.isEmpty()) item { Text(if (inbox.messages.isEmpty()) "The server reports no alerts." else "No alerts match these filters.") }
            items(shown, key = { it.ref }) { message ->
                val resolved = remember(message, inventory) { AlertObjects.resolve(message, inventory) }
                AlertCard(message, resolved, AlertSeen.isNew(message, baseline), canDismiss, onDismiss = { dismissOne = message }) {
                    when (resolved.kind) {
                        AlertObjectKind.VM -> resolved.guest?.let(actions.openGuest)
                        AlertObjectKind.HOST -> hostId = resolved.uuid
                        AlertObjectKind.STORAGE -> showStorage = true
                        AlertObjectKind.POOL -> actions.openOverview()
                        AlertObjectKind.OTHER -> Unit
                    }
                }
            }
        } else if (!state.alertsBusy && state.alertsProblem == null) item { Text("Connect to a server to read its alerts.") }
    }

    if (inventory != null) {
        inventory.hosts.firstOrNull { it.uuid == hostId }?.let { HostDetails(it, inventory, state.connected) { hostId = null } }
        if (showStorage) StorageDetails(inventory, state.connected) { showStorage = false }
    }
    dismissOne?.let { message ->
        AlertDialog(onDismissRequest = { dismissOne = null }, title = { Text("Dismiss this alert?") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(AlertNames.humanize(message.name), fontWeight = FontWeight.Medium)
                Text("This removes the message from the pool for everyone who uses it. It cannot be undone, although a condition that keeps recurring raises a new alert.")
                Text("Meteor confirms with the server that it is gone before removing it from this list.", style = MaterialTheme.typography.bodySmall)
            }
        }, confirmButton = { TextButton(onClick = { dismissOne = null; actions.dismiss(listOf(message)) }, enabled = canDismiss) { Text("Dismiss alert") } },
            dismissButton = { TextButton(onClick = { dismissOne = null }) { Text("Cancel") } })
    }
    dismissBatch?.let { batch ->
        var typed by remember(batch) { mutableStateOf("") }
        AlertDialog(onDismissRequest = { dismissBatch = null }, title = { Text("Dismiss ${batch.size} alerts?") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("This removes exactly these ${batch.size} messages from the pool for everyone who uses it. It cannot be undone.")
                if (shown.size > batch.size) Text("${shown.size} alerts match the filters; only the first ${batch.size} are included. Repeat to continue.", style = MaterialTheme.typography.bodySmall)
                Text("Type ${batch.size} to confirm.")
                OutlinedTextField(typed, { typed = it.filter(Char::isDigit).take(4) }, singleLine = true, label = { Text("Number of alerts") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
            }
        }, confirmButton = {
            TextButton(onClick = { dismissBatch = null; actions.dismiss(batch) }, enabled = canDismiss && typed == batch.size.toString()) {
                Text("Dismiss ${batch.size} alerts")
            }
        }, dismissButton = { TextButton(onClick = { dismissBatch = null }) { Text("Cancel") } })
    }
}

@Composable
private fun AlertCard(message: AlertMessage, resolved: ResolvedAlertObject, isNew: Boolean, canDismiss: Boolean,
    onDismiss: () -> Unit, openObject: () -> Unit) {
    var expanded by rememberSaveable(message.ref) { mutableStateOf(false) }
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SeverityMark(message.severity, Modifier.size(18.dp))
                Text(message.severity.label, style = MaterialTheme.typography.labelLarge, color = severityColor(message.severity))
                if (isNew) Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.primaryContainer) {
                    Text("New", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
                }
                Spacer(Modifier.weight(1f))
                Text(alertTime(message.timestamp), style = MaterialTheme.typography.bodySmall)
            }
            Text(AlertNames.humanize(message.name), style = MaterialTheme.typography.titleMedium)
            if (resolved.inInventory && resolved.kind != AlertObjectKind.OTHER) {
                // A long VM or host name is cut with an ellipsis rather than broken inside a word.
                TextButton(onClick = openObject, contentPadding = PaddingValues(0.dp)) { Text("${resolved.display}  ›", maxLines = 1, overflow = TextOverflow.Ellipsis) }
            } else Text(resolved.display, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            // Device finding: perfmon alarm bodies are raw XML-like text; show a readable summary first.
            AlarmText.summary(message.name, message.body)?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            if (message.body.isNotBlank()) {
                Text(message.body, style = MaterialTheme.typography.bodySmall, maxLines = if (expanded) 60 else 3)
                if (message.body.length > 140 || message.body.count { it == '\n' } >= 3) {
                    TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(0.dp)) { Text(if (expanded) "Show less" else "Show more") }
                }
            }
            if (expanded && message.name.isNotBlank()) Text("Server name: ${message.name}", style = MaterialTheme.typography.labelSmall)
            OutlinedButton(onClick = onDismiss, enabled = canDismiss) { Text("Dismiss") }
        }
    }
}

@Composable
private fun severityColor(severity: AlertSeverity): Color {
    val dark = MaterialTheme.colorScheme.surface.luminance() < .5f
    return when (severity) {
        AlertSeverity.CRITICAL -> if (dark) Color(0xFFFF8A80) else Color(0xFFB3261E)
        AlertSeverity.HIGH -> if (dark) Color(0xFFFFB86B) else Color(0xFFB45309)
        AlertSeverity.WARNING -> if (dark) Color(0xFFF2D16B) else Color(0xFF7A5E00)
        AlertSeverity.LOW -> if (dark) Color(0xFF9CC3E8) else Color(0xFF2F5F8A)
        AlertSeverity.INFO, AlertSeverity.UNRATED -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}

/** Distinct shapes: filled diamond, filled triangle, outlined triangle, filled circle, outlined circle, outlined square. */
@Composable
private fun SeverityMark(severity: AlertSeverity, modifier: Modifier) {
    val color = severityColor(severity)
    Canvas(modifier.semantics { contentDescription = "${severity.label} priority" }) {
        val w = size.width; val h = size.height; val line = Stroke(1.8.dp.toPx())
        fun triangle() = Path().apply { moveTo(w * .5f, h * .08f); lineTo(w * .94f, h * .9f); lineTo(w * .06f, h * .9f); close() }
        when (severity) {
            AlertSeverity.CRITICAL -> drawPath(Path().apply { moveTo(w * .5f, 0f); lineTo(w, h * .5f); lineTo(w * .5f, h); lineTo(0f, h * .5f); close() }, color)
            AlertSeverity.HIGH -> drawPath(triangle(), color)
            AlertSeverity.WARNING -> drawPath(triangle(), color, style = line)
            AlertSeverity.LOW -> drawCircle(color, w * .42f, center)
            AlertSeverity.INFO -> drawCircle(color, w * .4f, center, style = line)
            AlertSeverity.UNRATED -> drawRect(color, Offset(w * .12f, h * .12f), Size(w * .76f, h * .76f), style = line)
        }
    }
}

/** The bell for the navigation entry, with a badge for unread priority 1-3 alerts. */
@Composable
fun AlertsNavIcon(unread: Int) {
    BadgedBox(badge = { if (unread > 0) Badge { Text(if (unread > 99) "99+" else unread.toString()) } },
        modifier = Modifier.semantics { contentDescription = if (unread > 0) "Alerts, $unread unread" else "Alerts" }) {
        val color = MaterialTheme.colorScheme.primary
        Canvas(Modifier.size(23.dp)) {
            val w = size.width; val h = size.height; val line = Stroke(1.6.dp.toPx())
            drawArc(color, 180f, 180f, false, Offset(w * .22f, h * .1f), Size(w * .56f, w * .56f), style = line)
            drawLine(color, Offset(w * .22f, h * .38f), Offset(w * .13f, h * .72f), line.width)
            drawLine(color, Offset(w * .78f, h * .38f), Offset(w * .87f, h * .72f), line.width)
            drawLine(color, Offset(w * .13f, h * .72f), Offset(w * .87f, h * .72f), line.width)
            drawCircle(color, w * .07f, Offset(w * .5f, h * .86f))
        }
    }
}

private fun alertTime(value: Long?): String = value?.let {
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it))
} ?: "Time not reported"

private fun <T : Enum<T>> csvSet(csv: String, all: Collection<T>): Set<T> =
    csv.split(',').mapNotNull { name -> all.firstOrNull { it.name == name } }.toSet()
private fun <T : Enum<T>> csvEncode(values: Set<T>): String = values.joinToString(",") { it.name }
private fun <T> Set<T>.toggle(value: T): Set<T> = if (value in this) this - value else this + value
// --- end A4 ---
