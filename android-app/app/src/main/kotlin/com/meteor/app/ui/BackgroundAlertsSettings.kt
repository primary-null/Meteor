package com.meteor.app.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meteor.app.MeteorViewModel
import com.meteor.app.NotificationAccess
import com.meteor.app.data.AlertNotifySettings
import com.meteor.app.data.NotifyThreshold

// --- B3 background/summary ---

/**
 * Settings hook: per-profile, opt-in background alert notifications. The notification permission is requested at the
 * moment a switch is turned on (Android 13+); a refusal leaves the switch off and explains how to allow it later.
 */
@Composable
internal fun BackgroundAlertsSettings(model: MeteorViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var allowed by remember { mutableStateOf(NotificationAccess.allowed(context)) }
    var blockedChannels by remember { mutableStateOf(NotificationAccess.blockedChannels(context)) }
    var denied by rememberSaveable { mutableStateOf(false) }
    var pending by rememberSaveable { mutableStateOf<String?>(null) }
    LifecycleResumeEffect(Unit) {
        allowed = NotificationAccess.allowed(context); blockedChannels = NotificationAccess.blockedChannels(context)
        onPauseOrDispose { }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val id = pending
        pending = null
        allowed = NotificationAccess.allowed(context)
        if (granted && id != null) {
            denied = false
            model.setAlertNotify(id, (state.alertNotify[id] ?: AlertNotifySettings()).copy(enabled = true))
        } else denied = true
    }
    Panel {
        Text("Background alerts", style = MaterialTheme.typography.titleMedium)
        Text("Off by default. When on, Android checks that connection about every chosen interval while the network is up and the battery is not low: " +
            "one short read-only sign-in, new alerts only, then sign-out. Nothing is changed on the server. Notifications show the pool, the object and the alert name.",
            style = MaterialTheme.typography.bodySmall)
        if (state.vaultProblem) Text("Saved profiles could not be opened, so background checks are unavailable.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error)
        val anyEnabled = state.alertNotify.values.any { it.enabled }
        if ((denied || anyEnabled) && !allowed || blockedChannels.isNotEmpty() && anyEnabled) {
            Text(if (!allowed) "Notifications for Meteor are blocked in Android settings. Meteor will not check in the background until they are allowed."
                else "Turned off in Android settings: ${blockedChannels.joinToString(", ")}. Those alerts will not appear.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            OutlinedButton(onClick = { runCatching { context.startActivity(NotificationAccess.settingsIntent(context)) } }) { Text("Open notification settings") }
        }
        if (state.profiles.isEmpty()) Text("Save a connection first.", style = MaterialTheme.typography.bodySmall)
        state.profiles.forEach { profile ->
            val settings = state.alertNotify[profile.id] ?: AlertNotifySettings()
            HorizontalDivider()
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("notify-${profile.id}")
                .toggleable(settings.enabled, enabled = !state.vaultProblem, role = Role.Switch) { on ->
                    if (!on) model.setAlertNotify(profile.id, settings.copy(enabled = false))
                    else if (NotificationAccess.needsPermission(context)) { pending = profile.id; launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }
                    else model.setAlertNotify(profile.id, settings.copy(enabled = true))
                }, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Notify me about alerts")
                    Text(profile.label, style = MaterialTheme.typography.bodySmall)
                }
                Switch(settings.enabled, onCheckedChange = null, enabled = !state.vaultProblem)
            }
            if (settings.enabled) {
                Text("Minimum priority", style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NotifyThreshold.entries.forEach { threshold ->
                        FilterChip(selected = settings.threshold == threshold, onClick = { model.setAlertNotify(profile.id, settings.copy(threshold = threshold)) },
                            label = { Text(threshold.label) })
                    }
                }
                Text("Check about every", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AlertNotifySettings.INTERVALS.forEach { minutes ->
                        FilterChip(selected = settings.intervalMinutes == minutes, onClick = { model.setAlertNotify(profile.id, settings.copy(intervalMinutes = minutes)) },
                            label = { Text("$minutes min") })
                    }
                }
            }
        }
        Text("Android may delay checks to save battery. Unread markers inside Meteor are separate from notifications.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
// --- end B3 ---
