package com.meteor.app.data

import org.junit.Assert.*
import org.junit.Test

class DetailInfoTest {
    // ---- VM ----

    private fun vmRecord(vararg changes: Pair<String, Any?>): Map<String, Any?> = mapOf(
        "uuid" to "vm-uuid", "name_description" to "  Primary web server ", "HVM_boot_policy" to "BIOS order",
        "HVM_boot_params" to mapOf("order" to "cdn", "firmware" to "uefi"),
        "platform" to mapOf("secureboot" to "true", "device-model" to "qemu-upstream-uefi"),
        "VTPMs" to listOf("OpaqueRef:vtpm"), "ha_restart_priority" to "restart", "other_config" to mapOf("auto_poweron" to "true"),
        "snapshots" to listOf("s1", "s2", "s3"), "memory_dynamic_min" to "1073741824", "memory_dynamic_max" to "2147483648",
        "memory_static_min" to "536870912", "memory_static_max" to "4294967296", "VCPUs_max" to "4", "VCPUs_at_startup" to "2",
        "tags" to listOf("web", "prod", " prod ", "", "Z")).plus(changes)
    private val pool = mapOf<String, Any?>("uuid" to "pool", "other_config" to mapOf("auto_poweron" to "true"))
    private fun mapVm(vm: Map<String, Any?> = vmRecord(), snapshots: List<Map<String, Any?>> = emptyList(), drives: List<Map<String, Any?>> = emptyList(),
        iso: Map<String, String> = emptyMap(), pool: Map<String, Any?> = this.pool) = VmInfoMapper.map("pool", vm, pool, snapshots, drives, iso, 99)

