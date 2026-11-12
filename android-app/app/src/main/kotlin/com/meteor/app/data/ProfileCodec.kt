package com.meteor.app.data

/**
 * The stored shape of one profile, as plain maps so it is tested on the JVM (Android's `org.json` is a
 * stub there). The vault version stays 1 and the five original keys keep their meaning, so code from
 * before member-host pins reads a new document unchanged: it never asks for the optional `memberPins`
 * object, which is written only when at least one member pin was approved and is read as empty when
 * missing or damaged.
 */
object ProfileCodec {
    fun toMap(profile: Profile): Map<String, Any?> = linkedMapOf<String, Any?>(
        "id" to profile.id, "label" to profile.label, "endpoint" to profile.endpoint,
        "username" to profile.username, "password" to profile.password, "certificateSha256" to profile.certificateSha256,
    ).also { if (profile.memberPins.isNotEmpty()) it["memberPins"] = LinkedHashMap(profile.memberPins) }

    fun fromMap(map: Map<String, Any?>): Profile {
        fun text(key: String) = map[key] as? String ?: throw IllegalArgumentException("Missing profile field.")
        return Profile(text("id"), text("label"), Endpoints.normalize(text("endpoint")), text("username"), text("password"),
            text("certificateSha256"), MemberPins.sanitize(map["memberPins"] as? Map<*, *>)).also {
            require(it.certificateSha256.matches(Regex("[0-9a-f]{64}")))
            require(it.id.isNotBlank() && it.username.isNotBlank())
        }
    }
}
