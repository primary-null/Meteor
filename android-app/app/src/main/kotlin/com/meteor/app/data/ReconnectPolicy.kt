package com.meteor.app.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.security.cert.CertificateExpiredException
import java.security.cert.CertificateNotYetValidException

// --- offline and reconnect ---

/**
 * Why automatic reconnects have stopped. Retrying cannot change any of these by itself, so Meteor waits for the
 * user (edit the connection, or tap Refresh) instead of sending the saved password again and again.
 */
enum class RetryStop {
    /** The server rejected the saved username or password. Repeated logins can lock the account or trip fail2ban. */
    AUTHENTICATION,
    /** The server certificate no longer matches the trusted one (or is expired / not yet valid). */
    CERTIFICATE,
    /** The saved address is a pool member that named the pool master. The master offer stays open meanwhile. */
    POOL_MEMBER,
}

object ConnectionFailures {
    /** The stop an automatic retry must respect after [error], or null when a later retry may still succeed. */
    fun stopFor(error: Throwable): RetryStop? {
        val causes = generateSequence(error) { it.cause }.take(8).toList()
        return when {
            causes.any { it is RpcFailure && it.code == "SESSION_AUTHENTICATION_FAILED" } -> RetryStop.AUTHENTICATION
            causes.any { it is CertificateChanged || it is CertificateExpiredException || it is CertificateNotYetValidException } -> RetryStop.CERTIFICATE
            causes.any { it is RpcFailure && it.code == "HOST_IS_SLAVE" } -> RetryStop.POOL_MEMBER
            else -> null
        }
    }
}

/**
 * Bounded exponential backoff for automatic reconnects: 30 s, 1 min, 2 min, then 5 min between attempts.
 * Times come from the caller's monotonic clock, so tests move time without waiting. A failure schedules the next
 * attempt relative to when the failed attempt STARTED, so a slow, timing-out attempt does not push the next poll
 * tick a whole interval further out.
 */
class ReconnectBackoff(private val steps: List<Long> = DEFAULT_STEPS_MILLIS) {
    init { require(steps.isNotEmpty()) }
    private var failures = 0
    private var notBefore = Long.MIN_VALUE

    fun due(now: Long): Boolean = now >= notBefore
    fun failed(attemptStartedAt: Long) {
        notBefore = attemptStartedAt + steps[minOf(failures, steps.lastIndex)]
        failures++
    }
    fun reset() { failures = 0; notBefore = Long.MIN_VALUE }

    companion object {
        val DEFAULT_STEPS_MILLIS = listOf(30_000L, 60_000L, 120_000L, 300_000L)
    }
}

/**
 * Whether the phone has a default network, and a counter that grows whenever the default network changes
 * (Wi-Fi to mobile data, a VPN starting or stopping). It says nothing about the server: a private Wi-Fi without
 * internet access is still "online" here, because the XCP-ng server is usually on exactly that network.
 */
data class NetworkStatus(val online: Boolean = true, val changes: Int = 0)

/** Observes the default network. [start] and [stop] follow the app's foreground state. */
interface NetworkMonitor {
    val status: StateFlow<NetworkStatus>
    fun start()
    fun stop()

    /** Always online and never changing: the default for tests and for code that does not watch the network. */
    object AlwaysOnline : NetworkMonitor {
        override val status: StateFlow<NetworkStatus> = MutableStateFlow(NetworkStatus())
        override fun start() = Unit
        override fun stop() = Unit
    }
}
// --- end offline and reconnect ---
