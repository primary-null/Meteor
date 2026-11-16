package com.meteor.app.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.net.URI

/** Finding a host's console and the identity rules around it, against plain records and the recorded 8.3 host. */
class HostConsoleTest {
    private val profile = Profile("p1", "Lab", "https://xcp.lab/", "root", "secret", "a".repeat(64))
    private val dom0 = mapOf<String, Any?>("is_control_domain" to true, "resident_on" to "host-1", "is_a_template" to false, "is_a_snapshot" to false)

    @Test fun theHostRecordNamesItsControlDomain() {
        assertEquals("vm-0", HostConsoleRules.namedControlDomain(mapOf("control_domain" to "vm-0")))
        assertNull(HostConsoleRules.namedControlDomain(mapOf("control_domain" to "OpaqueRef:NULL")))
        assertNull("an older server without the field", HostConsoleRules.namedControlDomain(emptyMap()))
    }

    @Test fun onlyTheControlDomainResidentOnThisHostQualifies() {
        assertTrue(HostConsoleRules.isControlDomainOf("host-1", dom0))
        assertFalse("another host's control domain", HostConsoleRules.isControlDomainOf("host-2", dom0))
        assertFalse("a guest", HostConsoleRules.isControlDomainOf("host-1", dom0 + ("is_control_domain" to false)))
        assertFalse("a template", HostConsoleRules.isControlDomainOf("host-1", dom0 + ("is_a_template" to true)))
    }

    @Test fun theFallbackTakesTheOneControlDomainOnTheHostAndNeverGuesses() {
        val guest = dom0 + ("is_control_domain" to false)
        val other = dom0 + ("resident_on" to "host-2")
        assertEquals("d1", HostConsoleRules.fallbackControlDomain("host-1", mapOf("g" to guest, "d1" to dom0, "d2" to other)))
        assertNull(HostConsoleRules.fallbackControlDomain("host-1", mapOf("g" to guest)))
        assertNull("two candidates are not chosen between", HostConsoleRules.fallbackControlDomain("host-1", mapOf("a" to dom0, "b" to dom0)))
    }

    @Test fun theRfbConsoleIsChosenAndTheTextConsoleNever() {
        val rfb = mapOf("VM" to "d", "protocol" to "rfb", "location" to "https://h/console?ref=OpaqueRef:r")
        val text = mapOf("VM" to "d", "protocol" to "vt100", "location" to "https://h/console?ref=OpaqueRef:t")
        assertEquals(rfb["location"], HostConsoleRules.chooseConsole("d", listOf(text, rfb)))
        val textOnly = assertThrows(ConsoleError::class.java) { HostConsoleRules.chooseConsole("d", listOf(text)) }
        assertEquals(HostConsoleRules.TEXT_ONLY, textOnly.message); assertEquals(ConsoleFailureKind.NO_CONSOLE, textOnly.kind)
        assertEquals(HostConsoleRules.NO_CONSOLE, assertThrows(ConsoleError::class.java) { HostConsoleRules.chooseConsole("d", emptyList()) }.message)
        assertEquals("a console of another VM does not count", HostConsoleRules.NO_CONSOLE,
            assertThrows(ConsoleError::class.java) { HostConsoleRules.chooseConsole("d", listOf(rfb + ("VM" to "guest"))) }.message)
    }

    @Test fun theLocationMustNameTheHostItselfOrForTheCoordinatorThisConnectionsAddress() {
        fun check(location: String, address: String, coordinator: Boolean) =
            runCatching { HostConsoleRules.checkLocation(URI(location), HostConsoleLocation(location, address, coordinator), profile) }.isSuccess
        assertTrue(check("https://192.0.2.1/console?ref=x", "192.0.2.1", coordinator = false))
        assertTrue("IPv6 brackets and case", check("https://[FD00::1]/console?ref=x", "fd00::1", coordinator = false))
        assertTrue("the coordinator reached by this connection's own name", check("https://xcp.lab/console?ref=x", "192.0.2.1", coordinator = true))
        assertFalse("a member may not borrow the coordinator's address", check("https://xcp.lab/console?ref=x", "192.0.2.2", coordinator = false))
        assertFalse("another host's address", check("https://192.0.2.9/console?ref=x", "192.0.2.1", coordinator = false))
        assertFalse("a host without an address", check("https://192.0.2.9/console?ref=x", "", coordinator = false))
        val error = assertThrows(ConsoleError::class.java) {
            HostConsoleRules.checkLocation(URI("https://192.0.2.9/console?ref=x"), HostConsoleLocation("", "192.0.2.1", false), profile)
        }
        assertEquals(HostConsoleRules.WRONG_HOST, error.message)
        assertFalse("not retried automatically", ReconnectPolicy.retryable(error.kind))
    }

    @Test fun theHostDetailsSayWhyTheButtonIsDisabled() {
        assertEquals("Connect to open the host console.", HostConsoleRules.unavailableReason(false, true, HostConsoleOffer(null)))
        assertEquals("The host is offline, so it has no console.", HostConsoleRules.unavailableReason(true, false, HostConsoleOffer(null)))
        assertEquals("Checking the host console…", HostConsoleRules.unavailableReason(true, true, null))
        assertEquals(HostConsoleRules.TEXT_ONLY, HostConsoleRules.unavailableReason(true, true, HostConsoleOffer(HostConsoleRules.TEXT_ONLY)))
        assertEquals("The host console could not be checked: timed out", HostConsoleRules.unavailableReason(true, true, null, "timed out"))
        assertNull(HostConsoleRules.unavailableReason(true, true, HostConsoleOffer(null)))
    }

    @Test fun theRecordedHostNamesItsControlDomainWhoseRfbConsoleIsOnTheHostsOwnAddress() {
        val host = Live83.host()
        val dom0Ref = HostConsoleRules.namedControlDomain(host)!!
        val vms = Live83.records("VM")
        assertTrue(HostConsoleRules.isControlDomainOf(Live83.records("host").keys.single(), vms.getValue(dom0Ref)))
        assertEquals("the fallback finds the same VM", dom0Ref, HostConsoleRules.fallbackControlDomain(Live83.records("host").keys.single(), vms))
        val consoles = Live83.records("console").values.toList()
        val location = HostConsoleRules.chooseConsole(dom0Ref, consoles)
        assertEquals("rfb", consoles.single { it["location"] == location }["protocol"])
        assertEquals(host["address"], ConsoleSession.location(location).host)
    }

    @Test fun theGatewayFindsTheRecordedHostsConsoleWithReadsOnly() = runBlocking {
        Live83Server().use { server ->
            val offer = XapiGateway().hostConsoleOffer(server.profile, Live83.pool()["uuid"] as String, Live83.host()["uuid"] as String)
            assertNull(offer.reason)
            assertTrue("host.get_record" in server.calls && "console.get_record" in server.calls)
            assertFalse("the guest's console list is not read", "VM.get_consoles" in server.calls)
            server.assertReadOnly()
        }
    }
}
