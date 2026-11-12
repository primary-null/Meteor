package com.meteor.app

import com.meteor.app.data.*
import com.meteor.app.data.RfbScript.Companion.argb
import com.meteor.app.data.RfbScript.Companion.pixel
import com.meteor.app.data.RfbScript.Companion.rect
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
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
 * The console controller against a scripted RFB server on a plain loopback socket (TLS and the pin are covered
 * by the transport integration test). Real threads and short timeouts, polled with [eventually].
 */
class ConsoleControllerTest {
    private class Plan(val bytes: ByteArray, val closeAfterSend: Boolean = false)

    /** Serves the n-th plan to the n-th connection (the last plan repeats) and records what each client sends. */
    private class RfbServer(private vararg val plans: Plan) : Closeable {
        private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val port get() = server.localPort
        val accepted = AtomicInteger()
        private val received = CopyOnWriteArrayList<ByteArrayOutputStream>()
        init {
            thread(isDaemon = true) {
                while (!server.isClosed) {
                    val socket = try { server.accept() } catch (_: IOException) { break }
                    val log = ByteArrayOutputStream().also { received += it }
                    val plan = plans[minOf(accepted.getAndIncrement(), plans.size - 1)]
                    thread(isDaemon = true) {
                        try {
                            socket.getOutputStream().apply { write(plan.bytes); flush() }
                            // A graceful end (FIN) with the client's bytes still drained, so no reset discards the script.
                            if (plan.closeAfterSend) socket.shutdownOutput()
                            val buffer = ByteArray(4096); val input = socket.getInputStream()
                            while (true) { val count = input.read(buffer); if (count < 0) break; synchronized(log) { log.write(buffer, 0, count) } }
                        } catch (_: IOException) {} finally { runCatching { socket.close() } }
                    }
                }
            }
        }
        fun received(index: Int = 0): ByteArray = received.getOrNull(index)?.let { synchronized(it) { it.toByteArray() } } ?: ByteArray(0)
        override fun close() { server.close() }
    }

    private val profile = Profile("p1", "Lab", "https://xcp.lab/", "root", "secret", "a".repeat(64))
    private val target = ConsoleTarget("p1", "pool-1", "vm-1", "Guest")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val servers = mutableListOf<RfbServer>()
    private val logouts = AtomicInteger()
    private val opens = AtomicInteger()
    private val openedEncodings = CopyOnWriteArrayList<List<Int>>()
    private val approvals = CopyOnWriteArrayList<Triple<String, String, String>>()

    @After fun cleanUp() { scope.cancel(); servers.forEach { it.close() } }

    private fun serve(vararg plans: Plan) = RfbServer(*plans).also { servers += it }
    private fun hello(width: Int = 8, height: Int = 8) = RfbScript(width, height)
    private fun helloWithPixel() = RfbScript(8, 8).update(rect(0, 0, 1, 1, RfbEncoding.RAW, pixel(0x112233))).bytes()

    private fun controller(server: RfbServer? = null, profileExists: Boolean = true, grace: Long = 60_000, probe: Long = 60_000,
        deadline: Long = 45_000, opener: (suspend (Profile, ConsoleTarget, List<Int>) -> ConsoleSession)? = null,
        approve: suspend (String, String, String) -> Unit = { id, authority, pin -> approvals += Triple(id, authority, pin) }) =
        ConsoleController(scope,
            opener = opener ?: { _, _, encodings ->
                opens.incrementAndGet(); openedEncodings += encodings
                ConsoleSession(Socket("127.0.0.1", checkNotNull(server).port), encodings) { logouts.incrementAndGet() }
            },
            profileOf = { id -> profile.takeIf { profileExists && it.id == id } }, approvePin = approve,
            graceMillis = grace, probeMillis = probe, minRequestGapMillis = 0, connectDeadlineMillis = deadline)

