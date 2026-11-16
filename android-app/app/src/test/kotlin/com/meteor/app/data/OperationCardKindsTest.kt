package com.meteor.app.data

import com.meteor.app.data.VmSettingsFixtures.NOW
import com.meteor.app.data.VmSettingsFixtures.applied
import com.meteor.app.data.VmSettingsFixtures.confirm
import com.meteor.app.data.VmSettingsFixtures.draft
import com.meteor.app.data.VmSettingsFixtures.profile
import com.meteor.app.data.VmSettingsFixtures.running
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/**
 * D5 of the first phone run: the closed-card rule (OperationHistory.cardVisible) must be the same for every record kind:
 * VM power, VM settings and storage/host records, in each closed form, and for the real records a settings run publishes.
 */
class OperationCardKindsTest {
    private val linger = OperationHistory.CARD_LINGER_MILLIS
    private val origin = "a".repeat(64)
    private fun power(phase: PowerPhase, at: Long, resolved: Boolean = false) =
        PowerOperation("p", "profile", origin, "pool", "vm", "VM", PowerAction.START, null, phase, 1, at,
            manualResolution = if (resolved) ManualResolution(2, origin, "Halted", null) else null)
    private fun settings(phase: PowerPhase, at: Long, state: SettingState) =
        PowerOperation("s", "profile", origin, "pool", "vm", "VM", PowerAction.SETTINGS, null, phase, 1, at,
            settings = listOf(SettingStep(SettingKind.NAME, null, "a", "b", state)))
    private fun target(action: TargetAction, phase: PowerPhase, at: Long) =
        PowerOperation("t", "profile", origin, "pool", "vm", "VM", PowerAction.TARGET, null, phase, 1, at, targetAction = action, targetUuid = "target")

    private fun closedRecords(at: Long): Map<String, PowerOperation> = buildMap {
        put("power succeeded", power(PowerPhase.SUCCEEDED, at)); put("power failed", power(PowerPhase.FAILED, at))
        put("power closed by review", power(PowerPhase.UNKNOWN, at, resolved = true))
        put("settings verified", settings(PowerPhase.SUCCEEDED, at, SettingState.DONE))
        put("settings partly saved", settings(PowerPhase.FAILED, at, SettingState.DONE))
        put("settings refused", settings(PowerPhase.FAILED, at, SettingState.REJECTED))
        put("settings closed by review", settings(PowerPhase.UNKNOWN, at, SettingState.UNKNOWN).copy(manualResolution = ManualResolution(2, origin, "Running", null)))
        TargetAction.entries.forEach { put("${it.name} succeeded", target(it, PowerPhase.SUCCEEDED, at)); put("${it.name} failed", target(it, PowerPhase.FAILED, at)) }
    }

    @Test fun everyClosedRecordKindAppearsForTheLingerAndThenLeavesTheScreens() {
        closedRecords(1_000_000).forEach { (name, record) ->
            assertFalse("$name is closed", record.unresolved)
            assertTrue("$name just now", OperationHistory.cardVisible(record, 1_000_000))
            assertTrue("$name at the limit", OperationHistory.cardVisible(record, 1_000_000 + linger))
            assertFalse("$name just past the limit", OperationHistory.cardVisible(record, 1_000_000 + linger + 1))
            assertFalse("$name five minutes later", OperationHistory.cardVisible(record, 1_000_000 + 5 * 60_000))
            assertFalse("$name dated in the future", OperationHistory.cardVisible(record, 999_999))
        }
    }

    @Test fun everyUnresolvedRecordKindStaysUntilItIsReconciled() {
        val open = listOf(power(PowerPhase.UNKNOWN, 1), settings(PowerPhase.UNKNOWN, 1, SettingState.UNKNOWN), settings(PowerPhase.VERIFYING, 1, SettingState.SUBMITTING),
            target(TargetAction.entries.first(), PowerPhase.UNKNOWN, 1))
        open.forEach { assertTrue(it.unresolved); assertTrue(OperationHistory.cardVisible(it, 10 * linger)); assertNull(OperationHistory.cardTimeLeft(it, 10 * linger)) }
    }

    @Test fun theWakeUpIsScheduledForTheExactEndOfTheLingerForEveryKind() {
        closedRecords(1_000_000).forEach { (name, record) ->
            assertEquals(name, linger + 1, OperationHistory.cardTimeLeft(record, 1_000_000))
            assertEquals(name, 1L, OperationHistory.cardTimeLeft(record, 1_000_000 + linger))
            assertNull("$name already gone", OperationHistory.cardTimeLeft(record, 1_000_000 + linger + 1))
            assertNull("$name future-dated", OperationHistory.cardTimeLeft(record, 999_999))
        }
        assertNull(OperationHistory.cardTimeLeft(null, 1))
    }

    // ---- the records a real settings run publishes ----

    private class Store : OperationStore {
        val records = linkedMapOf<String, PowerOperation>()
        override suspend fun load() = records.values.map { JournalCodec.decode(JournalCodec.encode(it)) }
        override suspend fun put(operation: PowerOperation) { records[operation.id] = operation }
    }

    private class Server(var state: VmSettings) : PowerBackend, PowerConnection {
        override suspend fun <T> powerConnection(profile: Profile, action: suspend PowerConnection.() -> T): T = action(this)
        override suspend fun details(authority: String, uuid: String): VmDetails =
            VmDetails(authority, uuid, state.name, state.description, state.power, null, 1, 1, emptyList(), emptyList(), false, null, emptyList(),
                null, emptySet(), state.busy, NOW)
        override suspend fun submit(authority: String, uuid: String, action: PowerAction): String = error("no power sends")
        override suspend fun taskUuid(reference: String): String = error("no task")
        override suspend fun taskReference(uuid: String): String = error("no task")
        override suspend fun observeTask(reference: String): TaskObservation = error("no task")
        override suspend fun settings(authority: String, uuid: String): VmSettings? = state
        override suspend fun submitSetting(authority: String, uuid: String, step: SettingStep) { state = state.applied(step) }
    }

    @Test fun aRealSettingsRunEndsWithAClosedRecordThatTheCardRuleRetiresAndNothingPublishesItAgain() = runTest {
        val clock = longArrayOf(NOW)
        val server = Server(running()); val store = Store(); val published = mutableListOf<PowerOperation>()
        val service = PowerService(server, store, { clock[0] += 10; clock[0] }, {}, 2)
        val d = draft(running()).copy(name = "Renamed", description = "A new description")
        val result = service.executeSettings(profile, confirm(running(), d)) { published += it }
        assertEquals(PowerPhase.SUCCEEDED, result.phase)
        assertEquals(result, published.last()); assertFalse(published.last().unresolved)
        assertTrue("every record before the last one is still open", published.dropLast(1).all { it.unresolved })
        assertTrue(OperationHistory.cardVisible(result, result.updatedAt + 60_000))
        assertFalse(OperationHistory.cardVisible(result, result.updatedAt + linger + 1))
        assertEquals(linger + 1, OperationHistory.cardTimeLeft(result, result.updatedAt))
        // The same record read back from the journal (after a restart) follows the same rule.
        val reloaded = store.load().single()
        assertEquals(result.updatedAt, reloaded.updatedAt); assertFalse(reloaded.unresolved)
        assertFalse(OperationHistory.cardVisible(reloaded, reloaded.updatedAt + linger + 1))
    }
}
