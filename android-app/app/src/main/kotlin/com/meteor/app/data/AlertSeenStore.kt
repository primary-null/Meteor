package com.meteor.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

// --- A4 alerts/events ---

/**
 * The only alert data written to disk: per profile, the server-clock timestamp of the newest alert that was on
 * screen at the last view. No message names, bodies or object ids are persisted. Like every private file it is
 * excluded from Android backup and device transfer by the existing rules.
 */
interface AlertSeenStore {
    suspend fun lastSeen(profileId: String): Long?
    suspend fun save(profileId: String, millis: Long)
    // --- vault recovery ---
    /** Removes the value of every profile that is no longer saved, as the background store does. Returns the removed profile IDs. */
    suspend fun retainOnly(profileIds: Set<String>): Set<String> = emptySet()
    // --- end vault recovery ---
}

private val Context.meteorAlertSeen by preferencesDataStore("meteor_alert_seen")

class AlertSeenDataStore(private val store: DataStore<Preferences>) : AlertSeenStore {
    constructor(context: Context) : this(context.applicationContext.meteorAlertSeen)
    private companion object { const val SEEN = "seen." }
    private fun key(profileId: String) = longPreferencesKey("$SEEN$profileId")
    override suspend fun lastSeen(profileId: String): Long? = store.data.first()[key(profileId)]
    /** Only moves forward. */
    override suspend fun save(profileId: String, millis: Long) {
        store.edit { preferences ->
            val previous = preferences[key(profileId)]
            if (previous == null || millis > previous) preferences[key(profileId)] = millis
        }
    }
    // --- vault recovery ---
    override suspend fun retainOnly(profileIds: Set<String>): Set<String> {
        val removed = mutableSetOf<String>()
        store.edit { preferences ->
            preferences.asMap().keys.toList().forEach { key ->
                if (!key.name.startsWith(SEEN)) return@forEach
                val owner = key.name.removePrefix(SEEN)
                if (owner !in profileIds) { preferences.remove(key); removed += owner }
            }
        }
        return removed
    }
    // --- end vault recovery ---
}
// --- end A4 ---