    @Test fun firmwareComesFromBootParamsThenTheDeviceModelThenDefaultsToBios() {
        assertEquals("UEFI", VmInfoMapper.firmware(vmRecord()))
        assertEquals("BIOS", VmInfoMapper.firmware(vmRecord("HVM_boot_params" to mapOf("firmware" to "bios"))))
        assertEquals("BIOS", VmInfoMapper.firmware(vmRecord("HVM_boot_params" to mapOf("order" to "c"), "platform" to emptyMap<String, String>())))
        assertEquals("UEFI", VmInfoMapper.firmware(vmRecord("HVM_boot_params" to emptyMap<String, String>())))
        assertEquals("Unknown", VmInfoMapper.firmware(vmRecord("HVM_boot_params" to mapOf("firmware" to "coreboot"))))
        assertEquals("PV", VmInfoMapper.firmware(vmRecord("HVM_boot_policy" to "", "PV_bootloader" to "pygrub")))
        assertEquals("Unknown", VmInfoMapper.firmware(vmRecord("HVM_boot_policy" to "")))
    }
    @Test fun secureBootOnlyAppliesToUefiAndAbsentMeansOff() {
        assertEquals(SecureBoot.ON, VmInfoMapper.secureBoot(vmRecord()))
        assertEquals(SecureBoot.OFF, VmInfoMapper.secureBoot(vmRecord("platform" to mapOf("secureboot" to "false", "device-model" to "qemu-upstream-uefi"))))
        assertEquals(SecureBoot.OFF, VmInfoMapper.secureBoot(vmRecord("platform" to mapOf("device-model" to "qemu-upstream-uefi"))))
        assertEquals(SecureBoot.OTHER, VmInfoMapper.secureBoot(vmRecord("platform" to mapOf("secureboot" to "auto", "device-model" to "qemu-upstream-uefi"))))
        assertEquals(SecureBoot.NOT_UEFI, VmInfoMapper.secureBoot(vmRecord("HVM_boot_params" to mapOf("firmware" to "bios"), "platform" to mapOf("secureboot" to "true"))))
    }
    @Test fun vtpmIsPresentNoneOrNotReported() {
        assertEquals(true, mapVm().vtpm)
        assertEquals(false, mapVm(vmRecord("VTPMs" to emptyList<String>())).vtpm)
        assertNull(mapVm(vmRecord() - "VTPMs").vtpm)
    }
    @Test fun haPriorityAndAutoPowerOnIncludeTheMissingPoolFlag() {
        assertEquals(listOf("Not protected", "Restart", "Best effort", "Restart (priority 2)", "custom"),
            listOf("", "restart", "best-effort", "2", "custom").map(VmInfoMapper::haLabel))
        assertTrue(mapVm().autoPowerOn); assertEquals(true, mapVm().poolAutoPowerOn)
        val off = mapVm(pool = mapOf("other_config" to mapOf("auto_poweron" to "false")))
        assertTrue(off.autoPowerOn); assertEquals(false, off.poolAutoPowerOn)
        assertNull(mapVm(pool = mapOf("uuid" to "pool")).poolAutoPowerOn)
        assertFalse(mapVm(vmRecord("other_config" to mapOf("auto_poweron" to "false"))).autoPowerOn)
        assertFalse(mapVm(vmRecord("other_config" to emptyMap<String, String>())).autoPowerOn)
    }
    @Test fun snapshotsAreCountedFromTheVmAndListedNewestFirstWithoutNonSnapshots() {
        fun snapshot(uuid: String, name: String, time: String?, isSnapshot: Boolean = true) =
            mapOf("uuid" to uuid, "name_label" to name, "snapshot_time" to time, "is_a_snapshot" to isSnapshot)
        val info = mapVm(snapshots = listOf(snapshot("a", "old", "20260101T10:00:00Z"), snapshot("b", "new", "20260301T10:00:00Z"),
            snapshot("c", "untimed", null), snapshot("d", "not a snapshot", "20270101T00:00:00Z", isSnapshot = false), snapshot("", "no identity", "20260101T10:00:00Z")))
        assertEquals(3, info.snapshotCount)
        assertEquals(listOf("new", "old", "untimed"), info.snapshots.map { it.name })
        assertEquals(listOf(true, true, false), info.snapshots.map { it.createdAt != null })
        val many = mapVm(vmRecord("snapshots" to List(30) { "s$it" }), snapshots = List(30) { snapshot("u$it", "snap $it", "20260101T10:00:%02dZ".format(it)) })
        assertEquals(30, many.snapshotCount); assertEquals(VmInfoMapper.MAX_SNAPSHOTS, many.snapshots.size)
        assertEquals("snap 29", many.snapshots.first().name)
        assertEquals(0, mapVm(vmRecord("snapshots" to emptyList<String>())).snapshotCount)
    }
    @Test fun opticalDrivesReportEmptyOrTheLoadedDiscAndIgnoreDisks() {
        val drives = listOf(
            mapOf("type" to "CD", "userdevice" to "3", "empty" to true, "currently_attached" to true),
            mapOf("type" to "CD", "userdevice" to "2", "empty" to false, "VDI" to "vdi-iso", "currently_attached" to false),
            mapOf("type" to "CD", "userdevice" to "4", "empty" to false, "VDI" to "vdi-unknown", "currently_attached" to true),
            mapOf("type" to "Disk", "userdevice" to "0", "empty" to false, "VDI" to "vdi-disk", "currently_attached" to true))
        val cds = mapVm(drives = drives, iso = mapOf("vdi-iso" to " ubuntu-24.04.iso ")).cdDrives
        assertEquals(listOf(VmCdDrive("2", false, "ubuntu-24.04.iso", false), VmCdDrive("3", true, null, true), VmCdDrive("4", false, "Unnamed disc", true)), cds)
        assertTrue(mapVm().cdDrives.isEmpty())
    }
    @Test fun memoryVcpusDescriptionAndTagsAreNormalised() {
        val info = mapVm()
        assertEquals("Primary web server", info.description)
        assertEquals(listOf(1L shl 30, 2L shl 30, 512L shl 20, 4L shl 30), listOf(info.memoryDynamicMin, info.memoryDynamicMax, info.memoryStaticMin, info.memoryStaticMax))
        assertEquals(4, info.vcpusMax); assertEquals(2, info.vcpusAtStartup)
        assertEquals(listOf("Z", "prod", "web"), info.tags)
        assertEquals("pool", info.authority); assertEquals("vm-uuid", info.uuid); assertEquals(99L, info.observedAt)
        val sparse = mapVm(vmRecord("memory_dynamic_min" to "-1", "VCPUs_max" to "bad", "tags" to List(40) { "t$it" }))
        assertNull(sparse.memoryDynamicMin); assertNull(sparse.vcpusMax)
        assertEquals(VmInfoMapper.MAX_TAGS, sparse.tags.size)
        assertTrue(runCatching { mapVm(vmRecord("uuid" to "")) }.isFailure)
    }

