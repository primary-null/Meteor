package com.meteor.app.data

/*
 * Read-only detail models and the pure mappers that build them from XAPI records. The gateway only
 * fetches records; identity checks, caps and interpretation live here so JVM tests need no transport.
 */

private fun Map<String, Any?>.recText(key: String) = this[key]?.toString().orEmpty()
private fun Map<String, Any?>.recLong(key: String) = recText(key).toLongOrNull()
private fun Map<String, Any?>.recSize(key: String) = recLong(key)?.takeIf { it >= 0 }
private fun Map<String, Any?>.recFlag(key: String) = this[key] == true || recText(key) == "true" || recText(key) == "1"
private fun Map<String, Any?>.recList(key: String) = (this[key] as? List<*>)?.filterIsInstance<String>().orEmpty()
private fun Map<String, Any?>.recMap(key: String) = (this[key] as? Map<*, *>)?.entries
    ?.mapNotNull { (k, v) -> (k as? String)?.let { it to v?.toString().orEmpty() } }?.toMap().orEmpty()
private fun String.liveRef() = takeIf { it.isNotBlank() && it != "OpaqueRef:NULL" }
private fun String.tidied(limit: Int) = trim().replace(Regex("\\s+"), " ").take(limit)

enum class SecureBoot { NOT_UEFI, ON, OFF, OTHER }
data class VmSnapshotInfo(val uuid: String, val name: String, val createdAt: Long?)
data class VmCdDrive(val device: String, val empty: Boolean, val isoName: String?, val attached: Boolean)

/** [vtpm] is null when the server does not report the `VTPMs` field (XCP-ng 8.2). */
data class VmInfo(
    val authority: String, val uuid: String, val description: String, val firmware: String, val secureBoot: SecureBoot,
    val vtpm: Boolean?, val haRestartPriority: String, val autoPowerOn: Boolean, val poolAutoPowerOn: Boolean?,
    val snapshotCount: Int, val snapshots: List<VmSnapshotInfo>, val cdDrives: List<VmCdDrive>,
    val memoryDynamicMin: Long?, val memoryDynamicMax: Long?, val memoryStaticMin: Long?, val memoryStaticMax: Long?,
    val vcpusMax: Int?, val vcpusAtStartup: Int?, val tags: List<String>, val observedAt: Long,
    // --- inventory scale --- true counts behind the display caps
    val cdDriveTotal: Int = cdDrives.size, val tagTotal: Int = tags.size,
)

object VmInfoMapper {
    const val MAX_SNAPSHOTS = 20
    const val MAX_TAGS = 20
    const val MAX_DRIVES = 8

    fun firmware(vm: Map<String, Any?>): String {
        if (vm.recText("HVM_boot_policy").isBlank()) {
            return if (vm.recText("PV_bootloader").isNotBlank() || vm.recText("PV_kernel").isNotBlank()) "PV" else "Unknown"
        }
        val declared = vm.recMap("HVM_boot_params")["firmware"]?.lowercase()
        return when {
            declared == "uefi" -> "UEFI"
            declared == "bios" -> "BIOS"
            !declared.isNullOrBlank() -> "Unknown"
            vm.recMap("platform")["device-model"] == "qemu-upstream-uefi" -> "UEFI"
            else -> "BIOS"
        }
    }
    fun secureBoot(vm: Map<String, Any?>): SecureBoot {
        if (firmware(vm) != "UEFI") return SecureBoot.NOT_UEFI
        return when (vm.recMap("platform")["secureboot"]?.lowercase()) {
            "true" -> SecureBoot.ON
            "false", null, "" -> SecureBoot.OFF
            else -> SecureBoot.OTHER
        }
    }
    fun haLabel(raw: String): String = when {
        raw.isBlank() -> "Not protected"
        raw == "restart" -> "Restart"
        raw == "best-effort" -> "Best effort"
        raw.matches(Regex("[0-9]{1,3}")) -> "Restart (priority $raw)"
        else -> raw.tidied(40)
    }

