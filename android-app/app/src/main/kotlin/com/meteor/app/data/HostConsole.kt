package com.meteor.app.data

import java.net.URI

/**
 * Where an XCP-ng host's own console is served: the `rfb` console of the host's control domain (dom0), the screen with
 * `xsconsole` and the root login. Found by `XapiSession.hostConsoleLocation`; [address] is the host record's `address`
 * and [coordinator] says whether the host is the pool master. Opening it is not a host operation: nothing is journaled.
 */
data class HostConsoleLocation(val location: String, val address: String, val coordinator: Boolean)

/** Whether a host currently offers a console Meteor can show. [reason] says why not, in the words the host details show. */
data class HostConsoleOffer(val reason: String?) {
    val available: Boolean get() = reason == null
}

/** The host console's lookup and identity rules. Pure, so every decision is tested on the JVM. */
object HostConsoleRules {
    const val MAX_CONSOLES = 8

    const val NOT_LIVE = "This host is not live, so its console cannot be opened. Check the host, then reconnect."
    const val NO_CONTROL_DOMAIN = "The server did not report this host's control domain, so there is no host console to open."
    const val TEXT_ONLY = "This host offers only a text (vt100) console, which Meteor cannot display."
    const val NO_CONSOLE = "This host offers no graphical console right now. Try again shortly."
    const val WRONG_HOST = "The server gave a console address that is not this host's, so the console was not opened."

    private fun Map<String, Any?>.text(key: String) = this[key]?.toString().orEmpty()
    private fun Map<String, Any?>.flag(key: String) = this[key] == true || text(key) == "true" || text(key) == "1"
    private fun live(ref: String) = ref.isNotBlank() && ref != "OpaqueRef:NULL"

    /** The control domain the host record names (`host.control_domain`, the field `host.get_control_domain` returns), or null. */
    fun namedControlDomain(host: Map<String, Any?>): String? = host.text("control_domain").takeIf(::live)

    /** True only for the control domain resident on [hostRef]; a guest, template or another host's dom0 never qualifies. */
    fun isControlDomainOf(hostRef: String, vm: Map<String, Any?>): Boolean =
        vm.flag("is_control_domain") && vm.text("resident_on") == hostRef && !vm.flag("is_a_template") && !vm.flag("is_a_snapshot")

    /**
     * For servers without `host.control_domain`: the one control domain whose `resident_on` is the host. None, or more than
     * one, is not guessed between.
     */
    fun fallbackControlDomain(hostRef: String, vms: Map<String, Map<String, Any?>>): String? =
        vms.filter { (_, vm) -> isControlDomainOf(hostRef, vm) }.keys.singleOrNull()

    /**
     * The location of the control domain's `rfb` console. Only consoles that belong to [controlDomainRef] count; the `vt100`
     * console is never chosen (Meteor has no text terminal), and a control domain with only that one says so.
     */
    fun chooseConsole(controlDomainRef: String, consoles: List<Map<String, Any?>>): String {
        val own = consoles.filter { it.text("VM") == controlDomainRef }
        own.firstOrNull { it.text("protocol").equals("rfb", true) && it.text("location").isNotBlank() }?.let { return it.text("location") }
        throw if (own.any { it.text("protocol").equals("vt100", true) }) ConsoleError(TEXT_ONLY, ConsoleFailureKind.NO_CONSOLE)
        else ConsoleError(NO_CONSOLE, ConsoleFailureKind.NO_CONSOLE)
    }

    /**
     * The console must be served by the host itself: the location names the host's own `address`, or, for the pool
     * coordinator only, the address this connection uses. Certificate trust is decided afterwards, exactly as for a VM
     * console (the coordinator's pin, an approved member pin, or an explicit review before any session data is sent).
     */
    fun checkLocation(uri: URI, found: HostConsoleLocation, profile: Profile) {
        val host = uri.host.orEmpty().removeSurrounding("[", "]").lowercase()
        val address = found.address.trim().removeSurrounding("[", "]").lowercase()
        val ownAddress = address.isNotEmpty() && host == address
        val coordinatorEndpoint = found.coordinator && MemberPins.authority(uri) == MemberPins.endpointAuthority(profile)
        if (!ownAddress && !coordinatorEndpoint) throw ConsoleError(WRONG_HOST, ConsoleFailureKind.FAILED)
    }

    /**
     * Why "Open host console" is disabled, or null when it can be pressed. [offer] is the last availability read (null while
     * it loads); [problem] is set when that read failed.
     */
    fun unavailableReason(connected: Boolean, online: Boolean, offer: HostConsoleOffer?, problem: String? = null): String? = when {
        !connected -> "Connect to open the host console."
        !online -> "The host is offline, so it has no console."
        problem != null -> "The host console could not be checked: $problem"
        offer == null -> "Checking the host console…"
        else -> offer.reason
    }
}
