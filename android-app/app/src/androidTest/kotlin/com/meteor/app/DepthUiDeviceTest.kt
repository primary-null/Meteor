package com.meteor.app

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.meteor.app.data.*
import com.meteor.app.ui.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Offline presentation fixtures for the read-only history and detail screens. Nothing here contacts a server,
 * and no XAPI call is made: the actions are no-ops and every value comes from the fixture.
 */
@RunWith(AndroidJUnit4::class)
class DepthUiDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val now = 1_800_000_000_000L
    private val target = MetricTarget("pool", "host-1", MetricKind.HOST, "Example host")
    private val actions = DepthActions({}, {}, {}, {}, {}, {}, {})

    private fun show(depth: DepthState, content: @Composable () -> Unit) {
        compose.activityRule.scenario.onActivity { host -> host.setContent {
            MaterialTheme {
                Surface(color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground) {
                    CompositionLocalProvider(LocalDepthUi provides DepthUi(depth, "pool", true, true, actions)) { content() }
                }
            }
        } }
    }

    private fun snapshot(): HistorySnapshot {
        val times = listOf(1_799_999_990L, 1_799_999_995L, 1_800_000_000L)
        return HistorySnapshot(target, HistoryRange.TEN_MINUTES, 5, times, listOf(
            HistorySeries("CPU", "cpu_avg", "Average of physical CPUs", HistoryGroup.CPU, HistoryUnit.FRACTION, "fraction", listOf(.25, Double.NaN, .5)),
            HistorySeries("NIC eth0", "pif_eth0_rx", "Receive", HistoryGroup.NETWORK, HistoryUnit.BYTES_PER_SECOND, "bytes/s", listOf(1024.0, 2048.0, 4096.0)),
            HistorySeries("NIC eth0", "pif_eth0_tx", "Transmit", HistoryGroup.NETWORK, HistoryUnit.BYTES_PER_SECOND, "bytes/s", listOf(10.0, 20.0, 30.0))),
            now, 1_800_000_000L, listOf("Example note about the data."))
    }

    @Test fun historyShowsRangesChartsAndMissingDataExplicitly() {
        val history = HistoryState(target, HistoryRange.TEN_MINUTES, snapshot())
        val state = MeteorState(connected = true, metricTarget = target, depth = DepthState(history = history))
        show(state.depth) { PerformanceWithHistory(state, now, {}) { Text("live view fixture") } }
        compose.onNodeWithText("History · 10 minutes").assertIsDisplayed()
        compose.onNodeWithText("Live").assertIsDisplayed()
        compose.onNodeWithText("2 hours").assertIsDisplayed()
        compose.onNodeWithText("1 week").assertIsDisplayed()
        compose.onNodeWithText("live view fixture").assertDoesNotExist()
        for (text in listOf("Example note about the data.", "Average of physical CPUs", "Range 25.0% – 50.0%", "NIC eth0")) {
            compose.onNodeWithTag("history-list").performScrollToNode(hasText(text, substring = true))
            compose.onNodeWithText(text, substring = true).assertIsDisplayed()
        }
    }

    @Test fun liveStaysTheDefaultAndAFallbackExplainsWhyHistoryIsMissing() {
        val history = HistoryState(target, range = null, unavailable = "This VM runs on a pool member. Showing live values instead.")
        val state = MeteorState(connected = true, metricTarget = target, depth = DepthState(history = history))
        show(state.depth) { PerformanceWithHistory(state, now, {}) { Text("live view fixture") } }
        compose.onNodeWithText("live view fixture").assertIsDisplayed()
        compose.onNodeWithText("This VM runs on a pool member.", substring = true).assertIsDisplayed()
    }

    @Test fun vmSectionsShowFirmwareSnapshotsAndTheCopyableUuid() {
        val record = mapOf<String, Any?>("uuid" to "vm-uuid", "name_description" to "Fixture description", "HVM_boot_policy" to "BIOS order",
            "HVM_boot_params" to mapOf("firmware" to "uefi"), "platform" to mapOf("secureboot" to "true"), "VTPMs" to listOf("vtpm"),
            "other_config" to mapOf("auto_poweron" to "true"), "snapshots" to listOf("a", "b"), "VCPUs_max" to "4", "VCPUs_at_startup" to "2")
        val info = VmInfoMapper.map("pool", record, mapOf("other_config" to mapOf("auto_poweron" to "false")),
            listOf(mapOf("uuid" to "s1", "name_label" to "before upgrade", "is_a_snapshot" to true, "snapshot_time" to "20260101T10:00:00Z")),
            listOf(mapOf("type" to "CD", "userdevice" to "3", "empty" to true, "currently_attached" to true)), emptyMap(), now)
        val depth = DepthState(vm = InfoSlot("pool:vm-uuid", info))
        show(depth) { Column(androidx.compose.ui.Modifier.verticalScroll(rememberScrollState())) { VmDetailSections("vm-uuid") } }
        for (text in listOf("vm-uuid", "Fixture description", "UEFI", "Present", "pool-wide auto power-on flag is off"))
            compose.onNodeWithText(text, substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Copy").performScrollTo().performClick()
        compose.onNodeWithText("Copied").assertIsDisplayed()
    }

    @Test fun storageDetailWarnsWhenAHostDoesNotHaveTheRepositoryAttached() {
        val hosts = mapOf<String, Map<String, Any?>>("h1" to mapOf("name_label" to "Host A"), "h2" to mapOf("name_label" to "Host B"))
        val info = StorageMapper.map("pool", mapOf("uuid" to "sr-1", "name_label" to "Shared SR", "type" to "nfs", "shared" to true,
            "physical_size" to "1000", "physical_utilisation" to "400", "virtual_allocation" to "1500"), hosts,
            listOf(mapOf("host" to "h1", "currently_attached" to true), mapOf("host" to "h2", "currently_attached" to false)), emptyList(), 0, emptyMap(), emptyMap(), now)
        show(DepthState(storage = InfoSlot("pool:sr-1", info))) { Column(androidx.compose.ui.Modifier.verticalScroll(rememberScrollState())) { StorageDetail("sr-1") } }
        compose.onNodeWithText("Not attached on Host B", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Show repository detail").performClick()
        compose.onNodeWithText("Not attached on Host B", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Over-provisioned", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test fun networksScreenListsBridgesAndPerHostInterfaces() {
        val overview = NetworkMapper.map("pool", mapOf("h1" to mapOf("name_label" to "Host A")),
            mapOf("n1" to mapOf("uuid" to "net-1", "name_label" to "Pool-wide network", "bridge" to "xenbr0", "MTU" to "1500", "PIFs" to listOf("p1"), "VIFs" to listOf("v1"))),
            mapOf("p1" to mapOf("host" to "h1", "device" to "eth0", "VLAN" to "100", "currently_attached" to true, "metrics" to "m1")),
            mapOf("m1" to mapOf("carrier" to false)), now)
        show(DepthState(networks = InfoSlot("pool:networks", overview))) { NetworksScreen {} }
        compose.onNodeWithText("Pool-wide network").assertIsDisplayed()
        compose.onNodeWithText("xenbr0 · MTU 1500").assertIsDisplayed()
        compose.onNodeWithText("Host A · eth0 · VLAN 100", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Link down", substring = true).assertIsDisplayed()
    }
}
