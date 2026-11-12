package com.meteor.app.data

import com.meteor.app.ui.VmActionLayout
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

/** A2 operations: every new kind shares the journal, fence, uncertainty and verification rules. */
class VmOperationsTest {
    private val profile = Profile("p", "Lab", "https://test.invalid/", "tester", "secret", "a".repeat(64))
    private val hostA = HostChoice("host-a", "Host A", enabled = true, live = true)
    private val hostB = HostChoice("host-b", "Host B", enabled = true, live = true)
    private val everything = setOf("start", "start_on", "clean_shutdown", "clean_reboot", "hard_shutdown", "hard_reboot",
        "suspend", "resume", "pause", "unpause", "pool_migrate")
    private fun vm(power: String = "Running", resident: String? = if (power == "Halted") null else "host-a",
        boot: String? = "20260101T10:00:00Z", allowed: Set<String> = everything) =
        VmDetails("pool", "vm", "Guest", "", power, resident?.let { "Host ${it.last().uppercase()}" }, 1, 1024,
            emptyList(), emptyList(), false, null, emptyList(), boot, allowed, false, 1000, residentHostUuid = resident)

    private class Store : OperationStore {
        val records = linkedMapOf<String, PowerOperation>()
        val phases = mutableListOf<PowerPhase>()
        override suspend fun load() = records.values.map { JournalCodec.decode(JournalCodec.encode(it)) }
        override suspend fun put(operation: PowerOperation) { records[operation.id] = operation; phases += operation.phase }
    }
    private class Backend(var current: VmDetails, val store: Store) : PowerBackend, PowerConnection {
        var after: VmDetails = current
        var submitted = false; var sends = 0
        val sent = mutableListOf<Pair<PowerAction, String?>>()
        var failure: Exception? = null
        var task = TaskObservation("success", 1.0)
        val hosts = mutableMapOf("host-a" to HostChoice("host-a", "Host A", true, true), "host-b" to HostChoice("host-b", "Host B", true, true))
        override suspend fun <T> powerConnection(profile: Profile, action: suspend PowerConnection.() -> T): T = action(this)
        override suspend fun details(authority: String, uuid: String) = if (submitted) after else current
        override suspend fun submit(authority: String, uuid: String, action: PowerAction): String = error("Kinds must use submitOperation")
        override suspend fun submitOperation(authority: String, uuid: String, action: PowerAction, targetHost: String?): String {
            assertEquals(PowerPhase.SUBMITTING, store.records.values.last().phase)
            sends++; submitted = true; sent += action to targetHost
            failure?.let { throw it }
            return "task-ref"
        }
        override suspend fun host(authority: String, uuid: String) = hosts.getValue(uuid)
        override suspend fun taskUuid(reference: String) = "task-uuid"
        override suspend fun taskReference(uuid: String) = "task-ref"
        override suspend fun observeTask(reference: String) = task
    }
    private fun confirm(details: VmDetails, action: PowerAction, target: HostChoice? = null, typed: String? = details.name) =
        PowerConfirmation(profile.id, profile.origin(), details, action, target, typed)
    private fun service(backend: Backend, store: Store) = PowerService(backend, store, { 1000 }, {}, 2)

    // Eligibility and disabled reasons

