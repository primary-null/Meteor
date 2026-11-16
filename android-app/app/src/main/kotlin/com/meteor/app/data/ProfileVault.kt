package com.meteor.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.meteor.app.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
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

interface ProfileStorage {
    suspend fun load(): ProfileDocument
    suspend fun save(document: ProfileDocument)
    suspend fun importDebugBootstrap(context: Context): Boolean = false
    // --- vault recovery ---
    /**
     * Sets the unreadable vault aside (renamed, never deleted), replaces its key and leaves an empty, writable vault. Never touches
     * the operation journal. Returns true when a file was set aside. Implemented by [ProfileVault] only.
     */
    suspend fun startOver(): Boolean = throw UnsupportedOperationException("This storage cannot start over.")
    // --- end vault recovery ---
}

class ProfileVault(
    context: Context,
    fileName: String = "profiles.secured",
    private val alias: String = "meteor.profiles.v1",
) : ProfileStorage {
    init { require(fileName.matches(Regex("[A-Za-z0-9._-]+"))) }
    private val file = AtomicFile(File(context.filesDir, fileName))
    private val aad = "Meteor.profile.v1".toByteArray()
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return generateKey()
    }
    private fun generateKey(): SecretKey {
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    // --- B1 console --- Field mapping lives in ProfileCodec (JVM-tested); it adds the optional `memberPins` object.
    private fun JSONObject.plain(): Map<String, Any?> = keys().asSequence().associateWith { key ->
        get(key).let { value -> if (value is JSONObject) value.plain() else value }
    }
    private fun profile(json: JSONObject) = ProfileCodec.fromMap(json.plain())
    private fun json(profile: Profile) = JSONObject(ProfileCodec.toMap(profile))
    // --- end B1 console ---

    // --- vault recovery --- load() reports why it failed as a [VaultException] (see [VaultFailure]) and never creates a key:
    // a lost key must surface as such instead of being replaced by an unrelated new one.
    private inline fun <T> stage(failure: VaultFailure?, message: String, block: () -> T): T = try { block() }
        catch (error: VaultException) { throw error }
        catch (error: CancellationException) { throw error }
        catch (error: Exception) { throw VaultException(failure ?: VaultFailures.classify(error), message, error) }
    private fun existingKey(): SecretKey = stage(null, "The vault key could not be read.") {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        store.getKey(alias, null) as? SecretKey ?: throw VaultException(VaultFailure.KEY_UNAVAILABLE, "The vault key is missing.")
    }
    // --- end vault recovery ---

    override suspend fun load(): ProfileDocument = withContext(Dispatchers.IO) {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return@withContext ProfileDocument()
        val encoded = stage(null, "The vault could not be read.") { file.openRead().use { stream ->
            val output = ByteArrayOutputStream()
            val chunk = ByteArray(8192)
            while (true) {
                val count = stream.read(chunk)
                if (count == -1) break
                if (output.size() + count > 262144) throw VaultException(VaultFailure.CORRUPT, "Invalid vault size.")
                output.write(chunk, 0, count)
            }
            val bytes = output.toByteArray()
            if (bytes.size !in 30..262144) throw VaultException(VaultFailure.CORRUPT, "Invalid vault size.")
            bytes
        } }
        val buffer = ByteBuffer.wrap(encoded)
        val magic = buffer.int
        if (magic != VaultFailures.MAGIC) throw VaultException(
            if (VaultFailures.isNewerMagic(magic)) VaultFailure.UNKNOWN_FORMAT else VaultFailure.CORRUPT, "Unknown vault format.")
        val iv = ByteArray(12).also { buffer.get(it) }
        val payload = ByteArray(buffer.remaining()).also { buffer.get(it) }
        val secret = existingKey()
        val plain = stage(null, "The vault could not be unlocked.") {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secret, GCMParameterSpec(128, iv)); cipher.updateAAD(aad)
            cipher.doFinal(payload)
        }
        // The content authenticated, so a failure from here on means a format this version does not understand.
        stage(VaultFailure.UNKNOWN_FORMAT, "The vault content is not in a supported format.") {
            val root = JSONObject(String(plain, Charsets.UTF_8))
            require(root.getInt("version") == 1)
            val list = root.getJSONArray("profiles")
            require(list.length() <= 50)
            val profiles = (0 until list.length()).map { profile(list.getJSONObject(it)) }
            ProfileDocument(profiles, root.optString("selectedId").takeIf { selected ->
                selected.isNotBlank() && profiles.any { it.id == selected }
            })
        }
    }

    override suspend fun save(document: ProfileDocument) = withContext(Dispatchers.IO) {
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

    // --- vault recovery ---
    /**
     * For an unreadable vault: sets the file aside ([VaultQuarantine]), replaces the key and writes an empty vault that is read back.
     * Only the vault file and its key are touched; the operation journal and every other store are untouched. If setting the file aside
     * fails nothing has changed. After it, a crash at any point leaves "no file", which opens as an empty vault.
     */
    override suspend fun startOver(): Boolean = withContext(Dispatchers.IO) {
        val moved = VaultQuarantine.setAside(file.baseFile.parentFile ?: throw IOException("No storage folder."), file.baseFile.name, System.currentTimeMillis())
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (store.containsAlias(alias)) store.deleteEntry(alias)
        generateKey()
        save(ProfileDocument())
        check(load().profiles.isEmpty()) { "The new vault could not be read back." }
        moved
    }
    // --- end vault recovery ---

    override suspend fun importDebugBootstrap(context: Context): Boolean = withContext(Dispatchers.IO) {
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

