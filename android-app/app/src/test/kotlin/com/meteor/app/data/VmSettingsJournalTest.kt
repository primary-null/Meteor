package com.meteor.app.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/**
 * The settings record in journal version 1: frozen records load with every field, only optional keys are added, older
 * fixtures still load, and a record a build cannot interpret stays locked and can be closed only by manual review.
 */
class VmSettingsJournalTest {
    private val profile = B2Fixtures.profile
    private fun fixture(name: String): List<Map<String, Any?>> =
        TestJson.objects(requireNotNull(javaClass.classLoader!!.getResource(name)).readText(Charsets.UTF_8))
    private val legacyKeys = setOf("id", "profile", "origin", "authority", "vm", "name", "action", "beforeBoot", "phase",
        "createdAt", "updatedAt", "taskRef", "taskUuid", "message")
    /** Every `action` name a 1.10 build interprets (its PowerAction and TargetAction entries, frozen here). */
    private val names110 = setOf("START", "SHUTDOWN", "REBOOT", "HARD_SHUTDOWN", "HARD_REBOOT", "SUSPEND", "RESUME", "PAUSE", "UNPAUSE",
        "START_ON", "POOL_MIGRATE", "UNRECOGNIZED", "TARGET", "SNAPSHOT_TAKE", "SNAPSHOT_REVERT", "SNAPSHOT_DELETE", "CD_INSERT", "CD_EJECT",
        "HOST_DISABLE", "HOST_EVACUATE", "HOST_ENABLE", "HOST_REBOOT", "HOST_SHUTDOWN", "PBD_PLUG")

    private class CodecStore(initial: List<Map<String, Any?>>) : OperationStore {
        val rows = linkedMapOf<String, Map<String, Any?>>().apply { initial.forEach { put(it["id"] as String, it) } }
        override suspend fun load() = rows.values.map(JournalCodec::decode)
        override suspend fun put(operation: PowerOperation) { rows[operation.id] = JournalCodec.encode(operation) }
    }
    private class Backend : PowerBackend, PowerConnection {
        var sends = 0; var settingsReads = 0
        val vm = VmDetails("pool", "vm", "Web", "", "Running", null, 1, 1024, emptyList(), emptyList(), false, null, emptyList(),
            "20260101T10:00:00Z", emptySet(), false, 1000)
        override suspend fun <T> powerConnection(profile: Profile, action: suspend PowerConnection.() -> T) = action(this)
        override suspend fun details(authority: String, uuid: String) = vm
        override suspend fun submit(authority: String, uuid: String, action: PowerAction): String { sends++; return "ref" }
        override suspend fun submitSetting(authority: String, uuid: String, step: SettingStep) { sends++ }
        override suspend fun settings(authority: String, uuid: String): VmSettings? { settingsReads++; return VmSettingsFixtures.running("Web") }
        override suspend fun taskUuid(reference: String) = "t"
        override suspend fun taskReference(uuid: String) = "OpaqueRef:$uuid"
        override suspend fun observeTask(reference: String) = TaskObservation("success", 1.0)
    }

    @Test fun settingsRecordsLoadWithEveryFieldIntact() {
        val ops = fixture("journal-settings.json").map(JournalCodec::decode).associateBy { it.id }
        val ok = ops.getValue("settings-ok")
        assertEquals(PowerAction.SETTINGS, ok.action); assertNull(ok.foreign); assertFalse(ok.unresolved); assertNull(ok.targetAction)
        assertEquals(SettingStep(SettingKind.NAME, null, "Guest", "Web", SettingState.DONE, "Web", null, 1700000300100), ok.settings!![0])
        val uncertain = ops.getValue("settings-uncertain")
        assertTrue(uncertain.unresolved)
        assertEquals(listOf(SettingState.DONE, SettingState.UNKNOWN, SettingState.PENDING), uncertain.settings!!.map { it.state })
        assertEquals("prod", uncertain.settings!![0].argument)
        assertEquals("", uncertain.settings!![1].observed) // an empty description read back, not "nothing read"
        assertNull(uncertain.settings!![2].observed); assertNull(uncertain.settings!![2].sentAt)
        assertEquals("Web · Edit settings", OperationHistory.title(uncertain))
        assertEquals("INVALID_VALUE", ops.getValue("settings-refused").settings!!.single().code)
        val reviewed = ops.getValue("settings-reviewed")
        assertFalse(reviewed.unresolved); assertEquals("Closed · Outcome unverified", OperationHistory.status(reviewed))
    }

    @Test fun settingsRecordsReEncodeToTheSameKeysAndValues() {
        for (raw in fixture("journal-settings.json")) {
            val encoded = JournalCodec.encode(JournalCodec.decode(raw))
            assertEquals(raw, encoded)
            assertEquals(legacyKeys + "settings", encoded.keys - "manualResolution")
        }
    }

    @Suppress("UNCHECKED_CAST")
    @Test fun everyKindAndStateRoundTripsThroughJson() {
        for (kind in SettingKind.entries) for (state in SettingState.entries) {
            val step = SettingStep(kind, if (kind in SettingsRules.TAG_KINDS) "tag" else null, "a", "b", state,
                observed = if (state == SettingState.PENDING) null else "", code = "CODE".takeIf { state == SettingState.REJECTED }, sentAt = 5L.takeIf { state != SettingState.PENDING })
            val op = PowerOperation("o", "p", profile.origin(), "pool", "vm", "Guest", PowerAction.SETTINGS, null, PowerPhase.UNKNOWN, 1, 2,
                message = "m", settings = listOf(step))
            val encoded = JournalCodec.encode(op)
            assertEquals("VM_SETTINGS", encoded["action"])
            assertEquals(op, JournalCodec.decode(TestJson.parse(TestJson.render(encoded)) as Map<String, Any?>))
        }
    }

