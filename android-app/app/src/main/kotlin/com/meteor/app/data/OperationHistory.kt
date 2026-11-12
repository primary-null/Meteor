package com.meteor.app.data

/** Read-only presentation never turns a manually closed unknown outcome into task success. */
object OperationHistory {
    fun current(operations: List<PowerOperation>) =
        operations.filter { it.unresolved }.maxByOrNull { it.updatedAt } ?: operations.maxByOrNull { it.updatedAt }

    /** How long a closed operation (verified, failed, or manually closed) stays pinned above the screens after its last update. */
    const val CARD_LINGER_MILLIS = 120_000L

    /**
     * Whether the shell shows the operation card for [operation]. An unresolved record (including an uncertain outcome
     * waiting for review) is always shown: it blocks new writes and needs attention. A closed record is shown only
     * briefly so a result can be seen, then it leaves the screens; the full list stays in Settings → Action history.
     * A record dated in the future (the phone clock was set back) is not pinned, since its age cannot be trusted.
     */
    fun cardVisible(operation: PowerOperation?, now: Long): Boolean = when {
        operation == null -> false
        operation.unresolved -> true
        else -> now - operation.updatedAt in 0..CARD_LINGER_MILLIS
    }

    fun include(operations: List<PowerOperation>, next: PowerOperation): List<PowerOperation> {
        val all = operations.filterNot { it.id == next.id } + next
        return (all.filter { it.unresolved } + all.filterNot { it.unresolved }.sortedByDescending { it.updatedAt }.take(50))
            .sortedByDescending { it.updatedAt }
    }

    // --- B2 storage/host ---
    /** "Subject · action" for the operation card and history. VM kinds keep their 1.9/A2 wording. */
    fun title(operation: PowerOperation): String {
        val action = operation.targetAction ?: return "${operation.vmName} · ${operation.action.title}"
        val target = operation.targetName.orEmpty()
        val subject = when (action.targetKind) {
            TargetKind.VM -> operation.vmName
            TargetKind.SNAPSHOT, TargetKind.VBD -> "${operation.vmName} · $target"
            TargetKind.HOST, TargetKind.PBD -> target.ifBlank { operation.vmName }
        }
        return "$subject · ${action.title}"
    }
    // --- end B2 storage/host ---
    fun status(operation: PowerOperation): String = if (operation.manualResolution != null) "Closed · Outcome unverified"
        // --- B2 storage/host ---
        else if (operation.targetAction != null && operation.phase == PowerPhase.VERIFYING) "Verifying server state"
        // --- end B2 storage/host ---
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
