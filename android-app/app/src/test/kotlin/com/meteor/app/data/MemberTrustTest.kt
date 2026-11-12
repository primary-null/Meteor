package com.meteor.app.data

import org.junit.Assert.*
import org.junit.Test
import java.net.URI

/** Member-host detection, approved member pins, and the profile document's optional `memberPins` field. */
class MemberTrustTest {
    private val masterPin = "a".repeat(64)
    private val memberPin = "b".repeat(64)
    private val otherPin = "c".repeat(64)
    private fun profile(pins: Map<String, String> = emptyMap(), endpoint: String = "https://xcp-master.lab/") =
        Profile("p1", "Lab", endpoint, "root", "secret", masterPin, pins)
    private fun console(host: String) = URI("https://$host/console?uuid=abc-123")

    // ---- authority and decision ----

    @Test fun authoritiesAreLowerCaseAndOmitTheDefaultPort() {
        assertEquals("host.example", MemberPins.authority("Host.Example", 443))
        assertEquals("host.example", MemberPins.authority("host.example", -1))
        assertEquals("host.example:8443", MemberPins.authority("HOST.example", 8443))
        assertEquals("[fd00::5]", MemberPins.authority(URI("https://[fd00::5]/console?uuid=a")))
        assertEquals("xcp-master.lab", MemberPins.endpointAuthority(profile()))
        assertEquals("xcp-master.lab:8443", MemberPins.endpointAuthority(profile(endpoint = "https://XCP-Master.lab:8443/")))
    }

    @Test fun theCoordinatorUsesTheSavedPinAndNeedsNoReview() {
        val decision = ConsoleTrust.decide(profile(), console("xcp-master.lab"))
        assertEquals(ConsoleTrust.Decision.Pinned(masterPin, member = false), decision)
        assertEquals(ConsoleTrust.Decision.Pinned(masterPin, false), ConsoleTrust.decide(profile(), console("XCP-MASTER.lab:443")))
        assertFalse(ConsoleTrust.isMemberHost(profile(), console("xcp-master.lab")))
    }

    @Test fun anUnknownHostIsAMemberThatNeedsReview() {
        val decision = ConsoleTrust.decide(profile(), console("203.0.113.5"))
        assertEquals(ConsoleTrust.Decision.Review("203.0.113.5"), decision)
        assertTrue(ConsoleTrust.isMemberHost(profile(), console("203.0.113.5")))
        assertEquals("Another port is another authority", ConsoleTrust.Decision.Review("xcp-master.lab:9443"),
            ConsoleTrust.decide(profile(), console("xcp-master.lab:9443")))
    }

    @Test fun anApprovedMemberIsPinnedStrictlyToItsOwnCertificate() {
        val decision = ConsoleTrust.decide(profile(mapOf("203.0.113.5" to memberPin)), console("203.0.113.5"))
        assertEquals(ConsoleTrust.Decision.Pinned(memberPin, member = true), decision)
        assertEquals("A different member is still unknown", ConsoleTrust.Decision.Review("203.0.113.6"),
            ConsoleTrust.decide(profile(mapOf("203.0.113.5" to memberPin)), console("203.0.113.6")))
    }

    // ---- approving pins ----

    @Test fun approvingAMemberKeepsIdentityCredentialsAndTheCoordinatorPin() {
        val approved = MemberPins.approve(profile(), "203.0.113.5", memberPin)
        assertEquals(mapOf("203.0.113.5" to memberPin), approved.memberPins)
        assertEquals(profile().copy(memberPins = approved.memberPins), approved)
        assertEquals(masterPin, approved.certificateSha256); assertEquals("p1", approved.id)
        assertEquals("Replacing a member's pin keeps one entry", mapOf("203.0.113.5" to otherPin),
            MemberPins.approve(approved, "203.0.113.5", otherPin).memberPins)
    }

    @Test fun approvalRejectsMalformedValuesTheCoordinatorAndTooManyMembers() {
        assertThrows(IllegalArgumentException::class.java) { MemberPins.approve(profile(), "203.0.113.5", "short") }
        assertThrows(IllegalArgumentException::class.java) { MemberPins.approve(profile(), "bad host!", memberPin) }
        assertThrows(IllegalArgumentException::class.java) { MemberPins.approve(profile(), "xcp-master.lab", memberPin) }
        val full = profile((1..MemberPins.MAX_PINS).associate { "203.0.113.$it" to memberPin })
        assertThrows(IllegalArgumentException::class.java) { MemberPins.approve(full, "203.0.113.99", memberPin) }
        assertEquals("An existing member can still be refreshed", otherPin, MemberPins.approve(full, "203.0.113.1", otherPin).memberPins["203.0.113.1"])
    }

    @Test fun profilesStillRedactCredentialsAndPins() {
        val text = profile(mapOf("203.0.113.5" to memberPin)).toString()
        assertFalse(text.contains("secret")); assertFalse(text.contains(memberPin)); assertFalse(text.contains(masterPin))
    }

    // ---- profile document compatibility ----

    private val oldDocument = """{"version":1,"selectedId":"p1","profiles":[{"id":"p1","label":"Lab","endpoint":"https://xcp-master.lab/","username":"root","password":"secret","certificateSha256":"$masterPin"}]}"""

    @Suppress("UNCHECKED_CAST")
    private fun profilesOf(document: String) = ((TestJson.parse(document) as Map<String, Any?>)["profiles"] as List<Map<String, Any?>>)

