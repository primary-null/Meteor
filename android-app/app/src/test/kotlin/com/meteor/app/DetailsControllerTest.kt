package com.meteor.app

import com.meteor.app.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

/**
 * The automatic five-second refresh must not disable controls (it sets `detailsPolling`, never `detailsBusy`), a user-visible
 * load still must, and a stale result of either is discarded. Safety checks (re-read before confirmation) are untouched.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DetailsControllerTest {
    private val profile = Profile("fixture", "Example", "https://example.invalid/", "tester", "fixture", "a".repeat(64))
    private val allowed = setOf("start", "clean_shutdown", "hard_shutdown", "clean_reboot", "hard_reboot", "pool_migrate", "suspend", "pause")
    private fun detail(power: String = "Running", boot: String? = "20261107T08:00:00Z", host: String? = "host-a", observed: Long = 1000, uuid: String = "vm") =
        VmDetails("pool", uuid, "VM", "", power, host, 2, 1024, emptyList(), emptyList(), true, null, emptyList(), boot, allowed, false, observed, observed,
            null, residentHostUuid = host)
    private val guest = Guest("vm", "VM", "Running", "pool", "host-a", 2, 1024)
    private val inventory = Inventory("pool", "Pool", listOf(Host("host-a", "Host A", true, null, null), Host("host-b", "Host B", true, null, null)),
        listOf(guest), 0, 0, 1)
    private fun state(details: VmDetails? = detail(), connected: Boolean = true) = MutableStateFlow(MeteorState(
        profiles = listOf(profile), selectedId = profile.id, inventory = inventory, connected = connected, journalProblem = false,
        selectedGuest = guest, details = details))

    private class Backend : MeteorGateway {
        var fullReads = 0
        var polls = 0
        var onDetails: suspend () -> VmDetails = { error("Unexpected full read") }
        var onPoll: suspend (VmDetails) -> VmDetails = { error("Unexpected poll") }
        override suspend fun details(profile: Profile, authority: String, uuid: String): VmDetails { fullReads++; return onDetails() }
        override suspend fun pollDetails(profile: Profile, authority: String, uuid: String, previous: VmDetails): VmDetails { polls++; return onPoll(previous) }
        override suspend fun inventory(profile: Profile): Inventory = error("Unexpected inventory")
        override suspend fun <T> powerConnection(profile: Profile, action: suspend PowerConnection.() -> T): T = error("Unexpected power access")
    }
    private class Journal : OperationStore {
        override suspend fun load() = emptyList<PowerOperation>()
        override suspend fun put(operation: PowerOperation) = error("Reads must not touch the journal")
    }
    private class Fixture(scope: CoroutineScope, val state: MutableStateFlow<MeteorState>, val backend: Backend = Backend()) {
        var connection = 1L
        val changed = mutableListOf<Pair<String, String>>()
        val controller = DetailsController(scope, backend, state, { connection }, { "Read failed." }, { authority, uuid -> changed += authority to uuid })
    }

    // ---- flag semantics ----

    @Test fun anAutomaticPollNeverSetsTheUserVisibleBusyFlag() = runTest {
        val f = Fixture(backgroundScope, state()); val gate = CompletableDeferred<VmDetails>()
        f.backend.onPoll = { gate.await() }
        f.controller.refresh(); runCurrent()
        assertEquals(1, f.backend.polls)
        assertEquals("a poll reads the cheap way", 0, f.backend.fullReads)
        assertTrue(f.state.value.detailsPolling)
        assertFalse("controls stay enabled during a poll", f.state.value.detailsBusy)
        assertNotNull("the displayed details stay while it reads", f.state.value.details)
        gate.complete(detail(observed = 6000)); runCurrent()
        assertFalse(f.state.value.detailsPolling); assertFalse(f.state.value.detailsBusy)
        assertEquals(6000L, f.state.value.details!!.observedAt)
    }
    @Test fun controlsStayEnabledWhileAPollIsInFlight() = runTest {
        val f = Fixture(backgroundScope, state()); val gate = CompletableDeferred<VmDetails>()
        f.backend.onPoll = { gate.await() }
        assertTrue(ControlGates.vmActionsReady(f.state.value)); assertTrue(ControlGates.hostPickerEnabled(f.state.value))
        f.controller.refresh(); runCurrent()
        assertTrue(f.state.value.detailsPolling)
        assertTrue("VM action buttons", ControlGates.vmActionsReady(f.state.value))
        assertTrue("host picker buttons: the first tap is not swallowed", ControlGates.hostPickerEnabled(f.state.value))
        gate.complete(detail()); runCurrent()
        assertTrue(ControlGates.vmActionsReady(f.state.value)); assertTrue(ControlGates.hostPickerEnabled(f.state.value))
    }
    @Test fun storageAndHostControlsAreNotDisabledByAPollEither() = runTest {
        val f = Fixture(backgroundScope, state()); val gate = CompletableDeferred<VmDetails>()
        f.backend.onPoll = { gate.await() }
        val storage = StorageHostController(backgroundScope, f.backend, PowerService(f.backend, Journal()), f.state, { f.connection }, { "failed" }, {}, {}, {})
        assertTrue(storage.canWrite(f.state.value))
        f.controller.refresh(); runCurrent()
        assertTrue("snapshot, CD, host and storage controls", storage.canWrite(f.state.value))
        gate.complete(detail()); runCurrent()
        // A user-visible load still blocks them.
        f.backend.onDetails = { CompletableDeferred<VmDetails>().await() }
        f.controller.load(profile, "pool", "vm"); runCurrent()
        assertTrue(f.state.value.detailsBusy)
        assertFalse(storage.canWrite(f.state.value)); assertFalse(ControlGates.vmActionsReady(f.state.value)); assertFalse(ControlGates.hostPickerEnabled(f.state.value))
    }
    @Test fun aManualRefreshOrOpenIsUserVisibleAndReadsInFull() = runTest {
        val f = Fixture(backgroundScope, state()); val gate = CompletableDeferred<VmDetails>()
        f.backend.onDetails = { gate.await() }
        f.controller.load(profile, "pool", "vm"); runCurrent()
        assertEquals(1, f.backend.fullReads); assertEquals(0, f.backend.polls)
        assertTrue(f.state.value.detailsBusy)
        assertFalse(f.state.value.detailsPolling)
        assertFalse(ControlGates.vmActionsReady(f.state.value)); assertFalse(ControlGates.hostPickerEnabled(f.state.value))
        gate.complete(detail(observed = 7000)); runCurrent()
        assertFalse(f.state.value.detailsBusy); assertEquals(7000L, f.state.value.details!!.observedAt)
        assertTrue(ControlGates.vmActionsReady(f.state.value))
    }
    @Test fun openingFromNothingIsUserVisibleToo() = runTest {
        val f = Fixture(backgroundScope, state(details = null)); val gate = CompletableDeferred<VmDetails>()
        f.backend.onDetails = { gate.await() }
        f.controller.load(profile, "pool", "vm"); runCurrent()
        assertTrue(f.state.value.detailsBusy); assertFalse(f.state.value.detailsPolling)
        gate.complete(detail()); runCurrent()
        assertFalse(f.state.value.detailsBusy); assertNotNull(f.state.value.details)
        assertTrue("a first read is not a change", f.changed.isEmpty())
    }
    @Test fun anActionSubmissionIsUserVisibleAndReReadsBeforeConfirming() = runTest {
        val f = Fixture(backgroundScope, state()); val gate = CompletableDeferred<VmDetails>()
        f.backend.onDetails = { gate.await() }
        f.controller.load(profile, "pool", "vm", PowerAction.HARD_SHUTDOWN); runCurrent()
        assertEquals("a full re-read, not the cheap poll", 1, f.backend.fullReads); assertEquals(0, f.backend.polls)
        assertTrue(f.state.value.detailsBusy); assertFalse(f.state.value.detailsPolling)
        assertNull(f.state.value.pendingPower)
        gate.complete(detail(observed = 8000)); runCurrent()
        assertEquals(PowerAction.HARD_SHUTDOWN, f.state.value.pendingPower!!.action)
        assertEquals("the confirmation carries the fresh read", 8000L, f.state.value.pendingPower!!.details.observedAt)
        assertFalse(f.state.value.detailsBusy)
    }
    @Test fun anActionOnAVmThatNoLongerQualifiesIsNotConfirmed() = runTest {
        val f = Fixture(backgroundScope, state()); f.backend.onDetails = { detail(power = "Halted") }
        f.controller.load(profile, "pool", "vm", PowerAction.HARD_SHUTDOWN); runCurrent()
        assertNull(f.state.value.pendingPower)
        assertEquals(NoticeKind.WARNING, f.state.value.notice?.kind)
        assertEquals("Halted", f.state.value.details!!.power)
    }
    @Test fun refreshSkipsWhileAnythingIsAlreadyInProgressOrAConfirmationIsOpen() = runTest {
        val f = Fixture(backgroundScope, state()); val gate = CompletableDeferred<VmDetails>()
        f.backend.onPoll = { gate.await() }
        f.controller.refresh(); f.controller.refresh(); runCurrent()
        assertEquals("never two polls at once", 1, f.backend.polls)
        gate.complete(detail()); runCurrent()
        f.state.value = f.state.value.copy(detailsBusy = true); f.controller.refresh(); runCurrent()
        assertEquals("not while a user-visible read runs", 1, f.backend.polls)
        f.state.value = f.state.value.copy(detailsBusy = false, loading = true); f.controller.refresh(); runCurrent()
        assertEquals("not while the connection refreshes", 1, f.backend.polls)
        f.state.value = f.state.value.copy(loading = false, operationBusy = true); f.controller.refresh(); runCurrent()
        assertEquals("not while an operation runs", 1, f.backend.polls)
        f.state.value = f.state.value.copy(operationBusy = false, pendingPower = PowerConfirmation(profile.id, profile.origin(), detail(), PowerAction.SHUTDOWN))
        f.controller.refresh(); runCurrent()
        assertEquals("not while a confirmation is open", 1, f.backend.polls)
        f.state.value = f.state.value.copy(pendingPower = null, details = null); f.controller.refresh(); runCurrent()
        assertEquals("nothing to refresh without details", 1, f.backend.polls)
    }

    // ---- stale results stay fenced ----

    @Test fun aUserLoadSupersedesAPollInFlightAndTheLatePollIsDiscarded() = runTest {
        val f = Fixture(backgroundScope, state()); val pollGate = CompletableDeferred<VmDetails>(); val loadGate = CompletableDeferred<VmDetails>()
        f.backend.onPoll = { pollGate.await() }; f.backend.onDetails = { loadGate.await() }
        f.controller.refresh(); runCurrent()
        assertTrue(f.state.value.detailsPolling)
        // The user taps an action while the poll is still reading: it is not swallowed, and it starts a fresh, user-visible read.
        f.controller.load(profile, "pool", "vm", PowerAction.HARD_REBOOT); runCurrent()
        assertTrue(f.state.value.detailsBusy); assertFalse("the poll's flag is cleared with it", f.state.value.detailsPolling)
        pollGate.complete(detail(power = "Halted", observed = 5000)); runCurrent()
        assertEquals("the stale poll changed nothing", "Running", f.state.value.details!!.power)
        assertEquals(1000L, f.state.value.details!!.observedAt)
        assertTrue("and did not clear the user-visible read", f.state.value.detailsBusy)
        assertNull(f.state.value.pendingPower)
        assertTrue(f.changed.isEmpty())
        loadGate.complete(detail(observed = 9000)); runCurrent()
        assertEquals(PowerAction.HARD_REBOOT, f.state.value.pendingPower!!.action)
        assertEquals(9000L, f.state.value.pendingPower!!.details.observedAt)
        assertFalse(f.state.value.detailsBusy)
    }
    @Test fun aNewerUserLoadWinsOverAnOlderOne() = runTest {
        val f = Fixture(backgroundScope, state()); val first = CompletableDeferred<VmDetails>(); val second = CompletableDeferred<VmDetails>()
        var calls = 0
        f.backend.onDetails = { if (++calls == 1) first.await() else second.await() }
        f.controller.load(profile, "pool", "vm"); runCurrent()
        f.controller.load(profile, "pool", "vm"); runCurrent()
        second.complete(detail(observed = 2000)); runCurrent()
        first.complete(detail(observed = 1500)); runCurrent()
        assertEquals(2000L, f.state.value.details!!.observedAt)
        assertFalse(f.state.value.detailsBusy)
    }
    @Test fun closingDuringAPollDiscardsItAndClearsBothFlags() = runTest {
        val f = Fixture(backgroundScope, state()); val gate = CompletableDeferred<VmDetails>()
        f.backend.onPoll = { gate.await() }
        f.controller.refresh(); runCurrent()
        f.controller.close()
        assertFalse(f.state.value.detailsPolling); assertFalse(f.state.value.detailsBusy)
        assertNull(f.state.value.selectedGuest); assertNull(f.state.value.details)
        gate.complete(detail(observed = 5000)); runCurrent()
        assertNull("a closed VM stays closed", f.state.value.details)
        f.controller.refresh(); runCurrent()
        assertEquals("and is not polled again", 1, f.backend.polls)
    }
    @Test fun aNewConnectionDiscardsAPollResult() = runTest {
        val f = Fixture(backgroundScope, state()); val gate = CompletableDeferred<VmDetails>()
        f.backend.onPoll = { gate.await() }
        f.controller.refresh(); runCurrent()
        f.controller.invalidate() // what ConnectionController does when it starts a new connection
        gate.complete(detail(power = "Halted", observed = 5000)); runCurrent()
        assertEquals("Running", f.state.value.details!!.power)
        assertTrue(f.changed.isEmpty())
    }
    @Test fun aResultFromAnOlderConnectionClosesTheDetailsInsteadOfBeingShown() = runTest {
        val f = Fixture(backgroundScope, state()); val gate = CompletableDeferred<VmDetails>()
        f.backend.onPoll = { gate.await() }
        f.controller.refresh(); runCurrent()
        f.connection++ // the connection changed without the request being invalidated
        gate.complete(detail(observed = 5000)); runCurrent()
        assertNull(f.state.value.selectedGuest); assertNull(f.state.value.details)
        assertFalse(f.state.value.detailsPolling); assertFalse(f.state.value.detailsBusy)
    }
    @Test fun aChangedProfileOrPoolOrLostConnectionDiscardsTheResult() = runTest {
        for (change in listOf<(MeteorState) -> MeteorState>(
            { it.copy(profiles = listOf(profile.copy(endpoint = "https://other.invalid/"))) },
            { it.copy(inventory = it.inventory!!.copy(authority = "other-pool")) },
            { it.copy(connected = false) })) {
            val f = Fixture(backgroundScope, state()); val gate = CompletableDeferred<VmDetails>()
            f.backend.onPoll = { gate.await() }
            f.controller.refresh(); runCurrent()
            f.state.value = change(f.state.value)
            gate.complete(detail(observed = 5000)); runCurrent()
            assertNull(f.state.value.details); assertNull(f.state.value.selectedGuest)
            assertFalse(f.state.value.detailsPolling); assertFalse(f.state.value.detailsBusy); assertNull(f.state.value.pendingPower)
        }
    }
    @Test fun aPollForAnotherVmIdentityIsRejected() = runTest {
        val f = Fixture(backgroundScope, state())
        f.backend.onPoll = { detail(uuid = "someone-else") }
        f.controller.refresh(); runCurrent()
        assertNull(f.state.value.details)
        assertEquals(NoticeKind.ERROR, f.state.value.notice?.kind)
        assertFalse(f.state.value.detailsPolling); assertFalse(f.state.value.detailsBusy)
    }
    @Test fun aFailedPollClearsItsFlagAndSaysSo() = runTest {
        val f = Fixture(backgroundScope, state())
        f.backend.onPoll = { throw IOException("temporary read failure") }
        f.controller.refresh(); runCurrent()
        assertFalse(f.state.value.detailsPolling); assertFalse(f.state.value.detailsBusy)
        assertEquals("Read failed.", f.state.value.message)
        assertNull(f.state.value.details)
    }
    @Test fun aBlockedLoadStartsNothingAndSetsNoFlag() = runTest {
        val f = Fixture(backgroundScope, state().also { it.value = it.value.copy(operationBusy = true) })
        f.controller.load(profile, "pool", "vm"); f.controller.refresh(); runCurrent()
        assertEquals(0, f.backend.fullReads); assertEquals(0, f.backend.polls)
        assertFalse(f.state.value.detailsBusy); assertFalse(f.state.value.detailsPolling)
    }

    // ---- A3 info: when the open VM changed ----

    @Test fun aChangedPowerStateHostOrBootMarkerMarksTheVmInfoStaleOnce() = runTest {
        val cases = listOf(
            "power" to { d: VmDetails -> d.copy(power = "Halted", host = null, residentHostUuid = null) },
            "host" to { d: VmDetails -> d.copy(host = "host-b", residentHostUuid = "host-b") },
            "boot" to { d: VmDetails -> d.copy(bootTime = "20261107T09:00:00Z") })
        for ((name, change) in cases) {
            val f = Fixture(backgroundScope, state())
            f.backend.onPoll = { previous -> change(previous).copy(observedAt = 6000) }
            f.controller.refresh(); runCurrent()
            assertEquals(name, listOf("pool" to "vm"), f.changed)
        }
    }
    @Test fun anUnchangedPollNeverMarksTheVmInfoStale() = runTest {
        val f = Fixture(backgroundScope, state()); var tick = 1000L
        f.backend.onPoll = { previous -> previous.copy(observedAt = ++tick, addresses = listOf("203.0.113.$tick")) }
        repeat(6) { f.controller.refresh(); runCurrent() }
        assertEquals(6, f.backend.polls)
        assertTrue("six polls, no change, no info reload", f.changed.isEmpty())
    }
    @Test fun aFullReadAfterAMigrationAlsoMarksTheVmInfoStale() = runTest {
        val f = Fixture(backgroundScope, state())
        f.backend.onDetails = { detail(host = "host-b", observed = 7000) }
        f.controller.load(profile, "pool", "vm"); runCurrent() // manual "Refresh details" after a migration finished
        assertEquals(listOf("pool" to "vm"), f.changed)
    }
}
