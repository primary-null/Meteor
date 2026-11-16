package com.meteor.app.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.security.InvalidKeyException
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException

private class SeenPreferences : DataStore<Preferences> {
    private val lock = Mutex()
    override val data = MutableStateFlow(emptyPreferences())
    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
        lock.withLock { transform(data.value).also { data.value = it } }
}

/** The decisions behind an unreadable profile vault: why it failed, what a reset sets aside, and what happens to session connections. */
class VaultRecoveryTest {
    @get:Rule val folder = TemporaryFolder()
    private val time1 = 1_800_000_000_000L // 2027-01-15T08:00:00Z
    private val time2 = time1 + 60_000
    private val time3 = time1 + 120_000
    private fun profile(id: String, host: String = id, user: String = "tester") =
        Profile(id, id, "https://$host.invalid/", user, "fixture-password", "a".repeat(64))

    // --- why it failed ---

    @Test fun eachKindOfFailureIsClassifiedByItsCause() {
        assertEquals(VaultFailure.KEY_UNAVAILABLE, VaultFailures.classify(VaultException(VaultFailure.KEY_UNAVAILABLE, "key")))
        assertEquals(VaultFailure.KEY_UNAVAILABLE, VaultFailures.classify(UnrecoverableKeyException("gone")))
        assertEquals(VaultFailure.KEY_UNAVAILABLE, VaultFailures.classify(InvalidKeyException("invalidated")))
        assertEquals(VaultFailure.CORRUPT, VaultFailures.classify(AEADBadTagException("tag")))
        assertEquals(VaultFailure.UNKNOWN_FORMAT, VaultFailures.classify(VaultException(VaultFailure.UNKNOWN_FORMAT, "format")))
        assertEquals(VaultFailure.TEMPORARY, VaultFailures.classify(IOException("disk")))
    }

    @Test fun aWrappedCauseIsFoundAndAnythingUnknownIsTreatedAsTemporary() {
        assertEquals(VaultFailure.CORRUPT, VaultFailures.classify(IllegalStateException("outer", AEADBadTagException("tag"))))
        assertEquals(VaultFailure.TEMPORARY, VaultFailures.classify(IllegalStateException("unexpected")))
        assertEquals(VaultFailure.TEMPORARY, VaultFailures.classify(IllegalArgumentException("fixture")))
    }

    @Test fun onlyPermanentCausesAreSaidToBePermanent() {
        assertEquals(setOf(VaultFailure.KEY_UNAVAILABLE, VaultFailure.CORRUPT, VaultFailure.UNKNOWN_FORMAT),
            VaultFailure.entries.filter { it.permanent }.toSet())
        assertFalse(VaultFailure.TEMPORARY.permanent)
    }

    @Test fun aNewerVersionOfTheFileIsToldApartFromJunk() {
        assertFalse(VaultFailures.isNewerMagic(VaultFailures.MAGIC))
        assertTrue(VaultFailures.isNewerMagic(0x4d455432)) // "MET2"
        assertFalse(VaultFailures.isNewerMagic(0))
        assertFalse(VaultFailures.isNewerMagic(0x01020304))
    }

    @Test fun startOverIsOfferedForPermanentCausesAndForATemporaryOneOnlyAfterATryAgainFailed() {
        VaultFailure.entries.filter { it.permanent }.forEach { assertTrue(VaultFailures.canStartOver(it, 1)) }
        assertFalse(VaultFailures.canStartOver(VaultFailure.TEMPORARY, 1))
        assertTrue(VaultFailures.canStartOver(VaultFailure.TEMPORARY, 2))
    }

    // --- setting the file aside ---

    private fun write(name: String, text: String) = File(folder.root, name).also { it.writeText(text) }

