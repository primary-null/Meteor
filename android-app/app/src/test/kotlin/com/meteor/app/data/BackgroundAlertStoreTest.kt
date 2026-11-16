package com.meteor.app.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Test

/**
 * The Preferences DataStore protocol held in memory. The real file storage cannot replace an existing file on the
 * Windows JVM test host, so the key handling of [BackgroundAlertDataStore] is tested against the same API in memory.
 */
private class MemoryPreferences : DataStore<Preferences> {
    private val lock = Mutex()
    override val data = MutableStateFlow(emptyPreferences())
    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
        lock.withLock { transform(data.value).also { data.value = it } }
}

/** B3 background/summary: the store's keys, scoping and removal, through the Preferences DataStore API. */
class BackgroundAlertStoreTest {
    private val prefs = MemoryPreferences()
    private val store = BackgroundAlertDataStore(prefs)
    private val origin = "d".repeat(64)
    private val t0 = 1_800_000_000_000L

    @Test fun preferencesAreKeyedByProfileAndDefaultToNothing() = runBlocking<Unit> {
        assertTrue(store.allSettings().isEmpty())
        store.setSettings("p1", AlertNotifySettings(true, NotifyThreshold.CRITICAL, 15, t0))
        store.setSettings("p2", AlertNotifySettings(false, NotifyThreshold.WARNING, 60))
        assertEquals(mapOf("p1" to AlertNotifySettings(true, NotifyThreshold.CRITICAL, 15, t0), "p2" to AlertNotifySettings(false, NotifyThreshold.WARNING, 60)),
            store.allSettings())
        assertThrows(IllegalArgumentException::class.java) { runBlocking { store.setSettings("bad id/../x", AlertNotifySettings()) } }
    }

    @Test fun watermarkOnlyMovesForwardForTheSameScope() = runBlocking<Unit> {
        store.setWatermark("p1", AlertWatermark(origin, "pool", t0, mapOf("r1" to t0)))
        store.setWatermark("p1", AlertWatermark(origin, "pool", t0 - 1))
        assertEquals(t0, store.watermark("p1")?.newest)
        assertEquals(mapOf("r1" to t0), store.watermark("p1")?.recent)
        store.setWatermark("p1", AlertWatermark(origin, "other-pool", t0 - 5))
        assertEquals("other-pool", store.watermark("p1")?.authority)
    }

    @Test fun removingAProfileRemovesAllItsDataAndNothingElse() = runBlocking<Unit> {
        listOf("p1", "p2").forEach { id ->
            store.setSettings(id, AlertNotifySettings(enabled = true, enabledAt = t0))
            store.setWatermark(id, AlertWatermark(origin, "pool", t0))
            store.setStatus(id, CheckStatus(t0, CheckOutcome.NOTHING_NEW, t0, 0))
            store.setAttention(id, "CERTIFICATE_CHANGED:x")
        }
        store.remove("p1")
        assertEquals(setOf("p2"), store.allSettings().keys)
        assertNull(store.watermark("p1")); assertNull(store.attention("p1"))
        assertEquals(setOf("p2"), store.statuses().keys)
        assertNotNull(store.watermark("p2")); assertEquals("CERTIFICATE_CHANGED:x", store.attention("p2"))
    }

    @Test fun retainOnlyPrunesForgottenProfiles() = runBlocking<Unit> {
        listOf("keep", "gone1", "gone2").forEach { store.setSettings(it, AlertNotifySettings(enabled = true, enabledAt = t0)); store.setStatus(it, CheckStatus(t0, CheckOutcome.FAILED, null, 1)) }
        store.setWatermark("gone1", AlertWatermark(origin, "pool", t0))
        assertEquals(setOf("gone1", "gone2"), store.retainOnly(setOf("keep")))
        assertEquals(setOf("keep"), store.allSettings().keys)
        assertEquals(setOf("keep"), store.statuses().keys)
        assertNull(store.watermark("gone1"))
        assertEquals(emptySet<String>(), store.retainOnly(setOf("keep")))
    }

