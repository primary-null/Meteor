package com.meteor.app

import com.meteor.app.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

/** HOST_IS_SLAVE: offer, explicit certificate review of the master, and a profile update that keeps the profile id. */
@OptIn(ExperimentalCoroutinesApi::class)
class MasterFollowControllerTest {
    private val member = Profile("profile", "Lab", "https://member.invalid/", "root", "secret-pw", "a".repeat(64))
    private val masterAddress = "192.0.2.10"
    private val masterEndpoint = "https://192.0.2.10/"
    private val masterPin = "c".repeat(64)

    private class Vault(var document: ProfileDocument) : ProfileStorage {
        var writes = 0
        override suspend fun load() = document
        override suspend fun save(document: ProfileDocument) { writes++; this.document = document }
    }
    private class Backend : MeteorGateway {
        val calls = mutableListOf<Profile>()
        var behavior: suspend (Profile) -> Inventory = { Inventory("pool", "Pool", emptyList(), emptyList(), 0, 0, System.currentTimeMillis()) }
        override suspend fun inventory(profile: Profile): Inventory { calls += profile; return behavior(profile) }
        override suspend fun details(profile: Profile, authority: String, uuid: String): VmDetails = error("Unexpected details")
        override suspend fun <T> powerConnection(profile: Profile, action: suspend PowerConnection.() -> T): T = error("Unexpected power access")
    }
    private class Journal : OperationStore {
        override suspend fun load() = emptyList<PowerOperation>()
        override suspend fun put(operation: PowerOperation) = error("Connections must not change operation history")
    }
    private inner class Fixture(scope: CoroutineScope, profiles: List<Profile> = listOf(member), selected: String? = member.id) {
        val vault = Vault(ProfileDocument(profiles, selected))
        val backend = Backend().apply {
            behavior = { profile -> if (profile.endpoint == member.endpoint) throw RpcFailure("HOST_IS_SLAVE", details = listOf(masterAddress))
                else Inventory("pool", "Pool", emptyList(), emptyList(), 0, 0, System.currentTimeMillis()) }
        }
        val inspected = mutableListOf<String>()
        val state = MutableStateFlow(MeteorState(profiles = profiles, selectedId = selected, journalProblem = false))
        val controller = ConnectionController(scope, backend, vault, Journal(), CertificateInspector { endpoint ->
            inspected += endpoint; ServerCertificate(masterPin, "CN=master", 1, 2)
        }, state, {}, {}, {}, { ErrorReasons.describe(it) }).apply { initialized = true }
    }