    private fun <T : Any> eventually(timeoutMs: Long = 6000, what: String = "condition", block: () -> T?): T {
        val end = System.currentTimeMillis() + timeoutMs
        while (true) {
            block()?.let { return it }
            if (System.currentTimeMillis() > end) fail("Timed out waiting for $what")
            Thread.sleep(10)
        }
    }
    private fun ConsoleController.await(what: String = "state", predicate: (ConsoleUiState) -> Boolean) = eventually(what = what) { state.value.takeIf(predicate) }
    private fun contains(haystack: ByteArray, needle: ByteArray) = haystack.toList().windowed(needle.size).any { it == needle.toList() }
    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
    private fun awaitServerBytes(server: RfbServer, index: Int = 0, vararg needle: Int) =
        eventually(what = "client bytes ${needle.toList()}") { server.received(index).takeIf { contains(it, bytes(*needle)) } }

    // ---- connecting and streaming ----

    @Test fun connectsDecodesFramesAndRequestsTheNextIncrementalUpdate() {
        val server = serve(Plan(helloWithPixel()))
        val console = controller(server)
        console.open(target)
        assertEquals(ConsolePhase.CONNECTING, console.state.value.phase)
        val connected = console.await("connected") { it.phase == ConsolePhase.CONNECTED }
        assertEquals(8, connected.frameWidth); assertEquals("test", connected.serverName); assertNull(connected.message)
        eventually(what = "decoded pixel") { console.framebuffer?.pixels?.get(0)?.takeIf { it == argb(0x112233) } }
        awaitServerBytes(server, 0, 3, 1, 0, 0, 0, 0, 0, 8, 0, 8) // incremental full-screen request after the update
        assertTrue(console.frames.value > 0)
        console.close()
    }

    @Test fun sameTargetDoesNotRestartAndANewTargetReplacesTheOldConsole() {
        val server = serve(Plan(hello().bytes()))
        val console = controller(server)
        console.open(target); console.await { it.phase == ConsolePhase.CONNECTED }
        console.open(target)
        assertEquals(1, opens.get())
        console.open(target.copy(vmUuid = "vm-2", vmName = "Other"))
        eventually(what = "first console logged out") { logouts.get().takeIf { it == 1 } }
        console.await { it.phase == ConsolePhase.CONNECTED && it.target?.vmUuid == "vm-2" }
        assertEquals(2, opens.get())
        console.close()
    }

    @Test fun keyboardAndPointerInputReachTheServerAsRfbMessages() {
        val server = serve(Plan(hello().bytes()))
        val console = controller(server)
        console.open(target); console.await { it.phase == ConsolePhase.CONNECTED }
        console.input.type("Ab")
        console.input.click(RfbProtocol.LEFT, 3, 4)
        console.input.ctrlAltDel()
        val sent = eventually(what = "all input") {
            server.received().takeIf { contains(it, bytes(4, 1, 0, 0, 0, 0, 0xff, 0xff)) && contains(it, bytes(5, 1, 0, 3, 0, 4)) }
        }
        assertTrue("shift down, a down", contains(sent, bytes(4, 1, 0, 0, 0, 0, 0xff, 0xe1, 4, 1, 0, 0, 0, 0, 0, 0x61)))
        assertTrue("b is a plain key press", contains(sent, bytes(4, 1, 0, 0, 0, 0, 0, 0x62, 4, 0, 0, 0, 0, 0, 0, 0x62)))
        assertTrue("click: move, press, release", contains(sent, bytes(5, 0, 0, 3, 0, 4, 5, 1, 0, 3, 0, 4, 5, 0, 0, 3, 0, 4)))
        console.close()
    }

