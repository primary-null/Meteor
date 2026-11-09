package com.meteor.app.data

import org.junit.Assert.*
import org.junit.Test

class ConnectionProfilesTest {
    private val profile = Profile("original", "Fixture", "https://test.invalid/", "tester", "fixture", "a".repeat(64))
    private val document = ProfileDocument(listOf(profile), profile.id)
    private fun pending() = PowerOperation("operation", profile.id, profile.origin(), "pool", "vm", "Fixture VM",
        PowerAction.START, null, PowerPhase.UNKNOWN, 1, 1)

    @Test fun renameKeepsCredentialsPinAndStableProfileIdentity() {
        val draft = ConnectionProfiles.draft(document, profile.id, " Renamed ", "test.invalid", " tester ", "")
        assertEquals(profile.copy(label = "Renamed"), draft)
        assertEquals(profile.origin(), draft.origin())
        assertEquals(profile.id, ConnectionProfiles.put(document, draft).selectedId)
    }

    @Test fun credentialReplacementKeepsThePinButAChangedServerNeedsNewTrust() {
        val password = ConnectionProfiles.draft(document, profile.id, profile.label, profile.endpoint, profile.username, "replacement")
        assertEquals(profile.certificateSha256, password.certificateSha256)
        assertEquals(profile.origin(), password.origin())
        val server = ConnectionProfiles.draft(document, profile.id, profile.label, "other.invalid", profile.username, "")
        assertEquals(profile.password, server.password)
        assertTrue(server.certificateSha256.isEmpty())
        assertEquals(profile.id, server.id)
        val user = ConnectionProfiles.draft(document, profile.id, profile.label, profile.endpoint, "other-user", "")
        assertNotEquals(profile.origin(), user.origin())
        assertEquals(profile.certificateSha256, user.certificateSha256)
    }

    @Test fun missingEditAndDuplicateDestinationAreRefused() {
        assertThrows(IllegalArgumentException::class.java) {
            ConnectionProfiles.draft(document, "missing", "Fixture", profile.endpoint, profile.username, "")
        }
        val other = profile.copy(id = "other", endpoint = "https://other.invalid/")
        val both = ProfileDocument(listOf(profile, other), profile.id)
        assertThrows(IllegalArgumentException::class.java) {
            ConnectionProfiles.draft(both, other.id, "Fixture", profile.endpoint, profile.username, "")
        }
        assertThrows(IllegalArgumentException::class.java) { ConnectionProfiles.put(document, profile.copy(id = "duplicate")) }
    }

    @Test fun newProfilesRequireCredentialsAndCannotSilentlyReuseASavedPassword() {
        assertThrows(IllegalArgumentException::class.java) {
            ConnectionProfiles.draft(document, null, "Fixture", profile.endpoint, profile.username, "")
        }
        val updated = ConnectionProfiles.draft(document, null, "Fixture", profile.endpoint, profile.username, "replacement")
        assertEquals(profile.id, updated.id)
        val added = ConnectionProfiles.draft(document, null, "", "other.invalid", "tester", "fixture")
        assertEquals("XCP-ng connection", added.label)
        assertNotEquals(profile.id, added.id)
        assertTrue(added.certificateSha256.isEmpty())
    }

    @Test fun editsKeepTheirPositionAndCanPreserveTheActiveSelection() {
        val other = profile.copy(id = "other", endpoint = "https://other.invalid/")
        val both = ProfileDocument(listOf(profile, other), other.id)
        val next = ConnectionProfiles.put(both, profile.copy(label = "Renamed"), both.selectedId)
        assertEquals(listOf(profile.id, other.id), next.profiles.map { it.id })
        assertEquals(other.id, next.selectedId)
    }

    @Test fun forgettingActiveAndInactiveProfilesClearsOnlyTheMatchingSelection() {
        val other = profile.copy(id = "other", endpoint = "https://other.invalid/")
        val both = ProfileDocument(listOf(profile, other), profile.id)
        assertNull(ConnectionProfiles.forget(both, profile.id, emptyList()).selectedId)
        assertEquals(profile.id, ConnectionProfiles.forget(both, other.id, listOf(pending())).selectedId)
        assertEquals(document, both.copy(profiles = listOf(profile)))
    }

    @Test fun unresolvedActionsBlockForgettingEvenWhenANewerRecordIsClosed() {
        val unresolved = pending()
        val closed = unresolved.copy(id = "newer", phase = PowerPhase.SUCCEEDED, updatedAt = 2)
        assertThrows(IllegalArgumentException::class.java) {
            ConnectionProfiles.forget(document, profile.id, listOf(unresolved, closed))
        }
        assertEquals(profile.origin(), unresolved.origin)
        assertTrue(unresolved.unresolved)
        assertTrue(ConnectionProfiles.forget(document, profile.id, listOf(closed)).profiles.isEmpty())
    }

    @Test fun fullDocumentsAllowEditsButRefuseANewConnection() {
        val full = ProfileDocument((1..50).map { profile.copy(id = "$it", endpoint = "https://host-$it.invalid/") })
        assertThrows(IllegalArgumentException::class.java) {
            ConnectionProfiles.draft(full, null, "Fixture", profile.endpoint, profile.username, profile.password)
        }
        assertEquals("1", ConnectionProfiles.draft(full, "1", "Renamed", "host-1.invalid", profile.username, "").id)
    }
}
