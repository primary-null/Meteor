package com.meteor.app.data

data class VmTopology(val origin: String, val disks: List<String>, val networks: List<String>)

/** Only display topology is reused. Power, permissions, boot and task state are always read anew. */
object DetailPolling {
    const val TOPOLOGY_MAX_AGE = 60_000L
    fun canReuseTopology(previous: VmDetails, authority: String, uuid: String, topology: VmTopology,
        power: String, boot: String?, operations: Boolean, now: Long): Boolean =
        previous.authority == authority && previous.uuid == uuid && previous.topology == topology &&
            previous.power == power && previous.bootTime == boot && !previous.currentOperations && !operations &&
            now - previous.topologyObservedAt in 0 until TOPOLOGY_MAX_AGE
}
