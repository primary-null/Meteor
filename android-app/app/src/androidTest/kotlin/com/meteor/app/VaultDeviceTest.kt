package com.meteor.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.meteor.app.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import java.nio.ByteBuffer
import javax.crypto.Cipher
import org.json.JSONArray
import org.json.JSONObject

@RunWith(AndroidJUnit4::class)
class VaultDeviceTest {
    private fun fixtureProfile() = Profile("fixture", "Fixture", "https://test.invalid/", "tester", "fixture", "a".repeat(64))
    private fun writeAuthenticatedDocument(file: File, alias: String, selectedId: String, duplicate: Boolean = false) {
        val profile = fixtureProfile()
        val json = JSONObject().put("id", profile.id).put("label", profile.label).put("endpoint", profile.endpoint)
            .put("username", profile.username).put("password", profile.password).put("certificateSha256", profile.certificateSha256)
        val array = JSONArray().put(json).apply { if (duplicate) put(json) }
        val root = JSONObject().put("version", 1).put("selectedId", selectedId).put("profiles", array)
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, store.getKey(alias, null))
            updateAAD("Meteor.profile.v1".toByteArray())
        }
        val payload = cipher.doFinal(root.toString().toByteArray())
        file.writeBytes(ByteBuffer.allocate(16 + payload.size).putInt(0x4d455431).put(cipher.iv).put(payload).array())
    }

    @Test fun danglingSelectionKeepsValidProfilesAvailableWithoutRewriting() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val file = File(context.filesDir, "instrumentation-dangling-selection.secured")
        val alias = "meteor.instrumentation.dangling-selection"
        try {
            val vault = ProfileVault(context, file.name, alias)
            vault.save(ProfileDocument(listOf(fixtureProfile()), "fixture"))
            writeAuthenticatedDocument(file, alias, "missing-profile")
            val encrypted = file.readBytes()
            val document = vault.load()
            assertNull(document.selectedId)
            assertEquals("fixture", document.profiles.single().id)
            assertArrayEquals(encrypted, file.readBytes())
        } finally {
            file.delete()
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
        }
    }

    @Test fun duplicateIdentitiesAndUnknownMagicPreserveTheirOriginalBytes() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val file = File(context.filesDir, "instrumentation-invalid-document.secured")
        val alias = "meteor.instrumentation.invalid-document"
        try {
            val vault = ProfileVault(context, file.name, alias)
            vault.save(ProfileDocument(listOf(fixtureProfile()), "fixture"))
            writeAuthenticatedDocument(file, alias, "fixture", duplicate = true)
            val duplicate = file.readBytes()
            assertTrue(runCatching { vault.load() }.isFailure)
            assertArrayEquals(duplicate, file.readBytes())
            val badMagic = duplicate.copyOf().also { it[0] = 0 }
            file.writeBytes(badMagic)
            assertTrue(runCatching { vault.load() }.isFailure)
            assertArrayEquals(badMagic, file.readBytes())
        } finally {
            file.delete()
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
        }
    }

    @Test fun oversizedVaultIsRejectedAndPreserved() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val file = File(context.filesDir, "instrumentation-oversized.secured")
        val bytes = ByteArray(262145) { 42 }
        try {
            file.writeBytes(bytes)
            assertTrue(runCatching { ProfileVault(context, file.name, "meteor.instrumentation.oversized").load() }.isFailure)
            assertArrayEquals(bytes, file.readBytes())
        } finally { file.delete() }
    }

    @Test fun missingKeyDoesNotOverwriteEncryptedProfiles() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val file = File(context.filesDir, "instrumentation-missing-key.secured")
        val alias = "meteor.instrumentation.missing-key"
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        try {
            val vault = ProfileVault(context, file.name, alias)
            val profile = Profile("fixture", "Fixture", "https://test.invalid/", "tester", "fixture", "a".repeat(64))
            vault.save(ProfileDocument(listOf(profile), profile.id))
            val encrypted = file.readBytes()
            store.deleteEntry(alias)
            assertTrue(runCatching { vault.load() }.isFailure)
            assertArrayEquals(encrypted, file.readBytes())
        } finally {
            file.delete()
            store.deleteEntry(alias)
        }
    }

    @Test fun encryptedRoundTripAndCorruptionPreservesData() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "instrumentation-vault.secured"
        val alias = "meteor.instrumentation.vault"
        val file = File(context.filesDir, name)
        try {
            val vault = ProfileVault(context, name, alias)
            val profile = Profile("test-id", "Test profile", "https://test.invalid/", "tester", "test-only-password", "a".repeat(64))
            vault.save(ProfileDocument(listOf(profile), profile.id))
            assertFalse(String(file.readBytes(), Charsets.ISO_8859_1).contains(profile.password))
            assertEquals(profile, vault.load().profiles.single())
            val corrupted = file.readBytes().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
            file.writeBytes(corrupted)
            assertTrue(runCatching { vault.load() }.isFailure)
            assertArrayEquals(corrupted, file.readBytes())
        } finally {
            file.delete()
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
        }
    }
}
