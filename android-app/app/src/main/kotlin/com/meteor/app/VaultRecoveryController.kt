package com.meteor.app

import com.meteor.app.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// --- vault recovery ---

/** What the app knows about an unreadable vault. Meaningful while [MeteorState.vaultProblem] is set. */
data class VaultRecovery(
    val failure: VaultFailure? = null,
    /** Loads that failed so far: 1 after the start, plus one per failed Try again. */
    val attempts: Int = 0,
    /** Try again or Start over is running. */
    val busy: Boolean = false,
)

object VaultRecoveryRules {
    /** The shared gate: nothing else is changing profiles, connecting, reviewing a certificate or running an action. */
    fun ready(state: MeteorState) = !state.operationBusy && !state.profileBusy && !state.loading && state.pendingTrust == null &&
        !state.vaultRecovery.busy

    fun canRetry(state: MeteorState) = state.vaultProblem && ready(state)

    /** Start over is offered only once the failure is known to be permanent, or Try again has failed too. */
    fun startOverOffered(state: MeteorState) =
        state.vaultProblem && VaultFailures.canStartOver(state.vaultRecovery.failure ?: VaultFailure.TEMPORARY, state.vaultRecovery.attempts)

    fun canStartOver(state: MeteorState) = startOverOffered(state) && ready(state)
}

/**
 * Try again and Start over for an unreadable profile vault. No Android UI or Keystore dependency: the storage, the profile-write
 * lock and the steps that follow a successful open are injected.
 *
 * Connections added while the vault was unreadable stay in the list and are saved once the vault works again. Neither action
 * reads or writes the operation journal: a pending action, and the write lock it implies, are exactly as they were.
 */
internal class VaultRecoveryController(
    private val scope: CoroutineScope,
    private val vault: ProfileStorage,
    private val mutable: MutableStateFlow<MeteorState>,
    /** Serializes with every other profile write (the connection controller's lock). */
    private val writes: suspend (suspend () -> Unit) -> Unit,
    /** The gate for starting an action, in addition to [VaultRecoveryRules]. */
    private val allowed: () -> Boolean,
    /** Opens the storage the way the app does at start. */
    private val open: suspend () -> ProfileDocument,
    /**
     * Removes what other stores keep for profiles that are no longer saved (background alert settings and marks, in-app "seen"
     * values) and re-syncs the schedule. Best effort: a failure leaves harmless entries that the next profile change prunes again.
     */
    private val retain: suspend (Set<String>) -> Unit,
    /** Called once the vault works again. [connect] is the saved selection when nothing is connected yet. */
    private val opened: (connect: Profile?) -> Unit,
) {
    /** The first load failed. The app keeps running with connections that are not saved. */
    fun loadFailed(error: Throwable) {
        mutable.update { it.copy(vaultProblem = true, vaultRecovery = VaultRecovery(VaultFailures.classify(error), attempts = 1)) }
    }

    fun retry() {
        if (!VaultRecoveryRules.canRetry(mutable.value) || !allowed()) return
        begin()
        scope.launch {
            var done: Opened? = null
            try {
                writes {
                    val stored = try { open() } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        mutable.update { it.copy(vaultRecovery = it.vaultRecovery.copy(
                            failure = VaultFailures.classify(error), attempts = it.vaultRecovery.attempts + 1)) }
                        return@writes
                    }
                    done = finish(stored, startedOver = false)
                }
            } finally { end() }
            done?.let { opened(it.connect) } // after the busy flags are cleared, so a pending-action recheck is not refused
        }
    }

    /** The caller has shown the confirmation. */
    fun startOver() {
        if (!VaultRecoveryRules.canStartOver(mutable.value) || !allowed()) return
        begin()
        scope.launch {
            var done: Opened? = null
            try {
                writes {
                    try { vault.startOver() } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        // Setting the file aside failed first, or a later step did: either way no saved data was deleted.
                        mutable.update { it.copy(notice = UiNotice.error(VaultRecoveryText.START_OVER_FAILED)) }
                        return@writes
                    }
                    done = finish(ProfileDocument(), startedOver = true)
                }
            } finally { end() }
            done?.let { opened(it.connect) }
        }
    }

    /** Joins the connections added meanwhile to [stored], saves them, and returns the app to normal saving. */
    private class Opened(val connect: Profile?)

    private suspend fun finish(stored: ProfileDocument, startedOver: Boolean): Opened {
        val before = mutable.value
        val merge = VaultMerge.merge(stored, before.profiles, before.selectedId)
        val sessionSaved = merge.added == 0 || try { vault.save(merge.document); true } catch (error: Exception) {
            if (error is CancellationException) throw error
            false // They stay in the list and are saved with the next connection change.
        }
        val connect = merge.document.profiles.firstOrNull { it.id == merge.document.selectedId }
            ?.takeIf { before.selectedId == null && !before.connected && !before.loading }
        val pending = before.operation?.takeIf { it.unresolved }
        mutable.update { it.copy(profiles = merge.document.profiles, selectedId = merge.document.selectedId, vaultProblem = false,
            vaultRecovery = VaultRecovery(), notice = VaultRecoveryText.finished(startedOver, merge, sessionSaved, pending)) }
        try { retain(merge.document.profiles.map { it.id }.toSet()) } catch (error: Exception) { if (error is CancellationException) throw error }
        return Opened(connect)
    }

    private fun begin() = mutable.update { it.copy(profileBusy = true, vaultRecovery = it.vaultRecovery.copy(busy = true)) }
    private fun end() = mutable.update { it.copy(profileBusy = false, vaultRecovery = it.vaultRecovery.copy(busy = false)) }
}
// --- end vault recovery ---
