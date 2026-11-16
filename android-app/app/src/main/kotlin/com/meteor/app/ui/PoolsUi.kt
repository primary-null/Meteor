package com.meteor.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.meteor.app.ControlGates
import com.meteor.app.MeteorState
import com.meteor.app.MeteorViewModel
import com.meteor.app.PoolSummaryLine
import com.meteor.app.PoolSummaryLines
import com.meteor.app.UpdateCheckView
import com.meteor.app.data.PoolReachability

// --- B3 background/summary ---
// The all-pools card on Overview, the read-only update check on host details, and the "this alert is for another
// connection" offer on Alerts. Wired once by the shell through [LocalPoolsUi]; without a provider they render nothing.

class PoolsActions(
    val refresh: () -> Unit, val refreshIfStale: () -> Unit, val openPool: (String) -> Unit,
    val checkUpdates: (String) -> Unit, val switchToTarget: () -> Unit, val dismissTarget: () -> Unit,
)

class PoolsUi(val state: MeteorState, val actions: PoolsActions) {
    /** The same gate as the Connections screen's Connect button. */
    val canSwitch get() = !state.loading && !state.profileBusy && !ControlGates.operationExclusive(state) && state.pendingTrust == null
    val canCheckUpdates get() = state.connected && !state.loading && !state.profileBusy && !ControlGates.operationExclusive(state)
}
val LocalPoolsUi = compositionLocalOf<PoolsUi?> { null }

@Composable
fun rememberPoolsUi(state: MeteorState, model: MeteorViewModel): PoolsUi {
    val actions = remember(model) {
        PoolsActions(model::refreshAllPools, model::refreshAllPoolsIfStale, model::openPool, model::checkHostUpdates,
            model::switchToAlertsTarget, model::dismissAlertsTarget)
    }
    return remember(state, actions) { PoolsUi(state, actions) }
}

/** Shown on Overview when two or more profiles are saved. */
@Composable
internal fun AllPoolsCard(state: MeteorState) {
    val ui = LocalPoolsUi.current ?: return
    if (state.profiles.size < 2) return
    LaunchedEffect(state.profiles.size) { ui.actions.refreshIfStale() }
    val summary = state.allPools
    Panel {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("All pools", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = ui.actions.refresh, enabled = !summary.busy) { Text(if (summary.busy) "Checking…" else "Refresh") }
        }
        Text("Each saved connection is read with its own short sign-in, at most three at a time. Nothing is changed. Tap a pool to switch to it.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        PoolSummaryLines.of(state).forEach { line ->
            PoolRow(line, enabled = !line.current && ui.canSwitch) { ui.actions.openPool(line.profile.id) }
        }
        summary.refreshedAt?.let { Text("Checked ${time(it)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

private fun symbol(reachability: PoolReachability) = when (reachability) {
    PoolReachability.NOT_CHECKED -> "○"
    PoolReachability.REACHABLE -> "●"
    PoolReachability.UNREACHABLE -> "✕"
    PoolReachability.CERTIFICATE_CHANGED -> "⚠"
}

@Composable
private fun PoolRow(line: PoolSummaryLine, enabled: Boolean, open: () -> Unit) {
    val read = line.read
    val title = read?.poolName ?: line.profile.label
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().testTag("pool-row-${line.profile.id}")
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = "Switch to ${line.profile.label}", onClick = open)) {
        Column(Modifier.padding(12.dp).heightIn(min = 24.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                val status = if (line.checking) "Checking…" else "${symbol(line.reachability)} ${line.reachability.label}"
                Text(status, style = MaterialTheme.typography.labelMedium,
                    color = if (line.reachability == PoolReachability.REACHABLE) MaterialTheme.colorScheme.primary
                        else if (line.reachability == PoolReachability.NOT_CHECKED) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.error)
            }
            if (title != line.profile.label) Text(line.profile.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (line.current) Text("Current connection", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            if (read != null) {
                val unread = read.unreadUrgent?.let { "$it unread alert${if (it == 1) "" else "s"} (priority 1–3)${if (read.alertsPartial) ", recent only" else ""}" }
                    ?: "Alerts not available"
                Text("Hosts ${read.hostsLive}/${read.hostsTotal} live · VMs ${read.vmsRunning}/${read.vmsTotal} running\n$unread",
                    style = MaterialTheme.typography.bodySmall)
            }
            line.problem?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            if (!line.current && line.reachability != PoolReachability.NOT_CHECKED) line.checkedAt?.let {
                Text("${if (read != null) "Read" else "Tried"} ${time(it)}${if (read != null) " · last known" else ""}",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Host details hook: a manual, clearly read-only update availability check. */
@Composable
internal fun HostUpdateSection(hostUuid: String) {
    val ui = LocalPoolsUi.current ?: return
    val view: UpdateCheckView? = ui.state.currentUpdateChecks[hostUuid]
    SectionLabel("Updates (read-only check)")
    Text("Asks this host's updater plugin which package updates are available. Nothing is downloaded or installed, and Meteor never starts an update. Usually needs the pool-admin role.",
        style = MaterialTheme.typography.bodySmall)
    OutlinedButton(onClick = { ui.actions.checkUpdates(hostUuid) }, enabled = ui.canCheckUpdates && view?.busy != true,
        modifier = Modifier.testTag("check-updates-$hostUuid")) { Text(if (view?.busy == true) "Checking…" else "Check for updates") }
    if (view == null || view.busy) return
    view.problem?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    view.result?.let { result ->
        when {
            !result.recognized -> Text("The host answered in a format Meteor does not recognise, so availability is unknown.", style = MaterialTheme.typography.bodySmall)
            result.total == 0 -> Text("No updates reported.", style = MaterialTheme.typography.bodyMedium)
            else -> {
                Text("${result.total} update${if (result.total == 1) "" else "s"} reported", style = MaterialTheme.typography.bodyMedium)
                result.updates.take(30).forEach { update ->
                    Text("• ${update.name}${update.version?.let { " $it" }.orEmpty()}", style = MaterialTheme.typography.bodySmall)
                }
                if (result.total > 30) Text("…and ${result.total - 30} more", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    view.checkedAt?.let { Text("Checked ${time(it)} · unverified response format", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
}

/** Alerts hook: a notification for another saved connection offers the normal switch; it never connects by itself. */
@Composable
internal fun AlertsTargetCard(state: MeteorState) {
    val ui = LocalPoolsUi.current ?: return
    val target = state.alertsTarget ?: return
    if (target.profileId == state.selectedId) return
    val profile = state.profiles.firstOrNull { it.id == target.profileId }
    Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).testTag("alerts-target"),
        color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (profile == null) {
                Text("That notification was for a connection that is no longer saved on this device.", style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = ui.actions.dismissTarget) { Text("OK") }
            } else {
                Text("That notification was for ${profile.label}.", style = MaterialTheme.typography.bodyMedium)
                Text(if (state.selectedProfile != null) "You are viewing ${state.selectedProfile!!.label}. Switch to see its alerts." else "Connect to see its alerts.",
                    style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = ui.actions.switchToTarget, enabled = ui.canSwitch) { Text("Switch to ${profile.label}") }
                    TextButton(onClick = ui.actions.dismissTarget) { Text("Not now") }
                }
            }
        }
    }
}
// --- end B3 ---
