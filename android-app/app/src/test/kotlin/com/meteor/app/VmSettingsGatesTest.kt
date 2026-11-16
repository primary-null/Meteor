package com.meteor.app

import com.meteor.app.data.*
import org.junit.Assert.*
import org.junit.Test

/** When the settings editor may send, and the view's binding to one connection identity. */
class VmSettingsGatesTest {
    private val profile = VmSettingsFixtures.profile
    private val ready = MeteorState(profiles = listOf(profile), selectedId = profile.id, connected = true, journalProblem = false)
    private val pending = PowerOperation("o", profile.id, profile.origin(), "pool", "vm", "Guest", PowerAction.SETTINGS, null, PowerPhase.UNKNOWN, 1, 2,
        settings = listOf(SettingStep(SettingKind.NAME, null, "a", "b", SettingState.UNKNOWN)))

    @Test fun theFirstBlockingReasonIsShown() {
        assertNull(SettingsGates.blocker(ready)); assertTrue(SettingsGates.canWrite(ready))
        assertEquals("Reconnect before saving changes.", SettingsGates.blocker(ready.copy(connected = false)))
        assertEquals("Operation history must be readable before a change can be sent.", SettingsGates.blocker(ready.copy(journalProblem = true)))
        assertEquals("A pending operation must be reconciled before another change.", SettingsGates.blocker(ready.copy(operation = pending)))
        assertNull(SettingsGates.blocker(ready.copy(operation = pending.copy(phase = PowerPhase.SUCCEEDED))))
        assertEquals("Wait for the running action to finish.", SettingsGates.blocker(ready.copy(operationBusy = true)))
        assertEquals("Wait for the connection to finish.", SettingsGates.blocker(ready.copy(loading = true)))
        assertEquals("Reading the VM…", SettingsGates.blocker(ready.copy(detailsBusy = true)))
        assertEquals("Reading the VM…", SettingsGates.blocker(ready.copy(vmSettings = VmSettingsView(profile.id, profile.origin(), requestBusy = true))))
        // The automatic five-second detail refresh never disables the editor.
        assertNull(SettingsGates.blocker(ready.copy(detailsPolling = true)))
    }

    @Test fun aReadMadeWithAnotherConnectionIsNeverShown() {
        val view = VmSettingsView(profile.id, profile.origin(), settings = VmSettingsFixtures.running(), generation = 3)
        assertEquals(view, ready.copy(vmSettings = view).settingsView)
        val other = profile.copy(certificateSha256 = "b".repeat(64))
        val state = ready.copy(profiles = listOf(other), vmSettings = view)
        assertNull(state.settingsView.settings)
        val rebound = view.bind(other)
        assertNull(rebound.settings); assertNull(rebound.pending); assertEquals(3, rebound.generation)
    }
}