    /** [snapshots] and [drives] are already-read records; at most [MAX_SNAPSHOTS] and [MAX_DRIVES] are shown. */
    fun map(authority: String, vm: Map<String, Any?>, pool: Map<String, Any?>, snapshots: List<Map<String, Any?>>,
        drives: List<Map<String, Any?>>, isoNames: Map<String, String>, now: Long): VmInfo {
        val uuid = vm.recText("uuid").also { require(it.isNotBlank()) { "Missing VM identity." } }
        val snapshotInfo = snapshots.filter { it.recFlag("is_a_snapshot") && it.recText("uuid").isNotBlank() }.map {
            VmSnapshotInfo(it.recText("uuid"), it.recText("name_label").tidied(120).ifBlank { "Snapshot" }, ActivityMapper.time(it["snapshot_time"]))
        }.sortedWith(compareByDescending<VmSnapshotInfo> { it.createdAt ?: Long.MIN_VALUE }.thenBy { it.name }).take(MAX_SNAPSHOTS)
        val allCds = drives.filter { it.recText("type") == "CD" }
        val cds = allCds.map { vbd ->
            val empty = vbd.recFlag("empty")
            VmCdDrive(vbd.recText("userdevice").tidied(16).ifBlank { "?" }, empty,
                if (empty) null else isoNames[vbd.recText("VDI")]?.tidied(160)?.ifBlank { null } ?: "Unnamed disc", vbd.recFlag("currently_attached"))
        }.sortedBy { it.device }.take(MAX_DRIVES)
        val config = vm.recMap("other_config")
        val poolConfig = pool["other_config"] as? Map<*, *>
        val allTags = vm.recList("tags").map { it.tidied(64) }.filter { it.isNotBlank() }.distinct().sorted()
        return VmInfo(authority, uuid, vm.recText("name_description").trim().take(1000), firmware(vm), secureBoot(vm),
            (vm["VTPMs"] as? List<*>)?.isNotEmpty(), vm.recText("ha_restart_priority").trim(),
            config["auto_poweron"] == "true", poolConfig?.let { it["auto_poweron"]?.toString() == "true" },
            vm.recList("snapshots").size, snapshotInfo, cds,
            vm.recSize("memory_dynamic_min"), vm.recSize("memory_dynamic_max"), vm.recSize("memory_static_min"), vm.recSize("memory_static_max"),
            vm.recSize("VCPUs_max")?.toInt(), vm.recSize("VCPUs_at_startup")?.toInt(),
            allTags.take(MAX_TAGS), now, cdDriveTotal = allCds.size, tagTotal = allTags.size)
    }
}

enum class BondRole { NONE, MASTER, MEMBER }
data class HostNic(
    val device: String, val mac: String, val ip: String, val management: Boolean, val attached: Boolean,
    val carrier: Boolean?, val speedMbit: Long?, val duplex: String?, val vlan: Long?, val bond: BondRole, val physical: Boolean,
)
data class HostInfo(
    val authority: String, val uuid: String, val name: String, val address: String, val enabled: Boolean, val coordinator: Boolean,
    val cpuModel: String, val cpuCount: Int?, val socketCount: Int?, val cpuSpeedMhz: String?,
    val productBrand: String?, val productVersion: String?, val buildNumber: String?, val xapiVersion: String?, val xenVersion: String?,
    val managementIp: String?, val nics: List<HostNic>, val serverTimeMillis: Long?, val clockSkewMillis: Long?, val observedAt: Long,
    val nicTotal: Int = nics.size, // inventory scale: true count behind MAX_NICS
)

private object Pifs {
    fun role(pif: Map<String, Any?>) = when {
        pif.recList("bond_master_of").isNotEmpty() -> BondRole.MASTER
        pif.recText("bond_slave_of").liveRef() != null -> BondRole.MEMBER
        else -> BondRole.NONE
    }
    fun vlan(pif: Map<String, Any?>) = pif.recLong("VLAN")?.takeIf { it >= 0 }
    fun carrier(pif: Map<String, Any?>, metrics: Map<String, Map<String, Any?>>): Map<String, Any?>? =
        pif.recText("metrics").liveRef()?.let { metrics[it] }
}

object HostInfoMapper {
    const val MAX_NICS = 24

