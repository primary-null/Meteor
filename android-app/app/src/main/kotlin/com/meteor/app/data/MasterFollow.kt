package com.meteor.app.data

import java.net.URI

// --- A4 alerts/events ---

/**
 * The saved endpoint answered HOST_IS_SLAVE: it is a pool member and named the pool master. This is only an offer.
 * Nothing is switched until the user accepts and then explicitly trusts the master's own certificate.
 * [draft] keeps the profile id, label, username and password; its endpoint is the master and its pin is empty.
 */
data class MasterOffer(
    val profileId: String, val memberEndpoint: String, val masterAddress: String,
    val masterEndpoint: String, val draft: Profile,
) {
    /** Declining is remembered per profile, member and master so background refreshes do not nag. */
    val key: String get() = "$profileId|$memberEndpoint|$masterEndpoint"
}

object MasterFollow {
    private val hostName = Regex("[A-Za-z0-9](?:[A-Za-z0-9.-]{0,251}[A-Za-z0-9])?")
    private val ipv6 = Regex("[0-9A-Fa-f:]{2,45}")

    /** The master address is server-supplied, so only a plain host name, IPv4 or IPv6 literal is accepted. */
    fun address(details: List<String>): String? {
        val raw = details.firstOrNull()?.trim().orEmpty().removeSurrounding("[", "]")
        return when {
            raw.contains(':') -> raw.takeIf { it.matches(ipv6) }?.lowercase()
            raw.matches(hostName) -> raw.lowercase()
            else -> null
        }
    }

    /** HTTPS endpoint of the master. The member's explicit port, if any, is kept; the path is always `/`. */
    fun endpointFor(memberEndpoint: String, address: String): String? = runCatching {
        val member = URI(Endpoints.normalize(memberEndpoint))
        val host = if (address.contains(':')) "[$address]" else address
        Endpoints.normalize("https://$host${if (member.port == -1) "" else ":${member.port}"}/")
            .takeUnless { it == Endpoints.normalize(memberEndpoint) }
    }.getOrNull()

    fun offer(profile: Profile, error: Throwable): MasterOffer? {
        if (error !is RpcFailure || error.code != "HOST_IS_SLAVE") return null
        val address = address(error.details) ?: return null
        val master = endpointFor(profile.endpoint, address) ?: return null
        return MasterOffer(profile.id, Endpoints.normalize(profile.endpoint), address, master,
            profile.copy(endpoint = master, certificateSha256 = ""))
    }
}
// --- end A4 ---
