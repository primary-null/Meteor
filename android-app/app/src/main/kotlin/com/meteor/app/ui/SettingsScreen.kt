package com.meteor.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.meteor.app.BuildConfig
import com.meteor.app.MeteorViewModel
import com.meteor.app.data.Appearance

@Composable internal fun SettingsScreen(appearance: Appearance, model: MeteorViewModel, history: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { PageTitle("Make it yours", "One universe. Your atmosphere.") }
        item { Panel {
            Text("Activity", style = MaterialTheme.typography.titleMedium)
            Text("Review verified, failed and unresolved power actions saved on this device.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = history) { Text("Action history") }
        } }
        item { BackgroundAlertsSettings(model) } // B3 background/summary
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
            // The whole row toggles, giving one 48 dp target with the label as its accessible name.
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(appearance.texture, role = Role.Switch, onValueChange = model::texture),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text("Orbital texture"); Text("Subtle stars and orbit lines", style = MaterialTheme.typography.bodySmall) }
                Switch(appearance.texture, onCheckedChange = null)
            }
        } }
        item { Panel { Text("Meteor · Spark", style = MaterialTheme.typography.titleMedium); Text("${BuildConfig.VERSION_NAME} · Android 12+\n${if (BuildConfig.BUILD_TYPE == "candidate") "Release candidate" else if (BuildConfig.DEBUG) "Development build" else "Release build"} · Direct XAPI", style = MaterialTheme.typography.bodyMedium); Text("Your universe. Under control.", color = MaterialTheme.colorScheme.primary) } }
    }
}
