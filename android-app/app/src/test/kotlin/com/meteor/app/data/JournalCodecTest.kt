package com.meteor.app.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** A2 operations: journal format compatibility with 1.9 and handling of kinds this version does not know. */
class JournalCodecTest {
    private val profile = Profile("p", "Lab", "https://test.invalid/", "tester", "secret", "a".repeat(64))
    private fun fixture(): List<Map<String, Any?>> =
        TestJson.objects(requireNotNull(javaClass.classLoader!!.getResource("journal-1.9.json")).readText(Charsets.UTF_8))
    private val legacyKeys = setOf("id", "profile", "origin", "authority", "vm", "name", "action", "beforeBoot", "phase",
        "createdAt", "updatedAt", "taskRef", "taskUuid", "message")

    /** Simulates the file: every write is encoded and every read decoded, as OperationJournal does. */
    private class CodecStore(initial: List<Map<String, Any?>>) : OperationStore {
        val rows = linkedMapOf<String, Map<String, Any?>>().apply { initial.forEach { put(it["id"] as String, it) } }
        override suspend fun load() = rows.values.map(JournalCodec::decode)
        override suspend fun put(operation: PowerOperation) { rows[operation.id] = JournalCodec.encode(operation) }
    }
    private class Backend(var current: VmDetails) : PowerBackend, PowerConnection {
        var sends = 0; var reads = 0; var tasks = 0
        override suspend fun <T> powerConnection(profile: Profile, action: suspend PowerConnection.() -> T) = action(this)
        override suspend fun details(authority: String, uuid: String): VmDetails { reads++; return current }
        override suspend fun submit(authority: String, uuid: String, action: PowerAction): String { sends++; return "task-ref" }
        override suspend fun taskUuid(reference: String) = "task-uuid"
        override suspend fun taskReference(uuid: String): String { tasks++; return "OpaqueRef:task" }
        override suspend fun observeTask(reference: String): TaskObservation { tasks++; return TaskObservation("success", 1.0) }
    }
    private fun vm(power: String = "Running", boot: String? = "20260101T10:00:00Z") = VmDetails("pool", "vm", "Guest", "", power, null, 1, 1024,
        emptyList(), emptyList(), false, null, emptyList(), boot, setOf("start", "clean_shutdown", "clean_reboot", "hard_shutdown"), false, 1000)

    @Test fun journalWrittenBy19LoadsWithEveryFieldIntact() {
        val operations = fixture().map(JournalCodec::decode).associateBy { it.id }
        val tracking = operations.getValue("op-reboot-tracking")
        assertEquals(PowerAction.REBOOT, tracking.action); assertEquals(PowerPhase.TRACKING, tracking.phase)
        assertEquals("OpaqueRef:task", tracking.taskRef); assertEquals("task-uuid", tracking.taskUuid)
        assertEquals("20260101T10:00:00Z", tracking.beforeBoot); assertEquals(1700000000300, tracking.updatedAt)
        assertEquals("Waiting for server task…", tracking.message); assertTrue(tracking.unresolved)
        assertNull(tracking.foreign); assertNull(tracking.targetHostUuid)
        val failed = operations.getValue("op-shutdown-failed")
        assertEquals(PowerPhase.FAILED, failed.phase); assertNull(failed.taskRef); assertFalse(failed.unresolved)
        val start = operations.getValue("op-start-ok")
        assertNull(start.beforeBoot); assertFalse(start.unresolved)
        val reviewed = operations.getValue("op-start-reviewed")
        assertEquals(PowerPhase.UNKNOWN, reviewed.phase); assertFalse(reviewed.unresolved)
        assertEquals(ManualResolution(1670000000500, "b".repeat(64), "Running", "20251201T08:00:00Z"), reviewed.manualResolution)
        assertEquals("Closed · Outcome unverified", OperationHistory.status(reviewed))
        assertEquals(tracking, OperationHistory.current(operations.values.toList()))
    }

    @Test fun legacyRecordsReEncodeToTheSameKeysAndValues() {
        for (raw in fixture()) {
            val encoded = JournalCodec.encode(JournalCodec.decode(raw))
            assertEquals(raw.keys, encoded.keys)
            assertEquals(legacyKeys, encoded.keys - "manualResolution")
            for (key in raw.keys) assertEquals(key, raw[key], encoded[key])
        }
    }

    @Test fun unresolvedLegacyRecordStillBlocksAndReconcilesExactlyAsBefore() = runTest {
        val store = CodecStore(fixture()); val backend = Backend(vm())
        val service = PowerService(backend, store, { 2000 }, {}, 2)
        val confirmation = PowerConfirmation(profile.id, profile.origin(), vm().copy(observedAt = 2000), PowerAction.SHUTDOWN)
        assertTrue(runCatching { service.execute(profile, confirmation) }.isFailure)
        val pending = store.load().single { it.unresolved }
        // Same boot instant: a reboot is not proven, so it stays unresolved without resending.
        assertEquals(PowerPhase.UNKNOWN, service.reconcile(profile, pending).phase)
        backend.current = vm(boot = "20260101T10:05:00Z")
        val verified = service.reconcile(profile, store.load().single { it.unresolved })
        assertEquals(PowerPhase.SUCCEEDED, verified.phase)
        assertEquals("Reboot verified: boot timestamp changed.", verified.message)
        assertEquals(0, backend.sends)
        assertEquals(legacyKeys, store.rows.getValue("op-reboot-tracking").keys)
        assertFalse(store.load().single { it.id == "op-start-reviewed" }.unresolved)
    }

