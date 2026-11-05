package com.meteor.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

private val Context.meteorPreferences by preferencesDataStore("meteor_appearance")
data class Appearance(val mode: String = "dark", val accent: String = "coral", val texture: Boolean = true, val icons: Map<String, String> = emptyMap())

class AppearanceStore(context: Context) {
    private val store = context.applicationContext.meteorPreferences
    private val mode = stringPreferencesKey("mode")
    private val accent = stringPreferencesKey("accent")
    private val texture = booleanPreferencesKey("texture")
    private val icons = stringPreferencesKey("icons")
    val flow = store.data.map { prefs ->
        val mapping = runCatching {
            val json = JSONObject(prefs[icons] ?: "{}")
            json.keys().asSequence().associateWith { json.getString(it) }
        }.getOrDefault(emptyMap())
        Appearance(prefs[mode]?.takeIf { it in listOf("system", "light", "dark") } ?: "dark",
            prefs[accent]?.takeIf { it in listOf("coral", "slate", "green", "silver") } ?: "coral",
            prefs[texture] ?: true, mapping)
    }
    suspend fun setMode(value: String) { require(value in listOf("system", "light", "dark")); store.edit { it[mode] = value } }
    suspend fun setAccent(value: String) { require(value in listOf("coral", "slate", "green", "silver")); store.edit { it[accent] = value } }
    suspend fun setTexture(value: Boolean) { store.edit { it[texture] = value } }
    /** Returns the icon token this identity used before, if any. */
    suspend fun setIcon(identity: String, icon: String): String? {
        var previous: String? = null
        store.edit { prefs ->
            val json = JSONObject(prefs[icons] ?: "{}")
            previous = json.optString(identity).takeIf { it.isNotBlank() }
            prefs[icons] = json.put(identity, icon).toString()
        }
        return previous
    }
}

class CustomIcons(private val context: Context) {
    private val directory get() = File(context.filesDir, "vm-icons").apply { mkdirs() }
    fun file(token: String): File? {
        val name = token.removePrefix("file:")
        return if (token.startsWith("file:") && name.matches(Regex("[a-f0-9-]{36}")))
            File(directory, "$name.png") else null
    }
    suspend fun import(uri: Uri): String = withContext(Dispatchers.IO) {
        val output = ByteArrayOutputStream()
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val buffer = ByteArray(16384)
            while (true) {
                val count = stream.read(buffer)
                if (count == -1) break
                require(output.size() + count <= 3 * 1024 * 1024) { "Choose an image under 3 MB." }
                output.write(buffer, 0, count)
            }
        } ?: error("Image could not be opened.")
        val bytes = output.toByteArray()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0 &&
            bounds.outWidth.toLong() * bounds.outHeight <= 20_000_000 && maxOf(bounds.outWidth, bounds.outHeight) <= 8192) {
            "Choose a smaller PNG, JPEG, or WebP image."
        }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 512) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: error("Image format is not supported.")
        val token = UUID.randomUUID().toString()
        val target = File(directory, "$token.png")
        try {
            target.outputStream().use { require(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) { "Image could not be saved." } }
        } catch (error: Exception) {
            target.delete(); throw error
        } finally { bitmap.recycle() }
        "file:$token"
    }
    /** Deletes an imported icon. Built-in icon names and malformed tokens are ignored. */
    suspend fun delete(token: String) = withContext(Dispatchers.IO) { file(token)?.delete() ?: false }
}