    @Test fun setAsideRenamesTheVaultAndItsLeftoversWithoutChangingAnyByte() {
        write("profiles.secured", "main"); write("profiles.secured.bak", "backup"); write("profiles.secured.new", "partial")
        assertTrue(VaultQuarantine.setAside(folder.root, "profiles.secured", time1))
        assertFalse(File(folder.root, "profiles.secured").exists())
        assertFalse(File(folder.root, "profiles.secured.bak").exists()) // a surviving .bak would be restored as the live file
        assertFalse(File(folder.root, "profiles.secured.new").exists())
        assertEquals("main", File(folder.root, "profiles.secured.unreadable-20270115-080000").readText())
        assertEquals("backup", File(folder.root, "profiles.secured.unreadable-20270115-080000.bak").readText())
        assertEquals("partial", File(folder.root, "profiles.secured.unreadable-20270115-080000.new").readText())
    }

    @Test fun nothingToSetAsideIsReportedAndTouchesNothing() {
        write("other.secured", "x")
        assertFalse(VaultQuarantine.setAside(folder.root, "profiles.secured", time1))
        assertEquals(listOf("other.secured"), folder.root.list()!!.toList())
    }

    @Test fun onlyTheTwoNewestSetsAreKept() {
        repeat(4) { index ->
            write("profiles.secured", "vault-$index")
            VaultQuarantine.setAside(folder.root, "profiles.secured", time1 + index * 60_000L)
        }
        val kept = VaultQuarantine.setAsideFiles(folder.root, "profiles.secured")
        assertEquals(listOf("vault-3", "vault-2"), kept.map { it.readText() })
        assertEquals(2, folder.root.list()!!.size)
    }

    @Test fun aSetThatIncludesLeftoversIsKeptOrPrunedAsOne() {
        write("profiles.secured", "old"); write("profiles.secured.bak", "old-bak")
        VaultQuarantine.setAside(folder.root, "profiles.secured", time1)
        write("profiles.secured", "mid"); VaultQuarantine.setAside(folder.root, "profiles.secured", time2)
        write("profiles.secured", "new"); VaultQuarantine.setAside(folder.root, "profiles.secured", time3)
        assertEquals(listOf("new", "mid"), VaultQuarantine.setAsideFiles(folder.root, "profiles.secured").map { it.readText() })
        assertFalse(File(folder.root, "profiles.secured.unreadable-20270115-080000.bak").exists())
    }

    @Test fun otherFilesAndOtherVaultsAreNeverPruned() {
        write("operations-v1.json", "journal"); write("other.secured.unreadable-20260101-000000", "other vault")
        write("profiles.secured.unreadable-notatime", "not a set-aside file")
        repeat(3) { write("profiles.secured", "v$it"); VaultQuarantine.setAside(folder.root, "profiles.secured", time1 + it * 60_000L) }
        assertEquals("journal", File(folder.root, "operations-v1.json").readText())
        assertTrue(File(folder.root, "other.secured.unreadable-20260101-000000").exists())
        assertTrue(File(folder.root, "profiles.secured.unreadable-notatime").exists())
    }

    @Test fun aFailedRenameRestoresEverythingAlreadyMoved() {
        write("profiles.secured", "main"); write("profiles.secured.bak", "backup")
        var calls = 0
        val failure = assertThrows(IOException::class.java) {
            VaultQuarantine.setAside(folder.root, "profiles.secured", time1) { from, to -> if (++calls == 2) false else from.renameTo(to) }
        }
        assertNotNull(failure.message)
        assertEquals("main", File(folder.root, "profiles.secured").readText())
        assertEquals("backup", File(folder.root, "profiles.secured.bak").readText())
        assertTrue(VaultQuarantine.setAsideFiles(folder.root, "profiles.secured").isEmpty())
    }

    @Test fun anExistingCopyWithTheSameTimeIsNeverOverwritten() {
        write("profiles.secured", "again"); write("profiles.secured.unreadable-20270115-080000", "earlier")
        assertThrows(IOException::class.java) { VaultQuarantine.setAside(folder.root, "profiles.secured", time1) }
        assertEquals("again", File(folder.root, "profiles.secured").readText())
        assertEquals("earlier", File(folder.root, "profiles.secured.unreadable-20270115-080000").readText())
    }

    @Test fun aSetAsideFileNeverHasTheLiveName() {
        write("profiles.secured", "main")
        VaultQuarantine.setAside(folder.root, "profiles.secured", time1)
        assertTrue(VaultQuarantine.setAsideFiles(folder.root, "profiles.secured").none { it.name == "profiles.secured" })
        assertFalse(File(folder.root, "profiles.secured").exists()) // so the vault opens as empty and never reads it again
    }

