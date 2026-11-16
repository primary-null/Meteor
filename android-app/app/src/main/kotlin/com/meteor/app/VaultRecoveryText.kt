package com.meteor.app

import com.meteor.app.data.*

// --- vault recovery ---

/** What the person is shown when the saved connections cannot be opened. Plain words first; the cause goes in a smaller second line. */
data class VaultPanel(
    val title: String, val body: String, val detail: String,
    /** Set after a Try again that failed as well. */
    val stillFailing: Boolean,
    val retryEnabled: Boolean, val startOverShown: Boolean, val startOverEnabled: Boolean, val busy: Boolean,
)

data class VaultConfirmation(val title: String, val paragraphs: List<String>, val confirmLabel: String, val cancelLabel: String)

object VaultRecoveryText {
    const val START_OVER_FAILED = "Could not start over. Nothing was deleted. Try again."
    private const val TITLE = "Saved connections could not be opened"
    private const val BODY = "Meteor can't read the connections saved on this phone. Connections you add now work until you close Meteor, " +
        "but they aren't saved, so you would have to enter them again."

    private fun detail(failure: VaultFailure) = when (failure) {
        VaultFailure.KEY_UNAVAILABLE -> "Detail: the Android Keystore key that protects the saved connections is no longer available on this phone. " +
            "This can happen after restoring to a new phone or changing some screen-lock settings."
        VaultFailure.CORRUPT -> "Detail: the saved file is damaged, or the phone's key can no longer unlock it."
        VaultFailure.UNKNOWN_FORMAT -> "Detail: the saved file was written by a version of Meteor that this version cannot read."
        VaultFailure.TEMPORARY -> "Detail: reading the phone's storage or key failed. This is often temporary."
    }

    /** The Connections screen panel, or null when the saved connections opened. */
    fun panel(state: MeteorState): VaultPanel? {
        if (!state.vaultProblem) return null
        val recovery = state.vaultRecovery
        val failure = recovery.failure ?: VaultFailure.TEMPORARY
        val tail = when {
            VaultRecoveryRules.startOverOffered(state) -> "Try again first. If that does not help, you can start over with new saved connections."
            else -> "Try again. This is often temporary."
        }
        return VaultPanel(TITLE, "$BODY $tail", detail(failure), stillFailing = recovery.attempts >= 2 && !recovery.busy,
            retryEnabled = VaultRecoveryRules.canRetry(state), startOverShown = VaultRecoveryRules.startOverOffered(state),
            startOverEnabled = VaultRecoveryRules.canStartOver(state), busy = recovery.busy)
    }

    fun confirmation(state: MeteorState): VaultConfirmation {
        val paragraphs = mutableListOf(
            "The saved servers, passwords and approved certificates on this phone will be gone, and you will have to enter them again. " +
                "Nothing on any server is changed.",
            "The unreadable file is set aside in Meteor's private storage, not deleted. Background alert checks for the removed connections are turned off.")
        val pending = state.operation?.takeIf { it.unresolved }
        if (pending != null) paragraphs += "A power action is still pending (${OperationHistory.title(pending)}). Its record is kept and new actions stay blocked. " +
            "After you add the same server again, use Review pending action to close it."
        else if (state.journalProblem) paragraphs += "Operation history could not be read. It is not changed either, and a pending action may still be recorded."
        return VaultConfirmation("Start over with saved connections?", paragraphs, "Start over", "Cancel")
    }

    /** The one-time notice after Try again or Start over worked. */
    fun finished(startedOver: Boolean, merge: VaultMerge, sessionSaved: Boolean, pending: PowerOperation?): UiNotice {
        val lead = if (startedOver) "Started over. The old saved connections were set aside and can't be used."
            else "Saved connections are open again."
        val session = when {
            merge.added > 0 && sessionSaved -> " The ${connections(merge.added)} you added this time ${if (merge.added == 1) "was" else "were"} saved too."
            merge.added > 0 -> " The ${connections(merge.added)} you added this time could not be saved yet; they will be saved with the next connection change."
            else -> ""
        }
        val dropped = if (merge.skipped > 0) " ${connections(merge.skipped).replaceFirstChar { it.uppercase() }} you added this time " +
            "${if (merge.skipped == 1) "was" else "were"} not kept because ${if (merge.skipped == 1) "it duplicates" else "they duplicate"} a saved one." else ""
        val action = if (pending != null) " A power action is still pending: add the same server again, then use Review pending action." else ""
        val tail = if (startedOver && merge.added == 0) " Add your servers again." else ""
        return UiNotice.info(lead + session + dropped + tail + action)
    }

    private fun connections(count: Int) = if (count == 1) "connection" else "$count connections"

    /** The shell banner outside Connections, and the note that a pending action's own connection is no longer saved. */
    fun banners(state: MeteorState, onConnections: Boolean): List<UiNotice> = buildList {
        if (state.vaultProblem && !onConnections) add(UiNotice.warning("Saved connections could not be opened, and connections you add now are not saved. Open Connections to fix this."))
        pendingNote(state)?.let { add(it) }
    }

    /** Derived, so it cannot go stale: shown while a pending action's connection is not among the saved ones. */
    fun pendingNote(state: MeteorState): UiNotice? {
        val pending = state.operation?.takeIf { it.unresolved } ?: return null
        if (state.profiles.any { it.id == pending.profileId }) return null
        return UiNotice.warning("A power action is still pending, but the connection that sent it is not saved. " +
            "Add the same server again, then use Review pending action.")
    }
}
// --- end vault recovery ---
