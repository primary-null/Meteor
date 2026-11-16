package com.meteor.app.data

/**
 * What tapping a VM row in the fleet does. The console is the primary action: a running VM opens its console directly, and the
 * details (performance, actions, settings, disks) are one tap away on the row's own Details button. Any other power state has no
 * console to open, and neither does a list shown while disconnected, so such a row opens the details as before (never a tap that does nothing).
 */
enum class FleetRowAction {
    CONSOLE, DETAILS;

    companion object {
        fun primary(guest: Guest, connected: Boolean = true): FleetRowAction = if (connected && guest.power == "Running") CONSOLE else DETAILS
    }
}

/**
 * Which VM's details to show again when a console is left. Opened from the details, that is the console's own VM. Opened from a
 * fleet row, it is whatever the detail pane was showing (so a tap beside an open pane neither blanks nor replaces it), or nothing
 * when no details were open, so Back lands on the plain fleet. Identity is (uuid, pool uuid).
 */
object ConsoleReturn {
    fun target(fromDetails: Boolean, opened: Guest, selected: Guest?): Pair<String, String>? =
        (if (fromDetails) opened else selected)?.let { it.uuid to it.poolUuid }
}