    @Test fun pointerMotionIsCoalescedToTheNewestPosition() {
        val server = serve(Plan(hello(200, 200).bytes()))
        val console = controller(server)
        console.open(target); console.await { it.phase == ConsolePhase.CONNECTED }
        console.input.buttonDown(RfbProtocol.LEFT, 1, 1)
        repeat(100) { console.input.pointerMove(it + 10, 50) }
        console.input.buttonUp(RfbProtocol.LEFT)
        eventually(what = "release") { server.received().takeIf { contains(it, bytes(5, 0, 0, 109, 0, 50)) } }
        val sent = server.received()
        assertTrue("The newest position was sent", contains(sent, bytes(5, 1, 0, 109, 0, 50)))
        val pointerMessages = sent.toList().windowed(6).count { it[0].toInt() == 5 && it[1].toInt() == 1 && it[4].toInt() == 0 && it[5].toInt() == 50 }
        assertTrue("Far fewer than 100 moves went out ($pointerMessages)", pointerMessages < 100)
        console.close()
    }

    @Test fun viewOnlyDropsInputAndReleasingStillWorks() {
        val server = serve(Plan(hello().bytes()))
        val console = controller(server)
        console.open(target); console.await { it.phase == ConsolePhase.CONNECTED }
        console.setViewOnly(true)
        assertTrue(console.state.value.viewOnly)
        console.input.type("x"); console.input.press(KeySyms.RETURN); console.input.click(RfbProtocol.LEFT, 1, 1)
        console.setViewOnly(false)
        console.input.type("y")
        val sent = awaitServerBytes(server, 0, 4, 1, 0, 0, 0, 0, 0, 0x79)
        assertFalse(contains(sent, bytes(4, 1, 0, 0, 0, 0, 0, 0x78)))
        assertFalse(contains(sent, bytes(5, 1, 0, 1, 0, 1)))
        console.close()
    }

    @Test fun pasteTypesTextAsKeyEventsAndStopsWhenAsked() {
        val server = serve(Plan(hello().bytes()))
        val console = controller(server)
        console.open(target); console.await { it.phase == ConsolePhase.CONNECTED }
        console.paste("ab")
        awaitServerBytes(server, 0, 4, 0, 0, 0, 0, 0, 0, 0x62)
        console.await("paste finished") { !it.pasting }
        assertTrue(contains(server.received(), bytes(4, 1, 0, 0, 0, 0, 0, 0x61, 4, 0, 0, 0, 0, 0, 0, 0x61)))
        console.setViewOnly(true); console.paste("zzz")
        assertFalse(console.state.value.pasting)
        console.close()
    }

    @Test fun aQuietScreenIsProbedSoSilenceMeansALostLink() {
        val server = serve(Plan(hello().bytes()))
        val console = controller(server, probe = 100)
        console.open(target); console.await { it.phase == ConsolePhase.CONNECTED }
        awaitServerBytes(server, 0, 3, 0, 0, 0, 0, 0, 0, 1, 0, 1)
        console.close()
    }

    // ---- closing ----

    @Test fun closeReleasesHeldKeysBeforeTheSocketClosesAndLogsOutExactlyOnce() {
        val server = serve(Plan(hello().bytes()))
        val console = controller(server)
        console.open(target); console.await { it.phase == ConsolePhase.CONNECTED }
        console.input.toggle(ConsoleModifier.CTRL)
        console.input.buttonDown(RfbProtocol.LEFT, 2, 2)
        assertTrue(console.input.anyHeld)
        console.close()
        assertEquals(ConsolePhase.IDLE, console.state.value.phase); assertNull(console.state.value.target); assertNull(console.framebuffer)
        awaitServerBytes(server, 0, 4, 0, 0, 0, 0, 0, 0xff, 0xe3) // Control_L released
        awaitServerBytes(server, 0, 5, 0, 0, 2, 0, 2)               // button released
        eventually(what = "logout") { logouts.get().takeIf { it >= 1 } }
        console.close(); Thread.sleep(150)
        assertEquals(1, logouts.get())
        assertFalse(console.input.anyHeld)
        console.input.type("late") // dropped, no crash
    }

