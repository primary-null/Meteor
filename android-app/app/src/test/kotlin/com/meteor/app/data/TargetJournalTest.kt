package com.meteor.app.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/**
 * B2 storage/host journal format: frozen B2 records, unchanged 1.9/A2 records, optional keys only,
 * and records from other versions kept as locked foreign records.
 */
class TargetJournalTest {
    private val profile = B2Fixtures.profile
    private fun fixture(name: String): List<Map<String, Any?>> =
        TestJson.objects(requireNotNull(javaClass.classLoader!!.getResource(name)).readText(Charsets.UTF_8))
    private val legacyKeys = setOf("id", "profile", "origin", "authority", "vm", "name", "action", "beforeBoot", "phase",
        "createdAt", "updatedAt", "taskRef", "taskUuid", "message")
    private val b2Keys = setOf("targetKind", "target", "targetName", "argument", "argumentName", "marker", "steps")

    private class CodecStore(initial: List<Map<String, Any?>>) : OperationStore {
        val rows = linkedMapOf<String, Map<String, Any?>>().apply { initial.forEach { put(it["id"] as String, it) } }
        override suspend fun load() = rows.values.map(JournalCodec::decode)
        override suspend fun put(operation: PowerOperation) { rows[operation.id] = JournalCodec.encode(operation) }
    }
    private class Backend(var host: TargetState, var vm: VmDetails) : PowerBackend, PowerConnection {
        var sends = 0; var reads = 0
        override suspend fun <T> powerConnection(profile: Profile, action: suspend PowerConnection.() -> T) = action(this)
        override suspend fun details(authority: String, uuid: String): VmDetails { reads++; return vm }
        override suspend fun submit(authority: String, uuid: String, action: PowerAction): String { sends++; return "ref" }
        override suspend fun submitTarget(authority: String, action: TargetAction, target: String, argument: String?): String { sends++; return "ref" }
        override suspend fun submitStep(authority: String, step: OperationStep): String { sends++; return "ref" }
        override suspend fun target(authority: String, kind: TargetKind, uuid: String): TargetState { reads++; return host }
        override suspend fun absent(authority: String, step: OperationStep): Boolean { reads++; return false }
        override suspend fun taskUuid(reference: String) = "t3"
        override suspend fun taskReference(uuid: String) = "OpaqueRef:$uuid"
        override suspend fun observeTask(reference: String) = TaskObservation("success", 1.0)
    }
    private fun vm(boot: String = "20260101T10:00:00Z") = VmDetails("pool", "vm", "Guest", "", "Running", null, 1, 1024, emptyList(), emptyList(),
        false, null, emptyList(), boot, setOf("clean_reboot"), false, 1000)

