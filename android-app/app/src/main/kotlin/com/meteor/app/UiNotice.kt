package com.meteor.app

enum class NoticeKind(val title: String) { INFO("Notice"), WARNING("Attention needed"), ERROR("Could not complete") }

data class UiNotice(val text: String, val kind: NoticeKind) {
    companion object {
        fun info(text: String) = UiNotice(text, NoticeKind.INFO)
        fun warning(text: String) = UiNotice(text, NoticeKind.WARNING)
        fun error(text: String) = UiNotice(text, NoticeKind.ERROR)
    }
}

// --- offline and reconnect ---
/** Persistent connection status shown under the header. It is derived from state, so it cannot go stale or be dismissed away. */
object ConnectionStatus {
    const val OFFLINE = "No network connection. Meteor will reconnect when the network returns."

    fun banner(state: MeteorState): UiNotice? = when {
        state.offline -> UiNotice.info(OFFLINE)
        state.selectedProfile == null || state.loading -> null
        else -> when (state.retryStop) {
            com.meteor.app.data.RetryStop.AUTHENTICATION -> UiNotice.error(
                "The server rejected the saved username or password. Automatic reconnecting is paused. Edit the connection in Connections to enter the new password, or tap Refresh to try again.")
            com.meteor.app.data.RetryStop.CERTIFICATE -> UiNotice.error(
                "The server's certificate changed or is not valid. Automatic reconnecting is paused. Review it in Connections, or tap Refresh to try again.")
            com.meteor.app.data.RetryStop.POOL_MEMBER -> UiNotice.warning(
                "This address is a pool member, not the pool master. Automatic reconnecting is paused. Use the master's address, or tap Refresh to try again.")
            null -> null
        }
    }
}
// --- end offline and reconnect ---