    /**
     * [pifs] must belong to the host. Skew is the server clock minus the phone clock at the midpoint of the read,
     * so request latency does not count as skew. The server reports whole seconds.
     */
    fun map(authority: String, host: Map<String, Any?>, coordinator: Boolean, pifs: List<Map<String, Any?>>,
        pifMetrics: Map<String, Map<String, Any?>>, serverTime: String?, phoneBefore: Long, phoneAfter: Long, now: Long): HostInfo {
        val uuid = host.recText("uuid").also { require(it.isNotBlank()) { "Missing host identity." } }
        val cpu = host.recMap("cpu_info")
        val software = host.recMap("software_version")
        val allNics = pifs.map { pif ->
            val metrics = Pifs.carrier(pif, pifMetrics)
            HostNic(pif.recText("device").tidied(32).ifBlank { "?" }, pif.recText("MAC").tidied(32), pif.recText("IP").tidied(64),
                pif.recFlag("management"), pif.recFlag("currently_attached"),
                metrics?.takeIf { it.containsKey("carrier") }?.recFlag("carrier"),
                metrics?.recLong("speed")?.takeIf { it > 0 },
                metrics?.takeIf { it.containsKey("duplex") && (it.recLong("speed") ?: 0) > 0 }?.let { if (it.recFlag("duplex")) "Full" else "Half" },
                Pifs.vlan(pif), Pifs.role(pif), pif.recFlag("physical"))
        }.sortedWith(compareBy<HostNic> { !it.management }.thenBy { it.device }.thenBy { it.vlan ?: -1 })
        val nics = allNics.take(MAX_NICS)
        val serverMillis = serverTime?.let { ActivityMapper.time(it) }
        val phoneMidpoint = phoneBefore + (phoneAfter - phoneBefore) / 2
        return HostInfo(authority, uuid, host.recText("name_label").tidied(120).ifBlank { "Unnamed host" }, host.recText("address").tidied(64),
            host.recFlag("enabled"), coordinator,
            (cpu["modelname"] ?: cpu["vendor"]).orEmpty().tidied(120), cpu["cpu_count"]?.toIntOrNull(), cpu["socket_count"]?.toIntOrNull(),
            cpu["speed"]?.toDoubleOrNull()?.takeIf { it > 0 }?.let { String.format(java.util.Locale.ROOT, "%.0f", it) },
            software["product_brand"]?.tidied(40)?.ifBlank { null }, software["product_version"]?.tidied(40)?.ifBlank { null },
            software["build_number"]?.tidied(40)?.ifBlank { null }, software["xapi"]?.tidied(40)?.ifBlank { null },
            software["xen"]?.tidied(40)?.ifBlank { null },
            nics.firstOrNull { it.management && it.ip.isNotBlank() }?.ip ?: host.recText("address").tidied(64).ifBlank { null },
            nics, serverMillis, serverMillis?.let { it - phoneMidpoint }, now, allNics.size)
    }
}

data class NetworkPif(val hostName: String, val device: String, val vlan: Long?, val bond: BondRole, val attached: Boolean,
    val carrier: Boolean?, val ip: String)
data class NetworkInfo(val uuid: String, val name: String, val description: String, val bridge: String, val mtu: Long?,
    val vifCount: Int, val pifs: List<NetworkPif>, val pifTotal: Int = pifs.size) { // inventory scale: pifTotal
    val internal get() = pifs.isEmpty()
}
data class NetworkOverview(val authority: String, val networks: List<NetworkInfo>, val total: Int, val observedAt: Long)

object NetworkMapper {
    const val MAX_NETWORKS = 100
    const val MAX_PIFS_PER_NETWORK = 32

    fun map(authority: String, hosts: Map<String, Map<String, Any?>>, networks: Map<String, Map<String, Any?>>,
        pifs: Map<String, Map<String, Any?>>, pifMetrics: Map<String, Map<String, Any?>>, now: Long): NetworkOverview {
        require(networks.size <= 5000 && pifs.size <= 5000) { "Too many network records." }
        val ids = networks.values.map { it.recText("uuid") }
        require(ids.all { it.isNotBlank() } && ids.distinct().size == ids.size) { "Ambiguous network identity." }
        val mapped = networks.values.map { network ->
            val allAttachments = network.recList("PIFs").mapNotNull { pifs[it] }.map { pif ->
                NetworkPif(hosts[pif.recText("host")]?.recText("name_label")?.tidied(120)?.ifBlank { null } ?: "Unknown host",
                    pif.recText("device").tidied(32).ifBlank { "?" }, Pifs.vlan(pif), Pifs.role(pif), pif.recFlag("currently_attached"),
                    Pifs.carrier(pif, pifMetrics)?.takeIf { it.containsKey("carrier") }?.recFlag("carrier"), pif.recText("IP").tidied(64))
            }.sortedWith(compareBy<NetworkPif> { it.hostName.lowercase() }.thenBy { it.device })
            NetworkInfo(network.recText("uuid"), network.recText("name_label").tidied(120).ifBlank { "Network" }, network.recText("name_description").tidied(300),
                network.recText("bridge").tidied(40), network.recSize("MTU"), network.recList("VIFs").size,
                allAttachments.take(MAX_PIFS_PER_NETWORK), allAttachments.size)
        }.sortedBy { it.name.lowercase() }
        return NetworkOverview(authority, mapped.take(MAX_NETWORKS), mapped.size, now)
    }
}

