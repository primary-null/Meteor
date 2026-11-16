package com.meteor.app.data

/**
 * Journaled operation hardening: what the manual-review dialog says for each [ReviewSubject].
 * Every variant keeps the outcome unknown: closing never reports success and never resends.
 */
object ReviewText {
    /** The recorded kind as shown to the user. A foreign record shows its raw kind name, shortened. */
    fun originalAction(operation: PowerOperation): String {
        if (operation.foreign == null) return operation.action.title
        val raw = (operation.foreign["action"] as? String)?.filter { it.isLetterOrDigit() || it == '_' }?.take(64).orEmpty()
        return if (raw.isBlank()) "Not recognized by this version" else "$raw (not recognized by this version)"
    }

    fun currentState(review: PowerReview): String = if (review.settings != null && review.subject == ReviewSubject.VM)
        SettingsText.reviewState(review.operation, review.settings) else when (review.subject) { // --- VM settings ---
        ReviewSubject.VM -> review.details?.power.orEmpty()
        ReviewSubject.VM_MISSING -> "The VM no longer exists on this pool"
        ReviewSubject.POOL_ONLY -> "Not read: only the pool identity was checked"
    }

    /** The explanation paragraphs, in order. */
    fun explanation(review: PowerReview): List<String> = if (review.settings != null && review.subject == ReviewSubject.VM)
        SettingsText.reviewExplanation() else when (review.subject) { // --- VM settings ---
        ReviewSubject.VM -> listOf(
            "This fresh VM read does not prove the old task finished. Check this VM's task history on the server and make sure the action is no longer pending before closing the record.",
            "The original action stays recorded with an unverified outcome. Closing this record sends no power command and permits new actions after a fresh confirmation.")
        ReviewSubject.VM_MISSING -> listOf(
            "Meteor reached the original pool, but the server reports that this VM no longer exists, for example because it was deleted from another tool. The original task outcome cannot be verified.",
            "Check the pool's task history and make sure the action is no longer pending. Closing records the outcome as unknown, not as success. Nothing is resent.")
        ReviewSubject.POOL_ONLY -> listOf(
            "This action was recorded by a different version of Meteor, and this version cannot interpret it. Meteor checked only that this connection reaches the original pool; it did not read the object the action was about.",
            "Check the pool's task history and make sure the action is no longer pending. Closing records the outcome as unknown, not as success. Nothing is resent, and the original record is kept.")
    }
}