    // ---- host ----

    private val host = mapOf<String, Any?>("uuid" to "host-uuid", "name_label" to "xcp-a", "address" to "192.0.2.10", "enabled" to true,
        "cpu_info" to mapOf("modelname" to "Intel(R)   Xeon(R) CPU E5-2680", "vendor" to "GenuineIntel", "cpu_count" to "16", "socket_count" to "2", "speed" to "2399.998"),
        "software_version" to mapOf("product_brand" to "XCP-ng", "product_version" to "8.3.0", "build_number" to "release/yangtze/master/58", "xapi" to "25.6.0", "xen" to "4.17.5-12"))
    private val pifs = listOf(
        mapOf("device" to "eth1", "MAC" to "aa:bb:cc:00:00:02", "IP" to "", "management" to false, "currently_attached" to true, "VLAN" to "-1", "physical" to true, "metrics" to "m1"),
        mapOf("device" to "eth0", "MAC" to "aa:bb:cc:00:00:01", "IP" to "192.0.2.10", "management" to true, "currently_attached" to true, "VLAN" to "-1", "physical" to true, "metrics" to "m0"),
        mapOf("device" to "eth0", "IP" to "198.51.100.5", "management" to false, "currently_attached" to false, "VLAN" to "100", "physical" to false, "metrics" to "OpaqueRef:NULL"),
        mapOf("device" to "bond0", "management" to false, "currently_attached" to true, "VLAN" to "-1", "bond_master_of" to listOf("bond-ref"), "metrics" to "missing"),
        mapOf("device" to "eth2", "management" to false, "currently_attached" to true, "VLAN" to "-1", "bond_slave_of" to "OpaqueRef:bond", "metrics" to "m2"))
    private val metrics = mapOf<String, Map<String, Any?>>(
        "m0" to mapOf("carrier" to true, "speed" to "10000", "duplex" to true),
        "m1" to mapOf("carrier" to false, "speed" to "0", "duplex" to false),
        "m2" to mapOf("carrier" to true, "speed" to "1000", "duplex" to false))
    private fun mapHost(host: Map<String, Any?> = this.host, pifs: List<Map<String, Any?>> = this.pifs, time: String? = "20231114T22:15:00Z",
        before: Long = 1_700_000_098_000, after: Long = 1_700_000_100_000) =
        HostInfoMapper.map("pool", host, true, pifs, metrics, time, before, after, 77)

