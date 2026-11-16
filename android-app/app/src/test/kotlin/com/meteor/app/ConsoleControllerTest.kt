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
import kotlinx.coroutines.currentCoroutineContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ConnectException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
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

    /** Reconnect pauses that wait for the test: [requested] lists each wait, [release] ends the oldest one. No real time passes. */
    private class Pauses {
        val requested = CopyOnWriteArrayList<Long>()
        private val gates = LinkedBlockingQueue<CompletableDeferred<Unit>>()
        suspend fun pause(millis: Long) { val gate = CompletableDeferred<Unit>(); gates.add(gate); requested += millis; gate.await() }
        fun release() { gates.poll()?.complete(Unit) }
    }

    /** A socket over scripted bytes whose output can be stalled and whose input can be cut ([drop]) at a chosen moment. */
    private class ScriptedSocket(script: ByteArray) : Socket() {
        @Volatile var stalled = false
        @Volatile private var closedFlag = false
        private val cut = CountDownLatch(1)
        private val written = ByteArrayOutputStream()
        private val inbound = object : InputStream() {
            private var index = 0
            override fun read(): Int { val one = ByteArray(1); return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (index < script.size) {
                    val count = minOf(length, script.size - index)
                    System.arraycopy(script, index, buffer, offset, count); index += count
                    return count
                }
                cut.await()
                return -1
            }
        }
        private val outbound = object : OutputStream() {
            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
            override fun write(b: ByteArray, off: Int, len: Int) {
                while (stalled && !closedFlag) Thread.sleep(2)
                if (closedFlag) throw java.net.SocketException("Socket closed")
                synchronized(written) { written.write(b, off, len) }
            }
        }
        override fun getInputStream(): InputStream = inbound
        override fun getOutputStream(): OutputStream = outbound
        override fun setSoTimeout(timeout: Int) {}
        override fun isClosed() = closedFlag
        override fun close() { closedFlag = true; cut.countDown() }
        /** The link ends from the server's side. */
        fun drop() = cut.countDown()
        /** How many key-down messages for [keysym] the client has written. */
        fun keyDowns(keysym: Int): Int {
            val bytes = synchronized(written) { written.toByteArray() }
            val needle = byteArrayOf(4, 1, 0, 0, (keysym ushr 24).toByte(), (keysym ushr 16).toByte(), (keysym ushr 8).toByte(), keysym.toByte())
            var count = 0
            var index = 0
            while (index + needle.size <= bytes.size) { if (bytes.copyOfRange(index, index + needle.size).contentEquals(needle)) { count++; index += needle.size } else index++ }
            return count
        }
    }

    private val sockets = CopyOnWriteArrayList<ScriptedSocket>()
    private fun scriptedOpener(onOpen: () -> Unit = {}): suspend (Profile, ConsoleTarget, List<Int>) -> ConsoleSession = { _, _, encodings ->
        opens.incrementAndGet(); onOpen()
        val socket = ScriptedSocket(hello().bytes()).also { sockets += it }
        ConsoleSession(socket, encodings) { logouts.incrementAndGet() }
    }

    private fun controller(server: RfbServer? = null, profileExists: Boolean = true, grace: Long = 60_000, probe: Long = 60_000,
        deadline: Long = 45_000, opener: (suspend (Profile, ConsoleTarget, List<Int>) -> ConsoleSession)? = null,
        approve: suspend (String, String, String) -> Unit = { id, authority, pin -> approvals += Triple(id, authority, pin) },
        delays: List<Long> = emptyList(), pause: suspend (Long) -> Unit = {}, stable: Long = 30_000, clock: () -> Long = System::currentTimeMillis,
        queueCapacity: Int = ConsoleController.QUEUE_CAPACITY, pasteDelay: Long = ConsoleController.PASTE_DELAY_MILLIS) =
        ConsoleController(scope,
            opener = opener ?: { _, _, encodings ->
                opens.incrementAndGet(); openedEncodings += encodings
                ConsoleSession(Socket("127.0.0.1", checkNotNull(server).port), encodings) { logouts.incrementAndGet() }
            },
            profileOf = { id -> profile.takeIf { profileExists && it.id == id } }, approvePin = approve,
            graceMillis = grace, probeMillis = probe, minRequestGapMillis = 0, connectDeadlineMillis = deadline,
            reconnectDelaysMillis = delays, stableMillis = stable, pause = pause, clock = clock, queueCapacity = queueCapacity, pasteDelayMillis = pasteDelay)

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

    // ---- long paste, queue bound ----

    private fun connectedScripted(queueCapacity: Int = ConsoleController.QUEUE_CAPACITY): Pair<ConsoleController, ScriptedSocket> {
        val console = controller(opener = scriptedOpener(), queueCapacity = queueCapacity, pasteDelay = 0)
        console.open(target); console.await("connected") { it.phase == ConsolePhase.CONNECTED }
        return console to sockets.last()
    }

    @Test fun aLongPasteWaitsForRoomInsteadOfFailingTheConsole() {
        val (console, socket) = connectedScripted(queueCapacity = 64)
        socket.stalled = true
        console.paste("a".repeat(100)) // 200 key events, far more than the 64-command queue
        console.await("paste in progress") { it.pasting && it.pasteTotal == 100 }
        Thread.sleep(300)
        val waiting = console.state.value
        assertEquals("A stalled connection does not end the console", ConsolePhase.CONNECTED, waiting.phase)
        assertTrue(waiting.pasting); assertTrue("The paste is held back", waiting.pasteDone < 100)
        socket.stalled = false
        console.await("paste finished") { !it.pasting }
        assertEquals(ConsolePhase.CONNECTED, console.state.value.phase)
        eventually(what = "every character typed") { socket.keyDowns('a'.code).takeIf { it == 100 } }
        console.close()
    }

    @Test fun aPasteCanBeCancelledWhileItWaitsAndTypesNoMore() {
        val (console, socket) = connectedScripted(queueCapacity = 64)
        socket.stalled = true
        console.paste("a".repeat(100))
        console.await("paste in progress") { it.pasting }
        Thread.sleep(100)
        console.cancelPaste()
        val stopped = console.await("paste stopped") { !it.pasting }
        assertEquals(0, stopped.pasteTotal); assertEquals(0, stopped.pasteDone)
        socket.stalled = false
        Thread.sleep(300)
        assertTrue("Only what was already queued was typed", socket.keyDowns('a'.code) < 100)
        assertEquals(ConsolePhase.CONNECTED, console.state.value.phase)
        console.close()
    }

    @Test fun aPasteCountsTheCharactersItSkipsAndNeverSendsThem() {
        val (console, socket) = connectedScripted(queueCapacity = 8)
        socket.stalled = true
        console.paste("ab\u00e9" + "c".repeat(50))
        val progress = console.await("paste in progress") { it.pasting && it.pasteTotal == 52 }
        assertEquals(1, progress.pasteSkipped)
        socket.stalled = false
        console.await("finished") { !it.pasting }
        eventually(what = "typed") { socket.keyDowns('c'.code).takeIf { it == 50 } }
        assertEquals(1, socket.keyDowns('a'.code)); assertEquals(0, socket.keyDowns(0xe9)); assertEquals(0, socket.keyDowns(0x01000000 or 0xe9))
        console.close()
    }

    @Test fun aTextWithNothingTypableIsNotPastedAtAll() {
        val (console, socket) = connectedScripted()
        console.paste("\u00e9\u20ac")
        Thread.sleep(100)
        assertFalse(console.state.value.pasting)
        console.close()
    }

    @Test fun switchingToViewOnlyStopsAPasteInProgress() {
        val (console, socket) = connectedScripted(queueCapacity = 64)
        socket.stalled = true
        console.paste("a".repeat(100))
        console.await { it.pasting }
        console.setViewOnly(true)
        console.await("paste stopped") { !it.pasting }
        socket.stalled = false
        console.close()
    }

    @Test fun liveTypingSurvivesABriefStallButAFullQueueStillEndsTheLink() {
        val (console, socket) = connectedScripted(queueCapacity = 64)
        socket.stalled = true
        console.input.type("hello") // 10 events, well inside the bound
        socket.stalled = false
        eventually(what = "typed after the stall") { socket.keyDowns('o'.code).takeIf { it == 1 } }
        assertEquals(ConsolePhase.CONNECTED, console.state.value.phase)
        socket.stalled = true
        repeat(100) { console.input.press('a'.code) } // 200 events against a 64-command queue
        val failed = console.await("queue full") { it.phase == ConsolePhase.FAILED }
        assertEquals(ConsoleFailureKind.LOST, failed.failure); assertTrue(failed.message!!.contains("fast enough"))
        console.close()
    }

    // ---- automatic reconnect ----

    private val backoff = ReconnectPolicy.DELAYS_MILLIS

    @Test fun aLostConsoleReconnectsByItselfAfterTheFirstPause() {
        val pauses = Pauses()
        val console = controller(opener = scriptedOpener(), delays = backoff, pause = pauses::pause)
        console.open(target); console.await { it.phase == ConsolePhase.CONNECTED }
        sockets[0].drop()
        val waiting = console.await("reconnecting") { it.autoReconnect != null }
        assertEquals(ConsolePhase.CONNECTING, waiting.phase); assertEquals(AutoReconnect(1, 4), waiting.autoReconnect)
        assertNull(waiting.failure); assertEquals("Reconnecting… try 1 of 4", waiting.message)
        eventually(what = "the first pause") { pauses.requested.takeIf { it.size == 1 } }
        assertEquals(listOf(2_000L), pauses.requested.toList())
        assertEquals("Nothing reconnects before the pause ends", 1, opens.get())
        pauses.release()
        val back = console.await("connected again") { it.phase == ConsolePhase.CONNECTED }
        assertNull(back.autoReconnect); assertEquals(2, opens.get())
        eventually(what = "the dead link was logged out") { logouts.get().takeIf { it == 1 } }
        console.close()
    }

    @Test fun retriesBackOffTwoFiveTenTwentySecondsThenStopAtTheManualReconnect() {
        val pauses = Pauses(); val failing = AtomicBoolean(false)
        val console = controller(opener = { profile, t, encodings ->
            if (failing.get()) { opens.incrementAndGet(); throw ConnectException("no route") } else scriptedOpener()(profile, t, encodings)
        }, delays = backoff, pause = pauses::pause)
        console.open(target); console.await { it.phase == ConsolePhase.CONNECTED }
        failing.set(true); sockets[0].drop()
        for (round in 1..4) {
            eventually(what = "pause $round") { pauses.requested.takeIf { it.size == round } }
            assertEquals(AutoReconnect(round, 4), console.state.value.autoReconnect)
            pauses.release()
        }
        val stopped = console.await("retries used up") { it.phase == ConsolePhase.FAILED }
        assertEquals(listOf(2_000L, 5_000L, 10_000L, 20_000L), pauses.requested.toList())
        assertEquals(ConsoleFailureKind.NETWORK, stopped.failure); assertNull(stopped.autoReconnect)
        assertTrue(stopped.message!!.endsWith("Automatic reconnecting stopped after 4 tries."))
        assertEquals("The first connection plus four tries", 5, opens.get())
        Thread.sleep(150); assertEquals("No fifth try", 4, pauses.requested.size)
        failing.set(false); console.reconnect()
        console.await("manual reconnect") { it.phase == ConsolePhase.CONNECTED }
        console.close()
    }

    @Test fun aLinkThatKeepsDroppingRightAfterConnectingStillStopsAfterFourTries() {
        val pauses = Pauses()
        val console = controller(opener = scriptedOpener(), delays = backoff, pause = pauses::pause)
        console.open(target); console.await { it.phase == ConsolePhase.CONNECTED }
        sockets[0].drop()
        for (round in 1..4) {
            eventually(what = "pause $round") { pauses.requested.takeIf { it.size == round } }
            pauses.release()
            eventually(what = "reconnected $round") { sockets.getOrNull(round) }
            console.await("connected $round") { it.phase == ConsolePhase.CONNECTED }
            sockets[round].drop()
        }
        val stopped = console.await("retries used up") { it.phase == ConsolePhase.FAILED }
        assertEquals(ConsoleFailureKind.LOST, stopped.failure); assertEquals(5, opens.get())
    }

    @Test fun aLinkThatStayedUpStartsAFreshRunOfTries() {
        val pauses = Pauses(); val now = AtomicLong(0)
        val console = controller(opener = scriptedOpener(), delays = backoff, pause = pauses::pause, stable = 1_000, clock = { now.get() })
        console.open(target); console.await { it.phase == ConsolePhase.CONNECTED }
        now.set(5_000); sockets[0].drop()                       // up for 5 s: a fresh run, try 1
        eventually(what = "pause 1") { pauses.requested.takeIf { it.size == 1 } }; pauses.release()
        console.await("connected 2") { it.phase == ConsolePhase.CONNECTED && sockets.size == 2 }
        now.set(5_100); sockets[1].drop()                       // up for 0.1 s: the run continues, try 2
        eventually(what = "pause 2") { pauses.requested.takeIf { it.size == 2 } }; pauses.release()
        console.await("connected 3") { it.phase == ConsolePhase.CONNECTED && sockets.size == 3 }
        now.set(9_000); sockets[2].drop()                       // up for 3.9 s: a fresh run again
        eventually(what = "pause 3") { pauses.requested.takeIf { it.size == 3 } }
        assertEquals(listOf(2_000L, 5_000L, 2_000L), pauses.requested.toList())
        console.close()
    }

    @Test fun cancellingTheReconnectLeavesTheManualReconnect() {
        val pauses = Pauses()
        val console = controller(opener = scriptedOpener(), delays = backoff, pause = pauses::pause)
        console.open(target); console.await { it.phase == ConsolePhase.CONNECTED }
        sockets[0].drop()
        console.await("reconnecting") { it.autoReconnect != null }
        eventually(what = "pause") { pauses.requested.takeIf { it.size == 1 } }
        console.cancelAutoReconnect()
        val cancelled = console.state.value
        assertEquals(ConsolePhase.FAILED, cancelled.phase); assertEquals(ConsoleFailureKind.LOST, cancelled.failure)
        assertNull(cancelled.autoReconnect); assertNotNull(cancelled.message)
        pauses.release(); Thread.sleep(150)
        assertEquals("Nothing reconnects after Cancel", 1, opens.get()); assertEquals(ConsolePhase.FAILED, console.state.value.phase)
        console.reconnect()
        console.await("manual reconnect") { it.phase == ConsolePhase.CONNECTED }
        console.close()
    }

    @Test fun closingTheConsoleWhileItWaitsToReconnectNeverReconnects() {
        val pauses = Pauses()
        val console = controller(opener = scriptedOpener(), delays = backoff, pause = pauses::pause)
        console.open(target); console.await { it.phase == ConsolePhase.CONNECTED }
        sockets[0].drop()
        eventually(what = "pause") { pauses.requested.takeIf { it.size == 1 } }
        console.close()
        pauses.release(); Thread.sleep(150)
        assertEquals(ConsolePhase.IDLE, console.state.value.phase); assertNull(console.state.value.target)
        assertEquals(1, opens.get())
    }

    @Test fun rejectedCredentialsCertificateProblemsAndAHaltedVmAreNeverRetried() {
        val cases = listOf(
            ConsoleError("Access refused.", ConsoleFailureKind.AUTH),
            ConsoleError("Certificate changed.", ConsoleFailureKind.CERTIFICATE),
            ConsoleError("This VM is not running, so it has no console. Start it from VM details, then reconnect.", ConsoleFailureKind.NOT_RUNNING),
            ConsoleError("Unsupported.", ConsoleFailureKind.UNSUPPORTED),
            MemberTrustRequired("203.0.113.5", review, null),
        )
        for (error in cases) {
            opens.set(0); sockets.clear()
            val pauses = Pauses()
            val console = controller(opener = { profile, t, encodings -> if (opens.get() >= 1) { opens.incrementAndGet(); throw error } else scriptedOpener()(profile, t, encodings) },
                delays = backoff, pause = pauses::pause)
            console.open(target); console.await { it.phase == ConsolePhase.CONNECTED }
            sockets[0].drop()
            eventually(what = "pause before ${error.kind}") { pauses.requested.takeIf { it.size == 1 } }
            pauses.release()
            val stopped = console.await("stopped by ${error.kind}") { it.phase == ConsolePhase.FAILED }
            assertEquals(error.kind, stopped.failure); assertEquals(error.message, stopped.message); assertNull(stopped.autoReconnect)
            Thread.sleep(100)
            assertEquals("${error.kind}: one try only", 1, pauses.requested.size); assertEquals(2, opens.get())
            console.close()
        }
    }

    @Test fun aConsoleThatNeverConnectedIsNotRetriedByItself() {
        val pauses = Pauses()
        val console = controller(opener = { _, _, _ -> opens.incrementAndGet(); throw ConnectException("no route") }, delays = backoff, pause = pauses::pause)
        console.open(target)
        assertEquals(ConsoleFailureKind.NETWORK, console.await("failed") { it.phase == ConsolePhase.FAILED }.failure)
        Thread.sleep(150)
        assertTrue(pauses.requested.isEmpty()); assertEquals(1, opens.get())
    }

    @Test fun anUndecodableConsoleIsRetriedOnceInCompatibilityModeAndThenLeftAlone() {
        val server = serve(Plan(hello().raw(byteArrayOf(0x42)).bytes(), closeAfterSend = true), Plan(hello().bytes()))
        val pauses = Pauses()
        val console = controller(server, delays = backoff, pause = pauses::pause)
        console.open(target)
        eventually(what = "pause") { pauses.requested.takeIf { it.size == 1 } }
        assertTrue("The retry is already marked compatibility mode", console.state.value.safeMode)
        pauses.release()
        assertTrue(console.await("connected in compatibility mode") { it.phase == ConsolePhase.CONNECTED }.safeMode)
        assertEquals(listOf(RfbEncoding.DEFAULT, RfbEncoding.SAFE), openedEncodings.toList())
        console.close()

        opens.set(0); openedEncodings.clear()
        val broken = serve(Plan(hello().raw(byteArrayOf(0x42)).bytes(), closeAfterSend = true))
        val morePauses = Pauses()
        val second = controller(broken, delays = backoff, pause = morePauses::pause)
        second.open(target)
        eventually(what = "pause") { morePauses.requested.takeIf { it.size == 1 } }
        morePauses.release()
        val failed = second.await("still undecodable") { it.phase == ConsolePhase.FAILED }
        assertEquals(ConsoleFailureKind.PROTOCOL, failed.failure)
        Thread.sleep(100)
        assertEquals("Only one compatibility-mode retry", 1, morePauses.requested.size)
    }

    @Test fun returningFromTheBackgroundReconnectsAConsoleThatWasClosedOnlyForTheBackground() {
        val pauses = Pauses()
        val console = controller(opener = scriptedOpener(), grace = 80, delays = backoff, pause = pauses::pause)
        console.open(target); console.await { it.phase == ConsolePhase.CONNECTED }
        console.appBackgrounded()
        assertEquals(ConsoleFailureKind.BACKGROUND, console.await("closed in the background") { it.phase == ConsolePhase.FAILED }.failure)
        Thread.sleep(100); assertTrue("Nothing reconnects while the app is away", pauses.requested.isEmpty()); assertEquals(1, opens.get())
        console.appForegrounded()
        assertEquals(AutoReconnect(0, 4), console.state.value.autoReconnect)
        val back = console.await("reconnected") { it.phase == ConsolePhase.CONNECTED }
        assertNull(back.autoReconnect); assertEquals(2, opens.get()); assertTrue("An immediate reconnect needs no pause", pauses.requested.isEmpty())
        console.close()
    }

    @Test fun aConsoleTheUserClosedOrAnotherFailureIsNotReopenedOnReturn() {
        val console = controller(opener = scriptedOpener(), delays = backoff)
        console.open(target); console.await { it.phase == ConsolePhase.CONNECTED }
        console.close()
        console.appBackgrounded(); console.appForegrounded()
        assertEquals(ConsolePhase.IDLE, console.state.value.phase); assertEquals(1, opens.get())

        val refused = controller(opener = { _, _, _ -> opens.incrementAndGet(); throw ConsoleError("Access refused.", ConsoleFailureKind.AUTH) }, delays = backoff)
        refused.open(target); refused.await { it.phase == ConsolePhase.FAILED }
        refused.appBackgrounded(); refused.appForegrounded()
        Thread.sleep(100)
        assertEquals(ConsoleFailureKind.AUTH, refused.state.value.failure); assertEquals(2, opens.get())
    }

    @Test fun aLossWhileTheAppIsAwayWaitsForItsReturnBeforeReconnecting() {
        val pauses = Pauses()
        val console = controller(opener = scriptedOpener(), delays = backoff, pause = pauses::pause)
        console.open(target); console.await { it.phase == ConsolePhase.CONNECTED }
        console.appBackgrounded()
        sockets[0].drop()
        assertEquals(ConsoleFailureKind.LOST, console.await("lost in the background") { it.phase == ConsolePhase.FAILED }.failure)
        Thread.sleep(100); assertTrue(pauses.requested.isEmpty()); assertEquals(1, opens.get())
        console.appForegrounded()
        console.await("reconnected on return") { it.phase == ConsolePhase.CONNECTED }
        assertEquals(2, opens.get())
        console.close()
    }

    // ---- connect deadline ----

    @Test fun theConnectDeadlineClosesABlockedSocketInsteadOfWaitingForItsOwnTimeouts() {
        val silent = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")); val ended = CountDownLatch(1)
        try {
            val console = controller(deadline = 300, opener = { _, _, _ ->
                val socket = Socket("127.0.0.1", silent.localPort)
                checkNotNull(currentCoroutineContext()[ConsoleAbort]).register(socket)
                try { socket.getInputStream().read() } finally { ended.countDown(); socket.close() } // blocks until the deadline closes it
                error("unreachable")
            })
            console.open(target)
            val failed = console.await("deadline") { it.phase == ConsolePhase.FAILED }
            assertEquals(ConsoleFailureKind.TIMEOUT, failed.failure)
            assertTrue("The blocked call ended", ended.await(2, TimeUnit.SECONDS))
        } finally { silent.close() }
    }

    @Test fun closingTheConsoleWhileConnectingClosesTheBlockedSocket() {
        val silent = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")); val started = CountDownLatch(1); val ended = CountDownLatch(1)
        try {
            val console = controller(opener = { _, _, _ ->
                val socket = Socket("127.0.0.1", silent.localPort)
                checkNotNull(currentCoroutineContext()[ConsoleAbort]).register(socket)
                try { started.countDown(); socket.getInputStream().read() } finally { ended.countDown(); socket.close() }
                error("unreachable")
            })
            console.open(target)
            assertTrue(started.await(2, TimeUnit.SECONDS))
            console.close()
            assertTrue("Closing ended the blocked connect", ended.await(2, TimeUnit.SECONDS))
            assertEquals(ConsolePhase.IDLE, console.state.value.phase)
        } finally { silent.close() }
    }

    // ---- declining a member certificate ----

    @Test fun decliningWithNoReviewPendingChangesNothing() {
        val server = serve(Plan(hello().bytes()))
        val console = controller(server)
        console.open(target); console.await { it.phase == ConsolePhase.CONNECTED }
        val before = console.state.value
        console.declineMemberTrust()
        assertEquals("A late call from a closed dialog must not touch a newer state", before, console.state.value)
        console.close()
    }

    @Test fun decliningWhileAnApprovalIsBeingSavedDoesNotCancelIt() {
        val server = serve(Plan(hello().bytes()))
        val gate = CompletableDeferred<Unit>(); var pinned = false
        val console = controller(opener = { _, _, encodings ->
            opens.incrementAndGet()
            if (!pinned) throw MemberTrustRequired("203.0.113.5", review, null)
            ConsoleSession(Socket("127.0.0.1", server.port), encodings) { logouts.incrementAndGet() }
        }, approve = { id, authority, pin -> gate.await(); approvals += Triple(id, authority, pin); pinned = true })
        console.open(target); console.await { it.review != null }
        console.approveMemberTrust()
        console.await("saving") { it.trustBusy }
        console.declineMemberTrust()
        assertNotNull("The review stays while the approval is saved", console.state.value.review); assertTrue(console.state.value.trustBusy)
        gate.complete(Unit)
        console.await("connected after approval") { it.phase == ConsolePhase.CONNECTED }
        assertEquals(1, approvals.size)
        console.close()
    }
}