    @Test fun eachKindRequiresItsPowerStatesAndAllowedOperationKey() {
        val expectations = mapOf(
            PowerAction.HARD_SHUTDOWN to setOf("Running", "Paused", "Suspended"), PowerAction.HARD_REBOOT to setOf("Running"),
            PowerAction.SUSPEND to setOf("Running"), PowerAction.RESUME to setOf("Suspended"), PowerAction.PAUSE to setOf("Running"),
            PowerAction.UNPAUSE to setOf("Paused"), PowerAction.START_ON to setOf("Halted"), PowerAction.POOL_MIGRATE to setOf("Running"),
            PowerAction.START to setOf("Halted"), PowerAction.SHUTDOWN to setOf("Running"), PowerAction.REBOOT to setOf("Running"))
        for ((action, states) in expectations) for (power in listOf("Halted", "Running", "Paused", "Suspended")) {
            val details = vm(power)
            assertEquals("$action from $power", power in states, PowerRules.eligible(details, action))
            assertFalse(PowerRules.eligible(details.copy(allowed = everything - action.operation), action))
            assertFalse(PowerRules.eligible(details.copy(currentOperations = true), action))
        }
        assertEquals("Requires the VM to be running, paused or suspended.", PowerRules.unavailableReason(vm("Halted"), PowerAction.HARD_SHUTDOWN))
        assertEquals("Requires the VM to be halted.", PowerRules.unavailableReason(vm("Running"), PowerAction.START))
    }
    @Test fun disabledReasonsMatchTheGateForEveryKindAndState() {
        val cases = listOf("Halted", "Running", "Paused", "Suspended", "Unknown").flatMap { power ->
            listOf(vm(power), vm(power, boot = null), vm(power, resident = null), vm(power, allowed = emptySet()), vm(power).copy(currentOperations = true))
        }
        for (current in cases) for (action in PowerAction.entries)
            assertEquals("$action ${current.power}", PowerRules.eligible(current, action), PowerRules.unavailableReason(current, action) == null)
    }
    @Test fun suspendExplainsWhyTheServerOmitsIt() {
        val reason = PowerRules.unavailableReason(vm(allowed = everything - "suspend"), PowerAction.SUSPEND)!!
        assertTrue(reason.contains("vTPM")); assertTrue(reason.contains("passthrough"))
        assertEquals("The server does not currently allow this action.", PowerRules.unavailableReason(vm(allowed = emptySet()), PowerAction.PAUSE))
    }
    @Test fun rebootKindsNeedABootMarkerAndMigrationNeedsAKnownHost() {
        assertFalse(PowerRules.eligible(vm(boot = null), PowerAction.HARD_REBOOT))
        assertTrue(PowerRules.unavailableReason(vm(boot = null), PowerAction.HARD_REBOOT)!!.contains("last-boot"))
        assertFalse(PowerRules.eligible(vm(resident = null), PowerAction.POOL_MIGRATE))
        assertTrue(PowerRules.eligible(vm(resident = null), PowerAction.SUSPEND))
        assertFalse(PowerRules.eligible(vm(), PowerAction.UNRECOGNIZED))
    }
    @Test fun hostPickerOffersOnlyEnabledLiveHostsAndNeverTheCurrentHostForMigration() {
        val hosts = listOf(hostA.copy(resident = true), hostB, HostChoice("c", "Disabled", false, true), HostChoice("d", "Dead", true, false))
        assertEquals(listOf(hostB), PowerRules.hostCandidates(PowerAction.POOL_MIGRATE, hosts, "host-a"))
        assertEquals(listOf(hostA.copy(resident = true), hostB), PowerRules.hostCandidates(PowerAction.START_ON, hosts, null))
        assertNotNull(PowerRules.targetProblem(vm(), PowerAction.POOL_MIGRATE, null))
        assertNotNull(PowerRules.targetProblem(vm(), PowerAction.SUSPEND, hostB))
        assertNull(PowerRules.targetProblem(vm(), PowerAction.SUSPEND, null))
    }
    @Test fun xapiCallsAreDeclaredPerKindAndCombinedShutdownIsNeverBound() {
        assertEquals(listOf("vm", false, false), PowerAction.START.xapiArguments("vm", null))
        assertEquals(listOf("vm"), PowerAction.SHUTDOWN.xapiArguments("vm", null))
        assertEquals(listOf("vm", false, false), PowerAction.RESUME.xapiArguments("vm", null))
        assertEquals(listOf("vm", "host", false, false), PowerAction.START_ON.xapiArguments("vm", "host"))
        assertEquals(listOf("vm", "host", mapOf("live" to "true")), PowerAction.POOL_MIGRATE.xapiArguments("vm", "host"))
        assertEquals("Async.VM.hard_shutdown", PowerAction.HARD_SHUTDOWN.xapiMethod)
        assertTrue(runCatching { PowerAction.POOL_MIGRATE.xapiArguments("vm", null) }.isFailure)
        assertTrue(runCatching { PowerAction.SUSPEND.xapiArguments("vm", "host") }.isFailure)
        assertTrue(runCatching { PowerAction.UNRECOGNIZED.xapiArguments("vm", null) }.isFailure)
        assertTrue(PowerAction.entries.none { it.operation == "shutdown" || it.operation == "reboot" })
        for (kind in listOf(PowerAction.HARD_SHUTDOWN, PowerAction.HARD_REBOOT, PowerAction.POOL_MIGRATE))
            assertEquals(ConfirmationStrength.TYPED_NAME, kind.confirmation)
        for (kind in listOf(PowerAction.START, PowerAction.SUSPEND, PowerAction.RESUME, PowerAction.PAUSE, PowerAction.UNPAUSE, PowerAction.START_ON))
            assertEquals(ConfirmationStrength.SINGLE, kind.confirmation)
    }
    @Test fun panelLayoutCoversEveryRequestableKindOnce() {
        val shown = VmActionLayout.sections.flatMap { it.second }
        assertEquals(PowerAction.entries.filter { it.requestable }.toSet(), shown.toSet())
        assertEquals(shown.size, shown.toSet().size)
        assertEquals(listOf(PowerAction.START, PowerAction.START_ON), VmActionLayout.applicable(vm("Halted")).flatMap { it.second })
        assertEquals("Migrate to host…", VmActionLayout.label(PowerAction.POOL_MIGRATE))
        assertEquals("Start", VmActionLayout.label(PowerAction.START))
    }

