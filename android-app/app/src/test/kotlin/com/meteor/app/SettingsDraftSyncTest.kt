package com.meteor.app

import com.meteor.app.data.*
import com.meteor.app.data.VmSettingsFixtures.NOW
import com.meteor.app.data.VmSettingsFixtures.running
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

/**
 * D3 of the first phone run: after a save the editor must restart from the values read back from the server, with no
 * pending change, on every path; typing is kept across plain reloads. The rule is [DraftSync]; the ordering is the controller's.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsDraftSyncTest {
    private val profile = VmSettingsFixtures.profile
    private val old = running().copy(description = "Original long description")
    private val saved = old.copy(description = "Original long description + marker")

    private fun typed(description: String) = DraftSync.begin(old, 0).let { it.copy(draft = it.draft.copy(description = description)) }

    // ---- the rule ----

    @Test fun noDraftYetStartsFromTheRead() {
        val e = DraftSync.sync(null, old, 0)
        assertEquals(SettingsDraft.from(old), e.draft); assertEquals(e.draft, e.base)
    }

    @Test fun aLaterRoundRestartsFromTheReadAndDropsTypingOfTheSavedField() {
        val restarted = DraftSync.sync(typed(saved.description), saved, 1)
        assertEquals(SettingsDraft.from(saved), restarted.draft)
        assertEquals(1, restarted.round)
        assertTrue(SettingsRules.preview(saved, restarted.draft).first.isEmpty())
    }

    @Test fun aRestartAlsoDropsTypingInAnotherFieldThatWasNotSaved() {
        val half = DraftSync.begin(old, 0).let { it.copy(draft = it.draft.copy(name = "Half typed")) }
        assertEquals(old.name, DraftSync.sync(half, saved, 1).draft.name)
    }

    @Test fun aReloadInTheSameRoundKeepsTypingAndTakesNewServerValuesForUntouchedFields() {
        val half = DraftSync.begin(old, 0).let { it.copy(draft = it.draft.copy(name = "Half typed")) }
        val server = old.copy(description = "Changed by someone else")
        val kept = DraftSync.sync(half, server, 0)
        assertEquals("Half typed", kept.draft.name)
        assertEquals("Changed by someone else", kept.draft.description)
        assertEquals(SettingsDraft.from(server), kept.base)
    }

    @Test fun anIdenticalReadChangesNothingAndRotationRestoresTheSameState() {
        val e = DraftSync.begin(old, 2).let { it.copy(draft = it.draft.copy(description = "typing")) }
        assertSame(e, DraftSync.sync(e, old, 2))
        assertEquals(e, EditorDraft.fromSaved(e.toSaved()))
        assertEquals(e, DraftSync.sync(EditorDraft.fromSaved(e.toSaved()), old, 2))
        assertNull(EditorDraft.fromSaved(emptyList()))
    }

    @Test fun aTypedValueThatEqualsTheNewServerValueIsNoPendingChange() {
        val synced = DraftSync.sync(typed(saved.description), saved, 0)
        assertTrue(SettingsRules.preview(saved, synced.draft).first.isEmpty())
    }

    // ---- the controller's ordering ----

    private class Store : OperationStore {
        val records = linkedMapOf<String, PowerOperation>()
        override suspend fun load() = records.values.map { JournalCodec.decode(JournalCodec.encode(it)) }
        override suspend fun put(operation: PowerOperation) { records[operation.id] = operation }
    }

    private class Server(var state: VmSettings) : MeteorGateway, PowerConnection {
        var applyWrites = true
        var failWrite: Exception? = null
        /** Fails the editor's own read (the one that follows a save), not the service's read-back. */
        var failEditorRead = false
        override suspend fun inventory(profile: Profile): Inventory = error("unused")
        override suspend fun details(profile: Profile, authority: String, uuid: String): VmDetails = error("unused")
        override suspend fun vmSettings(profile: Profile, authority: String, uuid: String): VmSettings {
            if (failEditorRead) throw IOException("down")
            return state
        }
        override suspend fun <T> powerConnection(profile: Profile, action: suspend PowerConnection.() -> T): T = action(this)
        override suspend fun details(authority: String, uuid: String): VmDetails =
            VmDetails(authority, uuid, state.name, state.description, state.power, null, 1, 1, emptyList(), emptyList(), false, null, emptyList(),
                null, emptySet(), state.busy, NOW)
        override suspend fun submit(authority: String, uuid: String, action: PowerAction): String = error("no power sends")
        override suspend fun taskUuid(reference: String): String = error("no task")
        override suspend fun taskReference(uuid: String): String = error("no task")
        override suspend fun observeTask(reference: String): TaskObservation = error("no task")
        override suspend fun settings(authority: String, uuid: String): VmSettings? = state
        override suspend fun submitSetting(authority: String, uuid: String, step: SettingStep) {
            if (applyWrites) state = with(VmSettingsFixtures) { state.applied(step) }
            failWrite?.let { throw it }
        }
    }

    private class Rig(val server: Server, val state: MutableStateFlow<MeteorState>, val controller: VmSettingsController)

    private fun rig(scope: CoroutineScope): Rig {
        val store = Store(); val server = Server(old)
        val state = MutableStateFlow(MeteorState(profiles = listOf(profile), selectedId = profile.id, connected = true, journalProblem = false,
            inventory = Inventory("pool", "Pool", emptyList(), emptyList(), 0, 0, 1),
            vmSettings = VmSettingsView(profile.id, profile.origin(), settings = old)))
        val power = PowerService(server, store, { NOW }, {}, 2)
        val controller = VmSettingsController(scope, server, power, state, { 0L }, { it.message ?: "failed" }, { throw it }, {}, {}, OperationRuns(state) { })
        return Rig(server, state, controller)
    }

    /** What the editor shows, composed exactly as VmSettingsEditor does. */
    private fun shown(r: Rig, before: EditorDraft?): EditorDraft? = r.state.value.settingsView.let { v -> v.settings?.let { DraftSync.sync(before, it, v.generation) } }

    @Test fun afterAFullSaveTheEditorShowsTheSavedTextWithNoPendingChange() = runTest {
        val r = rig(backgroundScope); val typing = typed(saved.description)
        r.controller.request(typing.draft); runCurrent()
        assertNotNull(r.state.value.settingsView.pending)
        r.controller.confirm(null); runCurrent()
        val view = r.state.value.settingsView
        assertEquals(saved.description, view.settings!!.description)
        assertEquals(1, view.generation)
        val editor = shown(r, typing)!!
        assertEquals(saved.description, editor.draft.description)
        assertTrue(SettingsRules.preview(view.settings!!, editor.draft).first.isEmpty())
        assertFalse(r.state.value.operationBusy)
    }

    @Test fun whenTheReadBackFailsAfterASaveTheOldValuesAreDroppedAndALaterLoadStartsOver() = runTest {
        val r = rig(backgroundScope); val typing = typed(saved.description)
        r.controller.request(typing.draft); runCurrent()
        r.server.failEditorRead = true
        r.controller.confirm(null); runCurrent()
        val view = r.state.value.settingsView
        assertNull("no old read is shown against the new server values", view.settings)
        assertNotNull(view.problem); assertEquals(1, view.generation)
        assertNull(shown(r, typing))
        r.server.failEditorRead = false
        r.controller.load("pool", "vm"); runCurrent()
        val again = shown(r, typing)!!
        assertEquals(SettingsDraft.from(r.state.value.settingsView.settings!!), again.draft)
        assertEquals(saved.description, again.draft.description)
    }

    @Test fun anUncertainSaveAndACheckThatSettlesItBothRestartTheEditorFromTheServer() = runTest {
        val r = rig(backgroundScope)
        r.server.failWrite = IOException("reset") // applied on the server, reply lost
        val typing = typed(saved.description)
        r.controller.request(typing.draft); runCurrent()
        r.controller.confirm(null); runCurrent()
        val view = r.state.value.settingsView
        assertEquals(1, view.generation)
        val editor = shown(r, typing)!!
        assertEquals(SettingsDraft.from(view.settings!!), editor.draft)
        // Check status settling the record restarts it again.
        r.server.state = r.server.state.copy(description = "Settled elsewhere")
        r.controller.afterRecordClosed("pool", "vm"); runCurrent()
        val second = shown(r, editor)!!
        assertEquals(2, r.state.value.settingsView.generation)
        assertEquals("Settled elsewhere", second.draft.description)
        assertTrue(SettingsRules.preview(r.state.value.settingsView.settings!!, second.draft).first.isEmpty())
    }

    @Test fun aRefusedSaveRestartsFromTheUnchangedServerValues() = runTest {
        val r = rig(backgroundScope)
        r.server.applyWrites = false; r.server.failWrite = IllegalArgumentException("refused")
        val typing = typed(saved.description)
        r.controller.request(typing.draft); runCurrent()
        r.controller.confirm(null); runCurrent()
        val editor = shown(r, typing)!!
        assertEquals(old.description, editor.draft.description)
        assertTrue(SettingsRules.preview(r.state.value.settingsView.settings!!, editor.draft).first.isEmpty())
    }

    @Test fun anEditorForAnotherVmIsNotRestartedByARecordOfThisOne() = runTest {
        val r = rig(backgroundScope)
        r.controller.afterRecordClosed("pool", "other-vm"); runCurrent()
        assertEquals(0, r.state.value.settingsView.generation)
    }
}
