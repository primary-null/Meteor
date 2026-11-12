package com.meteor.app

import com.meteor.app.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicReference

/** The VM a console shows. Identity only: credentials and pins are looked up from the profile when connecting. */
data class ConsoleTarget(val profileId: String, val authority: String, val vmUuid: String, val vmName: String)

enum class ConsolePhase { IDLE, CONNECTING, CONNECTED, FAILED }

/** A pool member whose certificate must be reviewed before the console connects. */
data class MemberTrustReview(val authority: String, val certificate: ServerCertificate, val previousPin: String?)

data class ConsoleUiState(
    val target: ConsoleTarget? = null,
    val phase: ConsolePhase = ConsolePhase.IDLE,
    val failure: ConsoleFailureKind? = null,
    val message: String? = null,
    val review: MemberTrustReview? = null,
    val frameWidth: Int = 0,
    val frameHeight: Int = 0,
    val serverName: String = "",
    val viewOnly: Boolean = false,
    /** Raw/CopyRect only, used for a reconnect after a decoding failure. */
    val safeMode: Boolean = false,
    val trustBusy: Boolean = false,
    val pasting: Boolean = false,
)

/**
 * Owns the one open console. It lives in the view model, so the session survives rotation and fold/unfold;
 * the screen only observes [state], [frames] and [framebuffer] and feeds [input].
 *
 * Lifecycle: [close] on leaving the destination, on disconnect or a profile change (the view model calls
 * it), and after the app has been in the background for [graceMillis] ([appBackgrounded]). Closing first
 * releases held keys and buttons, drains the input queue briefly, then closes the socket and logs the
 * console's own XAPI session out, always off the main thread.
 *
 * Reading runs on [io]: updates are decoded into the framebuffer, [frames] is bumped, and the next
 * incremental request is spaced at least [minRequestGapMillis] apart. A probe about every [probeMillis]
 * makes a static screen answer, so [ConsoleSession.READ_TIMEOUT_MILLIS] of silence means the link is lost.
 */
