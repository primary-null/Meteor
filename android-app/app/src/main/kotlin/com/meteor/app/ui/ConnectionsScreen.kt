package com.meteor.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.meteor.app.MeteorState
import com.meteor.app.MeteorViewModel

@Composable internal fun ConnectionsScreen(state: MeteorState, model: MeteorViewModel) {
    var forgetId by rememberSaveable { mutableStateOf<String?>(null) }
    var certificateId by rememberSaveable { mutableStateOf<String?>(null) }
    val editable = !state.loading && !state.profileBusy && !state.operationBusy && state.pendingTrust == null
    val editorKey = "editor:${state.editingProfileId ?: "new"}:${state.profileEditSession}"
    val listState = rememberLazyListState()
    LaunchedEffect(state.editingProfileId) { if (state.editingProfileId != null) listState.animateScrollToItem(1) }
    LazyColumn(Modifier.fillMaxSize().testTag("connections-list"), state = listState, contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { PageTitle("Connections", "Direct XAPI · HTTPS · certificate pinning") }
        if (state.editingProfileId != null) item(key = editorKey) { ConnectionForm(state, model) }
        if (state.selectedProfile != null || state.loading) item {
            Panel {
                Text(state.selectedProfile?.label ?: "Connecting", style = MaterialTheme.typography.titleMedium)
                Text(if (state.connected) "Connected" else if (state.loading) "Connecting…" else "Last connection unavailable", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = model::disconnect, enabled = !state.profileBusy && !state.operationBusy) { Text("Disconnect") }
                Text("Disconnect stops refreshes and clears the active selection. Saved profiles and operation history are kept.", style = MaterialTheme.typography.bodySmall)
            }
        }
        items(state.profiles, key = { it.id }) { profile ->
            val pending = state.operation?.let { it.unresolved && it.profileId == profile.id } == true
            Panel {
                Text(profile.label, style = MaterialTheme.typography.titleMedium)
                Text("${profile.endpoint}\n${profile.username}", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { model.switchProfile(profile) }, enabled = editable) { Text(if (profile.id == state.selectedId) "Reconnect" else "Connect") }
                    OutlinedButton(onClick = { model.editProfile(profile.id) }, enabled = editable) { Text("Edit") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { certificateId = profile.id }) { Text("Certificate") }
                    TextButton(onClick = { forgetId = profile.id }, enabled = editable && !state.journalProblem && !pending) { Text("Forget") }
                }
                if (pending) Text("Resolve this connection's pending action before forgetting it. You can still edit credentials or review a renewed certificate.", style = MaterialTheme.typography.bodySmall)
                else if (state.journalProblem) Text("Forgetting is unavailable until operation history can be read.", style = MaterialTheme.typography.bodySmall)
            }
        }
        if (state.editingProfileId == null) item(key = editorKey) { ConnectionForm(state, model) }
        item { Text("Connect to the XCP-ng pool coordinator using direct XAPI.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    state.profiles.firstOrNull { it.id == certificateId }?.let { profile ->
        AlertDialog(onDismissRequest = { certificateId = null }, title = { Text("Saved server certificate") }, text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(profile.label)
                Text(profile.endpoint, style = MaterialTheme.typography.bodySmall)
                Fingerprint("Saved SHA-256", profile.certificateSha256)
                Text("To see the current certificate and its expiry, edit this connection and choose Review server certificate. A changed certificate always needs independent verification.", style = MaterialTheme.typography.bodySmall)
            }
        }, confirmButton = { TextButton(onClick = { certificateId = null }) { Text("Done") } })
    }
    state.profiles.firstOrNull { it.id == forgetId }?.let { profile ->
        AlertDialog(onDismissRequest = { forgetId = null }, title = { Text("Forget ${profile.label}?") }, text = {
            Text("Remove this saved connection and its credentials from Meteor on this device. Operation history is kept. This does not revoke the credentials on the server.")
        }, confirmButton = { TextButton(onClick = { model.forgetProfile(profile.id); forgetId = null }, enabled = editable) { Text("Forget connection") } },
            dismissButton = { TextButton(onClick = { forgetId = null }) { Text("Cancel") } })
    }
}

@Composable private fun ConnectionForm(state: MeteorState, model: MeteorViewModel) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    fun submit(action: () -> Unit) { focus.clearFocus(); keyboard?.hide(); action() }
    val editing = state.profiles.firstOrNull { it.id == state.editingProfileId }
    val key = "${editing?.id ?: "new"}:${state.profileEditSession}"
    var label by rememberSaveable(key) { mutableStateOf(editing?.label.orEmpty()) }
    var endpoint by rememberSaveable(key) { mutableStateOf(editing?.endpoint.orEmpty()) }
    var username by rememberSaveable(key) { mutableStateOf(editing?.username.orEmpty()) }
    // Password drafts never enter saved instance state, including during activity recreation.
    var password by remember(key) { mutableStateOf("") }
    LaunchedEffect(state.manualConnectionEvent) { if (state.manualConnectionEvent > 0) password = "" }
    val enabled = !state.loading && !state.profileBusy && !state.operationBusy && state.pendingTrust == null
    val valid = enabled && endpoint.isNotBlank() && username.isNotBlank() && (editing != null || password.isNotBlank())
    Panel {
        Text(if (editing == null) "Add XCP-ng server" else "Edit connection", style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(label, { label = it }, label = { Text("Connection name") }, enabled = enabled, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(endpoint, { endpoint = it }, label = { Text("Server address") }, placeholder = { Text("https://your-server") }, enabled = enabled, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(username, { username = it }, label = { Text("Username") }, enabled = enabled, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(password, { password = it }, label = { Text(if (editing == null) "Password" else "New password") }, enabled = enabled, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        if (editing != null) {
            Text("Leave the new password empty to keep the saved password. Changing credentials keeps the saved certificate pin; a different server requires certificate review.", style = MaterialTheme.typography.bodySmall)
            Button(onClick = { submit { model.saveConnection(label, endpoint, username, password, editing.id) } }, enabled = valid) { Text("Save changes") }
            OutlinedButton(onClick = { submit { model.inspect(label, endpoint, username, password, editing.id) } }, enabled = valid) { Text("Review server certificate") }
            TextButton(onClick = { submit(model::cancelEdit) }, enabled = !state.profileBusy && !state.operationBusy) { Text("Cancel editing") }
            if (state.operation?.let { it.unresolved && it.profileId == editing.id } == true)
                Text("Pending actions retain their original connection identity. Changing the server, username or certificate requires manual review of the pending action.", style = MaterialTheme.typography.bodySmall)
        } else Button(onClick = { submit { model.inspect(label, endpoint, username, password) } }, enabled = valid) { Text("Review certificate") }
        Text("Credentials are encrypted with Android Keystore after a successful connection. Use your private network or VPN.", style = MaterialTheme.typography.bodySmall)
    }
}
