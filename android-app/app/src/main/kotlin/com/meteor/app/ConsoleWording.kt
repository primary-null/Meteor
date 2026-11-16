package com.meteor.app

import com.meteor.app.data.ConsoleFailureKind

/**
 * The console screen's decisions and words that depend on whether it shows a VM or an XCP-ng host. Plain functions, so
 * they are tested on the JVM; `ui/ConsoleScreen.kt` only draws them.
 */
object ConsoleWording {
    /** The toolbar title: a host console says that it is one and names the host. */
    fun title(kind: ConsoleKind, name: String): String = if (kind == ConsoleKind.HOST) "Host console · $name" else name

    /** Ctrl+Alt+Del is offered on a VM console only. On the hypervisor's own console it could restart the host outside the guarded host reboot. */
    fun offersCtrlAltDel(kind: ConsoleKind): Boolean = kind == ConsoleKind.VM

    private fun subject(kind: ConsoleKind) = if (kind == ConsoleKind.HOST) "the host" else "the VM"

    fun typingStatus(kind: ConsoleKind, modifierHeld: Boolean): String =
        if (modifierHeld) "Modifier held · typing goes to ${subject(kind)}" else "Typing goes to ${subject(kind)}"

    fun leaveText(kind: ConsoleKind): String = "A key or mouse button is still held down in ${subject(kind)}. Leaving releases it first."

    const val CONFIRM_INPUT_TITLE = "Send input to this host?"

    /** The one confirmation before input reaches a host console. It names the host and says where keys and clicks go. */
    fun confirmInputText(hostName: String): String =
        "Keys and clicks will go to the hypervisor's own console on $hostName, where the control domain and its root login run. " +
            "Every VM on this host depends on it. Meteor does not record what is typed. Input turns off again when this console reconnects or closes."

    fun failureTitle(kind: ConsoleFailureKind?, console: ConsoleKind): String = when (kind) {
        ConsoleFailureKind.NOT_RUNNING -> if (console == ConsoleKind.HOST) "Host is not live" else "VM is not running"
        ConsoleFailureKind.NO_CONSOLE -> "No console available"
        ConsoleFailureKind.MEMBER_TRUST -> "Pool member not trusted yet"
        ConsoleFailureKind.CERTIFICATE -> "Certificate mismatch"
        ConsoleFailureKind.AUTH -> "Access refused"
        ConsoleFailureKind.NETWORK -> "Host unreachable"
        ConsoleFailureKind.TIMEOUT -> "Timed out"
        ConsoleFailureKind.LOST -> "Connection lost"
        ConsoleFailureKind.BACKGROUND -> "Console closed in the background"
        ConsoleFailureKind.PROTOCOL -> "Console data could not be read"
        ConsoleFailureKind.UNSUPPORTED -> "Console not supported"
        ConsoleFailureKind.FAILED, null -> "Console unavailable"
    }

    fun failureHint(kind: ConsoleFailureKind?, console: ConsoleKind): String? = when (kind) {
        ConsoleFailureKind.CERTIFICATE -> "If the server certificate was renewed, open Connect and review its fingerprint before trusting it again."
        ConsoleFailureKind.NETWORK, ConsoleFailureKind.TIMEOUT, ConsoleFailureKind.LOST ->
            if (console == ConsoleKind.HOST) "The host console needs a direct network path from this device to that host, which can be a pool member."
            else "The console needs a direct network path from this device to the host that runs the VM, which can be a pool member."
        ConsoleFailureKind.PROTOCOL -> "Reconnect retries in compatibility mode, which uses uncompressed encodings only."
        ConsoleFailureKind.MEMBER_TRUST ->
            if (console == ConsoleKind.HOST) "This host is a pool member. Nothing was sent to it." else "The VM runs on a pool member. Nothing was sent to that host."
        else -> null
    }

    fun memberTrustIntro(console: ConsoleKind, authority: String): String =
        "${if (console == ConsoleKind.HOST) "This host's" else "This VM's"} console is served by $authority, a pool member. Its certificate differs from " +
            "the pool coordinator's saved pin, so Meteor needs your approval before it sends the console session to that host."
}
