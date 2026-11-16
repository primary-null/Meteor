package com.meteor.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.meteor.app.MeteorState
import com.meteor.app.MeteorViewModel
import com.meteor.app.data.*
import com.meteor.app.storageView

/*
 * B2 storage/host UI shared by the snapshot, CD, host and storage sections: the view model local
 * for slots that do not receive it, the VM section loader, and every B2 dialog. Dialogs render once
 * from MeteorApp, above details shown as a dialog (compact) or a pane (medium/expanded).
 */

/** Provided by `MeteorApp` so host and storage slots can reach the view model without new parameters. */
val LocalMeteorModel = staticCompositionLocalOf<MeteorViewModel?> { null }

/** Common enablement for a B2 write control. Text explains the first blocking condition. */
internal fun storageWriteBlocker(state: MeteorState): String? = when {
    state.operation?.unresolved == true -> "A pending operation must be reconciled before another action."
    state.journalProblem -> "Operation history must be readable before an action can be sent."
    !state.connected -> "Reconnect before choosing an action."
    else -> null
}

/** One action button with its disabled reason and advisory role text. */
@Composable internal fun TargetActionButton(action: TargetAction, label: String, reason: String?, enabled: Boolean, state: MeteorState, onClick: () -> Unit) {
    ActionButton(label, onClick, enabled = enabled && reason == null)
    reason?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    TargetRules.roleExplanation(state.selectedPermissions, action)?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
}

/** Loads the VM's snapshots and CD drives while the details are shown, and drops them when they close. */
@Composable internal fun ColumnScope.VmStorageSections(detail: VmDetails, state: MeteorState, model: MeteorViewModel) {
    val controller = model.storageHost
    LaunchedEffect(detail.authority, detail.uuid, detail.power, detail.currentOperations, state.connected) {
        if (state.connected) controller.loadVm(detail.authority, detail.uuid)
    }
    DisposableEffect(detail.uuid) { onDispose { controller.closeVm(detail.uuid) } }
    val view = state.storageView
    val vm = view.vm?.takeIf { it.uuid == detail.uuid && it.authority == detail.authority }
    SnapshotsSection(detail, vm, view, state, model)
    CdDriveSection(vm, view, state, model)
}

@Composable fun StorageHostDialogs(state: MeteorState, model: MeteorViewModel) {
    val controller = model.storageHost
    val view = state.storageView
    view.isoRequest?.let { request ->
        if (view.pending == null) IsoPickerDialog(request, enabled = !state.operationBusy, onPick = controller::chooseIso, onDismiss = controller::cancelIso)
    }
    view.pending?.let { confirmation ->
        if (confirmation.action == TargetAction.SNAPSHOT_TAKE) SnapshotNameDialog(confirmation, state, controller::confirm, controller::cancel)
        else TargetConfirmDialog(confirmation, state, onConfirm = { typed -> controller.confirm(typed, null) }, onDismiss = controller::cancel)
    }
    view.review?.let { TargetReviewDialog(it, state, controller::confirmReview, controller::cancelReview) }
}

@Composable private fun TargetConfirmDialog(confirmation: TargetConfirmation, state: MeteorState, onConfirm: (String?) -> Unit, onDismiss: () -> Unit) {
    val action = confirmation.action
    val target = confirmation.state
    TypedConfirmDialog(key = "${target.authority}:${target.uuid}:${action.name}:${target.observedAt}",
        title = confirmationTitle(confirmation), phrase = TargetRules.confirmationPhrase(action, target),
        confirmLabel = "Confirm ${action.title.lowercase()}", enabled = !state.operationBusy, onConfirm = onConfirm, onDismiss = onDismiss,
        subject = TargetRules.phraseSubject(action)) {
        Text("Connection: ${state.selectedProfile?.label.orEmpty()}\nCurrent state: ${target.summary}")
        Text(action.description)
        when (target) {
            is SnapshotTarget -> {
                Text("Snapshot: ${target.name.ifBlank { target.uuid }}${target.time?.let { " · ${XapiTime.display(it)}" }.orEmpty()}${if (target.withMemory) " · with memory" else ""}",
                    style = MaterialTheme.typography.bodySmall)
                if (action == TargetAction.SNAPSHOT_REVERT) Text("The current disks of ${target.vmName} are replaced and their present contents are lost. " +
                    "After the revert the VM is expected to be ${TargetRules.revertedPower(target).lowercase()}.", color = MaterialTheme.colorScheme.error)
                if (action == TargetAction.SNAPSHOT_DELETE) DeletePlanText(target, confirmation.plan)
            }
            is HostState -> {
                if (target.master && (action == TargetAction.HOST_REBOOT || action == TargetAction.HOST_SHUTDOWN))
                    Text("This host is the pool coordinator (master). Meteor's connection runs through it and will drop. " +
                        "The pool cannot be managed until it is back, and Meteor cannot verify the result until then; the record stays uncertain " +
                        "until it is verified or reviewed.", color = MaterialTheme.colorScheme.error)
                if (action == TargetAction.HOST_EVACUATE) Text("${target.residentVms.size} VM${if (target.residentVms.size == 1) "" else "s"} will be migrated: " +
                    target.residentVms.joinToString(), style = MaterialTheme.typography.bodySmall)
            }
            is CdDrive -> confirmation.argument?.let { Text("ISO: ${it.name} (${it.srName})", style = MaterialTheme.typography.bodySmall) }
            else -> Unit
        }
        TargetRules.roleExplanation(state.selectedPermissions, action)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        Text("This action is sent once and never resent automatically.", style = MaterialTheme.typography.bodySmall)
        Text("This confirmation expires 30 seconds after the object was read.", style = MaterialTheme.typography.bodySmall)
    }
}

