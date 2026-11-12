package com.meteor.app

import com.meteor.app.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * Read-only history and detail requests, fenced like [ObservabilityController]: every result is discarded unless the
 * connection (profile), pool, requested object and request generation still match when it arrives.
 */
internal class DepthController(
    private val scope: CoroutineScope, private val gateway: MeteorGateway, private val state: MutableStateFlow<MeteorState>,
    private val clock: () -> Long = System::currentTimeMillis, private val refreshLive: () -> Unit = {},
) {
    private class Slot { var generation = 0L; var job: Job? = null }
    private var historyGeneration = 0L
    private var historyJob: Job? = null
    private val vmSlot = Slot()
    private val hostSlot = Slot()
    private val networksSlot = Slot()
    private val storageSlot = Slot()

    /** Disconnect, profile change or pool change: drop every in-flight and stored result. */
    fun invalidate() {
        historyGeneration++; historyJob?.cancel()
        listOf(vmSlot, hostSlot, networksSlot, storageSlot).forEach { it.generation++; it.job?.cancel() }
        state.update { it.copy(depth = DepthState()) }
    }
    /** The performance target changed, so charts for the previous object must not carry over. */
    fun resetHistory() {
        historyGeneration++; historyJob?.cancel()
        state.update { it.copy(depth = it.depth.copy(history = HistoryState())) }
    }

    private fun ready() = state.value.let { it.connected && !it.loading && !it.profileBusy && !it.operationBusy && it.pendingTrust == null }
    private fun matches(profile: Profile, authority: String) = state.value.let {
        it.connected && it.selectedProfile == profile && it.inventory?.authority == authority && it.pendingTrust == null
    }
    private fun history(change: (HistoryState) -> HistoryState) = state.update { it.copy(depth = it.depth.copy(history = change(it.depth.history))) }

    // ---- history ----

    /** Null selects the live view; a range starts a read immediately. */
    fun selectRange(range: HistoryRange?) {
        val target = state.value.metricTarget ?: return
        historyGeneration++; historyJob?.cancel()
        history { HistoryState(target = target, range = range) }
        if (range == null) refreshLive() else refreshHistory()
    }

    /** Called from the page's five-second tick; each range decides how often it really reads. */
    fun refreshHistoryIfDue() {
        val current = state.value
        val range = current.depth.history.range ?: return
        if (current.depth.history.target != current.metricTarget) return
        if (clock() - current.depth.history.requestedAt < range.pollSeconds * 1000) return
        refreshHistory()
    }

    fun refreshHistory() {
        val current = state.value
        val progress = current.depth.history
        val range = progress.range ?: return
        val target = current.metricTarget ?: return
        val profile = current.selectedProfile ?: return
        if (progress.target != target || progress.busy || !ready() || current.inventory?.authority != target.authority) return
        val request = ++historyGeneration
        // Later reads only ask for rows after the newest one held. The merge keeps the window bounded.
        val since = progress.snapshot?.timestamps?.lastOrNull()
        history { it.copy(busy = true, requestedAt = clock()) }
        fun fenced() = request == historyGeneration && matches(profile, target.authority) &&
            state.value.metricTarget == target && state.value.depth.history.range == range
        historyJob = scope.launch {
            try {
                val result = gateway.history(profile, target, range, since)
                require(result.target == target && result.range == range) { "History identity changed." }
                if (fenced()) history { it.copy(snapshot = HistoryWindow.merge(it.snapshot, result), problem = null, unavailable = null) }
            } catch (error: HistoryUnavailable) {
                if (fenced()) {
                    history { HistoryState(target = target, range = null, unavailable = error.reason) }
                    refreshLive()
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (fenced()) history { it.copy(problem = issue(error, "History")) }
            } finally { if (request == historyGeneration) history { it.copy(busy = false) } }
        }
    }

    // ---- on-demand detail reads ----

    fun loadVm(uuid: String) = load<VmInfo>(vmSlot, uuid, "VM details", { inventory -> inventory.guests.any { it.uuid == uuid } },
        { profile, authority -> gateway.vmInfo(profile, authority, uuid) }, { value, authority -> value.authority == authority && value.uuid == uuid },
        { it.vm }, { depth, slot -> depth.copy(vm = slot) })
    /**
     * The held info of this VM may no longer match the server: it moved, restarted or changed power state, an operation on it
     * closed, or the user opened or refreshed its details. A read in flight started before the change, so it is cancelled and
     * its late result discarded. The last value stays visible, marked [InfoSlot.stale]; the page reads again as soon as it is
     * allowed to (not loading, no operation running), which is how a skipped read is already retried. Nothing is read here,
     * and a VM whose info is not held needs no mark: it is read when its page first appears.
     */
    fun markVmStale(authority: String, uuid: String) {
        val key = "$authority:$uuid"
        if (state.value.depth.vm.key != key) return
        vmSlot.generation++; vmSlot.job?.cancel()
        state.update {
            val slot = it.depth.vm
            if (slot.key != key) it else it.copy(depth = it.depth.copy(vm = slot.copy(busy = false, problem = null, stale = true)))
        }
    }
    fun loadHost(uuid: String) = load<HostInfo>(hostSlot, uuid, "Host details", { inventory -> inventory.hosts.any { it.uuid == uuid } },
        { profile, authority -> gateway.hostInfo(profile, authority, uuid) }, { value, authority -> value.authority == authority && value.uuid == uuid },
        { it.host }, { depth, slot -> depth.copy(host = slot) })
    fun loadStorage(uuid: String) = load<StorageInfo>(storageSlot, uuid, "Storage details", { inventory -> inventory.repositories.any { it.uuid == uuid } },
        { profile, authority -> gateway.storageInfo(profile, authority, uuid) }, { value, authority -> value.authority == authority && value.uuid == uuid },
        { it.storage }, { depth, slot -> depth.copy(storage = slot) })
    fun loadNetworks() = load<NetworkOverview>(networksSlot, "networks", "Networks", { true },
        { profile, authority -> gateway.networks(profile, authority) }, { value, authority -> value.authority == authority },
        { it.networks }, { depth, slot -> depth.copy(networks = slot) })

    private fun <T> load(slot: Slot, objectKey: String, feature: String, known: (Inventory) -> Boolean,
        read: suspend (Profile, String) -> T, identity: (T, String) -> Boolean,
        get: (DepthState) -> InfoSlot<T>, set: (DepthState, InfoSlot<T>) -> DepthState) {
        val current = state.value
        val inventory = current.inventory ?: return
        val profile = current.selectedProfile ?: return
        val authority = inventory.authority
        val key = "$authority:$objectKey"
        val existing = get(current.depth)
        if (!ready() || !known(inventory) || (existing.busy && existing.key == key)) return
        val request = ++slot.generation
        slot.job?.cancel()
        // A refresh of the same object keeps its last value; a different object starts empty.
        state.update { it.copy(depth = set(it.depth, InfoSlot(key, existing.value.takeIf { existing.key == key }, busy = true, problem = existing.problem.takeIf { existing.key == key }))) }
        slot.job = scope.launch {
            try {
                val value = read(profile, authority)
                require(identity(value, authority)) { "Object identity changed." }
                if (request == slot.generation && matches(profile, authority)) state.update { it.copy(depth = set(it.depth, InfoSlot(key, value))) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (request == slot.generation && matches(profile, authority)) state.update {
                    it.copy(depth = set(it.depth, get(it.depth).copy(problem = issue(error, feature))))
                }
            } finally {
                if (request == slot.generation) state.update { it.copy(depth = set(it.depth, get(it.depth).copy(busy = false))) }
            }
        }
    }

    private fun issue(error: Throwable, feature: String): String = when {
        error is UnsupportedOperationException || error is RpcFailure && error.code in setOf("MESSAGE_METHOD_UNKNOWN", "NOT_IMPLEMENTED") ||
            error is GatewayError && error.reason == "http-unsupported" -> "$feature is not supported by this server."
        error is RpcFailure && error.code in setOf("RBAC_PERMISSION_DENIED", "PERMISSION_DENIED") ||
            error is GatewayError && error.reason == "http-denied" -> "$feature is not available to this account."
        error is GatewayError && error.reason == "response-limit" -> "$feature returned more data than Meteor will read safely."
        error is IllegalArgumentException -> "$feature returned data Meteor could not verify. Any displayed values are last known."
        else -> "$feature could not be refreshed securely. Any displayed values are last known. Check the connection before trying again."
    }
}
