package com.meteor.app

import com.meteor.app.data.*

/** A slow read must not replace a newer operation callback or an action already in progress. */
internal fun MeteorState.withJournal(operations: List<PowerOperation>, expectedRevision: Long? = null): MeteorState =
    if (expectedRevision != null && (historyRevision != expectedRevision || operationBusy)) this
    else copy(journalProblem = false, operation = OperationHistory.current(operations), historyRevision = historyRevision + 1,
        history = operations.sortedByDescending { it.updatedAt })

internal fun MeteorState.withOperation(next: PowerOperation): MeteorState =
    copy(operation = next, historyRevision = historyRevision + 1, history = OperationHistory.include(history, next))