    @Test fun olderFixturesStillLoadUnchanged() {
        for (raw in fixture("journal-1.9.json")) {
            val decoded = JournalCodec.decode(raw)
            assertNull(decoded.settings); assertEquals(raw, JournalCodec.encode(decoded))
        }
        for (raw in fixture("journal-b2.json")) {
            val decoded = JournalCodec.decode(raw)
            assertNull(decoded.settings); assertEquals(raw, JournalCodec.encode(decoded))
        }
    }

    @Test fun theSettingsKindIsNeverANameAnOlderBuildInterprets() {
        assertFalse(JournalCodec.SETTINGS_ACTION in names110)
        assertEquals(names110, (PowerAction.entries.map { it.name } + TargetAction.entries.map { it.name }).toSet() - "SETTINGS")
        for (raw in fixture("journal-settings.json")) { assertEquals("VM_SETTINGS", raw["action"]); assertTrue((raw["vm"] as String).isNotBlank()) }
        // SETTINGS never reaches the file.
        for (raw in fixture("journal-settings.json")) assertNotEquals("SETTINGS", raw["action"])
    }

    /**
     * A 1.10 build reads a VM_SETTINGS record through the unchanged foreign path (unknown `action`). The same path runs
     * here for a settings record with an unknown part, so this is what a 1.10 build does: lock, never check or resend,
     * write back unchanged, and close only through the VM review, keeping the raw keys.
     */
    @Test fun aRecordThisBuildCannotInterpretStaysLockedAndClosesOnlyByVmReview() = runTest {
        val base = fixture("journal-settings.json").single { it["id"] == "settings-uncertain" }
        val variants = listOf(base + ("phase" to "HALF_SENT"), base - "settings", base + ("settings" to emptyList<Any>()), base + ("vm" to ""),
            base + ("settings" to listOf(mapOf("kind" to "VM_SET_AFFINITY", "before" to "a", "requested" to "b", "state" to "PENDING"))),
            base + ("settings" to listOf(mapOf("kind" to "NAME", "before" to "a", "requested" to "b", "state" to "MAYBE"))))
        for (raw in variants) {
            val decoded = JournalCodec.decode(raw)
            assertEquals("$raw", PowerAction.UNRECOGNIZED, decoded.action); assertNull(decoded.settings); assertNotNull(decoded.foreign)
            assertTrue(decoded.unresolved); assertEquals(JournalCodec.FOREIGN_MESSAGE, decoded.message)
            assertEquals(raw, JournalCodec.encode(decoded))
            val store = CodecStore(listOf(raw)); val backend = Backend()
            val service = PowerService(backend, store, { 2000 }, {}, 2)
            assertTrue(runCatching { service.reconcile(profile, decoded) }.exceptionOrNull() is IllegalArgumentException)
            assertEquals(0, backend.sends)
            if ((raw["vm"] as String).isNotBlank()) {
                val review = service.review(profile, store.load().single())
                assertEquals(ReviewSubject.VM, review.subject); assertNull(review.settings)
                assertEquals("VM_SETTINGS (not recognized by this version)", ReviewText.originalAction(review.operation))
                val closed = service.resolveAfterReview(profile, review, serverHistoryChecked = true)
                assertFalse(closed.unresolved)
                val written = store.rows.values.single()
                assertEquals(raw.keys + "manualResolution", written.keys)
                assertEquals(raw["settings"], written["settings"])
                assertFalse(JournalCodec.decode(written).unresolved) // stays closed after the next load
            }
            assertEquals(0, backend.sends)
        }
    }

    @Test fun malformedKnownSettingsRecordsFailTheWholeLoad() {
        val base = fixture("journal-settings.json").single { it["id"] == "settings-uncertain" }
        val step = mapOf("kind" to "NAME", "before" to "a", "requested" to "b", "state" to "PENDING")
        val broken = listOf(base + ("createdAt" to "soon"), base - "message",
            base + ("settings" to listOf(step - "before")), base + ("settings" to listOf(step + ("sentAt" to "noon"))),
            base + ("settings" to listOf(step + ("observed" to 5L))), base + ("settings" to listOf(mapOf("kind" to "TAG_ADD", "before" to "absent", "requested" to "present", "state" to "PENDING"))))
        for (raw in broken) assertTrue("$raw", runCatching { JournalCodec.decode(raw) }.isFailure)
    }

    @Test fun aCombinedJournalLocksEveryFramework() = runTest {
        val store = CodecStore(fixture("journal-1.9.json") + fixture("journal-b2.json") + fixture("journal-settings.json"))
        assertEquals(4, store.load().count { it.unresolved })
        val backend = Backend()
        val service = PowerService(backend, store, { 2000 }, {}, 2)
        val s = VmSettingsFixtures.running("Web")
        assertTrue(runCatching { service.executeSettings(profile, SettingsConfirmation(profile.id, profile.origin(), s, SettingsDraft.from(s).copy(name = "x"),
            SettingsRules.plan(s, SettingsDraft.from(s).copy(name = "x")))) }.isFailure)
        assertEquals(0, backend.sends)
    }
}
