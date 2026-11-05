package com.meteor.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.meteor.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class ProfileDocument(val profiles: List<Profile> = emptyList(), val selectedId: String? = null) {
    companion object { const val MAX_PROFILES = 50 }
    init {
        require(profiles.size <= MAX_PROFILES) { "Too many profiles." }
        require(profiles.all { it.id.isNotBlank() }) { "Missing profile identity." }
        require(profiles.map { it.id }.distinct().size == profiles.size) { "Duplicate profile identity." }
        require(selectedId == null || profiles.any { it.id == selectedId }) { "Selected profile is missing." }
    }
}

class ProfileVault(
    context: Context,
    fileName: String = "profiles.secured",
    private val alias: String = "meteor.profiles.v1",
) {
    init { require(fileName.matches(Regex("[A-Za-z0-9._-]+"))) }
    private val file = AtomicFile(File(context.filesDir, fileName))
    private val aad = "Meteor.profile.v1".toByteArray()
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    private fun profile(json: JSONObject) = Profile(
        json.getString("id"), json.getString("label"), Endpoints.normalize(json.getString("endpoint")),
        json.getString("username"), json.getString("password"), json.getString("certificateSha256"),
    ).also {
        require(it.certificateSha256.matches(Regex("[0-9a-f]{64}")))
        require(it.id.isNotBlank() && it.username.isNotBlank())
    }
    private fun json(profile: Profile) = JSONObject().put("id", profile.id).put("label", profile.label)
        .put("endpoint", profile.endpoint).put("username", profile.username)
        .put("password", profile.password).put("certificateSha256", profile.certificateSha256)

    suspend fun load(): ProfileDocument = withContext(Dispatchers.IO) {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return@withContext ProfileDocument()
        val encoded = file.openRead().use { stream ->
            val output = ByteArrayOutputStream()
            val chunk = ByteArray(8192)
            while (true) {
                val count = stream.read(chunk)
                if (count == -1) break
                require(output.size() + count <= 262144) { "Invalid vault size." }
                output.write(chunk, 0, count)
            }
            val bytes = output.toByteArray()
            require(bytes.size in 30..262144) { "Invalid vault size." }
            bytes
        }
        val buffer = ByteBuffer.wrap(encoded)
        require(buffer.int == 0x4d455431) { "Unknown vault format." }
        val iv = ByteArray(12).also { buffer.get(it) }
        val payload = ByteArray(buffer.remaining()).also { buffer.get(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv)); cipher.updateAAD(aad)
        val root = JSONObject(String(cipher.doFinal(payload), Charsets.UTF_8))
        require(root.getInt("version") == 1)
        val list = root.getJSONArray("profiles")
        require(list.length() <= 50)
        val profiles = (0 until list.length()).map { profile(list.getJSONObject(it)) }
        ProfileDocument(profiles, root.optString("selectedId").takeIf { selected ->
            selected.isNotBlank() && profiles.any { it.id == selected }
        })
    }

    suspend fun save(document: ProfileDocument) = withContext(Dispatchers.IO) {
        require(document.profiles.size <= 50)
        val root = JSONObject().put("version", 1).put("selectedId", document.selectedId.orEmpty())
            .put("profiles", JSONArray().apply { document.profiles.forEach { put(json(it)) } })
        val plain = root.toString().toByteArray()
        require(plain.size < 200000)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key()); cipher.updateAAD(aad)
        val payload = cipher.doFinal(plain)
        require(cipher.iv.size == 12)
        val encoded = ByteBuffer.allocate(4 + 12 + payload.size).putInt(0x4d455431).put(cipher.iv).put(payload).array()
        val stream = file.startWrite()
        try { stream.write(encoded); file.finishWrite(stream) }
        catch (error: Exception) { file.failWrite(stream); throw error }
    }

    suspend fun importDebugBootstrap(context: Context): Boolean = withContext(Dispatchers.IO) {
        if (!BuildConfig.DEBUG) return@withContext false
        val source = File(context.filesDir, "bootstrap.json")
        if (!source.exists()) return@withContext false
        require(source.length() < 16384)
        val imported = profile(JSONObject(source.readText()))
        // Debug builds only: lets a test harness provision a profile through app-private storage.
        // The certificate pin is supplied out of band and validated like any saved profile.
        val previous = load()
        save(ProfileDocument(previous.profiles.filterNot { it.id == imported.id } + imported, imported.id))
        check(source.delete()) { "Bootstrap cleanup failed." }
        true
    }
}