enum class VdiKind { DISK, SNAPSHOT, BASE_COPY }
data class VdiInfo(val uuid: String, val name: String, val virtualSize: Long?, val physicalUtilisation: Long?,
    val kind: VdiKind, val readOnly: Boolean, val attachedVms: List<String>, val attachedTotal: Int = attachedVms.size) // inventory scale
/** [attached] is null when the host has no PBD for a shared repository. */
data class SrHostLink(val hostName: String, val attached: Boolean?)
data class StorageInfo(
    val authority: String, val uuid: String, val name: String, val type: String, val contentType: String, val shared: Boolean,
    val physicalSize: Long?, val physicalUtilisation: Long?, val virtualAllocation: Long?, val overProvisioning: Double?,
    val hostLinks: List<SrHostLink>, val warnings: List<String>, val vdis: List<VdiInfo>, val totalVdis: Int, val observedAt: Long,
) {
    val overProvisioned get() = (overProvisioning ?: 0.0) > 1.0
}

object StorageMapper {
    const val MAX_VDIS = 30
    const val MAX_ATTACHED_NAMES = 8

    /**
     * [pbds] are the repository's own PBD records. [vdis] are at most [MAX_VDIS] of its disks; [totalVdis] is the true count.
     * [vbds] and [vms] resolve which VMs use each disk; snapshot and template VMs are not reported as users.
     */
    fun map(authority: String, sr: Map<String, Any?>, hosts: Map<String, Map<String, Any?>>, pbds: List<Map<String, Any?>>,
        vdis: List<Map<String, Any?>>, totalVdis: Int, vbds: Map<String, Map<String, Any?>>, vms: Map<String, Map<String, Any?>>, now: Long): StorageInfo {
        val uuid = sr.recText("uuid").also { require(it.isNotBlank()) { "Missing storage identity." } }
        val shared = sr.recFlag("shared")
        val size = sr.recSize("physical_size")
        val allocation = sr.recSize("virtual_allocation")
        val links = hosts.entries.sortedBy { it.value.recText("name_label").lowercase() }.mapNotNull { (hostRef, host) ->
            val pbd = pbds.firstOrNull { it.recText("host") == hostRef }
            if (pbd == null && !shared) null
            else SrHostLink(host.recText("name_label").tidied(120).ifBlank { "Unnamed host" }, pbd?.recFlag("currently_attached"))
        }
        val warnings = links.mapNotNull {
            when (it.attached) {
                false -> "Not attached on ${it.hostName}"
                null -> "No connection (PBD) on ${it.hostName}"
                true -> null
            }
        }
        val disks = vdis.filter { it.recText("uuid").isNotBlank() }.map { vdi ->
            val users = vdi.recList("VBDs").mapNotNull { vbds[it]?.recText("VM")?.let { ref -> vms[ref] } }
                .filterNot { it.recFlag("is_a_snapshot") || it.recFlag("is_a_template") }
                .map { it.recText("name_label").tidied(120).ifBlank { "Unnamed VM" } }.distinct().sorted()
            VdiInfo(vdi.recText("uuid"), vdi.recText("name_label").tidied(120).ifBlank { "Unnamed disk" }, vdi.recSize("virtual_size"),
                vdi.recSize("physical_utilisation"),
                when { vdi.recFlag("is_a_snapshot") -> VdiKind.SNAPSHOT; vdi.containsKey("managed") && !vdi.recFlag("managed") -> VdiKind.BASE_COPY; else -> VdiKind.DISK },
                vdi.recFlag("read_only"), users.take(MAX_ATTACHED_NAMES), users.size)
        }.sortedWith(compareByDescending<VdiInfo> { it.virtualSize ?: -1 }.thenBy { it.name.lowercase() })
        return StorageInfo(authority, uuid, sr.recText("name_label").tidied(120).ifBlank { "Storage repository" }, sr.recText("type").tidied(40),
            sr.recText("content_type").tidied(40), shared, size, sr.recSize("physical_utilisation"), allocation,
            if (size != null && size > 0 && allocation != null) allocation.toDouble() / size else null,
            links, warnings, disks, totalVdis, now)
    }
}