    @Test fun hostHardwareSoftwareAndStateAreReportedAsRead() {
        val info = mapHost()
        assertEquals("Intel(R) Xeon(R) CPU E5-2680", info.cpuModel); assertEquals(16, info.cpuCount); assertEquals(2, info.socketCount)
        assertEquals("2400", info.cpuSpeedMhz)
        assertEquals(listOf("XCP-ng", "8.3.0", "release/yangtze/master/58", "25.6.0", "4.17.5-12"),
            listOf(info.productBrand, info.productVersion, info.buildNumber, info.xapiVersion, info.xenVersion))
        assertTrue(info.enabled); assertTrue(info.coordinator)
        assertFalse(mapHost(host + ("enabled" to false)).enabled)
        assertEquals("192.0.2.10", info.managementIp)
        assertEquals("xcp-a", info.name); assertEquals(77L, info.observedAt)
        val bare = mapHost(host - "cpu_info" - "software_version")
        assertEquals("", bare.cpuModel); assertNull(bare.cpuCount); assertNull(bare.productVersion)
        assertEquals("GenuineIntel", mapHost(host + ("cpu_info" to mapOf("vendor" to "GenuineIntel"))).cpuModel)
        assertTrue(runCatching { mapHost(host + ("uuid" to "")) }.isFailure)
    }
    @Test fun managementIpFallsBackToTheHostAddressWhenNoManagementPifHasOne() {
        assertEquals("192.0.2.10", mapHost(pifs = emptyList()).managementIp)
        assertEquals("192.0.2.10", mapHost(pifs = pifs.filter { it["management"] != true }).managementIp)
        assertNull(mapHost(host - "address", emptyList()).managementIp)
    }
    @Test fun nicsCarryCarrierSpeedDuplexVlanAndBondRoleWithManagementFirst() {
        val nics = mapHost().nics
        assertEquals(listOf("eth0", "bond0", "eth0", "eth1", "eth2"), nics.map { it.device })
        val management = nics.first()
        assertTrue(management.management); assertEquals(true, management.carrier); assertEquals(10_000L, management.speedMbit); assertEquals("Full", management.duplex)
        val eth1 = nics.single { it.device == "eth1" }
        assertEquals(false, eth1.carrier); assertNull("speed zero is unknown", eth1.speedMbit); assertNull("duplex means nothing without a link speed", eth1.duplex)
        val vlan = nics.single { it.vlan == 100L }
        assertNull("no metrics means unknown, not link down", vlan.carrier); assertFalse(vlan.attached); assertEquals("198.51.100.5", vlan.ip)
        assertEquals(BondRole.MASTER, nics.single { it.device == "bond0" }.bond); assertNull(nics.single { it.device == "bond0" }.carrier)
        val member = nics.single { it.device == "eth2" }
        assertEquals(BondRole.MEMBER, member.bond); assertEquals("Half", member.duplex)
        assertEquals(BondRole.NONE, management.bond); assertNull(management.vlan)
        assertEquals(HostInfoMapper.MAX_NICS, mapHost(pifs = List(40) { pifs[1] + ("device" to "n$it") }).nics.size)
    }
    @Test fun clockSkewIsMeasuredAgainstTheMidpointOfTheRead() {
        val info = mapHost()
        assertEquals(1_700_000_100_000L, info.serverTimeMillis)
        assertEquals(1_000L, info.clockSkewMillis)
        assertEquals(-4_000L, mapHost(before = 1_700_000_100_000, after = 1_700_000_108_000).clockSkewMillis)
        assertNull(mapHost(time = null).clockSkewMillis); assertNull(mapHost(time = "not a time").serverTimeMillis)
    }

    // ---- networks ----