    @Test fun b2JournalLoadsWithEveryFieldIntact() {
        val ops = fixture("journal-b2.json").map(JournalCodec::decode).associateBy { it.id }
        val take = ops.getValue("b2-take-ok")
        assertEquals(TargetAction.SNAPSHOT_TAKE, take.targetAction); assertEquals(PowerAction.TARGET, take.action); assertNull(take.foreign)
        assertEquals("vm", take.targetUuid); assertEquals("snap-new", take.argumentUuid); assertEquals("Before upgrade", take.argumentName)
        assertEquals(mapOf("snapshots" to "snap-1"), take.marker); assertFalse(take.unresolved)
        val delete = ops.getValue("b2-delete-unknown")
        assertEquals(listOf(OperationStep(StepKind.VDI_DESTROY, "vdi-a", "disk", StepState.DONE, "OpaqueRef:t1", "t1"),
            OperationStep(StepKind.VM_DESTROY, "snap-1", "Before upgrade", StepState.UNKNOWN, "OpaqueRef:t2", "t2")), delete.steps)
        assertTrue(delete.unresolved); assertEquals("Guest · Before upgrade · Delete snapshot", OperationHistory.title(delete))
        val reboot = ops.getValue("b2-reboot-tracking")
        assertEquals("", reboot.vmUuid); assertEquals("1700000000.5", reboot.beforeBoot); assertEquals(PowerPhase.TRACKING, reboot.phase)
        assertEquals("Host B · Reboot host", OperationHistory.title(reboot))
        assertEquals(TargetKind.PBD, ops.getValue("b2-plug-failed").targetAction?.targetKind)
        assertEquals("Shared NFS on Host B · Plug storage connection", OperationHistory.title(ops.getValue("b2-plug-failed")))
        val reviewed = ops.getValue("b2-evacuate-reviewed")
        assertFalse(reviewed.unresolved); assertEquals("Closed · Outcome unverified", OperationHistory.status(reviewed))
        assertEquals("Disabled · live · 0 VMs", reviewed.manualResolution?.power)
    }
    @Test fun b2RecordsReEncodeToTheSameKeysAndValues() {
        for (raw in fixture("journal-b2.json")) {
            val encoded = JournalCodec.encode(JournalCodec.decode(raw))
            assertEquals(raw.keys, encoded.keys)
            assertTrue(legacyKeys.all { it in encoded.keys })
            assertTrue((encoded.keys - legacyKeys - "manualResolution").all { it in b2Keys })
            for (key in raw.keys) assertEquals(key, raw[key], encoded[key])
        }
    }
    @Test fun legacyRecordsAreUnchangedNextToB2Records() {
        for (raw in fixture("journal-1.9.json")) {
            val decoded = JournalCodec.decode(raw)
            assertNull(decoded.targetAction); assertNull(decoded.steps); assertNull(decoded.marker)
            assertEquals(raw, JournalCodec.encode(decoded))
        }
    }
    @Suppress("UNCHECKED_CAST")
    @Test fun everyKindRoundTripsThroughJsonWithOptionalKeysOnly() {
        for (action in TargetAction.entries) {
            val full = PowerOperation("o-$action", "p", profile.origin(), "pool", "vm", "Guest", PowerAction.TARGET, "1700000000.5",
                PowerPhase.TRACKING, 1, 2, "OpaqueRef:t", "t", "Waiting", targetAction = action, targetUuid = "target", targetName = "Target",
                argumentUuid = "arg", argumentName = "Arg name", marker = mapOf("master" to "false", "disks" to "a,b"),
                steps = listOf(OperationStep(StepKind.VDI_DESTROY, "vdi", "disk", StepState.TRACKING, "OpaqueRef:t", "t", "CODE")))
            val encoded = JournalCodec.encode(full)
            assertEquals(action.name, encoded["action"]); assertEquals(action.targetKind.name, encoded["targetKind"])
            assertEquals(legacyKeys + b2Keys, encoded.keys)
            assertEquals(full, JournalCodec.decode(TestJson.parse(TestJson.render(encoded)) as Map<String, Any?>))
            val minimal = PowerOperation("m-$action", "p", profile.origin(), "pool", "", "Subject", PowerAction.TARGET, null,
                PowerPhase.SUCCEEDED, 1, 2, targetAction = action, targetUuid = "target")
            assertEquals(legacyKeys + setOf("targetKind", "target"), JournalCodec.encode(minimal).keys)
            assertEquals(minimal, JournalCodec.decode(JournalCodec.encode(minimal)))
        }
    }
    @Test fun b2KindNamesAreNeverPowerActionNamesSoOlderReadersKeepThemForeign() {
        val vmKinds = PowerAction.entries.map { it.name }.toSet()
        for (action in TargetAction.entries) assertFalse("$action", action.name in vmKinds)
        assertEquals(TargetAction.entries.size, TargetAction.entries.map { it.name }.toSet().size)
        // An A2-era reader knows only requestable PowerAction names; every B2 record's action is outside that set.
        for (raw in fixture("journal-b2.json")) assertTrue(PowerAction.entries.filter { it.requestable }.none { it.name == raw["action"] })
        // TARGET never reaches the file.
        for (raw in fixture("journal-b2.json")) assertNotEquals("TARGET", raw["action"])
    }
    @Test fun recordsLackingB2IdentityOrWithUnknownPartsStayForeignAndLocked() = runTest {
        val base = fixture("journal-b2.json").single { it["id"] == "b2-reboot-tracking" }
        val variants = listOf(base - "targetKind", base - "target", base + ("target" to ""), base + ("targetKind" to "NETWORK"),
            base + ("targetKind" to "PBD"), base + ("phase" to "AWAITING_QUORUM"),
            fixture("journal-b2.json").single { it["id"] == "b2-delete-unknown" }.let { delete ->
                delete + ("steps" to listOf(mapOf("kind" to "SR_DESTROY", "uuid" to "sr", "name" to "x", "state" to "PENDING")))
            },
            fixture("journal-b2.json").single { it["id"] == "b2-delete-unknown" }.let { delete ->
                delete + ("steps" to listOf(mapOf("kind" to "VDI_DESTROY", "uuid" to "vdi", "name" to "x", "state" to "HALF_DONE")))
            })
        for (raw in variants) {
            val decoded = JournalCodec.decode(raw)
            assertEquals("$raw", PowerAction.UNRECOGNIZED, decoded.action); assertNull(decoded.targetAction)
            assertNotNull(decoded.foreign); assertTrue(decoded.unresolved); assertEquals(JournalCodec.FOREIGN_MESSAGE, decoded.message)
            assertEquals(raw, JournalCodec.encode(decoded))
            val store = CodecStore(listOf(raw)); val backend = Backend(B2Fixtures.host(), vm())
            val service = PowerService(backend, store, { 2000 }, {}, 2)
            assertTrue(runCatching { service.reconcile(profile, decoded) }.exceptionOrNull() is IllegalArgumentException)
            assertTrue(runCatching { service.executeTarget(profile, B2Fixtures.confirm(TargetAction.HOST_DISABLE, B2Fixtures.host())) }.isFailure)
            assertEquals(0, backend.sends); assertEquals(0, backend.reads)
            assertEquals(raw, store.rows.values.single())
        }
        // A terminal foreign record stays closed.
        assertFalse(JournalCodec.decode(base - "targetKind" + ("phase" to "SUCCEEDED")).unresolved)
    }
    @Test fun malformedB2RecordWithItsIdentityFailsTheWholeLoadLikeA2() {
        val base = fixture("journal-b2.json").single { it["id"] == "b2-delete-unknown" }
        val broken = listOf(base + ("createdAt" to "soon"), base - "message", base + ("marker" to mapOf("master" to 1L)), base + ("marker" to "x"),
            base + ("steps" to listOf(mapOf("kind" to "VDI_DESTROY", "uuid" to "", "name" to "x", "state" to "PENDING"))),
            base + ("steps" to listOf(mapOf("kind" to "VDI_DESTROY", "uuid" to "v", "state" to "PENDING"))))
        for (raw in broken) assertTrue("$raw", runCatching { JournalCodec.decode(raw) }.isFailure)
    }
    @Test fun aCombinedJournalKeepsLegacyBehaviorAndLocksAcrossFrameworks() = runTest {
        val store = CodecStore(fixture("journal-1.9.json") + fixture("journal-b2.json"))
        val backend = Backend(B2Fixtures.host(enabled = false), vm())
        val service = PowerService(backend, store, { 2000 }, {}, 2)
        assertEquals(3, store.load().count { it.unresolved })
        assertTrue(runCatching { service.execute(profile, PowerConfirmation(profile.id, profile.origin(), vm().copy(observedAt = 2000), PowerAction.REBOOT)) }.isFailure)
        assertTrue(runCatching { service.executeTarget(profile, B2Fixtures.confirm(TargetAction.HOST_DISABLE, B2Fixtures.host())) }.isFailure)
        // The 1.9 reboot reconciles exactly as before, through the VM path.
        backend.vm = vm(boot = "20260101T10:05:00Z")
        assertEquals(PowerPhase.SUCCEEDED, service.reconcile(profile, store.load().single { it.id == "op-reboot-tracking" }).phase)
        assertEquals(legacyKeys, store.rows.getValue("op-reboot-tracking").keys)
        // The B2 reboot reconciles through its host read and needs a changed boot marker.
        assertEquals(PowerPhase.UNKNOWN, service.reconcile(profile, store.load().single { it.id == "b2-reboot-tracking" }).phase)
        backend.host = B2Fixtures.host(enabled = false, boot = "1700000900.0")
        assertEquals(PowerPhase.SUCCEEDED, service.reconcile(profile, store.load().single { it.id == "b2-reboot-tracking" }).phase)
        assertEquals("HOST_REBOOT", store.rows.getValue("b2-reboot-tracking")["action"])
        assertEquals(0, backend.sends)
        assertEquals(1, store.load().count { it.unresolved }) // the interrupted delete still waits for review
    }
}
