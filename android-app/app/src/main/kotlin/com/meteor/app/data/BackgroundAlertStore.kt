package com.meteor.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

// --- B3 background/summary ---

/**
 * Local, per-profile background-alert data, keyed by profile ID and kept outside the encrypted profile document:
 * the notification preference, the last-notified watermark (server timestamps and message references only, no
 * names or bodies), the last check time and the "needs attention" marker. Removing a profile removes all of it.
 * The A4 in-app "seen" value is a different store and is never written here.
 */
interface BackgroundAlertStore {
    val settings: Flow<Map<String, AlertNotifySettings>>
    suspend fun allSettings(): Map<String, AlertNotifySettings> = settings.first()
    suspend fun setSettings(profileId: String, value: AlertNotifySettings)
    suspend fun watermark(profileId: String): AlertWatermark?
    suspend fun setWatermark(profileId: String, value: AlertWatermark)
    suspend fun lastChecked(): Map<String, Long>
    suspend fun setLastChecked(profileId: String, millis: Long)
    suspend fun attention(profileId: String): String?
    suspend fun setAttention(profileId: String, marker: String?)
    /** Forgets the watermark, last check and attention marker, so the next check starts from a new enable time. */
    suspend fun resetProgress(profileId: String)
    /** Removes everything stored for [profileId]. */
    suspend fun remove(profileId: String)
    /** Removes everything stored for profiles that are no longer saved. Returns the removed IDs. */
    suspend fun retainOnly(profileIds: Set<String>): Set<String>
}

private val Context.meteorBackgroundAlerts by preferencesDataStore("meteor_background_alerts")

class BackgroundAlertDataStore(private val store: DataStore<Preferences>) : BackgroundAlertStore {
    constructor(context: Context) : this(context.applicationContext.meteorBackgroundAlerts)

    private companion object {
        const val NOTIFY = "notify."
        const val MARK = "mark."
        const val CHECKED = "checked."
        const val ATTENTION = "attention."
        val PREFIXES = listOf(NOTIFY, MARK, CHECKED, ATTENTION)
        val ID = Regex("[A-Za-z0-9._:-]{1,120}")
    }
    private fun id(profileId: String) = profileId.also { require(it.matches(ID)) { "Invalid profile identity." } }
    private fun notifyKey(id: String) = stringPreferencesKey(NOTIFY + id(id))
    private fun markKey(id: String) = stringPreferencesKey(MARK + id(id))
    private fun checkedKey(id: String) = longPreferencesKey(CHECKED + id(id))
    private fun attentionKey(id: String) = stringPreferencesKey(ATTENTION + id(id))

    override val settings: Flow<Map<String, AlertNotifySettings>> = store.data.map { preferences ->
        preferences.asMap().entries.filter { it.key.name.startsWith(NOTIFY) }
            .associate { it.key.name.removePrefix(NOTIFY) to AlertNotifySettings.decode(it.value as? String) }
    }
    override suspend fun setSettings(profileId: String, value: AlertNotifySettings) {
        store.edit { it[notifyKey(profileId)] = AlertNotifySettings.encode(value) }
    }
    override suspend fun watermark(profileId: String): AlertWatermark? = AlertWatermark.decode(store.data.first()[markKey(profileId)])
    /** Forward-only for the same pool; a watermark of a different pool replaces it. */
    override suspend fun setWatermark(profileId: String, value: AlertWatermark) {
        store.edit { preferences ->
            val previous = AlertWatermark.decode(preferences[markKey(profileId)])
            val sameScope = previous != null && previous.authority == value.authority
            if (!sameScope || value.newest >= previous.newest) preferences[markKey(profileId)] = AlertWatermark.encode(value)
        }
    }
    override suspend fun lastChecked(): Map<String, Long> = store.data.first().asMap().entries
        .filter { it.key.name.startsWith(CHECKED) }.mapNotNull { entry -> (entry.value as? Long)?.let { entry.key.name.removePrefix(CHECKED) to it } }.toMap()
    override suspend fun setLastChecked(profileId: String, millis: Long) { store.edit { it[checkedKey(profileId)] = millis } }
    override suspend fun attention(profileId: String): String? = store.data.first()[attentionKey(profileId)]
    override suspend fun setAttention(profileId: String, marker: String?) {
        store.edit { if (marker == null) it.remove(attentionKey(profileId)) else it[attentionKey(profileId)] = marker }
    }
    override suspend fun resetProgress(profileId: String) {
        store.edit { preferences ->
            preferences.remove(markKey(profileId)); preferences.remove(checkedKey(profileId)); preferences.remove(attentionKey(profileId))
        }
    }
    override suspend fun remove(profileId: String) {
        store.edit { preferences ->
            preferences.remove(notifyKey(profileId)); preferences.remove(markKey(profileId))
            preferences.remove(checkedKey(profileId)); preferences.remove(attentionKey(profileId))
        }
    }
    override suspend fun retainOnly(profileIds: Set<String>): Set<String> {
        val removed = mutableSetOf<String>()
        store.edit { preferences ->
            preferences.asMap().keys.toList().forEach { key ->
                val prefix = PREFIXES.firstOrNull { key.name.startsWith(it) } ?: return@forEach
                val owner = key.name.removePrefix(prefix)
                if (owner !in profileIds) { preferences.remove(key); removed += owner }
            }
        }
        return removed
    }
}
// --- end B3 ---
