package com.meteor.app

import com.meteor.app.data.*
import com.meteor.app.ui.OverviewEmptyKind
import com.meteor.app.ui.OverviewEmptyState
import org.junit.Assert.*
import org.junit.Test

/** D6 of the first phone run: the first-run invitation is never shown while a connection is saved. */
class OverviewEmptyTest {
    private val profile = Profile("p", "Sim pool", "https://example.invalid/", "tester", "secret", "a".repeat(64))
    private val saved = MeteorState(profiles = listOf(profile), selectedId = profile.id)
    private val inventory = Inventory("pool", "Pool", emptyList(), emptyList(), 0, 0, 1)

    @Test fun onlyAPersonWithNoConnectionSeesTheFirstRunInvitation() {
        val empty = OverviewEmptyState.of(MeteorState())!!
        assertEquals(OverviewEmptyKind.FIRST_RUN, empty.kind)
        assertEquals("Bring your first server into orbit", empty.title); assertEquals("Add connection", empty.action)
        // Every state of a saved connection says something else.
        val states = listOf(saved, saved.copy(loading = true), saved.copy(profileBusy = true), saved.copy(connected = true), saved.copy(offline = true),
            saved.copy(retryStop = RetryStop.AUTHENTICATION), saved.copy(retryStop = RetryStop.CERTIFICATE), saved.copy(notice = UiNotice.error("x")),
            saved.copy(selectedId = null), saved.copy(vaultProblem = true), saved.copy(profiles = listOf(profile), selectedId = "gone"))
        states.forEach { assertNotEquals("$it", OverviewEmptyKind.FIRST_RUN, OverviewEmptyState.of(it)!!.kind) }
        states.forEach { assertFalse(OverviewEmptyState.of(it)!!.title.contains("first server")) }
    }

    @Test fun eachStateOfASavedConnectionNamesTheConnectionAndWhatHappens() {
        val connecting = OverviewEmptyState.of(saved.copy(loading = true))!!
        assertEquals(OverviewEmptyKind.CONNECTING, connecting.kind); assertEquals("Connecting to Sim pool…", connecting.title); assertTrue(connecting.busy); assertNull(connecting.action)
        assertEquals(OverviewEmptyKind.CONNECTING, OverviewEmptyState.of(saved.copy(connected = true))!!.kind)
        assertEquals(OverviewEmptyKind.OFFLINE, OverviewEmptyState.of(saved.copy(offline = true))!!.kind)
        val failed = OverviewEmptyState.of(saved)!!
        assertEquals(OverviewEmptyKind.NOT_CONNECTED, failed.kind); assertEquals("Not connected to Sim pool", failed.title)
        assertTrue(failed.body.contains("Refresh")); assertEquals("Open connections", failed.action); assertFalse(failed.busy)
        val stopped = OverviewEmptyState.of(saved.copy(retryStop = RetryStop.POOL_MEMBER))!!
        assertEquals(OverviewEmptyKind.PAUSED, stopped.kind); assertTrue(stopped.body.contains("paused"))
        assertEquals(OverviewEmptyKind.NO_SELECTION, OverviewEmptyState.of(saved.copy(selectedId = null))!!.kind)
        assertEquals(OverviewEmptyKind.VAULT_UNREADABLE, OverviewEmptyState.of(MeteorState(vaultProblem = true))!!.kind)
    }

    @Test fun anOverviewWithAnInventoryHasNoPlaceholderAndABlankLabelFallsBackToThePlainWord() {
        assertNull(OverviewEmptyState.of(saved.copy(inventory = inventory)))
        assertNull(OverviewEmptyState.of(saved.copy(inventory = inventory, loading = true)))
        val blank = saved.copy(profiles = listOf(profile.copy(label = " ")))
        assertEquals("Not connected to the server", OverviewEmptyState.of(blank)!!.title)
    }
}