    // Confirmation fences

    @Test fun typedNameMustMatchExactlyBeforeAnythingIsJournaled() = runTest {
        for (typed in listOf(null, "", "guest", "Guest ", "GUEST")) {
            val store = Store(); val backend = Backend(vm(), store)
            assertTrue(runCatching { service(backend, store).execute(profile, confirm(backend.current, PowerAction.HARD_SHUTDOWN, typed = typed)) }.isFailure)
            assertEquals(0, backend.sends); assertTrue(store.records.isEmpty())
        }
        val store = Store(); val backend = Backend(vm(), store).apply { after = current.copy(power = "Halted", residentHostUuid = null, host = null) }
        assertEquals(PowerPhase.SUCCEEDED, service(backend, store).execute(profile, confirm(backend.current, PowerAction.HARD_SHUTDOWN, typed = "Guest")).phase)
        // Single-confirmation kinds ignore typed text.
        assertTrue(PowerRules.typedNameMatches(confirm(vm(), PowerAction.SUSPEND, typed = null)))
        assertEquals("vm", PowerRules.confirmationPhrase(vm().copy(name = " ")))
    }
    @Test fun expiredChangedOrRenamedConfirmationNeverSends() = runTest {
        val original = vm()
        val changes = listOf(original.copy(authority = "other"), original.copy(uuid = "other"), original.copy(bootTime = "20260102T10:00:00Z"),
            original.copy(power = "Paused"), original.copy(residentHostUuid = "host-b"), original.copy(name = "Renamed"))
        for (changed in changes) for (kind in listOf(PowerAction.HARD_SHUTDOWN, PowerAction.SUSPEND)) {
            val store = Store(); val backend = Backend(changed, store)
            assertTrue(runCatching { service(backend, store).execute(profile, confirm(original, kind)) }.isFailure)
            assertEquals(0, backend.sends); assertTrue(store.records.isEmpty())
        }
        val store = Store(); val backend = Backend(original, store)
        assertTrue(runCatching { service(backend, store).execute(profile, confirm(original.copy(observedAt = -30_001), PowerAction.PAUSE)) }.isFailure)
        assertTrue(runCatching { service(backend, store).execute(profile.copy(username = "other"), confirm(original, PowerAction.PAUSE)) }.isFailure)
        assertEquals(0, backend.sends)
    }
    @Test fun targetHostIsReadAgainAndMustStillQualifyBeforeJournaling() = runTest {
        for (fresh in listOf(hostB.copy(enabled = false), hostB.copy(live = false), hostB.copy(uuid = "host-a"))) {
            val store = Store(); val backend = Backend(vm(), store).apply { hosts["host-b"] = fresh }
            assertTrue(runCatching { service(backend, store).execute(profile, confirm(backend.current, PowerAction.POOL_MIGRATE, hostB)) }.isFailure)
            assertEquals(0, backend.sends); assertTrue(store.records.isEmpty())
        }
        val store = Store(); val backend = Backend(vm(), store)
        assertTrue(runCatching { service(backend, store).execute(profile, confirm(backend.current, PowerAction.POOL_MIGRATE, hostA)) }.isFailure)
        assertTrue(runCatching { service(backend, store).execute(profile, confirm(backend.current, PowerAction.POOL_MIGRATE, null)) }.isFailure)
        assertEquals(0, backend.sends)
    }
    @Test fun oneUnresolvedOperationOfAnyKindBlocksEveryOtherKind() = runTest {
        val store = Store(); val backend = Backend(vm(), store).apply { failure = IOException("lost") }
        val first = service(backend, store).execute(profile, confirm(backend.current, PowerAction.SUSPEND))
        assertEquals(PowerPhase.UNKNOWN, first.phase)
        backend.failure = null
        for (kind in listOf(PowerAction.PAUSE, PowerAction.HARD_SHUTDOWN, PowerAction.SHUTDOWN))
            assertTrue(runCatching { service(backend, store).execute(profile, confirm(backend.current, kind)) }.isFailure)
        assertEquals(1, backend.sends)
    }

