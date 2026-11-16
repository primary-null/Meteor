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


    // --- vault recovery --- Real Keystore paths for classifying a failed load and for "Start over". These are compiled by
    // :app:compileDebugAndroidTestKotlin; they need an emulator or device to run.
    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun failureOf(vault: ProfileVault): VaultFailure? =
        runCatching { runBlocking { vault.load() } }.exceptionOrNull()?.let { (it as? VaultException)?.failure ?: VaultFailures.classify(it) }
    private fun leftovers(context: android.content.Context, name: String) = VaultQuarantine.setAsideFiles(context.filesDir, name)

    @Test fun aLostKeyIsReportedAsSuchAndLoadDoesNotCreateAReplacementKey() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val file = File(context.filesDir, "instrumentation-lost-key.secured")
        val alias = "meteor.instrumentation.lost-key"
        try {
            val vault = ProfileVault(context, file.name, alias)
            vault.save(ProfileDocument(listOf(fixtureProfile()), "fixture"))
            val encrypted = file.readBytes()
            keyStore().deleteEntry(alias)
            assertEquals(VaultFailure.KEY_UNAVAILABLE, failureOf(vault))
            assertFalse("a failed load must not generate a key", keyStore().containsAlias(alias))
            assertArrayEquals(encrypted, file.readBytes())
        } finally { file.delete(); keyStore().deleteEntry(alias) }
    }

    @Test fun corruptAndNewerFormatFilesAreToldApartAndPreserved() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val file = File(context.filesDir, "instrumentation-failure-kinds.secured")
        val alias = "meteor.instrumentation.failure-kinds"
        try {
            val vault = ProfileVault(context, file.name, alias)
            vault.save(ProfileDocument(listOf(fixtureProfile()), "fixture"))
            val good = file.readBytes()
            val flipped = good.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
            file.writeBytes(flipped); assertEquals(VaultFailure.CORRUPT, failureOf(vault)); assertArrayEquals(flipped, file.readBytes())
            val junk = good.copyOf().also { it[0] = 0x7f }
            file.writeBytes(junk); assertEquals(VaultFailure.CORRUPT, failureOf(vault)); assertArrayEquals(junk, file.readBytes())
            val newer = good.copyOf().also { it[3] = 0x32 } // "MET2"
            file.writeBytes(newer); assertEquals(VaultFailure.UNKNOWN_FORMAT, failureOf(vault)); assertArrayEquals(newer, file.readBytes())
            file.writeBytes(ByteArray(10) { 1 }); assertEquals(VaultFailure.CORRUPT, failureOf(vault))
            writeAuthenticatedDocument(file, alias, "fixture", duplicate = true)
            assertEquals(VaultFailure.UNKNOWN_FORMAT, failureOf(vault)) // authenticates, but cannot be interpreted
        } finally { file.delete(); keyStore().deleteEntry(alias) }
    }

    @Test fun startOverAfterKeyLossSetsTheFileAsideCreatesAFreshKeyAndSavesAgain() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "instrumentation-start-over.secured"
        val file = File(context.filesDir, name)
        val alias = "meteor.instrumentation.start-over"
        try {
            val vault = ProfileVault(context, name, alias)
            vault.save(ProfileDocument(listOf(fixtureProfile()), "fixture"))
            val encrypted = file.readBytes()
            keyStore().deleteEntry(alias)
            assertEquals(VaultFailure.KEY_UNAVAILABLE, failureOf(vault))
            assertTrue(vault.startOver())
            val aside = leftovers(context, name).single()
            assertArrayEquals("the unreadable file is kept byte for byte", encrypted, aside.readBytes())
            assertTrue(keyStore().containsAlias(alias))
            assertTrue(vault.load().profiles.isEmpty())
            assertFalse(String(file.readBytes(), Charsets.ISO_8859_1).contains("fixture-password"))
            val fresh = Profile("fresh", "Fresh", "https://test.invalid/", "tester", "new-password", "b".repeat(64))
            vault.save(ProfileDocument(listOf(fresh), fresh.id))
            assertEquals(fresh, vault.load().profiles.single())
            assertArrayEquals(encrypted, aside.readBytes()) // never read, never changed
        } finally {
            file.delete(); leftovers(context, name).forEach { it.delete() }; keyStore().deleteEntry(alias)
        }
    }

    @Test fun startOverAfterCorruptionKeepsOnlyTheTwoNewestUnreadableCopies() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "instrumentation-start-over-corrupt.secured"
        val file = File(context.filesDir, name)
        val alias = "meteor.instrumentation.start-over-corrupt"
        try {
            val vault = ProfileVault(context, name, alias)
            repeat(3) { round ->
                file.writeBytes(ByteArray(64) { (round + 1).toByte() })
                assertEquals(VaultFailure.CORRUPT, failureOf(vault))
                assertTrue(vault.startOver())
                assertTrue(vault.load().profiles.isEmpty())
                Thread.sleep(1100) // set-aside names carry the time to the second
            }
            val kept = leftovers(context, name)
            assertEquals(2, kept.size)
            assertEquals(listOf<Byte>(3, 2), kept.map { it.readBytes()[0] })
        } finally {
            file.delete(); leftovers(context, name).forEach { it.delete() }; keyStore().deleteEntry(alias)
        }
    }

    @Test fun startOverWithNoFileStillLeavesAWorkingVault() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "instrumentation-start-over-empty.secured"
        val alias = "meteor.instrumentation.start-over-empty"
        try {
            val vault = ProfileVault(context, name, alias)
            assertFalse(vault.startOver())
            assertTrue(vault.load().profiles.isEmpty())
            vault.save(ProfileDocument(listOf(fixtureProfile()), "fixture"))
            assertEquals("fixture", vault.load().profiles.single().id)
        } finally {
            File(context.filesDir, name).delete(); leftovers(context, name).forEach { it.delete() }; keyStore().deleteEntry(alias)
        }
    }

    @Test fun startOverNeverTouchesAnUnresolvedOperationRecord() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "instrumentation-start-over-journal.secured"
        val journalName = "instrumentation-start-over-journal.json"
        val alias = "meteor.instrumentation.start-over-journal"
        val journalFile = File(context.filesDir, journalName)
        try {
            val vault = ProfileVault(context, name, alias)
            val profile = fixtureProfile()
            vault.save(ProfileDocument(listOf(profile), profile.id))
            val journal = OperationJournal(context, journalName)
            val pending = PowerOperation("pending", profile.id, profile.origin(), "pool", "vm", "Fixture VM", PowerAction.START, null,
                PowerPhase.UNKNOWN, 1, 1, "task-ref", "task-uuid", "Waiting for the server")
            journal.put(pending)
            val before = journalFile.readBytes()
            keyStore().deleteEntry(alias)
            assertTrue(vault.startOver())
            assertArrayEquals(before, journalFile.readBytes())
            val after = journal.load().single()
            assertEquals(pending, after)
            assertTrue(after.unresolved)
            assertEquals(profile.id, after.profileId) // still bound to the connection that sent it, which is no longer saved
            assertTrue(vault.load().profiles.none { it.id == after.profileId })
        } finally {
            journalFile.delete(); File(context.filesDir, name).delete()
            VaultQuarantine.setAsideFiles(context.filesDir, name).forEach { it.delete() }; keyStore().deleteEntry(alias)
        }
    }
    // --- end vault recovery ---
}