    @Test fun anOldDocumentWithoutMemberPinsStillLoadsWithAnEmptyDefault() {
        val loaded = ProfileCodec.fromMap(profilesOf(oldDocument).single())
        assertEquals(profile(), loaded)
        assertTrue(loaded.memberPins.isEmpty())
    }

    @Test fun profilesWithoutPinsAreWrittenExactlyInTheOldShape() {
        val map = ProfileCodec.toMap(profile())
        assertEquals(setOf("id", "label", "endpoint", "username", "password", "certificateSha256"), map.keys)
        assertEquals(oldDocument.substringAfter("[").substringBefore("]").let { TestJson.parse(it) }, map)
    }

    @Test fun memberPinsRoundTripThroughTheStoredText() {
        val original = profile(mapOf("203.0.113.5" to memberPin, "xcp2.lab:8443" to otherPin))
        val text = TestJson.render(mapOf("version" to 1, "profiles" to listOf(ProfileCodec.toMap(original))))
        assertEquals(original, ProfileCodec.fromMap(profilesOf(text).single()))
    }

    @Test fun theCurrentReaderIgnoresTheOptionalFieldAndSeesTheSameProfile() {
        // The pre-B1 reader asked for exactly these six strings and validated the pin and identity.
        fun legacyRead(map: Map<String, Any?>): Profile = Profile(map["id"] as String, map["label"] as String,
            Endpoints.normalize(map["endpoint"] as String), map["username"] as String, map["password"] as String,
            map["certificateSha256"] as String).also {
            require(it.certificateSha256.matches(Regex("[0-9a-f]{64}"))); require(it.id.isNotBlank() && it.username.isNotBlank())
        }
        val written = ProfileCodec.toMap(profile(mapOf("203.0.113.5" to memberPin)))
        assertTrue("memberPins" in written)
        assertEquals(profile(), legacyRead(written))
    }

    @Test fun damagedMemberPinEntriesAreDroppedWithoutFailingTheProfile() {
        val text = """{"profiles":[{"id":"p1","label":"Lab","endpoint":"https://xcp-master.lab/","username":"root","password":"secret",
            "certificateSha256":"$masterPin","memberPins":{"203.0.113.5":"$memberPin","bad host!":"$memberPin","203.0.113.6":"nothex","203.0.113.7":5,"HOST.Lab":"${memberPin.uppercase()}"}}]}"""
        assertEquals(mapOf("203.0.113.5" to memberPin), ProfileCodec.fromMap(profilesOf(text).single()).memberPins)
        for (shape in listOf("\"x\"", "[1,2]", "5", "null")) {
            val odd = text.replace(Regex("\"memberPins\":\\{.*\\}\\}\\]\\}"), "\"memberPins\":$shape}]}")
            assertTrue(shape, ProfileCodec.fromMap(profilesOf(odd).single()).memberPins.isEmpty())
        }
    }

    @Test fun missingRequiredFieldsStillFail() {
        val broken = profilesOf(oldDocument).single().filterKeys { it != "password" }
        assertThrows(IllegalArgumentException::class.java) { ProfileCodec.fromMap(broken) }
        assertThrows(IllegalArgumentException::class.java) { ProfileCodec.fromMap(profilesOf(oldDocument).single() + ("certificateSha256" to "nothex")) }
    }

    // ---- editing and writing profiles ----

    @Test fun editingCredentialsOrLabelKeepsMemberPinsIdentityAndTheCoordinatorPin() {
        val document = ProfileDocument(listOf(profile(mapOf("203.0.113.5" to memberPin))), "p1")
        val edited = ConnectionProfiles.draft(document, "p1", "Renamed", "https://xcp-master.lab", "root", "")
        assertEquals("p1", edited.id); assertEquals(masterPin, edited.certificateSha256)
        assertEquals(mapOf("203.0.113.5" to memberPin), edited.memberPins)
        assertEquals("secret", edited.password)
    }

    @Test fun changingTheServerAddressStartsWithoutMemberPinsAndWithoutTheOldCoordinatorPin() {
        val document = ProfileDocument(listOf(profile(mapOf("203.0.113.5" to memberPin))), "p1")
        val moved = ConnectionProfiles.draft(document, "p1", "Lab", "https://other.lab", "root", "")
        assertTrue(moved.memberPins.isEmpty()); assertEquals("", moved.certificateSha256); assertEquals("p1", moved.id)
    }

    @Test fun aWriteFromAnOlderCopyCannotDropAPinApprovedMeanwhile() {
        val stored = ProfileDocument(listOf(profile(mapOf("203.0.113.5" to memberPin))), "p1")
        val stale = profile() // e.g. a background refresh that started before the approval
        val next = ConnectionProfiles.put(stored, stale)
        assertEquals(mapOf("203.0.113.5" to memberPin), next.profiles.single().memberPins)
        val replaced = ConnectionProfiles.put(stored, profile(mapOf("203.0.113.5" to otherPin)))
        assertEquals("The incoming pin wins for the same host", mapOf("203.0.113.5" to otherPin), replaced.profiles.single().memberPins)
        val moved = ConnectionProfiles.put(stored, profile(endpoint = "https://other.lab/"))
        assertTrue("A new address does not inherit pins", moved.profiles.single().memberPins.isEmpty())
    }
}
