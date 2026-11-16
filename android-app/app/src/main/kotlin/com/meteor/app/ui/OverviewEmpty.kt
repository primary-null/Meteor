package com.meteor.app.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.meteor.app.MeteorState

/** What Overview shows in place of the pool while there is no inventory. */
internal enum class OverviewEmptyKind { FIRST_RUN, VAULT_UNREADABLE, NO_SELECTION, CONNECTING, OFFLINE, PAUSED, NOT_CONNECTED }

internal data class OverviewEmpty(val kind: OverviewEmptyKind, val title: String, val body: String, val action: String?, val busy: Boolean = false)

/**
 * D6 of the first phone run: with a saved connection that could not be reached, Overview showed the first-run invitation
 * ("Bring your first server into orbit") next to the error. The invitation is for a person with no connection at all; every
 * other case says what is happening to the one they have. The header's Refresh and the notice above carry the detail.
 */
internal object OverviewEmptyState {
    fun of(state: MeteorState): OverviewEmpty? {
        if (state.inventory != null) return null
        val profile = state.selectedProfile
        val name = profile?.label?.takeIf { it.isNotBlank() } ?: "the server"
        return when {
            state.vaultProblem -> OverviewEmpty(OverviewEmptyKind.VAULT_UNREADABLE, "Saved connections could not be read",
                "Meteor left them untouched. Open Connections to see what you can do.", "Open connections")
            state.profiles.isEmpty() -> OverviewEmpty(OverviewEmptyKind.FIRST_RUN, "Bring your first server into orbit",
                "Connect directly to XCP-ng to see hosts, virtual machines, and storage.", "Add connection")
            profile == null -> OverviewEmpty(OverviewEmptyKind.NO_SELECTION, "Choose a connection",
                "Pick one of your saved connections to see its hosts, virtual machines and storage.", "Open connections")
            state.loading || state.profileBusy || state.connected -> OverviewEmpty(OverviewEmptyKind.CONNECTING, "Connecting to $name…",
                "Hosts, virtual machines and storage appear here once the server answers.", null, busy = true)
            state.offline -> OverviewEmpty(OverviewEmptyKind.OFFLINE, "Waiting for the network",
                "Meteor will connect to $name when the network returns.", "Open connections")
            state.retryStop != null -> OverviewEmpty(OverviewEmptyKind.PAUSED, "Not connected to $name",
                "Automatic reconnecting is paused; the notice above says why. Tap Refresh to try again, or review the connection.", "Open connections")
            else -> OverviewEmpty(OverviewEmptyKind.NOT_CONNECTED, "Not connected to $name",
                "Meteor could not reach it. Tap Refresh to try again, or check the connection.", "Open connections")
        }
    }
}

@Composable internal fun OverviewEmptyPanel(empty: OverviewEmpty, connect: () -> Unit) {
    Panel {
        if (empty.kind == OverviewEmptyKind.FIRST_RUN) SpaceIcon("orbit", Modifier.size(52.dp))
        Text(empty.title, style = MaterialTheme.typography.titleLarge)
        Text(empty.body, style = MaterialTheme.typography.bodyMedium)
        if (empty.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        empty.action?.let { Button(onClick = connect) { Text(it) } }
    }
}
