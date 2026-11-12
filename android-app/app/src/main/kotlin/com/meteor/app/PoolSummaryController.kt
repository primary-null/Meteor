package com.meteor.app

import com.meteor.app.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.coroutines.CoroutineContext

// --- B3 background/summary ---

/**
 * The all-pools summary. Each saved profile other than the connected one gets one bounded read in its own short-lived
 * session ([MeteorGateway.poolSummary]); at most [parallel] run at once and each is waited for at most [timeoutMillis].
 * Only [MeteorState.allPools] is ever written: the current connection, its inventory, alerts, details and notices are
 * left alone. The connected profile's row comes from the live state instead of a second session.
 */
internal class PoolSummaryController(
    private val scope: CoroutineScope,
    private val gateway: MeteorGateway,
    private val state: MutableStateFlow<MeteorState>,
    private val seen: AlertSeenStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ioContext: CoroutineContext = Dispatchers.IO,
    private val timeoutMillis: Long = PoolSummaries.TIMEOUT_MILLIS,
    private val parallel: Int = PoolSummaries.MAX_PARALLEL,
) {
    private var job: Job? = null

    private fun currentId(value: MeteorState) = value.selectedId?.takeIf { value.inventory != null }

    fun refresh() {
        val current = state.value
        if (job?.isActive == true || current.profiles.size < 2) return
        val targets = current.profiles.filter { it.id != currentId(current) && it.certificateSha256.matches(Regex("[0-9a-f]{64}")) }
        if (targets.isEmpty()) return
        state.update { it.copy(allPools = it.allPools.copy(busy = true, checking = targets.map { profile -> profile.id }.toSet())) }
        job = scope.launch {
            try {
                val gate = Semaphore(parallel)
                coroutineScope { targets.forEach { profile -> launch { publish(profile, gate.withPermit { read(profile) }) } } }
            } finally {
                state.update { it.copy(allPools = it.allPools.copy(busy = false, checking = emptySet(), refreshedAt = clock())) }
            }
        }
    }

    /** Runs once when Overview opens if the summary was never read or is older than [PoolSummaries.STALE_MILLIS]. */
    fun refreshIfStale() {
        val at = state.value.allPools.refreshedAt
        if (at == null || clock() - at !in 0 until PoolSummaries.STALE_MILLIS) refresh()
    }

    /** A row is kept only while the same profile still has the same trusted origin. */
    private fun publish(profile: Profile, row: PoolSummaryRow) {
        state.update { value ->
            val alive = value.profiles.any { it.id == profile.id && it.origin() == row.origin }
            value.copy(allPools = value.allPools.copy(
                rows = if (alive) value.allPools.rows + (profile.id to row) else value.allPools.rows - profile.id,
                checking = value.allPools.checking - profile.id))
        }
    }

    private suspend fun read(profile: Profile): PoolSummaryRow {
        val lastSeen = try { seen.lastSeen(profile.id) } catch (error: Exception) {
            if (error is CancellationException) throw error
            null
        }
        return when (val result = bounded(timeoutMillis, ioContext) { gateway.poolSummary(profile, lastSeen) }) {
            is Bounded.Done -> PoolSummaries.reachable(profile, result.value, clock())
            is Bounded.Failed -> PoolSummaries.failed(profile, result.error, clock())
            Bounded.TimedOut -> PoolSummaries.timedOut(profile, clock())
        }
    }

    /**
     * Tapping a row: the same call as choosing the profile in Connections, which runs the existing connection flow
     * (gates, certificate pin, serialized profile commit). Nothing happens for an unknown profile.
     */
    fun open(profileId: String, switchProfile: (Profile) -> Unit) {
        state.value.profiles.firstOrNull { it.id == profileId }?.let(switchProfile)
    }
}

/** One displayed row of the all-pools card. */
data class PoolSummaryLine(
    val profile: Profile, val reachability: PoolReachability, val read: PoolSummaryRead?, val problem: String?,
    val checkedAt: Long?, val current: Boolean, val checking: Boolean,
)

object PoolSummaryLines {
    /** Rows in saved-profile order. The connected profile uses its live inventory and A4 unread count. */
    fun of(state: MeteorState): List<PoolSummaryLine> = state.profiles.map { profile ->
        val inventory = state.inventory
        if (profile.id == state.selectedId && inventory != null) {
            PoolSummaryLine(profile, if (state.connected) PoolReachability.REACHABLE else PoolReachability.NOT_CHECKED,
                PoolSummaryRead(inventory.authority, inventory.poolName, inventory.hosts.count { it.online }, inventory.hosts.size,
                    inventory.guests.count { it.power == "Running" }, inventory.guests.size, state.currentAlerts?.unreadUrgent, inventory.observedAtMillis),
                null, inventory.observedAtMillis, current = true, checking = false)
        } else {
            val row = state.allPools.rows[profile.id]?.takeIf { it.origin == profile.origin() }
            PoolSummaryLine(profile, row?.reachability ?: PoolReachability.NOT_CHECKED, row?.read, row?.problem, row?.checkedAt,
                current = false, checking = profile.id in state.allPools.checking)
        }
    }
}

/**
 * The manual, read-only update check on host details. One call per tap, fenced to the current profile, origin and
 * pool; a result from an older connection is never shown.
 */
internal class HostUpdateController(
    private val scope: CoroutineScope,
    private val gateway: MeteorGateway,
    private val state: MutableStateFlow<MeteorState>,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun check(hostUuid: String) {
        val current = state.value
        val profile = current.selectedProfile ?: return
        val inventory = current.inventory ?: return
        val authority = inventory.authority
        if (!current.connected || inventory.hosts.none { it.uuid == hostUuid }) return
        val scopeKey = AlertScope.key(profile, authority)
        if (current.currentUpdateChecks[hostUuid]?.busy == true) return
        state.update { value ->
            val base = value.updateChecks.takeIf { it.scopeKey == scopeKey } ?: UpdateChecks(scopeKey)
            value.copy(updateChecks = base.copy(hosts = base.hosts + (hostUuid to (base.hosts[hostUuid] ?: UpdateCheckView()).copy(busy = true))))
        }
        scope.launch {
            val view = try {
                UpdateCheckView(result = gateway.checkHostUpdates(profile, authority, hostUuid), checkedAt = clock())
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                UpdateCheckView(problem = HostUpdates.problem(error), checkedAt = clock())
            }
            state.update { value ->
                if (value.updateChecks.scopeKey != scopeKey) value
                else value.copy(updateChecks = value.updateChecks.copy(hosts = value.updateChecks.hosts + (hostUuid to view)))
            }
        }
    }
}

data class UpdateCheckView(val busy: Boolean = false, val result: HostUpdateResult? = null, val problem: String? = null, val checkedAt: Long? = null)
data class UpdateChecks(val scopeKey: String? = null, val hosts: Map<String, UpdateCheckView> = emptyMap())

/** The pending "open Alerts for this profile" request from a notification tap. */
data class AlertsTarget(val profileId: String, val requestedAt: Long)
// --- end B3 ---
