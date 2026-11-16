package com.meteor.app.data

import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

// --- A4 alerts/events ---

/**
 * Short human explanations for failures, with the raw XAPI code or gateway reason appended in parentheses.
 * Only values from closed vocabularies are shown: the XAPI code (already limited to [A-Z0-9_]), gateway reasons
 * (limited to [a-z-]) and fixed sentences per exception type. Exception messages, server error parameters,
 * session references and credentials are never included, except the validated master address for HOST_IS_SLAVE
 * and a validated permission name for RBAC_PERMISSION_DENIED.
 */
object ErrorReasons {
    private val xapi = mapOf(
        "SESSION_AUTHENTICATION_FAILED" to "Authentication failed. Check this profile's username and password.",
        "SESSION_INVALID" to "The server session expired.",
        "RBAC_PERMISSION_DENIED" to "This account's role does not allow that.",
        "PERMISSION_DENIED" to "This account is not allowed to do that.",
        "HOST_OFFLINE" to "The host is offline or cannot be reached by the pool.",
        "HOST_STILL_BOOTING" to "The host is still starting.",
        "HOST_IS_SLAVE" to "This address is a pool member, not the pool master.",
        "EVENTS_LOST" to "The server no longer holds the events Meteor missed.",
        "HANDLE_INVALID" to "The object no longer exists on the server.",
        "MESSAGE_METHOD_UNKNOWN" to "This server does not support that request.",
        "NOT_IMPLEMENTED" to "This server does not support that request.",
        "SERVER_ERROR" to "The server reported an internal error.",
    )
    private val gatewayText = mapOf(
        "redirect-refused" to "The server redirected the request. Verify the server address.",
        "http-error" to "The server answered with an HTTP error. Check the address and that it is an XCP-ng management endpoint.",
        "response-limit" to "The server's answer was larger than Meteor accepts.",
        "session-missing" to "The server did not return a session.",
    )
    private val permissionName = Regex("[A-Za-z0-9_.:-]{1,80}")
    private val reasonName = Regex("[a-z-]{1,40}")

    /** One sentence explaining an XAPI failure, ending with the raw code. */
    fun rpc(error: RpcFailure): String {
        val base = xapi[error.code] ?: "The server refused this request."
        val extra = when (error.code) {
            "HOST_IS_SLAVE" -> MasterFollow.address(error.details)?.let { " The pool master reports its address as $it." }
            "RBAC_PERMISSION_DENIED" -> error.details.firstOrNull()?.takeIf { it.matches(permissionName) }?.let { " Required permission: $it." }
            else -> null
        }.orEmpty()
        return "$base$extra (${error.code})"
    }

    fun gateway(error: GatewayError): String {
        val reason = error.reason.takeIf { it.matches(reasonName) }
        val text = gatewayText[reason] ?: if (reason?.startsWith("invalid-") == true) "The server's answer was not in the expected format."
            else "Meteor could not complete the request."
        return if (reason == null || reason == "redirect-refused") text else "$text ($reason)"
    }

    /** Fixed text per transport failure type, or null when the type says nothing useful. */
    fun transport(causes: List<Throwable>): String? = when {
        causes.any { it is SocketTimeoutException } -> "The server did not answer in time."
        causes.any { it is UnknownHostException } -> "The server name could not be resolved."
        causes.any { it is ConnectException || it is NoRouteToHostException } -> "The server could not be reached."
        causes.any { it is SSLException } -> "The secure connection could not be established."
        else -> null
    }

    const val GENERIC = "Could not refresh securely. Check server reachability, certificate validity, and permissions."

    private fun certificate(causes: List<Throwable>): String? = when {
        causes.any { it is CertificateChanged } -> "The server certificate changed. Verify its fingerprint before connecting again."
        causes.any { it is java.security.cert.CertificateExpiredException } -> "The server certificate has expired. Renew it on the server, then review its fingerprint before connecting."
        causes.any { it is java.security.cert.CertificateNotYetValidException } -> "The server certificate is not valid yet. Check the device clock and server certificate."
        else -> null
    }

    /** The text for a connection or refresh failure. Certificate causes keep their original wording. */
    fun describe(error: Throwable): String {
        val causes = generateSequence(error) { it.cause }.take(8).toList()
        return certificate(causes) ?: when (error) {
            is RpcFailure -> rpc(error)
            is GatewayError -> gateway(error)
            else -> transport(causes)?.let { "Could not refresh securely: $it Check server reachability, certificate validity, and permissions." } ?: GENERIC
        }
    }

    /** Only the reason, for notices that bring their own lead-in sentence (for example a failed dismissal). */
    fun reason(error: Throwable): String {
        val causes = generateSequence(error) { it.cause }.take(8).toList()
        return certificate(causes) ?: when (error) {
            is RpcFailure -> rpc(error)
            is GatewayError -> gateway(error)
            else -> transport(causes) ?: "The request did not complete."
        }
    }

    /**
     * A preference that could not be saved. A validation failure explains itself ("Choose an image under 3 MB.");
     * anything else, including a bare `require` with no message, stays generic.
     */
    fun preference(error: Throwable): String = (error as? IllegalArgumentException)?.message
        ?.takeIf { it.isNotBlank() && it != "Failed requirement." && it.length <= 120 } ?: "That preference could not be saved. Try again."

    /** For read-only features that keep last-known values. [feature] is e.g. "Pool activity". */
    fun readIssue(error: Throwable, feature: String): String = when {
        error is UnsupportedOperationException || error is RpcFailure && error.code in setOf("MESSAGE_METHOD_UNKNOWN", "NOT_IMPLEMENTED") -> "$feature is not supported by this server."
        error is RpcFailure && error.code in setOf("RBAC_PERMISSION_DENIED", "PERMISSION_DENIED") -> "$feature is not available to this account."
        error is RpcFailure -> "$feature could not be refreshed: ${rpc(error)} Any displayed values are last known."
        error is GatewayError -> "$feature could not be refreshed: ${gateway(error)} Any displayed values are last known."
        else -> {
            val hint = transport(generateSequence(error) { it.cause }.take(8).toList())?.let { "$it " }.orEmpty()
            "$feature could not be refreshed securely. ${hint}Any displayed values are last known. Check the connection before trying again."
        }
    }
}
// --- end A4 ---
