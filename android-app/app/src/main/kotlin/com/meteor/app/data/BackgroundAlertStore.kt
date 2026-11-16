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
 * names or bodies), the outcome and time of the last check and of the last successful one, and the "needs attention" marker.
 * Removing a profile removes all of it.
 * The A4 in-app "seen" value is a different store and is never written here.
 */
interface BackgroundAlertStore {
    val settings: Flow<Map<String, AlertNotifySettings>>
    suspend fun allSettings(): Map<String, AlertNotifySettings> = settings.first()
    suspend fun setSettings(profileId: String, value: AlertNotifySettings)
    suspend fun watermark(profileId: String): AlertWatermark?
    /**
     * Saves the watermark. For the same pool it only moves forward, unless [rewind]: the saved one was ahead of the server's
     * own clock (see [BackgroundAlerts.start]) and the new one is deliberately earlier.
     */
    suspend fun setWatermark(profileId: String, value: AlertWatermark, rewind: Boolean = false)
    /** The last check of each profile. A bare time saved by an earlier release reads as a status without an outcome. */
    suspend fun statuses(): Map<String, CheckStatus>
    suspend fun setStatus(profileId: String, value: CheckStatus)
    suspend fun runNote(): RunNote?
    suspend fun setRunNote(value: RunNote)
    /** What Settings shows: every profile's status and the last run's note, updated as the worker writes them. */
    val checks: Flow<BackgroundChecks>
    suspend fun attention(profileId: String): String?
    suspend fun setAttention(profileId: String, marker: String?)
    /** Forgets the watermark, check history and attention marker, so the next check starts from a new enable time. */
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
        /** Written by earlier releases: the time of the last attempt, nothing else. Read as a fallback, removed with the profile. */
        const val CHECKED = "checked."
        const val STATUS = "status."
        const val ATTENTION = "attention."
        const val RUN = "run.last"
        val PREFIXES = listOf(NOTIFY, MARK, CHECKED, STATUS, ATTENTION)
        val ID = Regex("[A-Za-z0-9._:-]{1,120}")
    }
    private fun id(profileId: String) = profileId.also { require(it.matches(ID)) { "Invalid profile identity." } }
    private fun notifyKey(id: String) = stringPreferencesKey(NOTIFY + id(id))
    private fun markKey(id: String) = stringPreferencesKey(MARK + id(id))
    private fun checkedKey(id: String) = longPreferencesKey(CHECKED + id(id))
    private fun statusKey(id: String) = stringPreferencesKey(STATUS + id(id))
    private val runKey = stringPreferencesKey(RUN)
    private fun attentionKey(id: String) = stringPreferencesKey(ATTENTION + id(id))

    override val settings: Flow<Map<String, AlertNotifySettings>> = store.data.map { preferences ->
        preferences.asMap().entries.filter { it.key.name.startsWith(NOTIFY) }
            .associate { it.key.name.removePrefix(NOTIFY) to AlertNotifySettings.decode(it.value as? String) }
    }
    override suspend fun setSettings(profileId: String, value: AlertNotifySettings) {
        store.edit { it[notifyKey(profileId)] = AlertNotifySettings.encode(value) }
    }
    override suspend fun watermark(profileId: String): AlertWatermark? = AlertWatermark.decode(store.data.first()[markKey(profileId)])
    /** Forward-only for the same pool (unless [rewind]); a watermark of a different pool replaces it. */
    override suspend fun setWatermark(profileId: String, value: AlertWatermark, rewind: Boolean) {
        store.edit { preferences ->
            val previous = AlertWatermark.decode(preferences[markKey(profileId)])
            val sameScope = previous != null && previous.authority == value.authority
            if (!sameScope || rewind || value.newest >= previous.newest) preferences[markKey(profileId)] = AlertWatermark.encode(value)
        }
    }
    private fun readStatuses(preferences: Preferences): Map<String, CheckStatus> {
        val entries = preferences.asMap().entries
        val legacy = entries.filter { it.key.name.startsWith(CHECKED) }.mapNotNull { entry ->
            (entry.value as? Long)?.let { entry.key.name.removePrefix(CHECKED) to CheckStatus(it, null, null) }
        }.toMap()
        val current = entries.filter { it.key.name.startsWith(STATUS) }.mapNotNull { entry ->
            CheckStatus.decode(entry.value as? String)?.let { entry.key.name.removePrefix(STATUS) to it }
        }.toMap()
        return legacy + current
    }
    override suspend fun statuses(): Map<String, CheckStatus> = readStatuses(store.data.first())
    override suspend fun setStatus(profileId: String, value: CheckStatus) {
        store.edit { it[statusKey(profileId)] = CheckStatus.encode(value); it.remove(checkedKey(profileId)) }
    }
    override suspend fun runNote(): RunNote? = RunNote.decode(store.data.first()[runKey])
    override suspend fun setRunNote(value: RunNote) { store.edit { it[runKey] = RunNote.encode(value) } }
    override val checks: Flow<BackgroundChecks> = store.data.map { BackgroundChecks(readStatuses(it), RunNote.decode(it[runKey])) }
    override suspend fun attention(profileId: String): String? = store.data.first()[attentionKey(profileId)]
    override suspend fun setAttention(profileId: String, marker: String?) {
        store.edit { if (marker == null) it.remove(attentionKey(profileId)) else it[attentionKey(profileId)] = marker }
    }
    override suspend fun resetProgress(profileId: String) {
        store.edit { preferences ->
            preferences.remove(markKey(profileId)); preferences.remove(checkedKey(profileId)); preferences.remove(statusKey(profileId))
            preferences.remove(attentionKey(profileId))
        }
    }
    override suspend fun remove(profileId: String) {
        store.edit { preferences ->
            preferences.remove(notifyKey(profileId)); preferences.remove(markKey(profileId))
            preferences.remove(checkedKey(profileId)); preferences.remove(statusKey(profileId)); preferences.remove(attentionKey(profileId))
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
