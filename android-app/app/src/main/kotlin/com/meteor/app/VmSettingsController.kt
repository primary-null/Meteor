package com.meteor.app

import com.meteor.app.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * VM settings view state, bound to the connection identity it was read with. [generation] counts the saves and status
 * checks whose read-back has arrived: it changes together with [settings], so the editor restarts from the server's
 * values (see [DraftSync]) and never from the read that was shown before the save. Display-only: the service checks again.
 */
data class VmSettingsView(
    val profileId: String? = null, val origin: String? = null,
    val settings: VmSettings? = null, val busy: Boolean = false, val problem: String? = null,
    val pending: SettingsConfirmation? = null, val requestBusy: Boolean = false, val generation: Int = 0,
) {
    fun matches(profile: Profile?) = profile != null && profileId == profile.id && origin == profile.origin()
    fun bind(profile: Profile): VmSettingsView = if (matches(profile)) this else VmSettingsView(profileId = profile.id, origin = profile.origin(), generation = generation)
}

/** The settings view of the selected connection, or an empty one when it was read with another connection. */
val MeteorState.settingsView: VmSettingsView get() = vmSettings.takeIf { it.matches(selectedProfile) } ?: VmSettingsView()

/** When the editor may send. Plain so the rule is tested on the JVM; the first blocking reason is shown. */
internal object SettingsGates {
    fun blocker(s: MeteorState): String? = when {
        !s.connected -> "Reconnect before saving changes."
        s.journalProblem -> "Operation history must be readable before a change can be sent."
        s.operation?.unresolved == true -> "A pending operation must be reconciled before another change."
        s.operationBusy -> "Wait for the running action to finish."
        s.loading || s.profileBusy || s.pendingTrust != null -> "Wait for the connection to finish."
        s.detailsBusy || s.settingsView.requestBusy -> "Reading the VM…"
        else -> null
    }
    fun canWrite(s: MeteorState) = blocker(s) == null
}

/**
 * Reads VM settings and sends confirmed edits. Reads are fenced by connection generation, profile and pool; the write
 * goes only through [PowerService.executeSettings] and shares the journal, mutex and pending-action lock.
 */
