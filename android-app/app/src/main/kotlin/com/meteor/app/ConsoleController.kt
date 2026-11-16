package com.meteor.app

import com.meteor.app.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Whose screen a console shows: a VM's, or an XCP-ng host's own (its control domain, where `xsconsole` and the root login live). */
enum class ConsoleKind { VM, HOST }

/**
 * The VM or host a console shows. Identity only: credentials and pins are looked up from the profile when connecting.
 * For a [ConsoleKind.HOST] target, [vmUuid] and [vmName] are the host's UUID and name.
 */
data class ConsoleTarget(val profileId: String, val authority: String, val vmUuid: String, val vmName: String,
    val kind: ConsoleKind = ConsoleKind.VM) {
    val host: Boolean get() = kind == ConsoleKind.HOST
}

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
    /** Paste progress: characters typed so far, characters to type, and characters left out because a US keyboard cannot type them. */
    val pasteDone: Int = 0,
    val pasteTotal: Int = 0,
    val pasteSkipped: Int = 0,
    /** Set while the console is reconnecting by itself after a loss, until it connects, fails for good or is cancelled. */
    val autoReconnect: AutoReconnect? = null,
    /** A host console waits for the user to confirm turning input on (see [ConsoleController.setViewOnly]). */
    val confirmHostInput: Boolean = false,
)

/** Automatic reconnecting in progress: try [attempt] of [attempts], or 0 for the immediate reconnect after the app returns. */
data class AutoReconnect(val attempt: Int, val attempts: Int)

