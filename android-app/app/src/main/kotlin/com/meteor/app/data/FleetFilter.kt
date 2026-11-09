package com.meteor.app.data

enum class FleetFilter(val title: String) {
    ALL("All"), RUNNING("Running"), HALTED("Halted"), OTHER("Other states");

    fun matches(guest: Guest): Boolean = when (this) {
        ALL -> true
        RUNNING -> guest.power == "Running"
        HALTED -> guest.power == "Halted"
        OTHER -> guest.power !in setOf("Running", "Halted")
    }
}

/** Filter the current snapshot only; searching never fetches data or changes VM identity. */
fun Inventory.filterFleet(query: String, filter: FleetFilter): Inventory {
    val search = query.trim()
    val hostNames = hosts.associate { it.uuid to it.name }
    return copy(guests = guests.filter { guest ->
        filter.matches(guest) && (guest.name.contains(search, ignoreCase = true) ||
            hostNames[guest.hostUuid]?.contains(search, ignoreCase = true) == true)
    })
}