    // Uncertainty, explicit failure and verification

    @Test fun explicitRejectionFailsButTransportLossIsUnknownAndNeverResent() = runTest {
        for (error in listOf(RpcFailure("VM_BAD_POWER_STATE"), RpcFailure("RPC_FAULT", explicitRejection = false), IOException("reset"))) {
            val store = Store(); val backend = Backend(vm(), store).apply { failure = error }
            val service = service(backend, store)
            val result = service.execute(profile, confirm(backend.current, PowerAction.POOL_MIGRATE, hostB))
            val explicit = error is RpcFailure && error.explicitRejection
            assertEquals(if (explicit) PowerPhase.FAILED else PowerPhase.UNKNOWN, result.phase)
            if (explicit) assertTrue(result.message.contains("VM_BAD_POWER_STATE"))
            else {
                assertTrue(result.unresolved)
                assertEquals(PowerPhase.UNKNOWN, service.reconcile(profile, result).phase)
            }
            assertEquals(1, backend.sends)
            assertEquals("host-b", store.load().single().targetHostUuid)
        }
    }
    @Test fun failedTaskSurfacesTheServerErrorCode() = runTest {
        val store = Store(); val backend = Backend(vm(), store).apply { task = TaskObservation("failure", 1.0, "VM_MIGRATE_FAILED") }
        val result = service(backend, store).execute(profile, confirm(backend.current, PowerAction.POOL_MIGRATE, hostB))
        assertEquals(PowerPhase.FAILED, result.phase); assertTrue(result.message.contains("VM_MIGRATE_FAILED"))
    }
    @Test fun migrationAndStartOnVerifyTheResidentHost() = runTest {
        val store = Store(); val backend = Backend(vm(), store)
        backend.after = backend.current // still on host-a: the task succeeded but placement is not proven
        val pending = service(backend, store).execute(profile, confirm(backend.current, PowerAction.POOL_MIGRATE, hostB))
        assertEquals(PowerPhase.UNKNOWN, pending.phase)
        backend.after = backend.current.copy(residentHostUuid = "host-b", host = "Host B")
        val verified = service(backend, store).reconcile(profile, pending)
        assertEquals(PowerPhase.SUCCEEDED, verified.phase); assertTrue(verified.message.contains("Host B"))
        assertEquals(listOf(PowerAction.POOL_MIGRATE to "host-b"), backend.sent)

        val startStore = Store(); val start = Backend(vm("Halted"), startStore)
        start.after = start.current.copy(power = "Running", residentHostUuid = "host-a")
        assertEquals(PowerPhase.UNKNOWN, service(start, startStore).execute(profile, confirm(start.current, PowerAction.START_ON, hostB)).phase)
        val op = startStore.load().single()
        start.after = start.after.copy(residentHostUuid = "host-b")
        assertEquals(PowerPhase.SUCCEEDED, service(start, startStore).reconcile(profile, op).phase)
        assertFalse(PowerRules.verified(op.copy(targetHostUuid = null), start.after))
    }
    @Test fun hardRebootNeedsAChangedBootMarker() = runTest {
        val store = Store(); val backend = Backend(vm(), store)
        val pending = service(backend, store).execute(profile, confirm(backend.current, PowerAction.HARD_REBOOT))
        assertEquals(PowerPhase.UNKNOWN, pending.phase)
        backend.after = backend.current.copy(bootTime = "2026-01-01T11:00:00+01:00") // same instant, other format
        assertEquals(PowerPhase.UNKNOWN, service(backend, store).reconcile(profile, pending).phase)
        backend.after = backend.current.copy(bootTime = "20260101T10:02:00Z")
        val verified = service(backend, store).reconcile(profile, pending)
        assertEquals(PowerPhase.SUCCEEDED, verified.phase); assertEquals("Force reboot verified: boot timestamp changed.", verified.message)
        assertEquals(1, backend.sends)
    }
    @Test fun suspendPauseAndTheirInversesVerifyTheirEndStates() = runTest {
        val transitions = listOf(Triple(PowerAction.SUSPEND, "Running", "Suspended"), Triple(PowerAction.RESUME, "Suspended", "Running"),
            Triple(PowerAction.PAUSE, "Running", "Paused"), Triple(PowerAction.UNPAUSE, "Paused", "Running"),
            Triple(PowerAction.HARD_SHUTDOWN, "Suspended", "Halted"), Triple(PowerAction.HARD_SHUTDOWN, "Paused", "Halted"))
        for ((kind, from, to) in transitions) {
            val store = Store(); val backend = Backend(vm(from), store)
            backend.after = backend.current // unchanged state is never success
            val pending = service(backend, store).execute(profile, confirm(backend.current, kind))
            assertEquals("$kind", PowerPhase.UNKNOWN, pending.phase)
            backend.after = backend.current.copy(power = to)
            assertEquals("$kind", PowerPhase.SUCCEEDED, service(backend, store).reconcile(profile, pending).phase)
            assertEquals(1, backend.sends)
        }
    }