/**
 * Owns the one open console. It lives in the view model, so the session survives rotation and fold/unfold;
 * the screen only observes [state], [frames] and [framebuffer] and feeds [input].
 *
 * Lifecycle: [close] on leaving the destination, on disconnect or a profile change (the view model calls
 * it), and after the app has been in the background for [graceMillis] ([appBackgrounded]). Closing first
 * releases held keys and buttons, drains the input queue briefly, then closes the socket and logs the
 * console's own XAPI session out, always off the main thread.
 *
 * Reconnecting: when an open console is lost (dropped link, unreachable host, timeout, a console that is not up
 * yet after a reboot, undecodable data) it retries by itself after [reconnectDelaysMillis] and then stops at the
 * manual Reconnect. Rejected credentials, certificate or trust problems and a VM that is not running are never
 * retried, and neither is a console the user closed. After a background close, returning to the foreground
 * reconnects at once. A link that stayed up for [stableMillis] starts a fresh run of retries when it drops.
 *
 * Reading runs on [io]: updates are decoded into the framebuffer, [frames] is bumped, and the next
 * incremental request is spaced at least [minRequestGapMillis] apart. A probe about every [probeMillis]
 * makes a static screen answer, so [ConsoleSession.READ_TIMEOUT_MILLIS] of silence means the link is lost.
 *
 * A host console ([ConsoleKind.HOST]) is a root console on the hypervisor that runs every VM of the host, so it is
 * guarded: every connection (open, reconnect, automatic reconnect) starts view-only; input turns on only through
 * [setViewOnly] followed by [confirmHostInput], and that choice ends with the connection. Ctrl+Alt+Del is never sent
 * ([ConsoleInput.blockCtrlAltDel]). Like a VM console it is never journaled and sends no VM or host operation: the only
 * bytes it writes are RFB handshake, update requests and, once confirmed, key and pointer events.
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
    private val reconnectDelaysMillis: List<Long> = ReconnectPolicy.DELAYS_MILLIS,
    private val stableMillis: Long = 30_000,
    /** Waits between reconnect tries. Injectable so tests need no real time. */
    private val pause: suspend (Long) -> Unit = { delay(it) },
    private val clock: () -> Long = System::currentTimeMillis,
    /** Commands that may wait to be written; live input fails the link only when this is full. */
    private val queueCapacity: Int = QUEUE_CAPACITY,
    private val pasteDelayMillis: Long = PASTE_DELAY_MILLIS,
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
        val commands = Channel<Command>(queueCapacity)
        /** Commands queued but not yet written, so a long paste can wait for room instead of overflowing. */
        val pending = AtomicInteger()
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
    private val pasteToken = AtomicLong()
    private var reconnectJob: Job? = null
    /** The attempt in progress; closed on teardown so a blocked socket call ends at once. */
    private var connectAbort: ConsoleAbort? = null
    /** True while the controller is working through automatic reconnect tries (or a connect that follows one). */
    private var sequence = false
    private var autoUsed = 0
    private var connectedAt = 0L
    private var lastError: ConsoleError? = null
    @Volatile private var foreground = true
    private var retryOnForeground = false

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
        current.pending.incrementAndGet()
        val result = current.commands.trySend(command)
        if (result.isFailure) {
            current.pending.decrementAndGet()
            // A full queue means the connection has not taken input for a long time (typing alone cannot fill it
            // in a few seconds, and a paste waits for room instead of queueing), so the link is treated as lost.
            if (!result.isClosed) fail(current, ConsoleError(
                "Console input could not be sent fast enough. Reconnect to continue.", ConsoleFailureKind.LOST))
        }
    }

    // ---- opening and closing ----

    /** Opens a console for [target]. Does nothing when this target is already open, connecting or failed (see [reconnect]). */
    fun open(target: ConsoleTarget) {
        if (mutable.value.target == target && mutable.value.phase != ConsolePhase.IDLE) return
        input.viewOnly = target.host
        input.blockCtrlAltDel = target.host
        endSequence()
        start(target, safeMode = false)
    }

    /** Manual reconnect after a failure, or a restart of a live session. */
    fun reconnect() {
        val current = mutable.value
        val target = current.target ?: return
        if (current.phase == ConsolePhase.CONNECTING) return
        endSequence()
        start(target, safeMode = current.safeMode || current.failure == ConsoleFailureKind.PROTOCOL)
    }

    private fun endSequence() { synchronized(lock) { sequence = false; autoUsed = 0; retryOnForeground = false; lastError = null } }

    private fun start(target: ConsoleTarget, safeMode: Boolean, auto: AutoReconnect? = null) {
        teardown()
        // The input object's lock is taken before this controller's lock (input -> sink), so read it outside the latter.
        // A host console starts every connection view-only; input confirmed for an earlier connection does not carry over.
        if (target.host) input.viewOnly = true
        val viewOnly = input.viewOnly
        val gen: Long
        synchronized(lock) {
            gen = ++generation
            framebuffer = null
            mutable.value = ConsoleUiState(target = target, phase = ConsolePhase.CONNECTING, viewOnly = viewOnly,
                safeMode = safeMode, autoReconnect = auto,
                message = if (auto != null) reconnectingMessage(auto) else if (safeMode) "Connecting in compatibility mode…" else "Connecting to the console…")
        }
        connectJob = scope.launch { connect(gen, target, safeMode) }
    }

    private fun current(gen: Long) = synchronized(lock) { gen == generation }

    private fun reconnectingMessage(auto: AutoReconnect) =
        if (auto.attempt == 0) "Reconnecting…" else "Reconnecting… try ${auto.attempt} of ${auto.attempts}"

    private suspend fun connect(gen: Long, target: ConsoleTarget, safeMode: Boolean) {
        var opened: ConsoleSession? = null
        try {
            val profile = profileOf(target.profileId) ?: throw ConsoleError("This connection is no longer available.")
            val session = openWithinDeadline(gen, profile, target, safeMode)
            opened = session
            handshaking = session
            val info = try { withContext(io) { session.start() } } finally { handshaking = null }
            val created = Link(session)
            val accepted = synchronized(lock) {
                if (gen != generation) false else {
                    link = created
                    framebuffer = session.rfb.framebuffer
                    connectedAt = clock()
                    mutable.update { it.copy(phase = ConsolePhase.CONNECTED, failure = null, message = null, review = null, autoReconnect = null,
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

    /**
     * Runs the opener under the connect deadline. The opener's blocking socket calls cannot be interrupted by
     * cancellation, so when the deadline passes (or the console is closed, see [teardown]) the sockets it registered
     * with its [ConsoleAbort] are closed and the blocked call fails at once.
     */
    private suspend fun openWithinDeadline(gen: Long, profile: Profile, target: ConsoleTarget, safeMode: Boolean): ConsoleSession {
        val abort = ConsoleAbort()
        val timedOut = AtomicBoolean()
        val timeout = { ConsoleError("The console connection timed out. Check the network and try again.", ConsoleFailureKind.TIMEOUT) }
        synchronized(lock) { if (gen == generation) connectAbort = abort }
        try {
            return coroutineScope {
                val watchdog = launch { delay(connectDeadlineMillis); timedOut.set(true); abort.abort() }
                try {
                    withTimeoutOrNull(connectDeadlineMillis) {
                        withContext(abort) { opener(profile, target, if (safeMode) RfbEncoding.SAFE else RfbEncoding.DEFAULT) }
                    } ?: throw timeout()
                } finally { watchdog.cancel() }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            throw if (timedOut.get()) timeout() else error
        } finally {
            abort.finish()
            synchronized(lock) { if (connectAbort === abort) connectAbort = null }
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
            for (command in link.commands) {
                try {
                    when (command) {
                        is Command.Key -> link.rfb.key(command.keysym, command.down)
                        is Command.Pointer -> link.rfb.pointer(command.x, command.y, command.buttons)
                        Command.Move -> link.pendingMove.getAndSet(null)?.let { link.rfb.pointer(it.x, it.y, it.buttons) }
                    }
                } finally { link.pending.decrementAndGet() }
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
            // A link that stayed up long enough starts a fresh run of tries; a connection that drops again at once keeps counting.
            if (dead != null) { if (clock() - connectedAt >= stableMillis) autoUsed = 0; sequence = true }
            val safeNow = mutable.value.safeMode
            val step = if (sequence) ReconnectPolicy.next(error.kind, safeNow, autoUsed, reconnectDelaysMillis) else null
            val exhausted = sequence && step == null && ReconnectPolicy.retryable(error.kind) && autoUsed > 0 &&
                !(error.kind == ConsoleFailureKind.PROTOCOL && safeNow)
            if (step != null && foreground) {
                autoUsed = step.attempt; lastError = error
                val auto = AutoReconnect(step.attempt, step.attempts)
                mutable.update { it.copy(phase = ConsolePhase.CONNECTING, failure = null, message = reconnectingMessage(auto), pasting = false,
                    pasteDone = 0, pasteTotal = 0, pasteSkipped = 0, review = null, trustBusy = false, autoReconnect = auto,
                    safeMode = it.safeMode || step.safeMode, confirmHostInput = false) }
                reconnectJob = scope.launch { reconnectAfterPause(gen, step) }
            } else {
                if (step != null) { retryOnForeground = true; lastError = error } // Lost while in the background: try again on return.
                else { sequence = false; autoUsed = 0 }
                mutable.update { it.copy(phase = ConsolePhase.FAILED, failure = error.kind, pasting = false, autoReconnect = null, confirmHostInput = false,
                    message = if (exhausted) error.message + " Automatic reconnecting stopped after ${reconnectDelaysMillis.size} tries." else error.message,
                    review = (error as? MemberTrustRequired)?.let { MemberTrustReview(it.authority, it.certificate, it.previousPin) },
                    trustBusy = false) }
            }
        }
        // Outside the lock: the input object calls back into this controller while it releases keys.
        input.releaseAll()
        pasteJob?.cancel()
        dead?.let { abort(it) }
    }

    private suspend fun reconnectAfterPause(gen: Long, step: ReconnectStep) {
        pause(step.delayMillis)
        val target = synchronized(lock) {
            if (gen != generation) return
            reconnectJob = null // start() tears the old state down; this job must not cancel itself.
            mutable.value.target
        } ?: return
        start(target, step.safeMode, AutoReconnect(step.attempt, step.attempts))
    }

    /** Stops automatic reconnecting and leaves the console failed, so the manual Reconnect is offered. */
    fun cancelAutoReconnect() {
        val current = mutable.value
        val target = current.target ?: return
        if (current.autoReconnect == null) return
        val error = synchronized(lock) { lastError }
        teardown()
        endSequence()
        synchronized(lock) {
            mutable.value = ConsoleUiState(target = target, phase = ConsolePhase.FAILED, failure = error?.kind ?: ConsoleFailureKind.LOST,
                viewOnly = current.viewOnly, safeMode = current.safeMode,
                message = error?.message ?: "The console connection was lost. Reconnecting was cancelled.")
        }
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
        val aborting: ConsoleAbort?
        synchronized(lock) { generation++; link = null; aborting = connectAbort; connectAbort = null }
        aborting?.abort()
        handshaking?.let { pending -> handshaking = null; cleanup.launch { runCatching { pending.close() } } }
        connectJob?.cancel(); graceJob?.cancel(); pasteJob?.cancel(); reconnectJob?.cancel()
        connectJob = null; graceJob = null; pasteJob = null; reconnectJob = null
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
        endSequence()
        input.viewOnly = false
        input.blockCtrlAltDel = false
        synchronized(lock) { framebuffer = null; mutable.value = ConsoleUiState() }
    }

    /** For the view model's `onCleared`: the same as [close]. */
    fun shutdown() = close()

    // ---- app lifecycle ----

    fun appBackgrounded() {
        foreground = false
        val phase = mutable.value.phase
        if (phase != ConsolePhase.CONNECTED && phase != ConsolePhase.CONNECTING) return
        graceJob?.cancel()
        graceJob = scope.launch {
            delay(graceMillis)
            closeForBackground()
        }
    }

    /**
     * Back in the foreground: cancels the pending background close. A console that was closed only because the app
     * stayed in the background (or lost its link while there) reconnects by itself; no other failure does.
     */
    fun appForegrounded() {
        foreground = true
        graceJob?.cancel(); graceJob = null
        val current = mutable.value
        val target = current.target ?: return
        val resume = synchronized(lock) {
            (current.phase == ConsolePhase.FAILED && (current.failure == ConsoleFailureKind.BACKGROUND || retryOnForeground)).also { retryOnForeground = false }
        }
        if (!resume) return
        synchronized(lock) { sequence = true; autoUsed = 0 }
        start(target, safeMode = current.safeMode || current.failure == ConsoleFailureKind.PROTOCOL, auto = AutoReconnect(0, reconnectDelaysMillis.size))
    }

    private fun closeForBackground() {
        val target = mutable.value.target ?: return
        val safeMode = mutable.value.safeMode
        val viewOnly = input.viewOnly
        teardown()
        endSequence()
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

    /**
     * The user turned the certificate down. Applies only to a review that is still waiting: a late call from a dialog
     * that no longer matches the console (it was closed or replaced), or one made while an approval is being saved,
     * changes nothing, so it can neither overwrite a newer state nor stop that approval from reconnecting.
     */
    fun declineMemberTrust() {
        synchronized(lock) {
            val state = mutable.value
            if (state.review == null || state.trustBusy || state.phase != ConsolePhase.FAILED) return
            mutable.value = state.copy(review = null,
                message = "The pool member was not trusted, so its console was not opened. Reconnect to review it again.")
        }
    }

    // ---- input helpers owned by the controller ----

    /**
     * View-only on, or off. For a host console, turning input on only asks: the state shows [ConsoleUiState.confirmHostInput]
     * and input stays off until [confirmHostInput].
     */
    fun setViewOnly(enabled: Boolean) {
        val state = mutable.value
        if (!enabled && state.target?.host == true) {
            if (state.phase == ConsolePhase.CONNECTED && state.viewOnly) mutable.update { it.copy(confirmHostInput = true) }
            return
        }
        input.viewOnly = enabled
        mutable.update { it.copy(viewOnly = enabled, confirmHostInput = false) }
    }

    /** The user confirmed that keys and clicks go to the host's own console. Applies only to a question still waiting. */
    fun confirmHostInput() {
        val state = mutable.value
        if (!state.confirmHostInput || state.target?.host != true || state.phase != ConsolePhase.CONNECTED) {
            mutable.update { it.copy(confirmHostInput = false) }
            return
        }
        input.viewOnly = false
        mutable.update { it.copy(viewOnly = false, confirmHostInput = false) }
    }

    /** The user kept the host console view-only. */
    fun declineHostInput() { mutable.update { it.copy(confirmHostInput = false) } }

    /**
     * Types [text] as key events, paced so the guest keyboard keeps up. Characters a US keyboard cannot type are skipped
     * (see [ConsolePaste]; the paste dialog counts them before sending). When the connection is slow the paste waits
     * for room in the send queue instead of failing the console; [cancelPaste] stops it. Clipboard text is not sent
     * with ClientCutText.
     */
    fun paste(text: String) {
        if (mutable.value.phase != ConsolePhase.CONNECTED || input.viewOnly || text.isEmpty()) return
        val plan = ConsolePaste.plan(text.take(MAX_PASTE_CHARS))
        if (plan.typed.isEmpty()) return
        val gen = synchronized(lock) { generation }
        pasteJob?.cancel()
        val token = pasteToken.incrementAndGet()
        pasteJob = scope.launch {
            mutable.update { it.copy(pasting = true, pasteDone = 0, pasteTotal = plan.typed.size, pasteSkipped = plan.skipped) }
            try {
                for ((index, codePoint) in plan.typed.withIndex()) {
                    awaitRoom(gen)
                    if (!current(gen) || mutable.value.phase != ConsolePhase.CONNECTED || input.viewOnly) break
                    input.typeCodePoint(codePoint)
                    if ((index + 1) % PASTE_PROGRESS_STEP == 0) mutable.update { it.copy(pasteDone = index + 1) }
                    delay(pasteDelayMillis)
                }
            } finally {
                // A newer paste owns the progress fields now; only the latest one may clear them.
                if (pasteToken.get() == token) mutable.update { it.copy(pasting = false, pasteDone = 0, pasteTotal = 0, pasteSkipped = 0) }
            }
        }
    }

    /** Suspends while a quarter of the send queue is in use, which leaves the rest for live typing. */
    private suspend fun awaitRoom(gen: Long) {
        while (current(gen) && !input.viewOnly) {
            val current = synchronized(lock) { link } ?: return
            if (current.pending.get() <= queueCapacity / 4) return
            delay(PASTE_WAIT_MILLIS)
        }
    }

    fun cancelPaste() { pasteJob?.cancel() }

    companion object {
        const val MAX_PASTE_CHARS = 8_000
        const val PASTE_DELAY_MILLIS = 6L
        /** Commands (key and pointer events) that may wait to be written. */
        const val QUEUE_CAPACITY = 8_192
        const val PASTE_WAIT_MILLIS = 20L
        private const val PASTE_PROGRESS_STEP = 16
    }
}