    @Test fun closingWhileConnectingCancelsTheAttempt() {
        val gate = CompletableDeferred<Unit>()
        val server = serve(Plan(hello().bytes()))
        val console = controller(server, opener = { _, _, encodings ->
            opens.incrementAndGet(); gate.await()
            ConsoleSession(Socket("127.0.0.1", server.port), encodings) { logouts.incrementAndGet() }
        })
        console.open(target)
        eventually(what = "opener running") { opens.get().takeIf { it == 1 } }
        console.close()
        assertEquals(ConsolePhase.IDLE, console.state.value.phase)
        gate.complete(Unit)
        Thread.sleep(300)
        assertEquals("A cancelled attempt never becomes a live console", ConsolePhase.IDLE, console.state.value.phase)
        assertNull(console.framebuffer)
    }

    // ---- failures, reconnect and compatibility mode ----

    @Test fun aDroppedConnectionFailsCleanlyAndManualReconnectWorks() {
        val server = serve(Plan(hello().bytes(), closeAfterSend = true), Plan(hello().bytes()))
        val console = controller(server)
        console.open(target)
        val failed = console.await("connection lost") { it.phase == ConsolePhase.FAILED }
        assertEquals(ConsoleFailureKind.LOST, failed.failure); assertNotNull(failed.message)
        eventually(what = "logout of the dead link") { logouts.get().takeIf { it == 1 } }
        console.reconnect()
        console.await("reconnected") { it.phase == ConsolePhase.CONNECTED }
        assertEquals(2, opens.get())
        console.close()
    }

    @Test fun anUndecodableServerGetsOneCompatibilityModeReconnect() {
        val server = serve(Plan(hello().raw(byteArrayOf(0x42)).bytes(), closeAfterSend = true), Plan(hello().bytes()))
        val console = controller(server)
        console.open(target)
        assertEquals(ConsoleFailureKind.PROTOCOL, console.await("protocol failure") { it.phase == ConsolePhase.FAILED }.failure)
        console.reconnect()
        val retry = console.await("safe mode connected") { it.phase == ConsolePhase.CONNECTED }
        assertTrue(retry.safeMode)
        assertEquals(listOf(RfbEncoding.DEFAULT, RfbEncoding.SAFE), openedEncodings.toList())
        console.close()
    }

    @Test fun openerFailuresBecomeSpecificStates() {
        val reasons = listOf(
            ConsoleError("This VM is not running", ConsoleFailureKind.NOT_RUNNING) to ConsoleFailureKind.NOT_RUNNING,
            ConsoleError("none", ConsoleFailureKind.NO_CONSOLE) to ConsoleFailureKind.NO_CONSOLE,
        )
        for ((error, kind) in reasons) {
            val console = controller(opener = { _, _, _ -> throw error })
            console.open(target)
            val failed = console.await("failure $kind") { it.phase == ConsolePhase.FAILED }
            assertEquals(kind, failed.failure); assertEquals(error.message, failed.message); assertNull(failed.review)
        }
        val network = controller(opener = { _, _, _ -> throw java.net.ConnectException("secret host detail") })
        network.open(target)
        val state = network.await("network failure") { it.phase == ConsolePhase.FAILED }
        assertEquals(ConsoleFailureKind.NETWORK, state.failure); assertFalse(state.message.orEmpty().contains("secret"))
    }

    @Test fun aMissingProfileFailsInsteadOfConnecting() {
        val console = controller(profileExists = false, opener = { _, _, _ -> error("must not connect") })
        console.open(target)
        assertEquals(ConsolePhase.FAILED, console.await { it.phase == ConsolePhase.FAILED }.phase)
        assertEquals(0, opens.get())
    }

    @Test fun aHungOpenGivesUpAfterTheDeadline() {
        val console = controller(deadline = 200, opener = { _, _, _ -> awaitCancellation() })
        console.open(target)
        assertEquals(ConsoleFailureKind.TIMEOUT, console.await("deadline") { it.phase == ConsolePhase.FAILED }.failure)
    }

