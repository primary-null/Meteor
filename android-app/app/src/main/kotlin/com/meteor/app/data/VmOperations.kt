package com.meteor.app.data

/*
 * A2 operations framework: shared declarations used by every journaled VM operation kind.
 *
 * A kind is one entry of [PowerAction] (Power.kt). Each entry declares its XAPI async method
 * (`Async.VM.<operation>`), its arguments ([PowerAction.xapiArguments]), the power states it
 * may start from, the allowed_operations key it needs, the verified end state (power state,
 * optional resident host, optional boot-marker change), the confirmation strength, the minimum
 * XAPI role (explanation only) and its display text. PowerService, PowerRules, the journal,
 * reconciliation and manual review are shared by all kinds; no kind has its own send path.
 */

/** Reversible actions take one confirmation. Disruptive ones require the exact object name. */
enum class ConfirmationStrength { SINGLE, TYPED_NAME }

/** Whether a kind needs a chosen host in the same pool, and whether that may be the current host. */
enum class HostTarget { NONE, ANY_LIVE_HOST, OTHER_LIVE_HOST }

/** A host offered by the picker and re-read immediately before a host-targeted send. */
data class HostChoice(val uuid: String, val name: String, val enabled: Boolean, val live: Boolean, val resident: Boolean = false)

/** Host candidates read fresh for one VM, bound to its pool and identity. */
data class HostChoiceRequest(val action: PowerAction, val authority: String, val vmUuid: String,
    val residentHostUuid: String?, val hosts: List<HostChoice>, val observedAt: Long)

/** XAPI's six static roles, lowest first. Custom or unknown role names are never ranked. */
enum class XapiRole(val label: String) {
    READ_ONLY("read-only"), VM_OPERATOR("vm-operator"), VM_ADMIN("vm-admin"),
    VM_POWER_ADMIN("vm-power-admin"), POOL_OPERATOR("pool-operator"), POOL_ADMIN("pool-admin");
    companion object {
        fun parse(name: String): XapiRole? {
            val normalized = name.trim().lowercase().replace('_', '-').replace(' ', '-')
            return entries.firstOrNull { it.label == normalized }
        }
    }
}

/**
 * What this session's login is known to hold. Advisory only: the server decides every request.
 * [roles] is null when roles could not be read; [problem] then says why.
 */
data class SessionPermissions(val superuser: Boolean = false, val roles: List<String>? = null, val problem: String? = null) {
    val known get() = superuser || roles != null
    companion object {
        fun unavailable(problem: String) = SessionPermissions(problem = problem)
    }
}

/** Permissions bound to the exact connection identity they were read with. */
data class PermissionView(val profileId: String, val origin: String, val permissions: SessionPermissions)

object RoleRules {
    /**
     * Explains, never enforces. Returns text only when every role is a recognized static role and
     * none reaches the kind's minimum. A session's subject can be one of several matching
     * external-auth groups, so a known role list may understate what the server grants.
     */
    fun explanation(permissions: SessionPermissions?, action: PowerAction): String? {
        if (permissions == null || !permissions.known || permissions.superuser || !action.requestable) return null
        val names = permissions.roles ?: return null
        if (names.isEmpty()) return "No role is assigned to this login. The server will probably refuse this action."
        val ranked = names.map { XapiRole.parse(it) ?: return null }
        if (ranked.any { it.ordinal >= action.minimumRole.ordinal }) return null
        return "Your role (${ranked.joinToString { it.label }}) normally does not include this action; " +
            "${action.minimumRole.label} or higher is needed. The server decides."
    }
    // --- B2 storage/host --- Same advisory rule for kinds that are not VM power actions.
    fun explanation(permissions: SessionPermissions?, minimumRole: XapiRole): String? {
        if (permissions == null || !permissions.known || permissions.superuser) return null
        val names = permissions.roles ?: return null
        if (names.isEmpty()) return "No role is assigned to this login. The server will probably refuse this action."
        val ranked = names.map { XapiRole.parse(it) ?: return null }
        if (ranked.any { it.ordinal >= minimumRole.ordinal }) return null
        return "Your role (${ranked.joinToString { it.label }}) normally does not include this action; " +
            "${minimumRole.label} or higher is needed. The server decides."
    }
    // --- end B2 storage/host ---
    fun summary(permissions: SessionPermissions?): String = when {
        permissions == null -> "Permissions have not been read yet. The server decides each request."
        permissions.superuser -> "Signed in as local superuser (pool-admin)."
        permissions.roles != null -> if (permissions.roles.isEmpty()) "No role is assigned to this login."
            else "Roles: ${permissions.roles.joinToString()}."
        else -> "Roles could not be read (${permissions.problem ?: "unavailable"}). The server decides each request."
    }
}