    @Test fun slaveLoginOffersTheMasterExplainsItWithTheRawCodeAndChangesNothing() = runTest {
        val f = Fixture(backgroundScope)
        f.controller.connect(member, manual = true); runCurrent()
        val offer = f.state.value.masterOffer!!
        assertEquals(masterEndpoint, offer.masterEndpoint); assertEquals(masterAddress, offer.masterAddress)
        assertEquals(member.id, offer.profileId)
        assertEquals(NoticeKind.ERROR, f.state.value.notice?.kind)
        assertTrue(f.state.value.notice!!.text.contains("pool member") && f.state.value.notice!!.text.contains("(HOST_IS_SLAVE)") &&
            f.state.value.notice!!.text.contains(masterAddress))
        assertEquals(0, f.vault.writes); assertTrue(f.inspected.isEmpty())                    // no silent switch, no review yet
        assertEquals(listOf(member), f.state.value.profiles); assertFalse(f.state.value.connected)
        assertEquals(listOf(member), f.backend.calls)                                          // credentials went only to the member
    }
    @Test fun followingRunsTheCertificateReviewFirstAndOnlyAnApprovedLoginRewritesTheProfileKeepingItsId() = runTest {
        val f = Fixture(backgroundScope)
        f.controller.connect(member, manual = true); runCurrent()
        f.controller.followMaster(); runCurrent()
        assertEquals(listOf(masterEndpoint), f.inspected)
        val pending = f.state.value.pendingTrust!!
        assertEquals(member.id, pending.profile.id); assertEquals(masterEndpoint, pending.profile.endpoint)
        assertEquals(masterPin, pending.profile.certificateSha256)                             // the master's own certificate, not the member's pin
        assertNotEquals(member.certificateSha256, pending.profile.certificateSha256)
        assertEquals(member, pending.previous); assertEquals(member.endpoint, pending.followedFrom)
        assertEquals("secret-pw", pending.profile.password); assertEquals("root", pending.profile.username); assertEquals("Lab", pending.profile.label)
        assertNull(f.state.value.masterOffer)
        // Still nothing sent to the master and nothing saved: the user has not trusted it yet.
        assertEquals(listOf(member), f.backend.calls); assertEquals(0, f.vault.writes); assertEquals(member, f.vault.document.profiles.single())
        f.controller.acceptTrust(); runCurrent()
        assertEquals(2, f.backend.calls.size); assertEquals(masterEndpoint, f.backend.calls.last().endpoint)
        assertEquals(masterPin, f.backend.calls.last().certificateSha256)
        val saved = f.vault.document.profiles.single()
        assertEquals(member.copy(endpoint = masterEndpoint, certificateSha256 = masterPin), saved)
        assertEquals(member.id, f.vault.document.selectedId); assertEquals(member.id, f.state.value.selectedId)
        assertTrue(f.state.value.connected); assertNull(f.state.value.masterOffer); assertNull(f.state.value.pendingTrust)
    }
    @Test fun cancellingTheReviewLeavesTheSavedProfileAloneAndBackgroundRetriesDoNotReopenTheOffer() = runTest {
        val f = Fixture(backgroundScope)
        f.controller.connect(member, manual = true); runCurrent()
        f.controller.followMaster(); runCurrent()
        f.controller.cancelTrust(); runCurrent()
        assertEquals(member, f.vault.document.profiles.single()); assertNull(f.state.value.pendingTrust)
        f.controller.connect(member, manual = false); runCurrent()                             // the 30 s background refresh fails again
        assertNull(f.state.value.masterOffer)
        assertTrue(f.state.value.notice!!.text.contains("HOST_IS_SLAVE"))                      // the explanation stays
        f.controller.connect(member, manual = true); runCurrent()                              // an explicit attempt offers again
        assertNotNull(f.state.value.masterOffer)
    }
    @Test fun decliningIsRememberedForBackgroundRetriesUntilTheNextManualConnect() = runTest {
        val f = Fixture(backgroundScope)
        f.controller.connect(member, manual = false); runCurrent()
        assertNotNull(f.state.value.masterOffer)
        f.controller.connect(member, manual = false); runCurrent()
        assertNotNull("an open offer survives a background retry", f.state.value.masterOffer)
        f.controller.declineMaster()
        assertNull(f.state.value.masterOffer)
        f.controller.connect(member, manual = false); runCurrent()
        assertNull(f.state.value.masterOffer)
        f.controller.connect(member, manual = true); runCurrent()
        assertNotNull(f.state.value.masterOffer)
        assertTrue(f.inspected.isEmpty())
    }
    @Test fun anExistingProfileForTheMasterIsRefusedBeforeAnyReview() = runTest {
        val existing = member.copy(id = "master-profile", label = "Master", endpoint = masterEndpoint, certificateSha256 = masterPin)
        val f = Fixture(backgroundScope, profiles = listOf(member, existing))
        f.controller.connect(member, manual = true); runCurrent()
        f.controller.followMaster(); runCurrent()
        assertTrue(f.inspected.isEmpty()); assertNull(f.state.value.pendingTrust); assertNull(f.state.value.masterOffer)
        assertEquals(NoticeKind.ERROR, f.state.value.notice?.kind)
        assertTrue(f.state.value.notice!!.text.contains("already uses the pool master"))
        assertEquals(0, f.vault.writes)
    }
    @Test fun aNewUnsavedConnectionKeepsItsGeneratedIdWhenItFollowsTheMaster() = runTest {
        val draft = member.copy(id = "generated-id", certificateSha256 = "d".repeat(64))
        val f = Fixture(backgroundScope, profiles = emptyList(), selected = null)
        f.controller.connect(draft, manual = true); runCurrent()
        assertEquals("generated-id", f.state.value.masterOffer?.profileId)
        f.controller.followMaster(); runCurrent()
        assertEquals("generated-id", f.state.value.pendingTrust?.profile?.id); assertNull(f.state.value.pendingTrust?.previous)
        f.controller.acceptTrust(); runCurrent()
        assertEquals("generated-id", f.vault.document.profiles.single().id); assertEquals(masterEndpoint, f.vault.document.profiles.single().endpoint)
    }
    @Test fun otherFailuresAndUnusableAddressesOfferNothing() = runTest {
        val f = Fixture(backgroundScope)
        f.backend.behavior = { throw RpcFailure("SESSION_AUTHENTICATION_FAILED") }
        f.controller.connect(member, manual = true); runCurrent()
        assertNull(f.state.value.masterOffer); assertTrue(f.state.value.notice!!.text.contains("(SESSION_AUTHENTICATION_FAILED)"))
        f.backend.behavior = { throw RpcFailure("HOST_IS_SLAVE", details = listOf("evil/host")) }
        f.controller.connect(member, manual = true); runCurrent()
        assertNull(f.state.value.masterOffer); assertTrue(f.state.value.notice!!.text.contains("pool member"))
        assertFalse(f.state.value.notice!!.text.contains("evil"))
    }
    @Test fun successAndDisconnectClearAnOffer() = runTest {
        val f = Fixture(backgroundScope)
        f.controller.connect(member, manual = true); runCurrent()
        assertNotNull(f.state.value.masterOffer)
        f.controller.connect(member.copy(endpoint = "https://other.invalid/"), manual = true); runCurrent()   // succeeds
        assertNull(f.state.value.masterOffer)
        f.state.value = f.state.value.copy(masterOffer = MasterFollow.offer(member, RpcFailure("HOST_IS_SLAVE", details = listOf(masterAddress))))
        f.controller.disconnect(); runCurrent()
        assertNull(f.state.value.masterOffer)
    }

    @Test fun healthyEventsLengthenTheStalenessThresholdOnly() = runTest {
        val f = Fixture(backgroundScope)
        f.backend.behavior = { Inventory("pool", "Pool", emptyList(), emptyList(), 0, 0, System.currentTimeMillis()) }
        val sixtySecondsOld = Inventory("pool", "Pool", emptyList(), emptyList(), 0, 0, System.currentTimeMillis() - 60_000)
        f.state.value = f.state.value.copy(inventory = sixtySecondsOld, connected = true)
        f.controller.refreshIfStale(EventPolicy.HEALTHY_STALE_MILLIS); runCurrent()
        assertEquals(0, f.backend.calls.size)                                                    // 60 s < 90 s: events are carrying it
        f.controller.refreshIfStale(); runCurrent()                                              // polling fallback: 30 s
        assertEquals(1, f.backend.calls.size)
    }
}