class ConsoleController(
    private val scope: CoroutineScope,
    private val opener: suspend (Profile, ConsoleTarget, List<Int>) -> ConsoleSession,
    private val profileOf: (String) -> Profile?,
    private val approvePin: suspend (profileId: String, authority: String, pin: String) -> Unit,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val graceMillis: Long = 30_000,
    private val probeMillis: Long = 20_000,
    private val minRequestGapMillis: Long = 30,
    private val connectDeadlineMillis: Long = 45_000,
    private val cleanup: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private sealed interface Command {
        data class Key(val keysym: Int, val down: Boolean) : Command
        data class Pointer(val x: Int, val y: Int, val buttons: Int) : Command
        /** Marker for the newest coalesced pointer motion in [Link.pendingMove]. */
        data object Move : Command
    }

    /** One connected session with its reader, writer and probe jobs. */
    private inner class Link(val session: ConsoleSession) {
        val rfb: RfbProtocol = session.rfb
        val commands = Channel<Command>(2048)
        val pendingMove = AtomicReference<Command.Pointer?>(null)
        var lastButtons = 0
        @Volatile var reader: Job? = null
        @Volatile var writer: Job? = null
        @Volatile var prober: Job? = null
    }

    private val lock = Any()
    private val mutable = MutableStateFlow(ConsoleUiState())
    val state: StateFlow<ConsoleUiState> = mutable.asStateFlow()
    private val frameTicks = MutableStateFlow(0L)
    /** Bumped after every decoded update. Readers throttle it; they never need every value. */
    val frames: StateFlow<Long> = frameTicks.asStateFlow()
    @Volatile var framebuffer: Framebuffer? = null; private set

    private var generation = 0L
    /** A session whose handshake is running. Closing the console closes it so a blocked read ends at once. */
    @Volatile private var handshaking: ConsoleSession? = null
    private var link: Link? = null
    private var connectJob: Job? = null
    private var graceJob: Job? = null
    private var pasteJob: Job? = null

    private val sink = object : ConsoleSink {
        override fun key(keysym: Int, down: Boolean) = enqueue(Command.Key(keysym, down))
        override fun pointer(x: Int, y: Int, buttons: Int) {
            val current = synchronized(lock) { link } ?: return
            if (buttons == current.lastButtons) {
                // Plain motion: only the newest position matters, so one marker serves any number of moves.
                if (current.pendingMove.getAndSet(Command.Pointer(x, y, buttons)) == null) enqueue(Command.Move)
            } else {
                // A button change keeps its place in the sequence: the motion before it is sent first.
                current.pendingMove.getAndSet(null)?.let { enqueue(it) }
                current.lastButtons = buttons
                enqueue(Command.Pointer(x, y, buttons))
            }
        }
    }
    /** Key, pointer and special-key input for the open console. Every call is dropped while not connected. */
    val input = ConsoleInput(sink)

    private fun enqueue(command: Command) {
        val current = synchronized(lock) { link } ?: return
        val result = current.commands.trySend(command)
        if (result.isFailure && !result.isClosed) fail(current, ConsoleError(
            "Console input could not be sent fast enough. Reconnect to continue.", ConsoleFailureKind.LOST))
    }

    // ---- opening and closing ----

    /** Opens a console for [target]. Does nothing when this target is already open, connecting or failed (see [reconnect]). */
    fun open(target: ConsoleTarget) {
        if (mutable.value.target == target && mutable.value.phase != ConsolePhase.IDLE) return
        input.viewOnly = false
        start(target, safeMode = false)
    }

    /** Manual reconnect after a failure, or a restart of a live session. */
    fun reconnect() {
        val current = mutable.value
        val target = current.target ?: return
        if (current.phase == ConsolePhase.CONNECTING) return
        start(target, safeMode = current.safeMode || current.failure == ConsoleFailureKind.PROTOCOL)
    }

    private fun start(target: ConsoleTarget, safeMode: Boolean) {
        teardown()
        // The input object's lock is taken before this controller's lock (input -> sink), so read it outside the latter.
        val viewOnly = input.viewOnly
        val gen: Long
        synchronized(lock) {
            gen = ++generation
            framebuffer = null
            mutable.value = ConsoleUiState(target = target, phase = ConsolePhase.CONNECTING, viewOnly = viewOnly,
                safeMode = safeMode, message = if (safeMode) "Connecting in compatibility mode…" else "Connecting to the console…")
        }
        connectJob = scope.launch { connect(gen, target, safeMode) }
    }

    private fun current(gen: Long) = synchronized(lock) { gen == generation }

    private suspend fun connect(gen: Long, target: ConsoleTarget, safeMode: Boolean) {
        var opened: ConsoleSession? = null
        try {
            val profile = profileOf(target.profileId) ?: throw ConsoleError("This connection is no longer available.")
            val session = withTimeoutOrNull(connectDeadlineMillis) {
                opener(profile, target, if (safeMode) RfbEncoding.SAFE else RfbEncoding.DEFAULT)
            } ?: throw ConsoleError("The console connection timed out. Check the network and try again.", ConsoleFailureKind.TIMEOUT)
            opened = session
            handshaking = session
            val info = try { withContext(io) { session.start() } } finally { handshaking = null }
            val created = Link(session)
            val accepted = synchronized(lock) {
                if (gen != generation) false else {
                    link = created
                    framebuffer = session.rfb.framebuffer
                    mutable.update { it.copy(phase = ConsolePhase.CONNECTED, failure = null, message = null, review = null,
                        frameWidth = info.width, frameHeight = info.height, serverName = info.name) }
                    true
                }
            }
            if (!accepted) return // The finally block closes the session this attempt no longer owns.
            opened = null
            frameTicks.update { it + 1 }
            created.reader = scope.launch(io) { read(gen, created) }
            created.writer = scope.launch(io) { write(created) }
            created.prober = scope.launch(io) { probe(created) }
            // A close can land between publishing the link and starting its jobs; then nothing else would stop them.
            if (!current(gen)) finish(created)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (current(gen)) failWith(gen, ConsoleFailures.classify(error))
        } finally {
            opened?.let { orphan -> cleanup.launch { runCatching { orphan.close() } } }
        }
    }

    private suspend fun read(gen: Long, link: Link) {
        try {
            var lastRequest = 0L
            while (current(gen) && currentCoroutineContext().isActive) {
                val event = link.rfb.readEvent()
                if (event != RfbEvent.UPDATE && event != RfbEvent.RESIZED) continue
                if (event == RfbEvent.RESIZED) {
                    val fb = link.rfb.framebuffer
                    synchronized(lock) { if (gen == generation) mutable.update { it.copy(frameWidth = fb.width, frameHeight = fb.height) } }
                }
                frameTicks.update { it + 1 }
                val wait = minRequestGapMillis - (System.currentTimeMillis() - lastRequest)
                if (wait > 0) delay(wait)
                // New guest dimensions leave the surface undefined, so a resize asks for the whole screen again.
                link.rfb.requestUpdate(incremental = event != RfbEvent.RESIZED)
                lastRequest = System.currentTimeMillis()
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            fail(link, ConsoleFailures.classify(error))
        }
    }

    private suspend fun write(link: Link) {
        try {
            for (command in link.commands) when (command) {
                is Command.Key -> link.rfb.key(command.keysym, command.down)
                is Command.Pointer -> link.rfb.pointer(command.x, command.y, command.buttons)
                Command.Move -> link.pendingMove.getAndSet(null)?.let { link.rfb.pointer(it.x, it.y, it.buttons) }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            fail(link, ConsoleFailures.classify(error))
        }
    }

    private suspend fun probe(link: Link) {
        try {
            while (true) {
                delay(probeMillis)
                if (synchronized(lock) { this.link } !== link) return
                link.rfb.probe()
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            fail(link, ConsoleFailures.classify(error))
        }
    }

    /** An established link broke. Ignored when it is no longer the current one (closed on purpose). */
    private fun fail(link: Link, error: ConsoleError) {
        val gen = synchronized(lock) { if (this.link !== link) return else generation }
        failWith(gen, error)
    }

    private fun failWith(gen: Long, error: ConsoleError) {
        val dead: Link?
        synchronized(lock) {
            if (gen != generation) return
            dead = link; link = null
            mutable.update { it.copy(phase = ConsolePhase.FAILED, failure = error.kind, message = error.message, pasting = false,
                review = (error as? MemberTrustRequired)?.let { MemberTrustReview(it.authority, it.certificate, it.previousPin) },
                trustBusy = false) }
        }
        // Outside the lock: the input object calls back into this controller while it releases keys.
        input.releaseAll()
        pasteJob?.cancel()
        dead?.let { abort(it) }
    }

    /** Stops a dead link: no key release is attempted because the connection is gone. */
    private fun abort(dead: Link) {
        dead.commands.close()
        cleanup.launch {
            dead.prober?.cancel(); dead.writer?.cancel()
            runCatching { dead.session.close() }
            dead.reader?.cancel()
        }
    }

    /** Releases input, ends any connection attempt and closes the live link without touching [state]. */
    private fun teardown() {
        val old: Link?
        synchronized(lock) { old = link }
        // While the link still accepts commands, key and button releases reach the guest before the socket closes.
        input.releaseAll()
        synchronized(lock) { generation++; link = null }
        handshaking?.let { pending -> handshaking = null; cleanup.launch { runCatching { pending.close() } } }
        connectJob?.cancel(); graceJob?.cancel(); pasteJob?.cancel()
        connectJob = null; graceJob = null; pasteJob = null
        old?.let { finish(it) }
    }

    private fun finish(old: Link) {
        old.commands.close()
        cleanup.launch {
            old.writer?.let { writer -> withTimeoutOrNull(500) { writer.join() } }
            old.prober?.cancel(); old.writer?.cancel()
            runCatching { old.session.close() }
            old.reader?.cancel()
        }
    }

    /** Closes the console and forgets the target. Idempotent. */
    fun close() {
        if (mutable.value.phase == ConsolePhase.IDLE && mutable.value.target == null && link == null) return
        teardown()
        input.viewOnly = false
        synchronized(lock) { framebuffer = null; mutable.value = ConsoleUiState() }
    }

    /** For the view model's `onCleared`: the same as [close]. */
    fun shutdown() = close()

    // ---- app lifecycle ----

    fun appBackgrounded() {
        val phase = mutable.value.phase
        if (phase != ConsolePhase.CONNECTED && phase != ConsolePhase.CONNECTING) return
        graceJob?.cancel()
        graceJob = scope.launch {
            delay(graceMillis)
            closeForBackground()
        }
    }

    fun appForegrounded() { graceJob?.cancel(); graceJob = null }

    private fun closeForBackground() {
        val target = mutable.value.target ?: return
        val safeMode = mutable.value.safeMode
        val viewOnly = input.viewOnly
        teardown()
        synchronized(lock) {
            mutable.value = ConsoleUiState(target = target, phase = ConsolePhase.FAILED, failure = ConsoleFailureKind.BACKGROUND,
                viewOnly = viewOnly, safeMode = safeMode,
                message = "The console was closed after the app stayed in the background. Reconnect to continue.")
        }
    }

    // ---- member host trust ----

    /** The user compared the fingerprint and approved this member host. The pin is stored with the profile, then the console reconnects. */
    fun approveMemberTrust() {
        val current = mutable.value
        val review = current.review ?: return
        val target = current.target ?: return
        if (current.trustBusy) return
        mutable.update { it.copy(trustBusy = true) }
        val gen = synchronized(lock) { generation }
        scope.launch {
            try {
                approvePin(target.profileId, review.authority, review.certificate.sha256)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (current(gen)) mutable.update { it.copy(trustBusy = false,
                    message = "The approval could not be saved. The certificate was not trusted.") }
                return@launch
            }
            if (current(gen)) start(target, safeMode = current.safeMode)
        }
    }

    fun declineMemberTrust() {
        mutable.update { it.copy(review = null, trustBusy = false,
            message = "The pool member was not trusted, so its console was not opened. Reconnect to review it again.") }
    }

    // ---- input helpers owned by the controller ----

    fun setViewOnly(enabled: Boolean) {
        input.viewOnly = enabled
        mutable.update { it.copy(viewOnly = enabled) }
    }

    /** Sends [text] as key events, paced so the guest keyboard keeps up. Clipboard text is not sent with ClientCutText. */
    fun paste(text: String) {
        if (mutable.value.phase != ConsolePhase.CONNECTED || input.viewOnly || text.isEmpty()) return
        val gen = synchronized(lock) { generation }
        pasteJob?.cancel()
        pasteJob = scope.launch {
            mutable.update { it.copy(pasting = true) }
            try {
                val clipped = text.take(MAX_PASTE_CHARS)
                var index = 0
                while (index < clipped.length && current(gen) && mutable.value.phase == ConsolePhase.CONNECTED) {
                    val codePoint = clipped.codePointAt(index)
                    input.typeCodePoint(codePoint)
                    index += Character.charCount(codePoint)
                    delay(PASTE_DELAY_MILLIS)
                }
            } finally { mutable.update { it.copy(pasting = false) } }
        }
    }

    fun cancelPaste() { pasteJob?.cancel() }

    companion object {
        const val MAX_PASTE_CHARS = 8_000
        const val PASTE_DELAY_MILLIS = 6L
    }
}
