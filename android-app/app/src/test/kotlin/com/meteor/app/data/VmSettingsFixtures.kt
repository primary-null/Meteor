package com.meteor.app.data

/** Shared values for the VM settings tests. Memory is in bytes; [GIB] and [MIB] keep the numbers readable. */
object VmSettingsFixtures {
    const val MIB = 1024L * 1024L
    const val GIB = 1024L * MIB
    const val NOW = 1_700_000_000_000L
    val profile = Profile("p", "Lab", "https://xcp.example.test/", "root", "secret", "a".repeat(64))

    fun running(name: String = "Guest") = VmSettings("pool", "vm", name, "", listOf("web"), "Running",
        setOf("changing_VCPUs_live", "changing_dynamic_range", "clean_shutdown"), false, 4, 2, 2,
        512 * MIB, 1 * GIB, 1 * GIB, 2 * GIB, null, true, true, "BIOS", mapOf("order" to "cd"), false, "", 3 * GIB, true, true, NOW)

    fun halted(name: String = "Guest") = running(name).copy(power = "Halted", vcpusLive = null,
        allowed = setOf("changing_VCPUs", "changing_memory_limits", "changing_static_range", "changing_dynamic_range", "start"))

    fun draft(s: VmSettings) = SettingsDraft.from(s)

    fun confirm(s: VmSettings, d: SettingsDraft, typed: String? = null) =
        SettingsConfirmation(profile.id, profile.origin(), s, d, SettingsRules.plan(s, d), typed)

    /** The server-side effect of one setter, as XAPI documents it. */
    fun VmSettings.applied(step: SettingStep): VmSettings = when (step.kind) {
        SettingKind.NAME -> copy(name = step.requested)
        SettingKind.DESCRIPTION -> copy(description = step.requested)
        SettingKind.TAG_ADD -> copy(tags = (tags + step.argument!!).distinct())
        SettingKind.TAG_REMOVE -> copy(tags = tags - step.argument!!)
        SettingKind.VCPUS_MAX -> copy(vcpusMax = step.requested.toLong())
        SettingKind.VCPUS_AT_STARTUP -> copy(vcpusAtStartup = step.requested.toLong())
        SettingKind.VCPUS_LIVE -> copy(vcpusLive = step.requested.toLong(), vcpusAtStartup = step.requested.toLong())
        SettingKind.MEMORY_LIMITS -> step.requested.split(",").map { it.toLong() }.let { copy(memoryStaticMin = it[0], memoryStaticMax = it[1], memoryDynamicMin = it[2], memoryDynamicMax = it[3]) }
        SettingKind.MEMORY_DYNAMIC -> step.requested.split(",").map { it.toLong() }.let { copy(memoryDynamicMin = it[0], memoryDynamicMax = it[1]) }
        SettingKind.AUTO_POWERON_ADD -> copy(autoPowerOn = step.requested.removePrefix("="))
        SettingKind.AUTO_POWERON_REMOVE -> copy(autoPowerOn = null)
        SettingKind.BOOT_PARAMS -> copy(bootParams = SettingsRules.parseMap(step.requested))
        SettingKind.HA_PRIORITY -> copy(haPriority = step.requested)
    }
}