    @Suppress("UNCHECKED_CAST")
    @Test fun newKindsRoundTripWithOptionalTargetKeysOnly() {
        val op = PowerOperation("m", "p", profile.origin(), "pool", "vm", "Guest", PowerAction.POOL_MIGRATE, "20260101T10:00:00Z",
            PowerPhase.TRACKING, 1, 2, "ref", "uuid", "Waiting", targetHostUuid = "host-b", targetHostName = "Host B")
        val encoded = JournalCodec.encode(op)
        assertEquals(legacyKeys + setOf("targetHost", "targetHostName"), encoded.keys)
        assertEquals("POOL_MIGRATE", encoded["action"])
        assertEquals(op, JournalCodec.decode(TestJson.parse(TestJson.render(encoded)) as Map<String, Any?>))
        for (kind in PowerAction.entries.filter { it.requestable }) {
            val plain = op.copy(action = kind, targetHostUuid = null, targetHostName = null,
                manualResolution = null, phase = PowerPhase.SUCCEEDED)
            assertEquals(plain, JournalCodec.decode(JournalCodec.encode(plain)))
            assertEquals(legacyKeys, JournalCodec.encode(plain).keys)
        }
    }

    @Test fun malformedKnownRecordFailsTheWholeLoadLike19() {
        val raw = fixture().first().toMutableMap().apply { remove("message") }
        assertTrue(runCatching { JournalCodec.decode(raw) }.isFailure)
        val badNumber = fixture().first().toMutableMap().apply { put("createdAt", "soon") }
        assertTrue(runCatching { JournalCodec.decode(badNumber) }.isFailure)
    }

    @Test fun unknownKindIsKeptAsUnresolvedLockedRecordAndWrittenBackVerbatim() = runTest {
        val future = fixture().first().toMutableMap().apply {
            put("id", "future"); put("action", "SNAPSHOT_REVERT"); put("phase", "TRACKING"); put("snapshot", mapOf("uuid" to "s1"))
        }
        val decoded = JournalCodec.decode(future)
        assertEquals(PowerAction.UNRECOGNIZED, decoded.action)
        assertTrue(decoded.unresolved); assertEquals(PowerPhase.UNKNOWN, decoded.phase)
        assertEquals(JournalCodec.FOREIGN_MESSAGE, decoded.message)
        assertEquals(future, JournalCodec.encode(decoded))
        assertFalse(PowerRules.verified(decoded, vm()))

        val store = CodecStore(listOf(future)); val backend = Backend(vm())
        val service = PowerService(backend, store, { 2000 }, {}, 2)
        assertTrue(runCatching { service.execute(profile, PowerConfirmation(profile.id, profile.origin(), vm().copy(observedAt = 2000), PowerAction.SHUTDOWN)) }.isFailure)
        val refused = runCatching { service.reconcile(profile, decoded) }.exceptionOrNull()
        assertTrue(refused is IllegalArgumentException)
        assertEquals(0, backend.reads); assertEquals(0, backend.tasks); assertEquals(0, backend.sends)
        assertEquals(future, store.rows.getValue("future"))

        // Manual review is the only way to close it; the raw record survives with the closure added.
        val review = service.review(profile, store.load().single())
        val closed = service.resolveAfterReview(profile, review, true)
        assertFalse(closed.unresolved)
        val written = store.rows.getValue("future")
        assertEquals("SNAPSHOT_REVERT", written["action"]); assertEquals(mapOf("uuid" to "s1"), written["snapshot"])
        assertEquals("UNKNOWN", written["phase"]); assertTrue(written.containsKey("manualResolution"))
        assertFalse(store.load().single().unresolved)
        assertEquals(0, backend.sends)
    }

    @Test fun unknownPhaseLocksEvenAKnownKindAndForeignTerminalRecordsStayClosed() {
        val base = fixture().first()
        val oddPhase = JournalCodec.decode(base + mapOf("id" to "x", "phase" to "AWAITING_QUORUM"))
        assertEquals(PowerAction.REBOOT, oddPhase.action); assertNotNull(oddPhase.foreign); assertTrue(oddPhase.unresolved)
        val doneFuture = JournalCodec.decode(base + mapOf("id" to "y", "action" to "HOST_EVACUATE", "phase" to "SUCCEEDED"))
        assertFalse(doneFuture.unresolved); assertEquals(PowerAction.UNRECOGNIZED, doneFuture.action)
        val sparse = JournalCodec.decode(mapOf("id" to "z", "action" to "SOMETHING_NEW"))
        assertTrue(sparse.unresolved); assertEquals("", sparse.vmUuid)
        assertTrue(runCatching { JournalCodec.decode(mapOf("action" to "SOMETHING_NEW")) }.isFailure)
    }
}