    private val hosts = mapOf<String, Map<String, Any?>>("h1" to mapOf("name_label" to "Host A"), "h2" to mapOf("name_label" to "Host B"))
    @Test fun networksListBridgeMtuVifsAndPerHostInterfacesWithVlanAndBond() {
        val pifRecords = mapOf<String, Map<String, Any?>>(
            "p1" to mapOf("host" to "h2", "device" to "eth0", "VLAN" to "-1", "currently_attached" to true, "metrics" to "m0", "IP" to "192.0.2.11"),
            "p2" to mapOf("host" to "h1", "device" to "eth0", "VLAN" to "-1", "currently_attached" to true, "metrics" to "m1"),
            "p3" to mapOf("host" to "h1", "device" to "eth0", "VLAN" to "100", "currently_attached" to true, "metrics" to "m0"),
            "p4" to mapOf("host" to "h9", "device" to "bond0", "VLAN" to "-1", "currently_attached" to false, "bond_master_of" to listOf("b")))
        val networkRecords = mapOf<String, Map<String, Any?>>(
            "n1" to mapOf("uuid" to "net-1", "name_label" to "Pool-wide network", "bridge" to "xenbr0", "MTU" to "1500", "PIFs" to listOf("p1", "p2", "missing"), "VIFs" to listOf("v1", "v2", "v3")),
            "n2" to mapOf("uuid" to "net-2", "name_label" to "Host internal management network", "bridge" to "xenapi", "PIFs" to emptyList<String>(), "VIFs" to emptyList<String>()),
            "n3" to mapOf("uuid" to "net-3", "name_label" to "VLAN 100", "bridge" to "xapi1", "MTU" to "9000", "PIFs" to listOf("p3", "p4"), "VIFs" to listOf("v4")))
        val overview = NetworkMapper.map("pool", hosts, networkRecords, pifRecords, metrics, 5)
        assertEquals(listOf("Host internal management network", "Pool-wide network", "VLAN 100"), overview.networks.map { it.name })
        assertEquals(3, overview.total); assertEquals(5L, overview.observedAt)
        val internal = overview.networks[0]
        assertTrue(internal.internal); assertNull(internal.mtu); assertEquals(0, internal.vifCount)
        val pool = overview.networks[1]
        assertFalse(pool.internal); assertEquals("xenbr0", pool.bridge); assertEquals(1500L, pool.mtu); assertEquals(3, pool.vifCount)
        assertEquals(listOf("Host A", "Host B"), pool.pifs.map { it.hostName })
        assertEquals(listOf(false, true), pool.pifs.map { it.carrier })
        assertEquals("192.0.2.11", pool.pifs[1].ip)
        val vlan = overview.networks[2]
        assertEquals(9000L, vlan.mtu)
        assertEquals(listOf("Host A" to 100L, "Unknown host" to null), vlan.pifs.map { it.hostName to it.vlan })
        assertEquals(BondRole.MASTER, vlan.pifs[1].bond); assertFalse(vlan.pifs[1].attached)
    }
    @Test fun networksAreBoundedAndAmbiguousIdentityIsRejected() {
        val many = (0 until 130).associate { "n$it" to mapOf<String, Any?>("uuid" to "u$it", "name_label" to "net %03d".format(it)) }
        val overview = NetworkMapper.map("pool", hosts, many, emptyMap(), emptyMap(), 1)
        assertEquals(130, overview.total); assertEquals(NetworkMapper.MAX_NETWORKS, overview.networks.size)
        assertTrue(runCatching { NetworkMapper.map("pool", hosts, mapOf("a" to mapOf("uuid" to "x"), "b" to mapOf("uuid" to "x")), emptyMap(), emptyMap(), 1) }.isFailure)
        assertTrue(runCatching { NetworkMapper.map("pool", hosts, mapOf("a" to mapOf("name_label" to "no uuid")), emptyMap(), emptyMap(), 1) }.isFailure)
    }

    // ---- storage ----

    private val srRecord = mapOf<String, Any?>("uuid" to "sr-uuid", "name_label" to "Shared NFS", "type" to "nfs", "content_type" to "user", "shared" to true,
        "physical_size" to "1000", "physical_utilisation" to "400", "virtual_allocation" to "1500")
    private val threeHosts = hosts + ("h3" to mapOf("name_label" to "Host C"))
    private fun mapSr(sr: Map<String, Any?> = srRecord, hosts: Map<String, Map<String, Any?>> = threeHosts, pbds: List<Map<String, Any?>> = emptyList(),
        vdis: List<Map<String, Any?>> = emptyList(), total: Int = vdis.size, vbds: Map<String, Map<String, Any?>> = emptyMap(), vms: Map<String, Map<String, Any?>> = emptyMap()) =
        StorageMapper.map("pool", sr, hosts, pbds, vdis, total, vbds, vms, 9)

