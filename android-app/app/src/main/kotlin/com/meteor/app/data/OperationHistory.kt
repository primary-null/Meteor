package com.meteor.app.data

/** Read-only presentation never turns a manually closed unknown outcome into task success. */
object OperationHistory {
    fun current(operations: List<PowerOperation>) =
        operations.filter { it.unresolved }.maxByOrNull { it.updatedAt } ?: operations.maxByOrNull { it.updatedAt }

    fun include(operations: List<PowerOperation>, next: PowerOperation): List<PowerOperation> {
        val all = operations.filterNot { it.id == next.id } + next
        return (all.filter { it.unresolved } + all.filterNot { it.unresolved }.sortedByDescending { it.updatedAt }.take(50))
            .sortedByDescending { it.updatedAt }
    }

    fun status(operation: PowerOperation): String = if (operation.manualResolution != null) "Closed · Outcome unverified"
        else when (operation.phase) {
            PowerPhase.PREPARED -> "Preparing"
            PowerPhase.SUBMITTING -> "Sending once"
            PowerPhase.TRACKING -> "Waiting for task"
            PowerPhase.VERIFYING -> "Verifying VM state"
            PowerPhase.UNKNOWN -> "Outcome uncertain"
            PowerPhase.SUCCEEDED -> "Verified"
            PowerPhase.FAILED -> "Failed"
        }
}
