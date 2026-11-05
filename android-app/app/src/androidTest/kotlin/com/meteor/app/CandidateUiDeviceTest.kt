package com.meteor.app

import android.app.Application
import androidx.activity.compose.setContent
import androidx.lifecycle.viewModelScope
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ApplicationProvider
import com.meteor.app.data.*
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.io.File
import java.security.KeyStore
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Offline journeys for the actual non-debuggable candidate; no lab credentials. */
@RunWith(AndroidJUnit4::class)
class CandidateUiDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun navigationThemesAndActivityRecreation() {
        repeat(3) {
            compose.onNodeWithText("VMs", useUnmergedTree = true).performClick()
            compose.onNodeWithText("Find a VM").performTextInput("fixture")
            compose.onNodeWithText("Settings", useUnmergedTree = true).performClick()
            listOf("Light", "Dark", "System", "Coral", "Slate", "Green", "Silver").forEach {
                compose.onNodeWithText(it).performScrollTo().performClick()
                compose.waitForIdle()
            }
            compose.onNodeWithText("Connect", useUnmergedTree = true).performClick()
            compose.onNodeWithText("Add XCP-ng server").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Overview", useUnmergedTree = true).performClick()
        }
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Your universe").assertIsDisplayed()
        compose.onNodeWithText("Settings", useUnmergedTree = true).performClick()
        compose.onNodeWithText("System").performScrollTo().assertIsSelected()
        compose.onNodeWithText("Silver").performScrollTo().assertIsSelected()
    }

    @Test fun malformedEndpointIsHandledWithoutNetworkOrCrash() {
        compose.onNodeWithText("Connect", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Server address").performScrollTo().performTextInput("http://invalid.invalid/path")
        compose.onNodeWithText("Username").performScrollTo().performTextInput("fixture")
        compose.onNodeWithText("Password").performScrollTo().performTextInput("fixture")
        compose.onNodeWithText("Review certificate").performScrollTo().performClick()
        compose.onNodeWithText("Enter an HTTPS server address without a path or credentials.").assertIsDisplayed()
    }

    @Test fun vmDetailsIconsAndPowerConfirmationSurviveRecreationWithoutSubmitting() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val vaultName = "instrumentation-ui.secured"
        val journalName = "instrumentation-ui.json"
        val alias = "meteor.instrumentation.ui"
        val profile = Profile("fixture", "Fixture", "https://test.invalid/", "tester", "fixture", "a".repeat(64))
        val guest = Guest("fixture-vm", "Fixture VM", "Halted", "fixture-pool", null, 2, 4096)
        var powerConnections = 0
        var failFirstDetail = true
        val detailCalls = AtomicInteger()
        val backend = object : MeteorGateway {
            override suspend fun inventory(profile: Profile) = Inventory("fixture-pool", "Fixture pool",
                listOf(Host("fixture-host", "Fixture host", true, 8192, 4096)), listOf(guest), 0, 0, System.currentTimeMillis())
            override suspend fun details(profile: Profile, authority: String, uuid: String): VmDetails {
                detailCalls.incrementAndGet()
                if (failFirstDetail) { failFirstDetail = false; throw java.io.IOException("temporary fixture failure") }
                return VmDetails(authority, uuid,
                guest.name, "", "Halted", null, 2, 4096, emptyList(), emptyList(), false, null, emptyList(), null,
                setOf("start"), false, System.currentTimeMillis())
            }
            override suspend fun <T> powerConnection(profile: Profile, action: suspend PowerConnection.() -> T): T {
                powerConnections++
                error("Cancelling a power confirmation must not open a power session.")
            }
        }
        val vault = ProfileVault(application, vaultName, alias)
        var fixture: MeteorViewModel? = null
        try {
            vault.save(ProfileDocument(listOf(profile), profile.id))
            compose.activityRule.scenario.onActivity { activity ->
                fixture = MeteorViewModel(application, backend, vault, OperationJournal(application, journalName))
                activity.setContent { MeteorApp(requireNotNull(fixture)) }
            }
            val model = requireNotNull(fixture)
            compose.waitUntil(15_000) { model.state.value.connected }
            compose.onNodeWithText("VMs", useUnmergedTree = true).performClick()
            compose.onNodeWithText("Fixture VM").performScrollTo().performClick()
            compose.waitUntil(15_000) { !model.state.value.detailsBusy && model.state.value.message != null }
            compose.onNodeWithText("Load details").performScrollTo().performClick()
            compose.onNodeWithText("Guest tools not reporting").assertIsDisplayed()
            compose.onNodeWithText("Moon").performScrollTo().performClick()
            compose.waitUntil(15_000) { model.state.value.appearance.icons[guest.identity("fixture-pool")] == "moon" }
            compose.onNodeWithText("Start", substring = false).performScrollTo().performClick()
            compose.onNodeWithText("Start Fixture VM?").assertIsDisplayed()
            compose.activityRule.scenario.recreate()
            compose.activityRule.scenario.onActivity { it.setContent { MeteorApp(model) } }
            compose.onNodeWithText("Start Fixture VM?").assertIsDisplayed()
            compose.onNodeWithText("Cancel").performClick()
            assertEquals(0, powerConnections)
            assertNull(model.state.value.pendingPower)
            compose.onNodeWithText("Guest tools not reporting").assertIsDisplayed()
            assertEquals(guest.uuid, model.state.value.selectedGuest?.uuid)
            compose.onNodeWithText("Done", substring = false).performClick()
            compose.waitForIdle()
            val callsAfterClose = detailCalls.get()
            // Cross a real polling interval: a closed dialog must create no hidden XAPI sessions.
            kotlinx.coroutines.delay(6_500)
            compose.waitForIdle()
            assertEquals(callsAfterClose, detailCalls.get())
            assertNull(model.state.value.selectedGuest)
        } finally {
            compose.activityRule.scenario.onActivity { fixture?.viewModelScope?.cancel() }
            for (name in listOf(vaultName, journalName)) {
                for (suffix in listOf("", ".bak", ".new")) File(application.filesDir, name + suffix).delete()
            }
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
        }
    }
}
