package com.meteor.app.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.MutableStateFlow
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
    private val store = BackgroundAlertDataStore(MemoryPreferences())
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
            store.setLastChecked(id, t0)
            store.setAttention(id, "CERTIFICATE_CHANGED:x")
        }
        store.remove("p1")
        assertEquals(setOf("p2"), store.allSettings().keys)
        assertNull(store.watermark("p1")); assertNull(store.attention("p1"))
        assertEquals(mapOf("p2" to t0), store.lastChecked())
        assertNotNull(store.watermark("p2")); assertEquals("CERTIFICATE_CHANGED:x", store.attention("p2"))
    }

    @Test fun retainOnlyPrunesForgottenProfiles() = runBlocking<Unit> {
        listOf("keep", "gone1", "gone2").forEach { store.setSettings(it, AlertNotifySettings(enabled = true, enabledAt = t0)); store.setLastChecked(it, t0) }
        store.setWatermark("gone1", AlertWatermark(origin, "pool", t0))
        assertEquals(setOf("gone1", "gone2"), store.retainOnly(setOf("keep")))
        assertEquals(setOf("keep"), store.allSettings().keys)
        assertEquals(setOf("keep"), store.lastChecked().keys)
        assertNull(store.watermark("gone1"))
        assertEquals(emptySet<String>(), store.retainOnly(setOf("keep")))
    }

    @Test fun resetProgressKeepsThePreferenceButForgetsTheWatermark() = runBlocking<Unit> {
        val setting = AlertNotifySettings(enabled = true, enabledAt = t0)
        store.setSettings("p1", setting); store.setWatermark("p1", AlertWatermark(origin, "pool", t0)); store.setLastChecked("p1", t0)
        store.setAttention("p1", "AUTHENTICATION_FAILED:x")
        store.resetProgress("p1")
        assertEquals(setting, store.allSettings()["p1"])
        assertNull(store.watermark("p1")); assertNull(store.attention("p1")); assertTrue(store.lastChecked().isEmpty())
    }
}