    // Roles (advisory only)

    @Test fun roleMappingExplainsButNeverDecides() {
        val operator = SessionPermissions(roles = listOf("vm-operator"))
        assertNull(RoleRules.explanation(operator, PowerAction.HARD_SHUTDOWN))
        assertNull(RoleRules.explanation(operator, PowerAction.SUSPEND))
        assertTrue(RoleRules.explanation(operator, PowerAction.POOL_MIGRATE)!!.contains("vm-power-admin"))
        assertNotNull(RoleRules.explanation(SessionPermissions(roles = listOf("vm-admin")), PowerAction.START_ON))
        assertNotNull(RoleRules.explanation(SessionPermissions(roles = listOf("read-only")), PowerAction.START))
        assertNull(RoleRules.explanation(SessionPermissions(roles = listOf("read-only", "Pool-Admin")), PowerAction.POOL_MIGRATE))
        assertNull(RoleRules.explanation(SessionPermissions(roles = listOf("vm-power-admin")), PowerAction.POOL_MIGRATE))
        assertNull(RoleRules.explanation(SessionPermissions(roles = listOf("pool-operator")), PowerAction.HARD_REBOOT))
        // Custom roles, unreadable roles and superuser never produce a limit.
        assertNull(RoleRules.explanation(SessionPermissions(roles = listOf("read-only", "lab-custom")), PowerAction.START))
        assertNull(RoleRules.explanation(SessionPermissions.unavailable("MESSAGE_METHOD_UNKNOWN"), PowerAction.POOL_MIGRATE))
        assertNull(RoleRules.explanation(null, PowerAction.POOL_MIGRATE))
        assertNull(RoleRules.explanation(SessionPermissions(superuser = true), PowerAction.POOL_MIGRATE))
        assertNotNull(RoleRules.explanation(SessionPermissions(roles = emptyList()), PowerAction.START))
        assertTrue(RoleRules.summary(SessionPermissions.unavailable("RBAC_PERMISSION_DENIED")).contains("server decides"))
        // Role text never changes the gate.
        assertTrue(PowerRules.eligible(vm(), PowerAction.POOL_MIGRATE))
        assertEquals(XapiRole.VM_POWER_ADMIN, XapiRole.parse("VM_POWER_ADMIN"))
    }
}
