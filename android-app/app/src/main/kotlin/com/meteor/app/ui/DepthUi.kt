package com.meteor.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.meteor.app.DepthState
import com.meteor.app.ControlGates
import com.meteor.app.MeteorState
import com.meteor.app.MeteorViewModel
import com.meteor.app.data.HistoryRange
import java.text.DateFormat
import java.util.Date

/** Everything the read-only depth screens can ask for. The app shell wires these once. */
class DepthActions(
    val selectRange: (HistoryRange?) -> Unit,
    val refreshHistory: () -> Unit,
    val loadVm: (String) -> Unit,
    val loadHost: (String) -> Unit,
    val loadStorage: (String) -> Unit,
    val loadNetworks: () -> Unit,
    val openNetworks: () -> Unit,
)

/**
 * Read-only observability state and actions for the depth composables. [connected] and [idle] gate refresh buttons the
 * same way the shell gates its own reads. It is provided through [LocalDepthUi] so that existing screens need only a
 * one-line hook; without a provider the hooks render nothing.
 */
class DepthUi(val state: DepthState, val authority: String?, val connected: Boolean, val idle: Boolean, val actions: DepthActions) {
    val canRead get() = connected && idle
}
val LocalDepthUi = compositionLocalOf<DepthUi?> { null }

@Composable
fun rememberDepthUi(state: MeteorState, model: MeteorViewModel, openNetworks: () -> Unit): DepthUi {
    val open by rememberUpdatedState(openNetworks)
    val actions = remember(model) {
        DepthActions(model::selectHistoryRange, model::refreshHistory, model::loadVmInfo, model::loadHostInfo, model::loadStorageInfo,
            model::loadNetworks) { open() }
    }
    val authority = state.inventory?.authority
    val idle = !state.loading && !state.profileBusy && !ControlGates.operationExclusive(state)
    return remember(state.depth, authority, state.connected, idle, actions) { DepthUi(state.depth, authority, state.connected, idle, actions) }
}

@Composable
internal fun DepthPanel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .35f))) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable
internal fun InfoRow(label: String, value: String, selectable: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (selectable) SelectionContainer { Text(value, style = MaterialTheme.typography.bodyMedium) }
        else Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
internal fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

@Composable
internal fun Caution(text: String) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(10.dp)) {
        Text("⚠ $text", Modifier.padding(horizontal = 10.dp, vertical = 6.dp), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer)
    }
}

internal fun time(millis: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(millis))
internal fun dateTime(millis: Long): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(millis))
