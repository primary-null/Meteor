package com.meteor.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.meteor.app.ControlGates
import com.meteor.app.MeteorState
import com.meteor.app.data.ConfirmationStrength
import com.meteor.app.data.HostChoice
import com.meteor.app.data.HostTarget
import com.meteor.app.data.PowerAction
import com.meteor.app.data.PowerRules
import com.meteor.app.data.RoleRules
import com.meteor.app.data.VmDetails

/** A2 operations: which kinds are shown, grouped, for a VM's current power state. */
object VmActionLayout {
    val sections: List<Pair<String, List<PowerAction>>> = listOf(
        "Power" to listOf(PowerAction.START, PowerAction.SHUTDOWN, PowerAction.REBOOT, PowerAction.RESUME,
            PowerAction.UNPAUSE, PowerAction.SUSPEND, PowerAction.PAUSE),
        "Placement" to listOf(PowerAction.START_ON, PowerAction.POOL_MIGRATE),
        "Forced" to listOf(PowerAction.HARD_SHUTDOWN, PowerAction.HARD_REBOOT),
    )
    /** Kinds that can start from this power state. The panel shows every kind (disabled when inapplicable) for a stable layout. */
    fun applicable(detail: VmDetails): List<Pair<String, List<PowerAction>>> =
        sections.map { (heading, kinds) -> heading to kinds.filter { detail.power in it.allowedFrom } }.filter { it.second.isNotEmpty() }
    fun label(action: PowerAction) = if (action.hostTarget == HostTarget.NONE) action.title else "${action.title}…"
}

/**
 * All VM operations for one VM, with the reason each disabled control is unavailable. A control is
 * enabled only when the shared gate ([PowerRules.eligible]) passes and no operation is pending.
 * Role text is advisory; the server decides. Host-targeted kinds open the host picker first.
 */
@Composable
fun VmActionsPanel(detail: VmDetails, state: MeteorState, onRequest: (PowerAction) -> Unit, onChooseHost: (PowerAction) -> Unit) {
    val ready = ControlGates.vmActionsReady(state)
    val permissions = state.selectedPermissions
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Power", fontWeight = FontWeight.Medium)
        // Device finding: hiding inapplicable kinds made the panel resize when a live update changed the power state,
        // moving another button (once "Revert…") under the user's finger. Every kind keeps its place; inapplicable
        // ones are disabled, and each keeps a one-line reason slot so the height does not change with eligibility.
        VmActionLayout.sections.forEach { (heading, kinds) ->
            if (heading != "Power") Text(heading, style = MaterialTheme.typography.labelLarge)
            if (heading == "Forced") Text("Forced actions do not ask the guest and can lose unsaved data. They require the VM name.",
                style = MaterialTheme.typography.bodySmall)
            kinds.forEach { action ->
                ActionButton(VmActionLayout.label(action), { if (action.hostTarget == HostTarget.NONE) onRequest(action) else onChooseHost(action) },
                    enabled = ready && PowerRules.eligible(detail, action))
                Text(PowerRules.unavailableReason(detail, action).orEmpty(), style = MaterialTheme.typography.bodySmall,
                    minLines = 1, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                RoleRules.explanation(permissions, action)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }
        }
        if (state.hostChoiceBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (state.operation?.unresolved == true) Text("A pending operation must be reconciled before another action.", style = MaterialTheme.typography.bodySmall)
        else if (state.journalProblem) Text("Operation history must be readable before a power action can be sent.", style = MaterialTheme.typography.bodySmall)
        else if (!state.connected) Text("Reconnect before choosing a power action.", style = MaterialTheme.typography.bodySmall)
        Text(RoleRules.summary(permissions), style = MaterialTheme.typography.bodySmall)
        Text("Availability comes from the server's current VM operations. Graceful actions may require working guest tools.", style = MaterialTheme.typography.bodySmall)
    }
}

/** Host picker and confirmation for the selected VM. Rendered once, outside the VM details. */
@Composable
fun VmOperationDialogs(state: MeteorState, onConfirm: (typedName: String?) -> Unit, onCancel: () -> Unit,
    onPickHost: (HostChoice) -> Unit, onCancelHost: () -> Unit) {
    state.pendingHostChoice?.let { request ->
        val detail = state.details?.takeIf { it.uuid == request.vmUuid && it.authority == request.authority }
        if (detail != null && state.pendingPower == null)
            // Disabled (not dismissed) while the connection refreshes or a user-visible read runs; an automatic poll never disables it.
            HostPickerDialog(request, detail.name, enabled = ControlGates.hostPickerEnabled(state), onPick = onPickHost, onDismiss = onCancelHost)
    }
    state.pendingPower?.let { confirmation ->
        val action = confirmation.action
        val phrase = if (action.confirmation == ConfirmationStrength.TYPED_NAME) PowerRules.confirmationPhrase(confirmation.details) else null
        TypedConfirmDialog(key = "${confirmation.details.authority}:${confirmation.details.uuid}:${action.name}:${confirmation.details.observedAt}",
            title = action.confirmationTitle(confirmation.details.name, confirmation.targetHost?.name), phrase = phrase,
            confirmLabel = "Confirm ${action.title.lowercase()}", enabled = !state.operationBusy, onConfirm = onConfirm, onDismiss = onCancel) {
            Text("Connection: ${state.selectedProfile?.label.orEmpty()}\nCurrent state: ${confirmation.details.power}")
            confirmation.targetHost?.let { Text("Target host: ${it.name}") }
            Text(action.description)
            RoleRules.explanation(state.selectedPermissions, action)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Text(if (action.forced) "This forced action is sent once. It is never resent automatically."
                else "No forced power action or automatic resubmission will occur.", style = MaterialTheme.typography.bodySmall)
            Text("This confirmation expires 30 seconds after the VM was read.", style = MaterialTheme.typography.bodySmall)
        }
    }
}
