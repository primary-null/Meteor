package com.meteor.app.data

import java.io.Closeable
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** One automatic reconnect try: wait [delayMillis], then connect (in compatibility mode when [safeMode]). */
data class ReconnectStep(val attempt: Int, val attempts: Int, val delayMillis: Long, val safeMode: Boolean)

/** Which console losses are retried by themselves, and with what pauses. */
object ReconnectPolicy {
    val DELAYS_MILLIS = listOf(2_000L, 5_000L, 10_000L, 20_000L)

    /**
     * Losses that can heal on their own: a dropped link, an unreachable or slow host, a console that is not up yet
     * after a reboot, and undecodable data (retried once, in compatibility mode). Rejected credentials, certificate
     * and trust problems, a VM that is not running, and unsupported servers are never retried.
     */
    fun retryable(kind: ConsoleFailureKind): Boolean = when (kind) {
        ConsoleFailureKind.LOST, ConsoleFailureKind.NETWORK, ConsoleFailureKind.TIMEOUT,
        ConsoleFailureKind.NO_CONSOLE, ConsoleFailureKind.PROTOCOL -> true
        else -> false
    }

    /** The next try after [used] tries in this run of retries, or null when the run is over or the loss is not retryable. */
    fun next(kind: ConsoleFailureKind, safeMode: Boolean, used: Int, delays: List<Long> = DELAYS_MILLIS): ReconnectStep? {
        if (!retryable(kind) || used >= delays.size) return null
        if (kind == ConsoleFailureKind.PROTOCOL && safeMode) return null
        return ReconnectStep(used + 1, delays.size, delays[used], safeMode || kind == ConsoleFailureKind.PROTOCOL)
    }
}

/**
 * Lets the controller end a connection attempt whose code is blocked in a socket call that coroutine cancellation
 * cannot interrupt. The opener's context carries it; [ConsoleSession.open] registers each socket it creates, and
 * [abort] closes them so the blocked call fails at once. Once the attempt has finished, [abort] does nothing.
 */
class ConsoleAbort : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ConsoleAbort>

    private val lock = Any()
    private val sockets = mutableListOf<Closeable>()
    private var aborted = false
    private var finished = false

    /** Starts tracking [socket]. When the attempt was already aborted the socket is closed and false is returned. */
    fun register(socket: Closeable): Boolean {
        synchronized(lock) {
            if (!aborted) { sockets += socket; return true }
        }
        runCatching { socket.close() }
        return false
    }

    /** The socket now belongs to a finished session; a later [abort] must not touch it. */
    fun release(socket: Closeable) { synchronized(lock) { sockets -= socket } }

    fun abort() {
        val toClose: List<Closeable>
        synchronized(lock) {
            if (aborted || finished) return
            aborted = true
            toClose = sockets.toList(); sockets.clear()
        }
        toClose.forEach { runCatching { it.close() } }
    }

    /** The attempt is over. */
    fun finish() { synchronized(lock) { finished = true; sockets.clear() } }
}
