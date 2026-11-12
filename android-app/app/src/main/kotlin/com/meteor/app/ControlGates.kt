package com.meteor.app

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
}
