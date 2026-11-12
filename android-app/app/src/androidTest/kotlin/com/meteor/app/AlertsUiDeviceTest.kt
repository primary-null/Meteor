package com.meteor.app

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.meteor.app.data.*
import com.meteor.app.ui.AlertsActions
import com.meteor.app.ui.AlertsScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Offline fixture for the Alerts screen. It never contacts a server and never sends a dismissal. */
@RunWith(AndroidJUnit4::class)
class AlertsUiDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val now = 1_800_000_000_000L
    private val profile = Profile("example", "Example", "https://example.invalid/", "tester", "fixture", "a".repeat(64))
    private val guest = Guest("vm-1", "Web VM", "Running", "pool", "host-1", 2, 4096)
    private val inventory = Inventory("pool", "Example pool", listOf(Host("host-1", "Host One", true, 8192, 4096)), listOf(guest), 0, 0, now)
    private val critical = AlertMessage("r1", "u1", "host_clock_went_backwards", 1, "Host", "host-1", now - 1_000, "Clock stepped back")
    private val warning = AlertMessage("r2", "u2", "ha_host_failed", 3, "VM", "vm-1", now - 2_000, "A host died")
    private val info = AlertMessage("r3", "u3", "VM_STARTED", 5, "VM", "template-1234567", now - 3_000, "Started")
    private val opened = mutableListOf<Guest>()
    private val dismissed = mutableListOf<List<AlertMessage>>()
    private val seen = mutableListOf<Unit>()
    private val actions = AlertsActions({}, { seen += Unit }, { dismissed += it }, { opened += it }, {})

    private fun stateOf(lastSeen: Long? = null) = MeteorState(profiles = listOf(profile), selectedId = profile.id, connected = true,
        inventory = inventory, journalProblem = false,
        alerts = AlertInbox(AlertScope.key(profile, "pool"), listOf(critical, warning, info), now - 1_000, now, now, lastSeen = lastSeen))
    private fun show(state: MeteorState) {
        compose.activityRule.scenario.onActivity { host -> host.setContent {
            MaterialTheme { Surface(color = MaterialTheme.colorScheme.background) { AlertsScreen(state, now, actions) } }
        } }
    }
    private fun scrollTo(text: String) = compose.onNodeWithTag("alerts-list").performScrollToNode(hasText(text))

    @Test fun namesObjectsAndUnresolvedObjectsAreShownAndResolvedOnesOpenTheirDetails() {
        show(stateOf())
        for (name in listOf("Host clock went backwards", "HA host failed", "VM started")) { scrollTo(name); compose.onNodeWithText(name).assertIsDisplayed() }
        scrollTo("not in the current inventory"); compose.onNodeWithText("not in the current inventory", substring = true).assertIsDisplayed()
        scrollTo("VM · Web VM"); compose.onNodeWithText("VM · Web VM", substring = true).performClick()
        assertEquals(listOf(guest), opened)
        assertTrue(seen.isNotEmpty())                                                      // the screen records that the list was seen
    }
    @Test fun priorityChipsAndSearchNarrowTheList() {
        show(stateOf())
        val criticalChip = hasText("Critical") and hasClickAction()
        scrollTo("Search alerts")
        compose.onNode(criticalChip).performClick()
        scrollTo("Host clock went backwards"); compose.onNodeWithText("Host clock went backwards").assertIsDisplayed()
        compose.onNodeWithText("HA host failed").assertDoesNotExist()
        compose.onNode(criticalChip).performClick()
        compose.onNodeWithText("Search alerts").performTextInput("died")
        scrollTo("HA host failed"); compose.onNodeWithText("HA host failed").assertIsDisplayed()
        compose.onNodeWithText("Host clock went backwards").assertDoesNotExist()
    }
    @Test fun dismissalAsksFirstAndTheBatchNeedsTheTypedCount() {
        show(stateOf())
        scrollTo("Host clock went backwards")
        compose.onAllNodesWithText("Dismiss")[0].performScrollTo().performClick()
        compose.onNodeWithText("Dismiss this alert?").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        assertTrue(dismissed.isEmpty())
        compose.onAllNodesWithText("Dismiss")[0].performScrollTo().performClick()
        compose.onNodeWithText("Dismiss alert").performClick()
        assertEquals(listOf(listOf(critical)), dismissed)                                 // highest priority is listed first
        scrollTo("Dismiss shown (3)")
        compose.onNodeWithText("Dismiss shown (3)").performClick()
        compose.onNodeWithText("Dismiss 3 alerts").assertIsNotEnabled()
        compose.onNodeWithText("Number of alerts").performTextInput("3")
        compose.onNodeWithText("Dismiss 3 alerts").assertIsEnabled().performClick()
        assertEquals(3, dismissed.last().size)
    }
    @Test fun newAlertsAreTaggedFromTheLastSeenTimeAndTheBadgeCountsOnlyUrgentOnes() {
        val state = stateOf(lastSeen = now - 2_500)
        assertEquals(2, state.unreadAlertBadge)                                           // the critical and the warning alert; the old info alert is not new
        show(state)
        scrollTo("Host clock went backwards")
        compose.onAllNodesWithText("New").assertCountEquals(2)
        assertEquals(0, stateOf(lastSeen = now).unreadAlertBadge)
    }
}