    // ---- member host trust ----

    private val review = ServerCertificate("b".repeat(64), "CN=member", 1, 2)

    @Test fun anUntrustedMemberAsksForReviewAndApprovalStoresThePinThenReconnects() {
        val server = serve(Plan(hello().bytes()))
        var pinned = false
        val console = controller(opener = { _, _, encodings ->
            opens.incrementAndGet()
            if (!pinned) throw MemberTrustRequired("203.0.113.5", review, null)
            ConsoleSession(Socket("127.0.0.1", server.port), encodings) { logouts.incrementAndGet() }
        }, approve = { id, authority, pin -> approvals += Triple(id, authority, pin); pinned = true })
        console.open(target)
        val waiting = console.await("trust review") { it.review != null }
        assertEquals(ConsoleFailureKind.MEMBER_TRUST, waiting.failure); assertEquals("203.0.113.5", waiting.review!!.authority)
        assertEquals("b".repeat(64), waiting.review!!.certificate.sha256)
        assertTrue("Nothing is approved before the user acts", approvals.isEmpty())
        console.approveMemberTrust()
        console.await("connected after approval") { it.phase == ConsolePhase.CONNECTED }
        assertEquals(listOf(Triple("p1", "203.0.113.5", "b".repeat(64))), approvals.toList())
        assertEquals(2, opens.get())
        console.close()
    }

    @Test fun decliningTheMemberKeepsTheConsoleClosedAndNothingIsStored() {
        val console = controller(opener = { _, _, _ -> throw MemberTrustRequired("203.0.113.5", review, "c".repeat(64)) })
        console.open(target)
        console.await { it.review != null }
        assertEquals("c".repeat(64), console.state.value.review!!.previousPin)
        console.declineMemberTrust()
        assertNull(console.state.value.review); assertEquals(ConsolePhase.FAILED, console.state.value.phase)
        assertTrue(approvals.isEmpty())
    }

    @Test fun anApprovalThatCannotBeSavedDoesNotTrustTheMember() {
        val console = controller(opener = { _, _, _ -> opens.incrementAndGet(); throw MemberTrustRequired("203.0.113.5", review, null) },
            approve = { _, _, _ -> throw IOException("disk full") })
        console.open(target)
        console.await { it.review != null }
        console.approveMemberTrust()
        val state = console.await("approval failure") { !it.trustBusy && it.message?.contains("could not be saved") == true }
        assertNotNull("The review stays so the user can retry", state.review)
        assertEquals(1, opens.get())
    }

    // ---- background grace ----

    @Test fun backgroundingClosesOnlyAfterTheGracePeriodAndReturningInTimeKeepsTheSession() {
        val server = serve(Plan(hello().bytes()))
        val console = controller(server, grace = 400)
        console.open(target); console.await { it.phase == ConsolePhase.CONNECTED }
        console.appBackgrounded(); Thread.sleep(100); console.appForegrounded()
        Thread.sleep(500)
        assertEquals("Rotation or fold changes stay within the grace period", ConsolePhase.CONNECTED, console.state.value.phase)
        assertEquals(0, logouts.get())
        console.appBackgrounded()
        val closed = console.await("closed in background") { it.phase == ConsolePhase.FAILED }
        assertEquals(ConsoleFailureKind.BACKGROUND, closed.failure); assertEquals(target, closed.target)
        eventually(what = "logout") { logouts.get().takeIf { it == 1 } }
        console.reconnect()
        console.await("reconnected") { it.phase == ConsolePhase.CONNECTED }
        console.close()
    }

    @Test fun backgroundingWithNothingOpenDoesNothing() {
        val console = controller(grace = 50)
        console.appBackgrounded(); Thread.sleep(150)
        assertEquals(ConsolePhase.IDLE, console.state.value.phase)
    }
}
