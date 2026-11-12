package com.meteor.app.data

import android.content.Context
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
}

private val Context.meteorAlertSeen by preferencesDataStore("meteor_alert_seen")

class AlertSeenDataStore(context: Context) : AlertSeenStore {
    private val store = context.applicationContext.meteorAlertSeen
    private fun key(profileId: String) = longPreferencesKey("seen.$profileId")
    override suspend fun lastSeen(profileId: String): Long? = store.data.first()[key(profileId)]
    /** Only moves forward. */
    override suspend fun save(profileId: String, millis: Long) {
        store.edit { preferences ->
            val previous = preferences[key(profileId)]
            if (previous == null || millis > previous) preferences[key(profileId)] = millis
        }
    }
}
// --- end A4 ---
