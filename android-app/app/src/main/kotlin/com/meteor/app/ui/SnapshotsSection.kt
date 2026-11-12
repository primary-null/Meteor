package com.meteor.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.meteor.app.MeteorState
import com.meteor.app.MeteorViewModel
import com.meteor.app.StorageHostView
import com.meteor.app.data.*

/**
 * B2 storage/host: the VM's snapshots with take, revert and delete. Revert and delete read the
 * snapshot again and open a typed confirmation. Content scrolls with the details pane or dialog.
 */
@Composable internal fun ColumnScope.SnapshotsSection(detail: VmDetails, vm: VmTarget?, view: StorageHostView, state: MeteorState, model: MeteorViewModel) {
    val controller = model.storageHost
    val ready = controller.canWrite(state)
    Text("Snapshots", fontWeight = FontWeight.Medium)
    if (view.vmBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
    view.vmProblem?.let { Text("Snapshots could not be read: $it", style = MaterialTheme.typography.bodySmall) }
    if (vm == null) {
        if (!view.vmBusy && view.vmProblem == null) Text("Snapshots are not loaded.", style = MaterialTheme.typography.bodySmall)
    } else {
        TargetActionButton(TargetAction.SNAPSHOT_TAKE, "Take snapshot…", TargetRules.unavailableReason(vm, TargetAction.SNAPSHOT_TAKE),
            ready, state) { controller.request(TargetAction.SNAPSHOT_TAKE, vm.authority, TargetKind.VM, vm.uuid) }
        if (vm.snapshots.isEmpty()) Text("No snapshots", style = MaterialTheme.typography.bodySmall)
        vm.snapshots.forEach { snapshot ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val current = if (vm.parentUuid == snapshot.uuid) " · current base" else ""
                Text("${snapshot.name.ifBlank { "Unnamed snapshot" }}\n${XapiTime.display(snapshot.time, missing = "Time not reported")}" +
                    "${if (snapshot.withMemory) " · with memory" else " · disks only"}$current", style = MaterialTheme.typography.bodySmall)
                for (action in listOf(TargetAction.SNAPSHOT_REVERT, TargetAction.SNAPSHOT_DELETE)) {
                    val reason = TargetRules.listReason(vm, snapshot, action)
                    ActionButton(if (action == TargetAction.SNAPSHOT_REVERT) "Revert…" else "Delete…",
                        { controller.request(action, vm.authority, TargetKind.SNAPSHOT, snapshot.uuid) }, enabled = ready && reason == null)
                    reason?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
        if (vm.snapshotsTruncated) Text("Only the first ${vm.snapshots.size} snapshots are listed.", style = MaterialTheme.typography.bodySmall)
        TargetRules.roleExplanation(state.selectedPermissions, TargetAction.SNAPSHOT_REVERT)?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
    storageWriteBlocker(state)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    TextButton(onClick = { controller.loadVm(detail.authority, detail.uuid) }, enabled = state.connected && !view.vmBusy) { Text("Reload snapshots and CD drives") }
    Text("Revert and delete need the exact name. Delete removes only the snapshot's own disk snapshots, never the VM's current disks.",
        style = MaterialTheme.typography.bodySmall)
}
