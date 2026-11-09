package com.meteor.app

import android.app.Application
import android.text.InputType
import android.view.inputmethod.EditorInfo
import androidx.activity.compose.setContent
import androidx.lifecycle.viewModelScope
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextClearance
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
        compose.waitUntil(5_000) { runCatching {
            compose.onNodeWithText("Enter an HTTPS server address without a path or credentials.").assertIsDisplayed(); true
        }.getOrDefault(false) }
        compose.onNodeWithText("Enter an HTTPS server address without a path or credentials.").assertIsDisplayed()
    }

    @Test fun passwordFieldUsesPasswordKeyboardWithoutAutocorrection() {
        compose.onNodeWithText("Connect", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Password", substring = false).performScrollTo().performClick()
        compose.waitForIdle()
        compose.activityRule.scenario.onActivity { activity ->
            val focused = requireNotNull(activity.currentFocus)
            val info = EditorInfo()
            assertNotNull(focused.onCreateInputConnection(info))
            assertEquals(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
                info.inputType and (InputType.TYPE_MASK_CLASS or InputType.TYPE_MASK_VARIATION))
            assertEquals(0, info.inputType and InputType.TYPE_TEXT_FLAG_AUTO_CORRECT)
        }
    }

    @Test fun connectionEditorRenamesWithoutExposingPasswordsThenDisconnectsAndForgets() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val vaultName = "instrumentation-connection-ui.secured"
        val journalName = "instrumentation-connection-ui.json"
        val alias = "meteor.instrumentation.connection-ui"
        val profile = Profile("fixture", "Fixture", "https://test.invalid/", "tester", "fixture", "a".repeat(64))
        val reads = AtomicInteger()
        val backend = object : MeteorGateway {
            override suspend fun inventory(profile: Profile): Inventory {
                reads.incrementAndGet()
                return Inventory("pool", "Fixture pool", emptyList(), emptyList(), 0, 0, System.currentTimeMillis())
            }
            override suspend fun details(profile: Profile, authority: String, uuid: String): VmDetails = error("No detail reads expected")
            override suspend fun <T> powerConnection(profile: Profile, action: suspend PowerConnection.() -> T): T = error("No power requests expected")
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
            compose.onNodeWithText("Connect", useUnmergedTree = true).performClick()
            compose.onNodeWithText("Certificate", substring = false).performScrollTo().performClick()
            compose.onNodeWithText("Saved server certificate").assertIsDisplayed()
            compose.onNodeWithText("Done", substring = false).performClick()
            compose.onNodeWithText("Edit", substring = false).performScrollTo().performClick()
            compose.onNodeWithText(profile.password, substring = false).assertDoesNotExist()
            compose.onNodeWithText("Connection name").performScrollTo().performTextClearance()
            compose.onNodeWithText("Connection name").performTextInput("Renamed")
            compose.onNodeWithText("Save changes").performScrollTo().performClick()
            compose.waitUntil(15_000) { !model.state.value.profileBusy && model.state.value.profiles.single().label == "Renamed" }
            assertEquals(profile.copy(label = "Renamed"), vault.load().profiles.single())
            assertEquals(1, reads.get())
            compose.onNodeWithText("Disconnect", substring = false).performScrollTo().performClick()
            compose.waitUntil(15_000) { !model.state.value.profileBusy && model.state.value.selectedId == null }
            compose.onNodeWithText("Forget", substring = false).performScrollTo().performClick()
            compose.onNodeWithText("Forget Renamed?").assertIsDisplayed()
            compose.onNodeWithText("Forget connection", substring = false).performClick()
            compose.waitUntil(15_000) { !model.state.value.profileBusy && model.state.value.profiles.isEmpty() }
            assertTrue(vault.load().profiles.isEmpty())
        } finally {
            compose.activityRule.scenario.onActivity { fixture?.viewModelScope?.cancel() }
            for (name in listOf(vaultName, journalName)) {
                for (suffix in listOf("", ".bak", ".new")) File(application.filesDir, name + suffix).delete()
            }
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
        }
        Unit
    }

    @Test fun disposedEditorsCannotRestoreAnotherProfilesDraftOrACancelledSession() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val vaultName = "instrumentation-editor-ui.secured"
        val journalName = "instrumentation-editor-ui.json"
        val alias = "meteor.instrumentation.editor-ui"
        val profiles = (1..30).map { Profile("fixture-$it", "Fixture $it", "https://host-$it.invalid/", "tester-$it", "fixture", "a".repeat(64)) }
        val backend = object : MeteorGateway {
            override suspend fun inventory(profile: Profile) = Inventory("pool", "Fixture pool", emptyList(), emptyList(), 0, 0, System.currentTimeMillis())
            override suspend fun details(profile: Profile, authority: String, uuid: String): VmDetails = error("No detail reads expected")
            override suspend fun <T> powerConnection(profile: Profile, action: suspend PowerConnection.() -> T): T = error("No power requests expected")
        }
        val vault = ProfileVault(application, vaultName, alias)
        var fixture: MeteorViewModel? = null
        try {
            vault.save(ProfileDocument(profiles, profiles.first().id))
            compose.activityRule.scenario.onActivity { activity ->
                fixture = MeteorViewModel(application, backend, vault, OperationJournal(application, journalName))
                activity.setContent { MeteorApp(requireNotNull(fixture)) }
            }
            val model = requireNotNull(fixture)
            compose.waitUntil(15_000) { model.state.value.connected }
            compose.onNodeWithText("Connect", useUnmergedTree = true).performClick()
            compose.runOnIdle { model.editProfile(profiles.first().id) }
            compose.onNodeWithText("Connection name").performScrollTo().performTextClearance()
            compose.onNodeWithText("Connection name").performTextInput("Unsaved first draft")
            // Dispose the lazy editor before switching to the distant profile.
            compose.onNodeWithTag("connections-list").performScrollToNode(hasText(profiles.last().label, substring = false))
            compose.runOnIdle { model.editProfile(profiles.last().id) }
            compose.onNodeWithText("Connection name").performScrollTo().assertTextContains(profiles.last().label)
            compose.onNodeWithText("Server address").assertTextContains(profiles.last().endpoint)
            compose.onNodeWithText("Username").assertTextContains(profiles.last().username)
            compose.onNodeWithText("Save changes").performScrollTo().performClick()
            compose.waitUntil(15_000) { !model.state.value.profileBusy && model.state.value.editingProfileId == null }
            assertEquals(profiles, vault.load().profiles)
            compose.runOnIdle { model.editProfile(profiles.first().id) }
            compose.onNodeWithText("Connection name").performScrollTo().assertTextContains(profiles.first().label)
            compose.onNodeWithText("Connection name").performTextClearance()
            compose.onNodeWithText("Connection name").performTextInput("Cancelled draft")
            compose.runOnIdle { model.cancelEdit(); model.editProfile(profiles.first().id) }
            compose.onNodeWithText("Connection name").performScrollTo().assertTextContains(profiles.first().label)
        } finally {
            compose.activityRule.scenario.onActivity { fixture?.viewModelScope?.cancel() }
            for (name in listOf(vaultName, journalName)) {
                for (suffix in listOf("", ".bak", ".new")) File(application.filesDir, name + suffix).delete()
            }
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
        }
        Unit
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
