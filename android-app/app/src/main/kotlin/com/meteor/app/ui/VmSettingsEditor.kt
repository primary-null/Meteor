package com.meteor.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.meteor.app.DraftSync
import com.meteor.app.EditorDraft
import com.meteor.app.MeteorState
import com.meteor.app.MeteorViewModel
import com.meteor.app.SettingsGates
import com.meteor.app.data.*
import com.meteor.app.settingsView

/*
 * VM settings editor (METEOR-VM-SETTINGS.md). Shown in place of the VM details: inside the full-screen details dialog
 * on compact width, inside the detail pane on medium/expanded width. The draft is saveable state, so it survives
 * rotation and fold; the server read and any open confirmation live in the view model.
 */

private val DraftSaver = Saver<EditorDraft?, ArrayList<String>>(save = { it?.toSaved() ?: arrayListOf() }, restore = { EditorDraft.fromSaved(it) })

@Composable internal fun ColumnScope.VmSettingsEditor(detail: VmDetails, state: MeteorState, model: MeteorViewModel) {
    val controller = model.vmSettings
    LaunchedEffect(detail.authority, detail.uuid, state.connected) { if (state.connected) controller.load(detail.authority, detail.uuid) }
    DisposableEffect(detail.uuid) { onDispose { controller.close(detail.uuid) } }
    val view = state.settingsView
    val settings = view.settings?.takeIf { it.uuid == detail.uuid && it.authority == detail.authority }
    // The draft follows the server by DraftSync's rule: a finished save restarts it from the read-back values; a plain reload keeps typing.
    val draftState = rememberSaveable(detail.uuid, stateSaver = DraftSaver) { mutableStateOf<EditorDraft?>(null) }
    val editor = settings?.let { DraftSync.sync(draftState.value, it, view.generation) }
    SideEffect { if (draftState.value != editor) draftState.value = editor }
    val draft = editor?.draft
    if (view.busy || view.requestBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
    view.problem?.let { Text("Settings could not be read: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    if (settings == null || editor == null || draft == null) {
        if (!view.busy && view.problem == null) Text("Settings are not loaded.", style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { controller.load(detail.authority, detail.uuid) }, enabled = state.connected && !view.busy) { Text("Load settings") }
        return
    }
    fun update(change: (SettingsDraft) -> SettingsDraft) { draftState.value = editor.copy(draft = change(draft)) }
    val problems = SettingsRules.problems(settings, draft)
    val blocker = SettingsGates.blocker(state)
    val editable = blocker == null

    Text("General", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    SettingText("Name", draft.name, problems[SettingField.NAME], editable) { v -> update { it.copy(name = v) } }
    SettingText("Description", draft.description, problems[SettingField.DESCRIPTION], editable) { v -> update { it.copy(description = v) } }
    TagEditor(draft.tags, problems[SettingField.TAGS], editable) { tags -> update { it.copy(tags = tags) } }

    Text("Resources", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    Text("vCPUs", fontWeight = FontWeight.Medium)
    val vcpuReason = SettingsRules.unavailableReason(settings, SettingGroup.VCPUS)
    if (vcpuReason != null) {
        Text("${settings.vcpusAtStartup} at start-up · maximum ${settings.vcpusMax}", style = MaterialTheme.typography.bodySmall)
        Text(vcpuReason, style = MaterialTheme.typography.bodySmall)
    } else if (settings.power == "Halted") {
        SettingNumber("vCPUs at start-up", draft.vcpusAtStartup, problems[SettingField.VCPUS_AT_STARTUP], editable) { v -> update { it.copy(vcpusAtStartup = v) } }
        SettingNumber("Maximum vCPUs", draft.vcpusMax, problems[SettingField.VCPUS_MAX], editable) { v -> update { it.copy(vcpusMax = v) } }
        Text("The maximum also limits how many vCPUs can be added later while the VM runs.", style = MaterialTheme.typography.bodySmall)
    } else {
        SettingNumber("vCPUs now", draft.vcpusLive, problems[SettingField.VCPUS_LIVE], editable) { v -> update { it.copy(vcpusLive = v) } }
        Text("Running VM: up to the maximum of ${settings.vcpusMax}. The count also applies at the next start. Shut the VM down to change the maximum.",
            style = MaterialTheme.typography.bodySmall)
        if (settings.guestVcpuHotplug != true) Text("The guest has not reported vCPU hot-plug support. It may ignore the change or need a restart.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
    Text("Memory (MiB)", fontWeight = FontWeight.Medium)
    val memoryReason = SettingsRules.unavailableReason(settings, SettingGroup.MEMORY)
    if (memoryReason != null) {
        Text("${SettingsRules.size(settings.memoryDynamicMin)}–${SettingsRules.size(settings.memoryDynamicMax)} " +
            "(static ${SettingsRules.size(settings.memoryStaticMin)}–${SettingsRules.size(settings.memoryStaticMax)})", style = MaterialTheme.typography.bodySmall)
        Text(memoryReason, style = MaterialTheme.typography.bodySmall)
    } else {
        if (settings.power == "Halted") SettingNumber("Static minimum", draft.memoryStaticMin, problems[SettingField.MEMORY_STATIC_MIN], editable) { v -> update { it.copy(memoryStaticMin = v) } }
        SettingNumber("Dynamic minimum", draft.memoryDynamicMin, problems[SettingField.MEMORY_DYNAMIC_MIN], editable) { v -> update { it.copy(memoryDynamicMin = v) } }
        SettingNumber("Dynamic maximum", draft.memoryDynamicMax, problems[SettingField.MEMORY_DYNAMIC_MAX], editable) { v -> update { it.copy(memoryDynamicMax = v) } }
        if (settings.power == "Halted") SettingNumber("Static maximum", draft.memoryStaticMax, problems[SettingField.MEMORY_STATIC_MAX], editable) { v -> update { it.copy(memoryStaticMax = v) } }
        else Text("Static range ${SettingsRules.size(settings.memoryStaticMin)}–${SettingsRules.size(settings.memoryStaticMax)}. Shut the VM down to change it.",
            style = MaterialTheme.typography.bodySmall)
        Text("Must satisfy static minimum ≤ dynamic minimum ≤ dynamic maximum ≤ static maximum.", style = MaterialTheme.typography.bodySmall)
    }
    settings.hostFreeMemory?.let { free ->
        Text("${if (settings.hostFreeMemoryOnResident) "Free on this VM's host" else "Most free on any live host"}: ${SettingsRules.size(free - free % SettingsRules.MIB)}. " +
            "Context only; the server decides whether the memory fits.", style = MaterialTheme.typography.bodySmall)
    }

    Text("Start-up", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Start with the host", Modifier.weight(1f))
        Switch(draft.autoPowerOn, { v -> update { it.copy(autoPowerOn = v) } }, enabled = editable)
    }
    Text(if (settings.poolAutoPowerOn) "Starts this VM when its host starts. The pool's auto power-on is on."
        else "The pool's auto power-on is off, so this setting has no effect until it is turned on for the pool. Meteor does not change the pool setting.",
        style = MaterialTheme.typography.bodySmall, color = if (settings.poolAutoPowerOn) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
    Text("Firmware: ${settings.firmware}", style = MaterialTheme.typography.bodySmall)
    val bootReason = SettingsRules.unavailableReason(settings, SettingGroup.BOOT_ORDER)
    if (bootReason != null) Text("Boot order: $bootReason", style = MaterialTheme.typography.bodySmall)
    else {
        SettingText("Boot order (c disk, d CD drive, n network)", draft.bootOrder, problems[SettingField.BOOT_ORDER], editable, lowercase = true) { v ->
            update { it.copy(bootOrder = v.lowercase().filter { c -> !c.isWhitespace() }) }
        }
        if (SettingsRules.bootOrderProblem(draft.bootOrder) == null) Text(SettingsRules.bootOrderText(draft.bootOrder) + ". Applies at the next start.", style = MaterialTheme.typography.bodySmall)
    }
    Text("HA restart priority", fontWeight = FontWeight.Medium)
    val haReason = SettingsRules.unavailableReason(settings, SettingGroup.HA_PRIORITY)
    if (haReason != null) Text(haReason, style = MaterialTheme.typography.bodySmall)
    else Column(Modifier.selectableGroup()) {
        SettingsRules.HA_CHOICES.forEach { choice ->
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(draft.haPriority == choice, enabled = editable, role = Role.RadioButton,
                onClick = { update { it.copy(haPriority = choice) } }), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(draft.haPriority == choice, onClick = null, enabled = editable)
                Text(SettingsRules.haLabel(choice).replaceFirstChar { it.uppercase() }, Modifier.padding(start = 8.dp))
            }
        }
    }

    Text("Changes", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    val (lines, none) = SettingsRules.preview(settings, draft)
    none?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    lines.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
    if (none == null) FullTexts(runCatching { SettingsRules.plan(settings, draft) }.getOrDefault(emptyList()), "editor")
    blocker?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { controller.request(draft) }, enabled = editable && lines.isNotEmpty()) { Text("Review and save") }
        OutlinedButton(onClick = { draftState.value = DraftSync.begin(settings, view.generation) }, enabled = lines.isNotEmpty() || none != "No changes yet.") { Text("Reset") }
    }
    Text("Each change is sent once and checked by reading it back. Nothing is resent automatically.", style = MaterialTheme.typography.bodySmall)
    TextButton(onClick = { controller.load(detail.authority, detail.uuid) }, enabled = state.connected && !view.busy) { Text("Reload from server") }

    view.pending?.takeIf { it.settings.uuid == detail.uuid }?.let { SettingsConfirmDialog(it, state, controller::confirm, controller::cancel) }
}

@Composable private fun SettingText(label: String, value: String, problem: String?, enabled: Boolean, lowercase: Boolean = false, onChange: (String) -> Unit) {
    OutlinedTextField(value, onChange, Modifier.fillMaxWidth(), enabled = enabled, singleLine = true, label = { Text(label) },
        keyboardOptions = KeyboardOptions(capitalization = if (lowercase) KeyboardCapitalization.None else KeyboardCapitalization.Sentences, autoCorrectEnabled = false),
        isError = problem != null, supportingText = problem?.let { { Text(it) } })
}

@Composable private fun SettingNumber(label: String, value: String, problem: String?, enabled: Boolean, onChange: (String) -> Unit) {
    OutlinedTextField(value, { onChange(it.filter { c -> !c.isWhitespace() }.take(12)) }, Modifier.fillMaxWidth(), enabled = enabled, singleLine = true,
        label = { Text(label) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        isError = problem != null, supportingText = problem?.let { { Text(it) } })
}

@Composable private fun TagEditor(tags: List<String>, problem: String?, enabled: Boolean, onChange: (List<String>) -> Unit) {
    var entry by rememberSaveable { mutableStateOf("") }
    val entryProblem = if (entry.isEmpty()) null else SettingsRules.tagProblem(entry, tags)
    Text("Tags", fontWeight = FontWeight.Medium)
    if (tags.isEmpty()) Text("No tags", style = MaterialTheme.typography.bodySmall)
    tags.forEach { tag ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(tag, Modifier.weight(1f))
            TextButton(onClick = { onChange(tags - tag) }, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) { Text("Remove") }
        }
    }
    problem?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(entry, { entry = it }, Modifier.weight(1f), enabled = enabled, singleLine = true, label = { Text("New tag") },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
            isError = entryProblem != null, supportingText = entryProblem?.let { { Text(it) } })
        OutlinedButton(onClick = { onChange(tags + entry); entry = "" }, enabled = enabled && entry.isNotEmpty() && entryProblem == null) { Text("Add") }
    }
}

@Composable private fun SettingsConfirmDialog(confirmation: SettingsConfirmation, state: MeteorState, onConfirm: (String?) -> Unit, onDismiss: () -> Unit) {
    val settings = confirmation.settings
    val typed = SettingsRules.needsTypedName(settings, confirmation.steps)
    TypedConfirmDialog(key = "settings:${settings.authority}:${settings.uuid}:${settings.observedAt}",
        title = "Save changes to ${settings.name.ifBlank { "this VM" }}?", phrase = if (typed) SettingsRules.confirmationPhrase(settings) else null,
        confirmLabel = "Save", enabled = !state.operationBusy, onConfirm = onConfirm, onDismiss = onDismiss) {
        Text("Connection: ${state.selectedProfile?.label.orEmpty()} · VM ${settings.power.lowercase()}")
        Text("In this order:", style = MaterialTheme.typography.labelLarge)
        confirmation.steps.forEach { Text("• ${SettingsRules.change(it)}", style = MaterialTheme.typography.bodySmall) }
        FullTexts(confirmation.steps, "confirm")
        if (typed) Text("Lowering memory or vCPUs of a running VM takes them away from the guest while it runs. Services can slow down or fail.",
            color = MaterialTheme.colorScheme.error)
        if (confirmation.steps.size > 1) Text("If a change is refused or uncertain, Meteor stops there and lists what was saved and what was not sent.",
            style = MaterialTheme.typography.bodySmall)
        Text("Each change is sent once and never resent automatically. This confirmation expires 30 seconds after the VM was read.",
            style = MaterialTheme.typography.bodySmall)
    }
}

/** Long texts are shown cut around the change in the lines above; this opens the whole before and after so they can be read before confirming. */
@Composable private fun FullTexts(steps: List<SettingStep>, where: String) {
    val cut = steps.mapNotNull { step -> SettingsRules.fullValues(step)?.let { step.kind.label to it } }
    if (cut.isEmpty()) return
    var open by rememberSaveable(where) { mutableStateOf(false) }
    TextButton(onClick = { open = !open }) { Text(if (open) "Hide the full text" else "Show the full text") }
    if (open) Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        cut.forEach { (label, values) ->
            Text("$label before", style = MaterialTheme.typography.labelLarge)
            Text(values.first.ifEmpty { "none" }, style = MaterialTheme.typography.bodySmall)
            Text("$label after", style = MaterialTheme.typography.labelLarge)
            Text(values.second.ifEmpty { "none" }, style = MaterialTheme.typography.bodySmall)
        }
    }
}
