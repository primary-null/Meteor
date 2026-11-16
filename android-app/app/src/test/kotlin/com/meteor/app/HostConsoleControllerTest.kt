package com.meteor.app

import com.meteor.app.data.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * The host console's safety rules in the controller, against a scripted RFB 3.3 server on a loopback socket: it opens
 * view-only on every connection, input needs one confirmation, and Ctrl+Alt+Del never reaches it. A VM console is unchanged.
 */
class HostConsoleControllerTest {
    private class Server : Closeable {
        private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val port get() = server.localPort
        private val logs = CopyOnWriteArrayList<ByteArrayOutputStream>()
        private val sockets = CopyOnWriteArrayList<Socket>()
        init {
            thread(isDaemon = true) {
                while (!server.isClosed) {
                    val socket = try { server.accept() } catch (_: IOException) { break }
                    sockets += socket
                    val log = ByteArrayOutputStream().also { logs += it }
                    thread(isDaemon = true) {
                        try {
                            socket.getOutputStream().apply { write(RfbScript(640, 384, "RFB 003.003\n", name = "XenServer Virtual Terminal").bytes()); flush() }
                            val buffer = ByteArray(4096); val input = socket.getInputStream()
                            while (true) { val count = input.read(buffer); if (count < 0) break; synchronized(log) { log.write(buffer, 0, count) } }
                        } catch (_: IOException) {} finally { runCatching { socket.close() } }
                    }
                }
            }
        }
        fun received(index: Int = 0): ByteArray = logs.getOrNull(index)?.let { synchronized(it) { it.toByteArray() } } ?: ByteArray(0)
        val connections get() = logs.size
        /** The server drops every open connection. */
        fun drop() = sockets.forEach { runCatching { it.close() } }
        override fun close() { server.close() }
    }

    private val profile = Profile("p1", "Lab", "https://xcp.lab/", "root", "secret", "a".repeat(64))
    private val host = ConsoleTarget("p1", "pool-1", "host-1", "xcp-01", ConsoleKind.HOST)
    private val vm = ConsoleTarget("p1", "pool-1", "vm-1", "Guest")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val server = Server()
    private val opened = CopyOnWriteArrayList<ConsoleKind>()
    private val logouts = AtomicInteger()

    @After fun cleanUp() { scope.cancel(); server.close() }

    private fun controller(delays: List<Long> = emptyList()) = ConsoleController(scope,
        opener = { _, target, encodings -> opened += target.kind; ConsoleSession(Socket("127.0.0.1", server.port), encodings) { logouts.incrementAndGet() } },
        profileOf = { profile }, approvePin = { _, _, _ -> }, graceMillis = 60_000, probeMillis = 60_000, minRequestGapMillis = 0,
        reconnectDelaysMillis = delays, pause = {})

    private fun <T : Any> eventually(what: String, block: () -> T?): T {
        val end = System.currentTimeMillis() + 6000
        while (true) { block()?.let { return it }; if (System.currentTimeMillis() > end) fail("Timed out waiting for $what"); Thread.sleep(10) }
    }
    private fun ConsoleController.await(what: String, predicate: (ConsoleUiState) -> Boolean) = eventually(what) { state.value.takeIf(predicate) }
    private fun contains(haystack: ByteArray, needle: ByteArray) = haystack.toList().windowed(needle.size).any { it == needle.toList() }
    private fun keyOrPointer(bytes: ByteArray): Boolean {
        // Client bytes after the handshake: version (12), ClientInit (1), SetPixelFormat (20), SetEncodings (4 + 4n), then only
        // update requests (10 bytes, type 3) unless input was sent.
        var offset = 13 + 20
        val count = ((bytes[offset + 2].toInt() and 0xff) shl 8) or (bytes[offset + 3].toInt() and 0xff)
        offset += 4 + 4 * count
        while (offset < bytes.size) { if (bytes[offset].toInt() != 3) return true; offset += 10 }
        return false
    }

    @Test fun aHostConsoleOpensViewOnlyAndDropsInputUntilConfirmed() {
        val console = controller()
        console.open(host)
        assertTrue("view-only from the start", console.state.value.viewOnly)
        console.await("connected") { it.phase == ConsolePhase.CONNECTED }
        assertTrue(console.state.value.viewOnly)
        assertEquals(listOf(ConsoleKind.HOST), opened.toList())
        console.input.type("root"); console.input.click(RfbProtocol.LEFT, 1, 1); console.input.press(KeySyms.RETURN)
        console.paste("reboot\n")
        Thread.sleep(200)
        assertFalse("nothing but handshake and update requests reached the host", keyOrPointer(server.received()))
        console.close()
    }

    @Test fun turningInputOnAsksOnceAndOnlyTheConfirmationEnablesIt() {
        val console = controller()
        console.open(host); console.await("connected") { it.phase == ConsolePhase.CONNECTED }
        console.setViewOnly(false)
        assertTrue("still view-only while the question is open", console.state.value.viewOnly)
        assertTrue(console.state.value.confirmHostInput)
        assertTrue(console.input.viewOnly)
        console.declineHostInput()
        assertFalse(console.state.value.confirmHostInput); assertTrue(console.state.value.viewOnly)
        console.confirmHostInput()
        assertTrue("a confirmation without a question changes nothing", console.state.value.viewOnly)
        console.setViewOnly(false); console.confirmHostInput()
        assertFalse(console.state.value.viewOnly); assertFalse(console.input.viewOnly)
        console.input.type("a")
        eventually("typed key at the host") { server.received().takeIf { contains(it, byteArrayOf(4, 1, 0, 0, 0, 0, 0, 0x61)) } }
        console.setViewOnly(true)
        assertTrue("turning input off needs no question", console.state.value.viewOnly)
        console.close()
    }

