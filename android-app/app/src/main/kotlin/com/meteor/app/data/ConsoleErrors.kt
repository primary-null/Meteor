package com.meteor.app.data

import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** Why a console is not (or no longer) usable. The UI maps each kind to an explanation and its actions. */
enum class ConsoleFailureKind {
    /** The VM is halted or suspended, so XAPI has no console for it. */
    NOT_RUNNING,
    /** The VM is running but offers no RFB console (still starting, or only a text console). */
    NO_CONSOLE,
    /** The VM's console is on a pool member whose certificate is not trusted yet or changed. */
    MEMBER_TRUST,
    /** The pinned certificate of the pool coordinator (or an approved member) did not match. */
    CERTIFICATE,
    AUTH,
    NETWORK,
    TIMEOUT,
    /** An established connection dropped. */
    LOST,
    /** Closed on purpose after the app stayed in the background. */
    BACKGROUND,
    /** The server sent data the client could not decode. */
    PROTOCOL,
    /** The server needs a security type or feature this client does not provide. */
    UNSUPPORTED,
    FAILED,
}

/** What a console shows: a VM's screen, or an XCP-ng host's own screen (its control domain). Only wording depends on it here. */
enum class ConsoleSubject(val possessive: String) {
    VM("this VM's"), HOST("this host's");
}

/** A console failure with a user-presentable message. Messages never contain session references. */
open class ConsoleError(message: String, val kind: ConsoleFailureKind = ConsoleFailureKind.FAILED) : Exception(message)

/**
 * The console's host is a pool member whose certificate is not the saved pool-coordinator pin and has
 * no approved member pin (or its approved pin no longer matches). [certificate] is what the member
 * presented to a credential-free probe; [previousPin] is the pin approved earlier, if any.
 */
class MemberTrustRequired(val authority: String, val certificate: ServerCertificate, val previousPin: String?,
    subject: ConsoleSubject = ConsoleSubject.VM) :
    ConsoleError("${subject.possessive.replaceFirstChar { it.uppercase() }} console is served by a pool member whose certificate is not trusted yet.",
        ConsoleFailureKind.MEMBER_TRUST)

object ConsoleFailures {
    /** Maps transport exceptions to a console error. A [ConsoleError] passes through unchanged. */
    fun classify(error: Throwable): ConsoleError {
        if (error is ConsoleError) return error
        val chain = generateSequence(error) { it.cause }.take(10).toList()
        return when {
            chain.any { it is CertificateChanged } -> ConsoleError(
                "The console server's certificate does not match the saved pin.", ConsoleFailureKind.CERTIFICATE)
            chain.any { it is java.security.cert.CertificateExpiredException || it is java.security.cert.CertificateNotYetValidException } -> ConsoleError(
                "The console server's certificate is outside its validity period. Check the device clock and the server certificate.", ConsoleFailureKind.CERTIFICATE)
            chain.any { it is RpcFailure && (it.code == "RBAC_PERMISSION_DENIED" || it.code == "PERMISSION_DENIED") } -> ConsoleError(
                "This account is not permitted to open VM consoles.", ConsoleFailureKind.AUTH)
            chain.any { it is RpcFailure && it.code == "SESSION_AUTHENTICATION_FAILED" } -> ConsoleError(
                "Authentication failed. Check this connection's username and password.", ConsoleFailureKind.AUTH)
            chain.any { it is SocketTimeoutException } -> ConsoleError(
                "The console connection timed out. Check the network and try again.", ConsoleFailureKind.TIMEOUT)
            chain.any { it is UnknownHostException || it is ConnectException || it is NoRouteToHostException } -> ConsoleError(
                "The console host could not be reached. Check that this device can reach the host that serves the VM.", ConsoleFailureKind.NETWORK)
            chain.any { it is SSLException } -> ConsoleError(
                "The secure console connection failed. Verify the server certificate and try again.", ConsoleFailureKind.CERTIFICATE)
            chain.any { it is SocketException || it is java.io.EOFException || it is java.io.IOException } -> ConsoleError(
                "The console connection was lost. The network may have changed.", ConsoleFailureKind.LOST)
            else -> ConsoleError("The console could not be opened. Inventory and VM actions remain available.", ConsoleFailureKind.FAILED)
        }
    }
}