    // --- connections added while the vault was unreadable ---

    @Test fun sessionConnectionsJoinTheStoredOnesAndTheSelectionIsKept() {
        val stored = ProfileDocument(listOf(profile("saved")), "saved")
        val merge = VaultMerge.merge(stored, listOf(profile("typed")), "typed")
        assertEquals(listOf("saved", "typed"), merge.document.profiles.map { it.id })
        assertEquals("typed", merge.document.selectedId)
        assertEquals(1, merge.added); assertEquals(0, merge.skipped)
    }

    @Test fun aSessionConnectionForASavedServerAndUsernameIsDroppedAndTheSelectionFollowsTheSavedOne() {
        val saved = profile("saved", host = "pool-a")
        val duplicate = profile("typed", host = "pool-a")
        val merge = VaultMerge.merge(ProfileDocument(listOf(saved), null), listOf(duplicate), "typed")
        assertEquals(listOf(saved), merge.document.profiles)
        assertEquals("saved", merge.document.selectedId)
        assertEquals(0, merge.added); assertEquals(1, merge.skipped)
    }

    @Test fun theSavedSelectionIsKeptWhenNothingWasSelectedMeanwhile() {
        val stored = ProfileDocument(listOf(profile("a"), profile("b")), "b")
        val merge = VaultMerge.merge(stored, emptyList(), null)
        assertEquals(stored, merge.document)
        assertEquals(0, merge.added)
    }

    @Test fun theConnectionCapIsKeptAndTheExtrasAreCounted() {
        val stored = ProfileDocument((1..49).map { profile("s$it") })
        val merge = VaultMerge.merge(stored, listOf(profile("x1"), profile("x2")), null)
        assertEquals(ProfileDocument.MAX_PROFILES, merge.document.profiles.size)
        assertEquals(1, merge.added); assertEquals(1, merge.skipped)
    }

    @Test fun startingOverWithNothingSavedKeepsExactlyWhatWasTyped() {
        val merge = VaultMerge.merge(ProfileDocument(), listOf(profile("typed")), "typed")
        assertEquals(ProfileDocument(listOf(profile("typed")), "typed"), merge.document)
    }

    // --- other stores of forgotten profiles ---

    @Test fun seenValuesOfForgottenProfilesArePrunedAndTheRestKept() = runBlocking<Unit> {
        val store = AlertSeenDataStore(SeenPreferences())
        store.save("kept", 100); store.save("gone", 200); store.save("also-gone", 300)
        assertEquals(setOf("gone", "also-gone"), store.retainOnly(setOf("kept", "unrelated")))
        assertEquals(100L, store.lastSeen("kept"))
        assertNull(store.lastSeen("gone")); assertNull(store.lastSeen("also-gone"))
        assertEquals(emptySet<String>(), store.retainOnly(setOf("kept")))
    }

    @Test fun pruningSeenValuesLeavesForeignKeysAlone() = runBlocking<Unit> {
        val prefs = SeenPreferences()
        prefs.edit { it[longPreferencesKey("something.else")] = 7L }
        val store = AlertSeenDataStore(prefs)
        store.save("gone", 1)
        store.retainOnly(emptySet())
        assertEquals(7L, prefs.data.value[longPreferencesKey("something.else")])
        assertNull(store.lastSeen("gone"))
    }

    // --- the Add form ---

    @Test fun onlyAnAddThatLandsOnASavedConnectionIsSaidToReplaceIt() {
        val saved = profile("saved")
        assertTrue(ConnectionProfiles.replacesOnAdd(saved, editingProfileId = null, followedFrom = null))
        assertFalse(ConnectionProfiles.replacesOnAdd(saved, editingProfileId = "saved", followedFrom = null)) // an edit
        assertFalse(ConnectionProfiles.replacesOnAdd(saved, editingProfileId = null, followedFrom = "https://member.invalid/")) // master follow
        assertFalse(ConnectionProfiles.replacesOnAdd(null, editingProfileId = null, followedFrom = null)) // a new connection
    }
}