    @Test fun resetProgressKeepsThePreferenceButForgetsTheWatermark() = runBlocking<Unit> {
        val setting = AlertNotifySettings(enabled = true, enabledAt = t0)
        store.setSettings("p1", setting); store.setWatermark("p1", AlertWatermark(origin, "pool", t0)); store.setStatus("p1", CheckStatus(t0, CheckOutcome.NOTHING_NEW, t0, 0))
        store.setAttention("p1", "AUTHENTICATION_FAILED:x")
        store.resetProgress("p1")
        assertEquals(setting, store.allSettings()["p1"])
        assertNull(store.watermark("p1")); assertNull(store.attention("p1")); assertTrue(store.statuses().isEmpty())
    }

    @Test fun theOutcomeOfTheLastCheckAndOfTheLastSuccessIsKeptPerProfile() = runBlocking<Unit> {
        assertTrue(store.statuses().isEmpty())
        val ok = CheckStatus(t0, CheckOutcome.NOTHING_NEW, t0, 0)
        store.setStatus("p1", ok)
        val failed = CheckStatus.after(ok, t0 + 1000, CheckOutcome.FAILED)
        store.setStatus("p1", failed); store.setStatus("p2", CheckStatus(t0, CheckOutcome.AUTHENTICATION_FAILED, null, 1))
        assertEquals(failed, store.statuses()["p1"])
        assertEquals(t0, store.statuses()["p1"]?.lastSuccessAt)
        assertEquals(CheckOutcome.AUTHENTICATION_FAILED, store.statuses()["p2"]?.outcome)
        assertEquals(store.statuses(), store.checks.first().statuses)
    }

    @Test fun aCheckTimeSavedByTheCurrentReleaseStillLoadsAndIsReplacedByTheNewStatus() = runBlocking<Unit> {
        prefs.edit { it[longPreferencesKey("checked.p1")] = t0 }
        assertEquals(CheckStatus(t0, null, null, 0), store.statuses()["p1"])
        store.setStatus("p1", CheckStatus(t0 + 5, CheckOutcome.NOTIFIED, t0 + 5, 0))
        assertEquals(CheckStatus(t0 + 5, CheckOutcome.NOTIFIED, t0 + 5, 0), store.statuses()["p1"])
        assertNull(prefs.data.first().asMap().keys.firstOrNull { it.name == "checked.p1" })
        // A bare time of a forgotten profile is pruned like the rest.
        prefs.edit { it[longPreferencesKey("checked.gone")] = t0 }
        assertEquals(setOf("gone"), store.retainOnly(setOf("p1")))
        assertTrue("gone" !in store.statuses())
    }

    @Test fun anUnreadableStatusReadsAsNothingRecorded() = runBlocking<Unit> {
        prefs.edit { it[stringPreferencesKey("status.p1")] = "garbage" }
        assertTrue(store.statuses().isEmpty())
    }

    @Test fun theLastRunNoteIsKeptOnceForAllProfilesAndSurvivesPruning() = runBlocking<Unit> {
        assertNull(store.runNote())
        store.setRunNote(RunNote(t0, RunProblem.VAULT_UNAVAILABLE))
        store.setSettings("p1", AlertNotifySettings(enabled = true, enabledAt = t0))
        store.retainOnly(emptySet())
        assertEquals(RunNote(t0, RunProblem.VAULT_UNAVAILABLE), store.runNote())
        assertEquals(RunProblem.VAULT_UNAVAILABLE, store.checks.first().runNote?.problem)
        store.setRunNote(RunNote(t0 + 1, RunProblem.OK))
        assertEquals(RunProblem.OK, store.runNote()?.problem)
    }

    @Test fun aWatermarkAheadOfTheServerCanBeRewoundButOnlyOnRequest() = runBlocking<Unit> {
        store.setWatermark("p1", AlertWatermark(origin, "pool", t0 + 3_600_000))
        store.setWatermark("p1", AlertWatermark(origin, "pool", t0))
        assertEquals(t0 + 3_600_000, store.watermark("p1")?.newest)
        store.setWatermark("p1", AlertWatermark(origin, "pool", t0), rewind = true)
        assertEquals(t0, store.watermark("p1")?.newest)
    }
}
