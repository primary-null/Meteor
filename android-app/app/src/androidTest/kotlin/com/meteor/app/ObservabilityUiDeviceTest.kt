package com.meteor.app

import android.graphics.Bitmap
import android.app.Application
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ApplicationProvider
import androidx.lifecycle.viewModelScope
import com.meteor.app.data.*
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore

/** Offline read-only presentation fixtures; never contacts a server or changes the power journal. */
@RunWith(AndroidJUnit4::class)
class ObservabilityUiDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val now = 1_800_000_000_000L
    private val target = MetricTarget("example-pool", "example-vm", MetricKind.VM, "Example VM")
    private fun show(state: MeteorState, activity: Boolean = false) {
        compose.activityRule.scenario.onActivity { host -> host.setContent {
            MaterialTheme {
                Surface(color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground) {
                    if (activity) PoolActivityScreen(state, now, {}, {}) else PerformanceScreen(state, now, {}, {})
                }
            }
        } }
    }
    private fun capture(name: String) {
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        File(compose.activity.getExternalFilesDir(null), "$name.png").outputStream().use {
            image.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
    @Test fun performanceKeepsMissingValuesAndStaleSamplesExplicit() {
        val readings = listOf(MetricReading("cpu0", "CPU 0", "fraction", MetricGroup.CPU, .25),
            MetricReading("memory_internal_free", "Guest-reported free memory", "bytes", MetricGroup.MEMORY, null, "No valid sample"))
        val sample = MetricSnapshot(target, now - 20_000, readings, 2)
        show(MeteorState(connected = true, metricTarget = target, metricSamples = listOf(sample)))
        compose.onNodeWithText("Live performance").assertIsDisplayed()
        compose.onNodeWithText("Last-known sample", substring = true).assertIsDisplayed()
        compose.onNodeWithText("25.0%").assertIsDisplayed()
        capture("performance-fixture")
        compose.onNodeWithText("Unavailable", substring = false).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("No valid sample").assertIsDisplayed()
    }
    @Test fun pendingAtFullProgressStaysInProgressAndAlertsAreCollapsed() {
        val task = PoolTask("task", "Example migration", "pending", 1.0, now - 10_000, null, null)
        val alert = PoolAlert("alert", "Storage warning", 2, "SR", "example-storage", now, "Example detail body")
        show(MeteorState(connected = true, poolActivity = PoolActivity("example-pool", now,
            ReadSection(listOf(task)), ReadSection(listOf(alert)))), activity = true)
        compose.onNodeWithText("In progress", substring = false).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Estimated progress: 100%", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Server reported success").assertDoesNotExist()
        capture("activity-fixture")
        compose.onNodeWithText("Alerts", substring = false).performScrollTo().performClick()
        compose.onNodeWithText("Storage warning").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Example detail body").assertDoesNotExist()
        compose.onNodeWithText("Show alert details").performScrollTo().performClick()
        compose.onNodeWithText("Example detail body").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Hide alert details").performScrollTo().performClick()
        compose.onNodeWithText("Example detail body").assertDoesNotExist()
    }
    @Test fun forbiddenTasksAreNotMisreportedAsAnEmptyFeed() {
        show(MeteorState(connected = true, poolActivity = PoolActivity("example-pool", now,
            ReadSection(emptyList(), issue = ReadIssue.FORBIDDEN), ReadSection(emptyList()))), activity = true)
        compose.onNodeWithText("Not available to this account").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("No active tasks reported.").assertDoesNotExist()
        compose.onNodeWithText("Alerts", substring = false).performScrollTo().performClick()
        compose.onNodeWithText("No alerts reported.").performScrollTo().assertIsDisplayed()
    }
    @Test fun overviewAndVmEntryPointsReadMetricsAndActivityWithoutPowerRequests() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val vaultName = "instrumentation-observation-ui.secured"
        val journalName = "instrumentation-observation-ui.json"
        val alias = "meteor.instrumentation.observation-ui"
        val profile = Profile("example", "Example", "https://example.invalid/", "tester", "fixture", "a".repeat(64))
        val guest = Guest(target.uuid, target.name, "Running", target.authority, "example-host", 2, 4096)
        val gateway = object : MeteorGateway {
            override suspend fun inventory(profile: Profile) = Inventory(target.authority, "Example pool",
                listOf(Host("example-host", "Example host", true, 8192, 4096)), listOf(guest), 0, 0, System.currentTimeMillis())
            override suspend fun details(profile: Profile, authority: String, uuid: String) = VmDetails(authority, uuid, target.name, "", "Running",
                "Example host", 2, 4096, emptyList(), emptyList(), true, null, emptyList(), "20260101T00:00:00Z",
                emptySet(), false, System.currentTimeMillis())
            override suspend fun metrics(profile: Profile, target: MetricTarget) = MetricSnapshot(target, System.currentTimeMillis(),
                listOf(MetricReading("cpu0", "CPU 0", "fraction", MetricGroup.CPU, .25)), 1)
            override suspend fun activity(profile: Profile, authority: String) = PoolActivity(authority, System.currentTimeMillis(),
                ReadSection(emptyList()), ReadSection(emptyList()))
            override suspend fun <T> powerConnection(profile: Profile, action: suspend PowerConnection.() -> T): T = error("No power request expected")
        }
        val vault = ProfileVault(application, vaultName, alias)
        var model: MeteorViewModel? = null
        try {
            vault.save(ProfileDocument(listOf(profile), profile.id))
            compose.activityRule.scenario.onActivity { host ->
                model = MeteorViewModel(application, gateway, vault, OperationJournal(application, journalName))
                host.setContent { MeteorApp(requireNotNull(model)) }
            }
            compose.waitUntil(15_000) { model?.state?.value?.connected == true }
            compose.onNodeWithTag("overview-list").performScrollToNode(hasText("Host performance"))
            compose.onNodeWithText("Host performance").performClick()
            compose.onNodeWithText("Live performance").assertIsDisplayed()
            compose.waitUntil(15_000) { model?.state?.value?.metricSamples?.isNotEmpty() == true }
            compose.onNodeWithText("Latest sample", substring = true).assertIsDisplayed()
            compose.onNodeWithText("Back", substring = false).performClick()
            compose.onNodeWithText("VMs", useUnmergedTree = true).performClick()
            compose.onNodeWithTag("fleet-list").performScrollToNode(hasText(target.name))
            // A running VM's row opens its console; its Details button opens the details.
            compose.onNodeWithContentDescription("Details for ${target.name}").performClick()
            compose.onNodeWithText("VM performance").performScrollTo().performClick()
            compose.waitUntil(15_000) { model?.state?.value?.metricTarget?.kind == MetricKind.VM && model?.state?.value?.metricsBusy == false }
            compose.onNodeWithText("Live performance").assertIsDisplayed()
            compose.onNodeWithText("Latest sample", substring = true).assertIsDisplayed()
            capture("performance-app-fixture")
            compose.onNodeWithText("Overview", useUnmergedTree = true).performClick()
            compose.onNodeWithTag("overview-list").performScrollToNode(hasText("Pool activity"))
            compose.onNodeWithText("Pool activity", substring = false).performClick()
            compose.waitUntil(15_000) { model?.state?.value?.poolActivity != null }
            compose.onNodeWithTag("activity-list").performScrollToNode(hasText("Server snapshot", substring = true))
            compose.onNodeWithText("Server snapshot", substring = true).assertIsDisplayed()
            compose.onNodeWithText("No active tasks reported.").performScrollTo().assertIsDisplayed()
            capture("activity-app-fixture")
        } finally {
            compose.activityRule.scenario.onActivity { model?.viewModelScope?.cancel() }
            for (name in listOf(vaultName, journalName)) for (suffix in listOf("", ".bak", ".new")) File(application.filesDir, name + suffix).delete()
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
        }
        Unit
    }
}