private fun confirmationTitle(confirmation: TargetConfirmation): String {
    val target = confirmation.state
    return when (confirmation.action) {
        TargetAction.SNAPSHOT_REVERT -> "Revert ${target.vmName} to “${target.name}”?"
        TargetAction.SNAPSHOT_DELETE -> "Delete snapshot “${target.name}”?"
        TargetAction.CD_INSERT -> "Insert ${confirmation.argument?.name ?: "ISO"} into ${target.vmName}?"
        TargetAction.CD_EJECT -> "Eject the ISO from ${target.vmName}?"
        TargetAction.PBD_PLUG -> "Plug ${target.name}?"
        else -> "${confirmation.action.title}: ${target.name}?"
    }
}

@Composable private fun DeletePlanText(snapshot: SnapshotTarget, plan: List<OperationStep>) {
    Text("Will be destroyed, in this order:", style = MaterialTheme.typography.labelLarge)
    plan.forEach { Text("• ${it.kind.title}: ${it.name}", style = MaterialTheme.typography.bodySmall) }
    val kept = snapshot.disks.mapNotNull { disk -> TargetRules.keptReason(disk)?.let { disk to it } }
    if (kept.isNotEmpty()) {
        Text("Not touched:", style = MaterialTheme.typography.labelLarge)
        kept.forEach { (disk, reason) -> Text("• ${disk.vdiName.ifBlank { disk.type }} ($reason)", style = MaterialTheme.typography.bodySmall) }
    }
    Text("Each step is recorded before it is sent. If Meteor is interrupted, the record lists what was and was not destroyed and nothing is retried.",
        style = MaterialTheme.typography.bodySmall)
}

@Composable private fun SnapshotNameDialog(confirmation: TargetConfirmation, state: MeteorState, onConfirm: (String?, String?) -> Unit, onDismiss: () -> Unit) {
    val target = confirmation.state
    var name by rememberSaveable("${target.uuid}:${target.observedAt}") { mutableStateOf(confirmation.snapshotName.orEmpty()) }
    val problem = TargetRules.snapshotNameProblem(name)
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Snapshot ${target.name}?") }, text = {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Connection: ${state.selectedProfile?.label.orEmpty()}\nCurrent state: ${target.summary}")
            Text(TargetAction.SNAPSHOT_TAKE.description)
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Snapshot name") },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
                isError = problem != null, supportingText = problem?.let { { Text(it) } })
            TargetRules.roleExplanation(state.selectedPermissions, TargetAction.SNAPSHOT_TAKE)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Text("This is a disk snapshot without memory. It is sent once and never resent automatically. The confirmation expires 30 seconds after the VM was read.",
                style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = {
        TextButton(onClick = { onConfirm(null, name) }, enabled = problem == null && !state.operationBusy) { Text("Take snapshot") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable private fun IsoPickerDialog(request: IsoChoiceRequest, enabled: Boolean, onPick: (IsoChoice) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Insert ISO: ${request.drive.vmName}") }, text = {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Choose an image from the pool's ISO libraries for ${request.drive.name}.", style = MaterialTheme.typography.bodySmall)
            if (request.catalog.isos.isEmpty()) Text("No ISO images were found on ISO storage repositories.", style = MaterialTheme.typography.bodySmall)
            request.catalog.isos.forEach { iso ->
                ActionButton("${iso.name}\n${iso.srName}", { onPick(iso) }, enabled = enabled)
            }
            if (request.catalog.truncated) Text("Only the first ${request.catalog.isos.size} of ${request.catalog.total} images (by name) are listed.", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable private fun TargetReviewDialog(review: TargetReview, state: MeteorState, onConfirm: (Boolean) -> Unit, onDismiss: () -> Unit) {
    var historyChecked by remember(review) { mutableStateOf(false) }
    val operation = review.operation
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Review ${OperationHistory.title(operation)}") }, text = {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Original action: ${operation.targetAction?.title.orEmpty()}\nCurrent state: ${review.state.summary}\nConnection: ${state.selectedProfile?.label.orEmpty()}")
            (review.state as? HostState)?.let { host ->
                if (operation.targetAction == TargetAction.HOST_REBOOT) Text("Boot time before: ${XapiTime.displayBootMarker(operation.beforeBoot)} · now: ${XapiTime.displayBootMarker(host.bootMarker)}" +
                    if (TargetRules.hostBootChanged(operation.beforeBoot, host.bootMarker)) " (changed)" else " (unchanged)", style = MaterialTheme.typography.bodySmall)
            }
            operation.steps?.let { Text(TargetRules.stepSummary(it), style = MaterialTheme.typography.bodySmall) }
            Text("This fresh read does not prove the old task finished. Check the server's task history and make sure the action is no longer pending before closing the record.")
            Text("The original action stays recorded with an unverified outcome. Closing it sends nothing and permits new actions after a fresh confirmation.")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = historyChecked, onCheckedChange = { historyChecked = it })
                Text("I checked the server task history and confirmed this action is no longer pending.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }, confirmButton = { TextButton(onClick = { onConfirm(historyChecked) }, enabled = historyChecked && !state.operationBusy) { Text("Close reviewed record") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Keep pending") } })
}
