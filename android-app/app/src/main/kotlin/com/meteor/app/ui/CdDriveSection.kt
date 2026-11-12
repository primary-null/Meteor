package com.meteor.app.ui

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import com.meteor.app.MeteorState
import com.meteor.app.MeteorViewModel
import com.meteor.app.StorageHostView
import com.meteor.app.data.TargetAction
import com.meteor.app.data.TargetKind
import com.meteor.app.data.TargetRules
import com.meteor.app.data.VmTarget

/**
 * B2 storage/host: each virtual CD drive's state with Insert ISO (picker of ISO-library images)
 * and Eject. Both use one confirmation; the drive and the chosen ISO are read again before sending.
 */
@Composable internal fun ColumnScope.CdDriveSection(vm: VmTarget?, view: StorageHostView, state: MeteorState, model: MeteorViewModel) {
    val controller = model.storageHost
    val ready = controller.canWrite(state)
    Text("CD drives", fontWeight = FontWeight.Medium)
    if (vm == null) return
    if (vm.cdDrives.isEmpty()) Text("No CD drive. Adding a drive is not available in Meteor.", style = MaterialTheme.typography.bodySmall)
    vm.cdDrives.forEach { drive ->
        Text("${drive.name} · ${if (drive.empty) "Empty" else drive.vdiName ?: "ISO inserted"}", style = MaterialTheme.typography.bodySmall)
        if (drive.empty) TargetActionButton(TargetAction.CD_INSERT, "Insert ISO…", TargetRules.unavailableReason(drive, TargetAction.CD_INSERT),
            ready, state) { controller.requestIso(drive) }
        else TargetActionButton(TargetAction.CD_EJECT, "Eject", TargetRules.unavailableReason(drive, TargetAction.CD_EJECT),
            ready, state) { controller.request(TargetAction.CD_EJECT, drive.authority, TargetKind.VBD, drive.uuid) }
    }
    if (view.isoBusy) Text("Reading ISO libraries…", style = MaterialTheme.typography.bodySmall)
}
