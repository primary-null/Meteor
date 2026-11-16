package com.meteor.app

import com.meteor.app.data.OperationWatch

/**
 * When the VM action buttons and the host picker accept a tap. Kept apart from the composables so the rule can be tested.
 *
 * `detailsBusy` is user-visible loading only; the automatic five-second refresh sets `detailsPolling`, which is
 * deliberately absent here, so a background read never disables a control. Everything else that gates a write is
 * unchanged: connection, journal, a running or unresolved operation and an in-progress host read.
 */
internal object ControlGates {
    fun vmActionsReady(state: MeteorState): Boolean =
        state.connected && !state.loading && !state.profileBusy && !state.detailsBusy && !state.journalProblem &&
            !state.operationBusy && !state.hostChoiceBusy && state.operation?.unresolved != true

    /** The picker stays open, with its buttons disabled, while a refresh runs, instead of dropping the tap. */
    fun hostPickerEnabled(state: MeteorState): Boolean =
        state.connected && !state.loading && !state.profileBusy && !state.operationBusy && !state.detailsBusy

    // --- stop watching ---
    /**
     * A send, a review or the first reads of a check is running. Reads, refresh, browsing and connection changes wait
     * only for this short window. Once the operation is sent and observed by its saved task UUID ([MeteorState.operationWatching])
     * they run alongside it: the tracker uses its own XAPI session and its own copy of the original profile, and only
     * reads. Every write gate keeps checking `operationBusy` and the unresolved record, so no second write can start.
     */
    fun operationExclusive(state: MeteorState): Boolean = state.operationBusy && !state.operationWatching

    /**
     * While the operation runs, its own connection keeps its identity: no endpoint, credential or certificate change and
     * no master follow. A rename, a reconnect with the same identity, or any other connection is unaffected.
     */
    fun identityHeld(state: MeteorState, profileId: String?): Boolean = state.operationBusy && profileId != null &&
        state.operation?.let { it.unresolved && it.profileId == profileId } == true

    /** "Stop watching" is offered once the task UUID is saved, so stopping is exactly an app restart for recovery. */
    fun canStopWatching(state: MeteorState): Boolean = state.operationBusy && state.operationWatching && !state.operationStopping &&
        state.operation?.let(OperationWatch::watchable) == true

    const val IDENTITY_HELD = "This connection is in use by the action being watched. Stop watching it first, or wait for the result."
    const val IDENTITY_HELD_EDIT = "While the action on this connection runs, only its name can change."
    // --- end stop watching ---
}
