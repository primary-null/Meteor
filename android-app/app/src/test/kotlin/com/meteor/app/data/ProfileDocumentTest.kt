package com.meteor.app.data

import org.junit.Assert.*
import org.junit.Test

class ProfileDocumentTest {
    private fun profile(id: String) = Profile(id, "Fixture", "https://test.invalid/", "tester", "fixture", "a".repeat(64))

    @Test fun rejectsDuplicateProfilesBeforeTheyBecomeLazyListKeys() {
        assertThrows(IllegalArgumentException::class.java) {
            ProfileDocument(listOf(profile("same"), profile("same")), "same")
        }
    }
    @Test fun rejectsBlankAndDanglingIdentities() {
        assertThrows(IllegalArgumentException::class.java) { ProfileDocument(listOf(profile(" "))) }
        assertThrows(IllegalArgumentException::class.java) { ProfileDocument(listOf(profile("one")), "missing") }
    }
    @Test fun acceptsEmptyUnselectedAndValidSelectedProfiles() {
        assertTrue(ProfileDocument().profiles.isEmpty())
        assertNull(ProfileDocument(listOf(profile("one"))).selectedId)
        assertEquals("one", ProfileDocument(listOf(profile("one")), "one").selectedId)
    }
    @Test fun enforcesProfileCountLimit() {
        assertEquals(50, ProfileDocument((1..50).map { profile(it.toString()) }).profiles.size)
        assertThrows(IllegalArgumentException::class.java) { ProfileDocument((1..51).map { profile(it.toString()) }) }
    }
}
