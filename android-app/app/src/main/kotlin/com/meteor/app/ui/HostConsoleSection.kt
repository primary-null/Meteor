package com.meteor.app.ui

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meteor.app.data.ErrorReasons
import com.meteor.app.data.Host
import com.meteor.app.data.HostConsoleOffer
import com.meteor.app.data.HostConsoleRules
import com.meteor.app.data.Inventory
import kotlinx.coroutines.CancellationException

/**
 * B1 console: "Open host console" in the host details. A read-only check finds whether the host offers a console Meteor can
 * show; the button says why it is disabled when it is. The console itself opens view-only and is never journaled.
 */
@Composable internal fun ColumnScope.HostConsoleSection(host: Host, inventory: Inventory) {
    val model = LocalMeteorModel.current ?: return
    val state by model.state.collectAsStateWithLifecycle()
    val connected = state.connected && state.inventory?.authority == inventory.authority
    val check by produceState<Pair<HostConsoleOffer?, String?>>(null to null, inventory.authority, host.uuid, connected, host.online) {
        value = null to null
        if (!connected || !host.online) return@produceState
        value = try { model.hostConsoleOffer(host) to null } catch (error: CancellationException) { throw error }
            catch (error: Exception) { null to ErrorReasons.reason(error) }
    }
    val (offer, problem) = check
    val reason = HostConsoleRules.unavailableReason(connected, host.online, offer, problem)
    OutlinedButton(onClick = { model.openHostConsole(host) }, enabled = reason == null) { Text("Open host console") }
    Text(reason ?: "Opens view-only. Turning input on asks first: keys and clicks go to the hypervisor's own console.",
        style = MaterialTheme.typography.bodySmall)
}
