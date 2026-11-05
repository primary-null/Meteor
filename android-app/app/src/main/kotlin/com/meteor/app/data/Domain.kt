package com.meteor.app.data

import java.net.URI

data class Profile(
    val id: String,
    val label: String,
    val endpoint: String,
    val username: String,
    val password: String,
    val certificateSha256: String,
) {
    override fun toString() = "Profile(id=$id, credentials=redacted)"
}

object Endpoints {
    fun normalize(input: String): String {
        val value = input.trim().let { if ("://" in it) it else "https://$it" }
        val uri = URI(value)
        require(uri.scheme.equals("https", ignoreCase = true)) { "Use an HTTPS server address." }
        require(!uri.host.isNullOrBlank() && uri.rawUserInfo == null) { "Enter a server address without credentials." }
        require(uri.rawQuery == null && uri.rawFragment == null && uri.path.orEmpty() in listOf("", "/")) {
            "Enter the server address only, without a path or query."
        }
        require(uri.port == -1 || uri.port in 1..65535) { "Invalid port." }
        return URI("https", null, uri.host.lowercase(), uri.port, "/", null, null).toASCIIString()
    }
}

data class Host(val uuid: String, val name: String, val online: Boolean, val totalBytes: Long?, val freeBytes: Long?)
data class Guest(
    val uuid: String, val name: String, val power: String, val poolUuid: String,
    val hostUuid: String?, val vcpus: Int, val memoryBytes: Long,
) {
    fun identity(authority: String) = "$authority:$uuid"
}
data class Inventory(
    val authority: String, val poolName: String, val hosts: List<Host>, val guests: List<Guest>,
    val storageTotal: Long, val storageUsed: Long, val observedAtMillis: Long,
)
data class VmGroup(val id: String, val name: String, val subtitle: String, val guests: List<Guest>)

object InventoryMapper {
    private fun Map<String, Any?>.text(key: String) = this[key]?.toString().orEmpty()
    private fun Map<String, Any?>.number(key: String) = this[key]?.toString()?.toLongOrNull()
    private fun Map<String, Any?>.flag(key: String) = this[key] == true || text(key) == "true" || text(key) == "1"

    fun normalize(
        pools: Map<String, Map<String, Any?>>,
        hosts: Map<String, Map<String, Any?>>,
        hostMetrics: Map<String, Map<String, Any?>>,
        vms: Map<String, Map<String, Any?>>,
        storage: Map<String, Map<String, Any?>>,
        nowMillis: Long,
    ): Inventory {
        require(pools.size == 1) { "Direct XAPI must identify exactly one pool." }
        val pool = pools.values.single()
        val authority = pool.text("uuid").also { require(it.isNotBlank()) { "Missing pool identity." } }
        val byRef = hosts.mapValues { (_, host) ->
            val metrics = hostMetrics[host.text("metrics")].orEmpty()
            Host(host.text("uuid").also { require(it.isNotBlank()) { "Missing host identity." } }, host.text("name_label").ifBlank { "Unnamed host" },
                metrics.flag("live"), metrics.number("memory_total"), metrics.number("memory_free"))
        }
        require(byRef.values.map { it.uuid }.distinct().size == byRef.size) { "Duplicate host identity." }
        val guests = vms.values.filterNot { it.flag("is_a_template") || it.flag("is_control_domain") || it.flag("is_a_snapshot") }
            .map { vm ->
                val state = vm.text("power_state").ifBlank { "Unknown" }
                Guest(vm.text("uuid").also { require(it.isNotBlank()) }, vm.text("name_label").ifBlank { "Unnamed VM" },
                    state, authority, if (state == "Halted") null else byRef[vm.text("resident_on")]?.uuid,
                    vm.number("VCPUs_max")?.toInt() ?: 0, vm.number("memory_static_max") ?: 0)
            }.sortedBy { it.name.lowercase() }
        require(guests.map { it.uuid }.distinct().size == guests.size) { "Duplicate VM identity." }
        val diskRepositories = storage.values.filter { it.text("content_type") != "iso" && (it.number("physical_size") ?: 0) > 0 }
        return Inventory(authority, pool.text("name_label").ifBlank { "XCP-ng pool" },
            byRef.values.sortedBy { it.name.lowercase() }, guests,
            diskRepositories.sumOf { it.number("physical_size") ?: 0 },
            diskRepositories.sumOf { it.number("physical_utilisation") ?: 0 }, nowMillis)
    }

    fun groups(inventory: Inventory): List<VmGroup> {
        val groups = inventory.hosts.map { host ->
            VmGroup("host:${host.uuid}", host.name, "HOST · ${if (host.online) "Online" else "Offline"}",
                inventory.guests.filter { it.hostUuid == host.uuid })
        }.toMutableList()
        inventory.guests.filter { it.power == "Halted" }.takeIf { it.isNotEmpty() }?.let {
            groups += VmGroup("unassigned", "Unassigned", "Halted VMs · no current host", it)
        }
        inventory.guests.filter { it.power != "Halted" && it.hostUuid == null }.takeIf { it.isNotEmpty() }?.let {
            groups += VmGroup("unknown", "Placement unknown", "Refresh to resolve current placement", it)
        }
        return groups
    }
}