internal class VmSettingsController(
    private val scope: CoroutineScope, private val gateway: MeteorGateway, private val power: PowerService,
    private val state: MutableStateFlow<MeteorState>, private val generation: () -> Long,
    private val friendly: (Throwable) -> String, private val recover: suspend (Throwable) -> Unit,
    private val refresh: () -> Unit, private val refreshDetails: () -> Unit,
    private val runs: OperationRuns, private val clock: () -> Long = System::currentTimeMillis,
) {
    private var request = 0L
    private fun view(change: (VmSettingsView) -> VmSettingsView) = state.update { it.copy(vmSettings = change(it.vmSettings)) }
    private fun current(profile: Profile, authority: String, connection: Long) = state.value.let {
        it.connected && it.selectedProfile == profile && it.inventory?.authority == authority && generation() == connection
    }

    /**
     * Reads the settings of one VM. With [restart] (after a save or a status check closed a record) the read-back starts
     * the editor over: the generation moves in the same update that stores the read, and when no read can be stored the
     * old values are dropped instead, so an old draft can never be shown against, or sent over, newer server values.
     */
    fun load(authority: String, uuid: String, restart: Boolean = false) {
        val s = state.value
        val profile = s.selectedProfile ?: return
        if (!s.connected || s.loading || s.profileBusy || s.inventory?.authority != authority) {
            if (restart) view { it.copy(settings = null, busy = false, generation = it.generation + 1) }
            return
        }
        val id = ++request
        val connection = generation()
        view { it.bind(profile).let { v -> v.copy(busy = true, problem = null, settings = v.settings?.takeIf { x -> x.uuid == uuid && x.authority == authority }) } }
        scope.launch {
            try {
                val read = gateway.vmSettings(profile, authority, uuid)
                if (id != request) return@launch
                if (!current(profile, authority, connection)) { view { it.copy(busy = false, settings = null, generation = if (restart) it.generation + 1 else it.generation) }; return@launch }
                view { it.copy(busy = false, settings = read, generation = if (restart) it.generation + 1 else it.generation) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (id == request) view { it.copy(busy = false, problem = if (error is IllegalArgumentException) error.message ?: friendly(error) else friendly(error),
                    settings = if (restart) null else it.settings, generation = if (restart) it.generation + 1 else it.generation) }
            }
        }
    }
    /** Leaving the editor drops its read and any confirmation for that VM. */
    fun close(uuid: String) {
        request++
        view { v -> v.copy(settings = v.settings?.takeUnless { it.uuid == uuid }, busy = false, problem = null,
            pending = v.pending?.takeUnless { it.settings.uuid == uuid }) }
    }

    /** Reads the VM again, plans the setter calls from the draft and opens the confirmation only when there is something valid to send. */
    fun request(draft: SettingsDraft) {
        val s = state.value
        val profile = s.selectedProfile ?: return
        val shown = s.settingsView.settings ?: return
        if (!SettingsGates.canWrite(s) || s.inventory?.authority != shown.authority) return
        val connection = generation()
        view { it.bind(profile).copy(requestBusy = true, pending = null) }
        scope.launch {
            try {
                val fresh = gateway.vmSettings(profile, shown.authority, shown.uuid)
                if (!current(profile, shown.authority, connection)) return@launch
                view { it.copy(settings = fresh) }
                if (fresh.fence != shown.fence) { notice(UiNotice.warning("The VM changed on the server. Check the values and review again.")); return@launch }
                val problems = SettingsRules.problems(fresh, draft)
                if (problems.isNotEmpty()) { notice(UiNotice.warning(problems.values.first())); return@launch }
                val steps = SettingsRules.plan(fresh, draft)
                if (steps.isEmpty()) { notice(UiNotice.warning("Nothing to change.")); return@launch }
                view { it.copy(pending = SettingsConfirmation(profile.id, profile.origin(), fresh, draft, steps)) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                notice(UiNotice.error(if (error is IllegalArgumentException) error.message ?: friendly(error) else friendly(error)))
            } finally { view { it.copy(requestBusy = false) } }
        }
    }
    fun cancel() = view { it.copy(pending = null) }

    fun confirm(typedName: String?) {
        val s = state.value
        val pending = s.settingsView.pending ?: return
        val profile = s.selectedProfile ?: return
        if (!SettingsGates.canWrite(s)) return
        val confirmation = pending.copy(typedName = typedName)
        // A typed confirmation that does not match never reaches the service.
        if (SettingsRules.needsTypedName(confirmation.settings, confirmation.steps) && typedName != SettingsRules.confirmationPhrase(confirmation.settings)) return
        state.update { it.copy(operationBusy = true, vmSettings = it.vmSettings.copy(pending = null)) }
        scope.launch {
            try { runs.run { changed -> power.executeSettings(profile, confirmation, changed) } }
            catch (error: Exception) {
                if (error is CancellationException) throw error
                recover(error)
            } finally {
                state.update { it.copy(operationBusy = false) }
                // The fleet list, the details and the editor show the server's values without a manual refresh.
                refresh(); refreshDetails()
                afterRecordClosed(confirmation.settings.authority, confirmation.settings.uuid)
            }
        }
    }

    /**
     * A save finished, or Check status settled a settings record, for this VM. If the editor shows that VM it reads it
     * again and restarts from the result on every outcome: saved, partly saved, refused or uncertain.
     */
    fun afterRecordClosed(authority: String, uuid: String) {
        val shown = state.value.settingsView.settings
        if (shown != null && shown.uuid == uuid && shown.authority == authority) load(authority, uuid, restart = true)
    }
    private fun notice(text: UiNotice) = state.update { it.copy(notice = text) }
}