    @Test fun sharedRepositoryFlagsDetachedAndMissingHostConnections() {
        val info = mapSr(pbds = listOf(mapOf("host" to "h1", "currently_attached" to true), mapOf("host" to "h2", "currently_attached" to false)))
        assertEquals(listOf(SrHostLink("Host A", true), SrHostLink("Host B", false), SrHostLink("Host C", null)), info.hostLinks)
        assertEquals(listOf("Not attached on Host B", "No connection (PBD) on Host C"), info.warnings)
        assertTrue(info.shared)
    }
    @Test fun localRepositoryOnlyListsItsOwnHostAndWarnsWhenThatPbdIsDetached() {
        val local = srRecord + ("shared" to false)
        val ok = mapSr(local, pbds = listOf(mapOf("host" to "h1", "currently_attached" to true)))
        assertEquals(listOf(SrHostLink("Host A", true)), ok.hostLinks); assertTrue(ok.warnings.isEmpty()); assertFalse(ok.shared)
        assertEquals(listOf("Not attached on Host A"), mapSr(local, pbds = listOf(mapOf("host" to "h1", "currently_attached" to false))).warnings)
        assertTrue(mapSr(local).hostLinks.isEmpty())
    }
    @Test fun overProvisioningComparesVirtualAllocationWithPhysicalSize() {
        val info = mapSr()
        assertEquals(1.5, info.overProvisioning!!, 1e-9); assertTrue(info.overProvisioned)
        assertEquals(400L, info.physicalUtilisation); assertEquals(1000L, info.physicalSize); assertEquals(1500L, info.virtualAllocation)
        val thick = mapSr(srRecord + ("virtual_allocation" to "600"))
        assertEquals(.6, thick.overProvisioning!!, 1e-9); assertFalse(thick.overProvisioned)
        assertNull(mapSr(srRecord + ("physical_size" to "-1")).overProvisioning)
        assertNull(mapSr(srRecord + ("physical_size" to "0")).overProvisioning)
        assertNull(mapSr(srRecord - "virtual_allocation").overProvisioning)
        assertFalse(mapSr(srRecord - "virtual_allocation").overProvisioned)
        assertTrue(runCatching { mapSr(srRecord + ("uuid" to "")) }.isFailure)
    }
    @Test fun disksShowKindSizesAndWhichVmsUseThemWithoutSnapshotOrTemplateOwners() {
        val vdis = listOf(
            mapOf("uuid" to "d1", "name_label" to "root", "virtual_size" to "20", "physical_utilisation" to "5", "managed" to true, "VBDs" to listOf("b1", "b2", "b3", "missing")),
            mapOf("uuid" to "d2", "name_label" to "base copy", "virtual_size" to "30", "physical_utilisation" to "-1", "managed" to false, "read_only" to true, "VBDs" to emptyList<String>()),
            mapOf("uuid" to "d3", "name_label" to "root (snapshot)", "virtual_size" to "20", "is_a_snapshot" to true, "managed" to true, "VBDs" to emptyList<String>()),
            mapOf("uuid" to "", "name_label" to "no identity"))
        val vbds = mapOf<String, Map<String, Any?>>("b1" to mapOf("VM" to "vm-a"), "b2" to mapOf("VM" to "vm-snap"), "b3" to mapOf("VM" to "vm-tmpl"))
        val vms = mapOf<String, Map<String, Any?>>("vm-a" to mapOf("name_label" to "web"), "vm-snap" to mapOf("name_label" to "web (old)", "is_a_snapshot" to true),
            "vm-tmpl" to mapOf("name_label" to "template", "is_a_template" to true))
        val info = mapSr(vdis = vdis, total = 75, vbds = vbds, vms = vms)
        assertEquals(listOf("base copy", "root", "root (snapshot)"), info.vdis.map { it.name })
        assertEquals(75, info.totalVdis)
        assertEquals(listOf(VdiKind.BASE_COPY, VdiKind.DISK, VdiKind.SNAPSHOT), info.vdis.map { it.kind })
        val base = info.vdis[0]
        assertNull("-1 means the server does not know", base.physicalUtilisation); assertEquals(30L, base.virtualSize); assertTrue(base.readOnly)
        assertEquals(listOf("web"), info.vdis[1].attachedVms); assertEquals(5L, info.vdis[1].physicalUtilisation)
        assertTrue(info.vdis[0].attachedVms.isEmpty() && info.vdis[2].attachedVms.isEmpty())
        assertFalse(info.vdis[1].readOnly)
    }
}