    @Test fun theInputChoiceIsNotRememberedByANewConnectionOrANewSession() {
        val console = controller()
        console.open(host); console.await("connected") { it.phase == ConsolePhase.CONNECTED }
        console.setViewOnly(false); console.confirmHostInput()
        assertFalse(console.state.value.viewOnly)
        console.reconnect()
        assertTrue("a reconnect starts view-only", console.state.value.viewOnly)
        console.await("reconnected") { it.phase == ConsolePhase.CONNECTED }
        assertTrue(console.state.value.viewOnly); assertTrue(console.input.viewOnly)
        console.setViewOnly(false); console.confirmHostInput()
        console.close()
        console.open(host)
        assertTrue("a new session starts view-only", console.state.value.viewOnly)
        console.close()
    }

    @Test fun anAutomaticReconnectAlsoReturnsToViewOnly() {
        val console = controller(delays = listOf(1L))
        console.open(host); console.await("connected") { it.phase == ConsolePhase.CONNECTED }
        console.setViewOnly(false); console.confirmHostInput()
        assertFalse(console.state.value.viewOnly)
        server.drop()
        eventually("second connection") { server.connections.takeIf { it >= 2 } }
        console.await("reconnected") { it.phase == ConsolePhase.CONNECTED && it.autoReconnect == null }
        assertTrue(console.state.value.viewOnly); assertTrue(console.input.viewOnly)
        console.close()
    }

    @Test fun ctrlAltDelNeverReachesAHostConsole() {
        val console = controller()
        console.open(host); console.await("connected") { it.phase == ConsolePhase.CONNECTED }
        console.setViewOnly(false); console.confirmHostInput()
        console.input.ctrlAltDel()
        // The same chord built from sticky modifiers and the Del key, and from a hardware keyboard.
        console.input.toggle(ConsoleModifier.CTRL); console.input.toggle(ConsoleModifier.ALT); console.input.press(KeySyms.DELETE)
        console.input.keyEvent(KeySyms.CONTROL_L, true); console.input.keyEvent(KeySyms.ALT_L, true)
        console.input.keyEvent(KeySyms.DELETE, true); console.input.keyEvent(KeySyms.DELETE, false)
        console.input.keyEvent(KeySyms.ALT_L, false); console.input.keyEvent(KeySyms.CONTROL_L, false)
        console.input.press(KeySyms.DELETE) // Delete alone still works
        val sent = eventually("plain Delete") { server.received().takeIf { contains(it, byteArrayOf(4, 1, 0, 0, 0, 0, 0xff.toByte(), 0xff.toByte())) } }
        Thread.sleep(100)
        val deleteDowns = server.received().toList().windowed(8).count { it == byteArrayOf(4, 1, 0, 0, 0, 0, 0xff.toByte(), 0xff.toByte()).toList() }
        assertEquals("only the plain Delete went out", 1, deleteDowns)
        assertTrue(sent.isNotEmpty())
        assertFalse(ConsoleWording.offersCtrlAltDel(ConsoleKind.HOST))
        console.close()
    }

    @Test fun aVmConsoleIsUnchanged() {
        val console = controller()
        console.open(vm)
        assertFalse(console.state.value.viewOnly)
        console.await("connected") { it.phase == ConsolePhase.CONNECTED }
        console.setViewOnly(true); console.setViewOnly(false)
        assertFalse("no question for a VM", console.state.value.confirmHostInput); assertFalse(console.state.value.viewOnly)
        console.input.ctrlAltDel()
        eventually("Ctrl+Alt+Del at the VM") { server.received().takeIf { contains(it, byteArrayOf(4, 1, 0, 0, 0, 0, 0xff.toByte(), 0xff.toByte())) } }
        assertTrue(ConsoleWording.offersCtrlAltDel(ConsoleKind.VM))
        console.close()
        // After a host console, a VM console gets Ctrl+Alt+Del back.
        console.open(host); console.close(); console.open(vm)
        assertFalse(console.input.blockCtrlAltDel)
        console.close()
    }

    @Test fun theScreenNamesTheHostAndSaysWhereInputGoes() {
        assertEquals("Host console · xcp-01", ConsoleWording.title(ConsoleKind.HOST, "xcp-01"))
        assertEquals("Guest", ConsoleWording.title(ConsoleKind.VM, "Guest"))
        assertEquals("Typing goes to the host", ConsoleWording.typingStatus(ConsoleKind.HOST, false))
        assertEquals("Typing goes to the VM", ConsoleWording.typingStatus(ConsoleKind.VM, false))
        val text = ConsoleWording.confirmInputText("xcp-01")
        assertTrue(text.contains("xcp-01")); assertTrue(text.contains("hypervisor's own console"))
        assertEquals("Host is not live", ConsoleWording.failureTitle(ConsoleFailureKind.NOT_RUNNING, ConsoleKind.HOST))
        assertEquals("VM is not running", ConsoleWording.failureTitle(ConsoleFailureKind.NOT_RUNNING, ConsoleKind.VM))
        assertEquals("This host is a pool member. Nothing was sent to it.", ConsoleWording.failureHint(ConsoleFailureKind.MEMBER_TRUST, ConsoleKind.HOST))
        assertTrue(ConsoleWording.memberTrustIntro(ConsoleKind.HOST, "m:1").startsWith("This host's console is served by m:1"))
    }
}
