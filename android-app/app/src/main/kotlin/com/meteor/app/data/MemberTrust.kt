package com.meteor.app.data

import java.net.URI

/**
 * Pins for pool member hosts that serve a VM console. A console location can name the member the VM
 * runs on, whose certificate differs from the saved pool-coordinator pin. The user approves each
 * member's exact leaf SHA-256 once; it is stored with the profile, keyed by the member's authority
 * (`host` or `host:port`, lower case, port omitted when 443).
 */
object MemberPins {
    /** Kept small: the whole profile document is capped at 200 KB for up to 50 profiles. */
    const val MAX_PINS = 24
    private val authorityPattern = Regex("[a-z0-9._\\[\\]:-]{1,255}")
    private val pinPattern = Regex("[0-9a-f]{64}")

    fun authority(host: String, port: Int): String =
        host.lowercase().let { if (port == -1 || port == 443) it else "$it:$port" }

    fun authority(uri: URI): String = authority(uri.host.orEmpty(), uri.port)

    /** The authority of the profile's own (pool coordinator) endpoint. */
    fun endpointAuthority(profile: Profile): String = authority(URI(Endpoints.normalize(profile.endpoint)))

    fun validAuthority(value: String) = authorityPattern.matches(value)
    fun validPin(value: String) = pinPattern.matches(value)

    /** Keeps only well-formed entries. A damaged entry loses trust (safe) instead of failing the whole profile. */
    fun sanitize(raw: Map<*, *>?): Map<String, String> {
        if (raw == null) return emptyMap()
        val clean = linkedMapOf<String, String>()
        for ((key, value) in raw) {
            val authority = key as? String ?: continue
            val pin = (value as? String)?.lowercase() ?: continue
            if (validAuthority(authority) && validPin(pin) && clean.size < MAX_PINS) clean[authority] = pin
        }
        return clean
    }

    /** Adds or replaces one approved pin, leaving the profile's id, endpoint, credentials and coordinator pin untouched. */
    fun approve(profile: Profile, authority: String, pin: String): Profile {
        require(validAuthority(authority) && validPin(pin)) { "Invalid member host pin." }
        require(authority != endpointAuthority(profile)) { "The pool coordinator is pinned by the connection itself." }
        require(authority in profile.memberPins || profile.memberPins.size < MAX_PINS) { "Too many trusted member hosts." }
        return profile.copy(memberPins = profile.memberPins + (authority to pin))
    }
}

/** Chooses which certificate pin may protect a console connection. Pure, so detection is unit-tested. */
object ConsoleTrust {
    sealed interface Decision {
        /** Connect strictly pinned to [pin]. [member] is true when the host is an approved member. */
        data class Pinned(val pin: String, val member: Boolean) : Decision
        /** An unknown host: probe its certificate and ask the user (or accept it if it is the saved coordinator certificate). */
        data class Review(val authority: String) : Decision
    }

    fun isMemberHost(profile: Profile, uri: URI): Boolean = MemberPins.authority(uri) != MemberPins.endpointAuthority(profile)

    fun decide(profile: Profile, uri: URI): Decision {
        val authority = MemberPins.authority(uri)
        if (authority == MemberPins.endpointAuthority(profile)) return Decision.Pinned(profile.certificateSha256, false)
        profile.memberPins[authority]?.let { return Decision.Pinned(it, true) }
        return Decision.Review(authority)
    }
}
