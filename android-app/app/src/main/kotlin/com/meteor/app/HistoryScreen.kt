package com.meteor.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.meteor.app.data.OperationHistory
import java.text.DateFormat
import java.util.Date

@Composable
internal fun HistoryScreen(state: MeteorState, refresh: () -> Unit, back: () -> Unit, currentAction: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Text("Action history", style = MaterialTheme.typography.headlineMedium)
            Text("Saved on this device. The latest 50 closed actions and all unresolved actions are retained.", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = back) { Text("Back") }
                OutlinedButton(onClick = refresh, enabled = !state.historyLoading && !state.operationBusy && !state.profileBusy) { Text("Reload history") }
            }
        }
        if (state.historyLoading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (state.journalProblem) item { Text("History is unavailable. Existing records were preserved; power controls remain locked.") }
        else if (state.history.isEmpty() && !state.historyLoading) item { Text("No power actions recorded yet.") }
        items(state.history, key = { it.id }) { operation ->
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(OperationHistory.title(operation), style = MaterialTheme.typography.titleMedium) // B2 storage/host: target-aware title
                    Text(OperationHistory.status(operation), style = MaterialTheme.typography.labelLarge)
                    Text(state.profiles.firstOrNull { it.id == operation.profileId }?.label ?: "Saved connection unavailable", style = MaterialTheme.typography.bodySmall)
                    Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(operation.updatedAt)),
                        style = MaterialTheme.typography.bodySmall)
                    Text(operation.message.ifBlank { OperationHistory.status(operation) }, style = MaterialTheme.typography.bodySmall)
                    if (operation.unresolved) TextButton(onClick = currentAction) { Text("View current action") }
                }
            }
        }
    }
}
