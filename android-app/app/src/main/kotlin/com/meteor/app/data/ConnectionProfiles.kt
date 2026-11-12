package com.meteor.app.data

import java.util.UUID

/** Profile changes never rewrite the original identity recorded in the operation journal. */
object ConnectionProfiles {
    fun draft(document: ProfileDocument, id: String?, label: String, input: String,
        username: String, password: String): Profile {
        val endpoint = Endpoints.normalize(input)
        val user = username.trim()
        require(user.isNotBlank()) { "Enter a username." }
        val original = if (id != null) document.profiles.singleOrNull { it.id == id }
            ?: throw IllegalArgumentException("This connection is no longer available.")
        else document.profiles.firstOrNull { it.endpoint == endpoint && it.username == user }
        require(document.profiles.none { it.id != original?.id && it.endpoint == endpoint && it.username == user }) {
            "Another profile already uses this server and username. Edit that connection instead."
        }
        val credential = if (id != null && password.isEmpty()) requireNotNull(original).password else password
        require(credential.isNotBlank()) { "Enter a password." }
        require(original != null || document.profiles.size < ProfileDocument.MAX_PROFILES) {
            "Up to ${ProfileDocument.MAX_PROFILES} connections can be saved. Edit or forget an existing profile."
        }
        return Profile(original?.id ?: UUID.randomUUID().toString(), label.trim().ifBlank { "XCP-ng connection" },
            endpoint, user, credential, original?.takeIf { it.endpoint == endpoint }?.certificateSha256.orEmpty(),
            // --- B1 console --- Member pins belong to the same server address; a new address starts without any.
            memberPins = original?.takeIf { it.endpoint == endpoint }?.memberPins.orEmpty())
    }

    fun put(document: ProfileDocument, profile: Profile, selectedId: String? = profile.id): ProfileDocument {
        require(document.profiles.none { it.id != profile.id && it.endpoint == profile.endpoint && it.username == profile.username }) {
            "Another profile already uses this server and username."
        }
        // --- B1 console --- A write built from an older copy of the profile must not drop member pins approved
        // meanwhile. Pins of the same server address are merged; the incoming profile wins for the same host.
        val stored = document.profiles.firstOrNull { it.id == profile.id }
        val merged = if (stored != null && stored.endpoint == profile.endpoint && stored.memberPins.isNotEmpty())
            profile.copy(memberPins = stored.memberPins + profile.memberPins) else profile
        // --- end B1 console ---
        val profiles = if (document.profiles.any { it.id == profile.id })
            document.profiles.map { if (it.id == profile.id) merged else it }
        else document.profiles + merged
        return ProfileDocument(profiles, selectedId)
    }

    fun forget(document: ProfileDocument, id: String, operations: List<PowerOperation>): ProfileDocument {
        require(document.profiles.any { it.id == id }) { "This connection is no longer available." }
        require(operations.none { it.profileId == id && it.unresolved }) {
            "Resolve this connection's pending action before forgetting it. Its recovery information must be preserved."
        }
        return ProfileDocument(document.profiles.filterNot { it.id == id }, document.selectedId?.takeUnless { it == id })
    }
}
