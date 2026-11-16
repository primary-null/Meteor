package com.meteor.app

import com.meteor.app.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Reads of the open VM's details, fenced by a request generation, the connection generation, the profile and the pool.
 *
 * Two kinds of read share this class, and they must not disable the same controls:
 *  - a **user-visible** read (opening the details, "Refresh details", submitting an action) sets
 *    [MeteorState.detailsBusy], which greys out controls and shows progress;
 *  - the **automatic** five-second refresh (and the refresh after an action or an event) sets only
 *    [MeteorState.detailsPolling]. It never disables a control, so a tap is never swallowed because a background read happened
 *    to be in flight.
 *
 * Letting a tap through during an automatic read is safe: every action starts a new user-visible read that bumps
 * [generation], so the poll's result is discarded, and every confirmation re-reads and re-validates the VM before it is
 * journaled (`PowerRules.confirm`, the target-host re-read). This class does not relax any of those checks.
 */
internal class DetailsController(
    private val scope: CoroutineScope,
    private val gateway: MeteorGateway,
    private val state: MutableStateFlow<MeteorState>,
    private val connectionGeneration: () -> Long,
    private val friendly: (Throwable) -> String,
    /** The open VM's power state, resident host or boot marker differs from the previous read: its read-only info is stale. */
    private val vmChanged: (authority: String, uuid: String) -> Unit = { _, _ -> },
) {
    private var generation = 0L

    /** A read in flight belongs to the connection that started it; a new connection makes every result stale. */
    fun invalidate() { generation++ }

    fun close() {
        generation++
        state.update { it.copy(selectedGuest = null, details = null, detailsBusy = false, detailsPolling = false, pendingPower = null,
            pendingHostChoice = null, hostChoiceBusy = false) }
    }

    /** The five-second refresh. Skips while anything user-visible, or an earlier automatic read, is in progress. */
    fun refresh() {
        val current = state.value
        if (current.pendingPower != null || current.detailsBusy || current.detailsPolling || current.loading || !current.connected) return
        val profile = current.selectedProfile ?: return
        val detail = current.details ?: return
        if (current.selectedGuest?.uuid == detail.uuid) load(profile, detail.authority, detail.uuid, automatic = true)
    }

    /**
     * Reads the VM. [automatic] marks the background refresh (only honoured for a plain refresh of details already held);
     * every other call is user-visible. With an [action] the result opens that action's confirmation.
     */
    fun load(profile: Profile, authority: String, uuid: String, action: PowerAction? = null, automatic: Boolean = false,
        target: HostChoice? = null) {
        val start = state.value
        // Stop watching: plain reads run while a sent operation is only observed; a load that opens a confirmation still waits.
        if (ControlGates.operationExclusive(start) || start.profileBusy || action != null && start.operationBusy) return
        val request = ++generation
        val connection = connectionGeneration()
        val previous = start.details
        val background = automatic && action == null && previous != null
        state.update {
            it.copy(detailsBusy = if (background) it.detailsBusy else true, detailsPolling = background, pendingPower = null,
                details = it.details?.takeIf { detail -> detail.uuid == uuid && detail.authority == authority })
        }
        scope.launch {
            runCatching {
                (if (background) gateway.pollDetails(profile, authority, uuid, requireNotNull(previous)) else gateway.details(profile, authority, uuid)).also {
                    require(it.uuid == uuid && it.authority == authority) { "VM identity changed." }
                }
            }.onSuccess { detail ->
                if (request != generation) return@onSuccess
                val now = state.value
                if (connection != connectionGeneration() || now.selectedProfile != profile || now.inventory?.authority != authority || !now.connected) {
                    close()
                    return@onSuccess
                }
                val held = now.details
                state.update {
                    it.copy(detailsBusy = false, detailsPolling = false, details = detail,
                        pendingPower = action?.takeIf { selected -> ready(detail, selected, target) }
                            ?.let { selected -> PowerConfirmation(profile.id, profile.origin(), detail, selected, targetHost = target) },
                        notice = if (action != null && !ready(detail, action, target)) UiNotice.warning("This action is no longer available. VM details were refreshed.") else it.notice)
                }
                if (VmInfoReload.markersChanged(held, detail)) vmChanged(authority, uuid)
            }.onFailure { error ->
                if (error is CancellationException) throw error
                if (request == generation) state.update {
                    it.copy(details = null, detailsBusy = false, detailsPolling = false, pendingPower = null, notice = UiNotice.error(friendly(error)))
                }
            }
        }
    }

    private fun ready(detail: VmDetails, action: PowerAction, target: HostChoice?) =
        PowerRules.eligible(detail, action) && PowerRules.targetProblem(detail, action, target) == null
}
