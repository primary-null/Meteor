package com.meteor.app

import com.meteor.app.data.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

// --- session reuse ---
/** ConnectionController names the profile that owns the gateway's shared XAPI session and ends it on disconnect and forget. */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionOwnershipTest {
    private val first = Profile("first", "First", "https://192.0.2.10/", "tester", "fixture", "a".repeat(64))
    private val second = Profile("second", "Second", "https://198.51.100.20/", "tester", "fixture", "b".repeat(64))

    private class Gateway : MeteorGateway {
        val events = mutableListOf<String>()
        override suspend fun inventory(profile: Profile): Inventory {
            events += "inventory ${profile.id}"
            return Inventory("pool-${profile.id}", "Pool", emptyList(), emptyList(), 0, 0, 1)
        }
        override suspend fun details(profile: Profile, authority: String, uuid: String): VmDetails = error("Unexpected details")
        override suspend fun <T> powerConnection(profile: Profile, action: suspend PowerConnection.() -> T): T = error("Unexpected power access")
        override fun keepSession(profile: Profile) { events += "keep ${profile.id}:${profile.password}" }
        override fun releaseSession(profileId: String?) { events += "release ${profileId ?: "all"}" }
    }
    private class Vault(var document: ProfileDocument) : ProfileStorage {
        override suspend fun load() = document
        override suspend fun save(document: ProfileDocument) { this.document = document }
    }
    private class Journal : OperationStore {
        override suspend fun load() = emptyList<PowerOperation>()
        override suspend fun put(operation: PowerOperation) = error("Connections must not change operation history")
    }

    @Test fun connectionsNameTheOwnerAndDisconnectAndForgetEndIt() = runTest {
        val gateway = Gateway()
        val state = MutableStateFlow(MeteorState(profiles = listOf(first, second), journalProblem = false))
        val controller = ConnectionController(backgroundScope, gateway, Vault(ProfileDocument(listOf(first, second))), Journal(),
            CertificateInspector { error("Unexpected inspection") }, state, {}, {}, {}, { "failed" }).apply { initialized = true }

        controller.switchProfile(first); runCurrent()
        // The owner is named before its first call, so the connection's own inventory read already uses the shared session.
        assertEquals(listOf("keep first:fixture", "inventory first"), gateway.events)
        controller.switchProfile(second); runCurrent()
        assertEquals(listOf("keep second:fixture", "inventory second"), gateway.events.drop(2))
        // A credential edit reconnects with the new identity, which replaces the kept session.
        controller.saveConnection("Second", second.endpoint, second.username, "changed", second.id); runCurrent()
        assertEquals("keep second:changed", gateway.events[4])
        controller.disconnect(); runCurrent()
        assertEquals("release all", gateway.events.last())
        controller.forgetProfile(first.id); runCurrent()
        assertEquals("release first", gateway.events.last())
    }
}
// --- end session reuse ---
