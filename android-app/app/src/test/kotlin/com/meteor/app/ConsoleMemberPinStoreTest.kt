package com.meteor.app

import com.meteor.app.data.*
import com.meteor.app.ui.BackStack
import com.meteor.app.ui.Destination
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

/** Storing an approved member-host pin through the connection controller, and the Console navigation destination. */
@OptIn(ExperimentalCoroutinesApi::class)
class ConsoleMemberPinStoreTest {
    private val original = Profile("profile", "Fixture", "https://test.invalid/", "tester", "fixture", "a".repeat(64))
    private val memberPin = "b".repeat(64)
    private class Vault(var document: ProfileDocument) : ProfileStorage {
        var fail = false
        var writes = 0
        override suspend fun load() = document
        override suspend fun save(document: ProfileDocument) {
            writes++
            if (fail) throw IOException("fixture write failure")
            this.document = document
        }
    }
    private class Backend : MeteorGateway {
        override suspend fun inventory(profile: Profile): Inventory = Inventory("pool", "Pool", emptyList(), emptyList(), 0, 0, 1)
        override suspend fun details(profile: Profile, authority: String, uuid: String): VmDetails = error("Unexpected details")
        override suspend fun <T> powerConnection(profile: Profile, action: suspend PowerConnection.() -> T): T = error("Unexpected power access")
    }
    private class Journal : OperationStore {
        override suspend fun load() = emptyList<PowerOperation>()
        override suspend fun put(operation: PowerOperation) = error("Console trust must not change operation history")
    }
    private inner class Fixture(scope: CoroutineScope, vaultProblem: Boolean = false) {
        val vault = Vault(ProfileDocument(listOf(original), "profile"))
        val state = MutableStateFlow(MeteorState(profiles = listOf(original), selectedId = "profile", journalProblem = false, vaultProblem = vaultProblem))
        val controller = ConnectionController(scope, Backend(), vault, Journal(), CertificateInspector { error("No inspection") },
            state, {}, {}, {}, { "failed" }).apply { initialized = true }
    }

    @Test fun anApprovedPinIsSavedInTheEncryptedProfileAndVisibleInTheSelectedProfile() = runTest {
        val f = Fixture(backgroundScope)
        f.controller.approveMemberPin("profile", "203.0.113.5", memberPin)
        val saved = f.vault.document.profiles.single()
        assertEquals(mapOf("203.0.113.5" to memberPin), saved.memberPins)
        assertEquals(original.copy(memberPins = saved.memberPins), saved)
        assertEquals("The profile id, credentials and coordinator pin are preserved", original.id, saved.id)
        assertEquals("profile", f.vault.document.selectedId)
        assertEquals(saved, f.state.value.selectedProfile)
        assertEquals(1, f.vault.writes)
    }

    @Test fun aSecondMemberIsAddedWithoutReplacingTheFirst() = runTest {
        val f = Fixture(backgroundScope)
        f.controller.approveMemberPin("profile", "203.0.113.5", memberPin)
        f.controller.approveMemberPin("profile", "203.0.113.6", "c".repeat(64))
        assertEquals(setOf("203.0.113.5", "203.0.113.6"), f.vault.document.profiles.single().memberPins.keys)
    }

    @Test fun aFailedWriteThrowsAndLeavesTheProfileUntouched() = runTest {
        val f = Fixture(backgroundScope)
        f.vault.fail = true
        assertThrows(IOException::class.java) { runBlocking { f.controller.approveMemberPin("profile", "203.0.113.5", memberPin) } }
        assertTrue(f.state.value.selectedProfile!!.memberPins.isEmpty()); assertTrue(f.vault.document.profiles.single().memberPins.isEmpty())
    }

    @Test fun anUnavailableVaultTrustsTheMemberForThisSessionOnlyAndSaysSo() = runTest {
        val f = Fixture(backgroundScope, vaultProblem = true)
        f.controller.approveMemberPin("profile", "203.0.113.5", memberPin)
        assertEquals(0, f.vault.writes)
        assertEquals(mapOf("203.0.113.5" to memberPin), f.state.value.selectedProfile!!.memberPins)
        assertEquals(NoticeKind.WARNING, f.state.value.notice?.kind)
    }

    @Test fun invalidApprovalsAndUnknownProfilesAreRejectedWithoutWriting() = runTest {
        val f = Fixture(backgroundScope)
        assertThrows(IllegalArgumentException::class.java) { runBlocking { f.controller.approveMemberPin("missing", "203.0.113.5", memberPin) } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { f.controller.approveMemberPin("profile", "test.invalid", memberPin) } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { f.controller.approveMemberPin("profile", "203.0.113.5", "nothex") } }
        assertEquals(0, f.vault.writes)
    }

    // ---- navigation ----

    private val console = Destination.Console("profile", "pool", "vm", "Guest")

    @Test fun theConsoleIsAFullScreenDestinationThatIsNeverRestored() {
        assertEquals("console", console.key)
        assertFalse(console.restorable)
        val stack = BackStack().select(Destination.Fleet).open(console)
        assertEquals(listOf(Destination.Overview, Destination.Fleet, console), stack.entries)
        assertEquals(Destination.Fleet, stack.selectedItem)
        assertEquals("Back leaves the console for the VM list", Destination.Fleet, stack.back().current)
        assertEquals(listOf(Destination.Overview, Destination.Fleet), BackStack.restore(stack.keys).entries)
        assertEquals(listOf(Destination.Overview), BackStack.restore(listOf("console")).entries)
        assertEquals(listOf(Destination.Overview), stack.select(Destination.Overview).entries)
    }
}
