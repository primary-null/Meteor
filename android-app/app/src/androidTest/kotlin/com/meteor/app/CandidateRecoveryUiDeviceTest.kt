package com.meteor.app

import android.app.Application
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.meteor.app.data.*
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore

/** Isolated synthetic operation history; never uses the user's saved profiles or a server. */
@RunWith(AndroidJUnit4::class)
class CandidateRecoveryUiDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun repairedConnectionCanCloseReviewedUnknownRecordWithoutSendingPower() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val vaultName = "instrumentation-review.secured"
        val journalName = "instrumentation-review.json"
        val alias = "meteor.instrumentation.review"
        val original = Profile("fixture", "Fixture", "https://test.invalid/", "tester", "fixture", "a".repeat(64))
        val repaired = original.copy(certificateSha256 = "b".repeat(64))
        val pending = PowerOperation("pending", original.id, original.origin(), "pool", "vm", "Fixture VM",
            PowerAction.START, null, PowerPhase.UNKNOWN, 1, 1)
        var sends = 0
        var reads = 0
        val connection = object : PowerConnection {
            override suspend fun details(authority: String, uuid: String): VmDetails {
                reads++
                return VmDetails("pool", "vm", "Fixture VM", "", "Running", null, 1, 1024, emptyList(), emptyList(),
                    false, null, emptyList(), "20260101T00:00:00Z", emptySet(), false, System.currentTimeMillis())
            }
            override suspend fun submit(authority: String, uuid: String, action: PowerAction): String { sends++; error("No power command permitted") }
            override suspend fun taskUuid(reference: String): String = error("Manual review does not resolve task identity")
            override suspend fun taskReference(uuid: String): String = error("Manual review does not resolve task identity")
            override suspend fun observeTask(reference: String): TaskObservation = error("Manual review does not claim task success")
        }
        val backend = object : MeteorGateway {
            override suspend fun inventory(profile: Profile) = Inventory("pool", "Fixture", emptyList(), emptyList(), 0, 0, System.currentTimeMillis())
            override suspend fun details(profile: Profile, authority: String, uuid: String) = connection.details(authority, uuid)
            override suspend fun <T> powerConnection(profile: Profile, action: suspend PowerConnection.() -> T) = action(connection)
        }
        val vault = ProfileVault(application, vaultName, alias)
        val journal = OperationJournal(application, journalName)
        var fixture: MeteorViewModel? = null
        try {
            vault.save(ProfileDocument(listOf(repaired), repaired.id))
            journal.put(pending)
            compose.activityRule.scenario.onActivity { activity ->
                fixture = MeteorViewModel(application, backend, vault, journal)
                activity.setContent { MeteorApp(requireNotNull(fixture)) }
            }
            val model = requireNotNull(fixture)
            compose.waitUntil(15_000) { model.state.value.connected && !model.state.value.operationBusy }
            compose.onNodeWithText("Review pending action").performClick()
            compose.waitUntil(15_000) { model.state.value.pendingReview != null && !model.state.value.operationBusy }
            compose.onNodeWithText("Review Fixture VM").assertIsDisplayed()
            compose.onNodeWithText("Close reviewed record").assertIsNotEnabled()
            compose.onNodeWithText("Keep pending").performClick()
            assertTrue(journal.load().single().unresolved)
            compose.onNodeWithText("Review pending action").performClick()
            compose.waitUntil(15_000) { model.state.value.pendingReview != null && !model.state.value.operationBusy }
            compose.onNode(isToggleable()).performClick()
            compose.onNodeWithText("Close reviewed record").performClick()
            compose.waitUntil(15_000) { model.state.value.operation?.manualResolution != null && !model.state.value.operationBusy }
            val closed = journal.load().single()
            assertFalse(closed.unresolved); assertEquals(PowerPhase.UNKNOWN, closed.phase)
            assertEquals(original.origin(), closed.origin)
            assertEquals(repaired.origin(), closed.manualResolution?.origin)
            assertEquals(0, sends); assertEquals(3, reads)
        } finally {
            compose.activityRule.scenario.onActivity { fixture?.viewModelScope?.cancel() }
            for (name in listOf(vaultName, journalName)) for (suffix in listOf("", ".bak", ".new")) File(application.filesDir, name + suffix).delete()
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
        }
    }
}
