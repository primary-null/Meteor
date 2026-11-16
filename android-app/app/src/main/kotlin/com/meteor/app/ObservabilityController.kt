package com.meteor.app

import com.meteor.app.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** Read-only requests are fenced by connection origin, pool, selection and request generation. */
internal class ObservabilityController(
    private val scope: CoroutineScope, private val gateway: MeteorGateway, private val state: MutableStateFlow<MeteorState>,
) {
    private var metricsGeneration = 0L
    private var activityGeneration = 0L
    private var metricsJob: Job? = null
    private var activityJob: Job? = null
    // --- A3 observability --- history and detail reads share this controller's lifecycle and fences
    internal val depth = DepthController(scope, gateway, state, refreshLive = { refreshMetrics() })
    // --- end A3 observability ---
    fun invalidate() {
        depth.invalidate() // A3 observability
        metricsGeneration++; activityGeneration++
        metricsJob?.cancel(); activityJob?.cancel()
        state.update { it.copy(metricTarget = null, metricSamples = emptyList(), metricsBusy = false, metricsProblem = null,
            poolActivity = null, activityBusy = false, activityProblem = null) }
    }
    private fun ready() = state.value.let { it.connected && !it.loading && !it.profileBusy && !ControlGates.operationExclusive(it) && it.pendingTrust == null } // stop watching: reads run while an operation is observed
    private fun matches(profile: Profile, authority: String) = state.value.let {
        it.connected && it.selectedProfile == profile && it.inventory?.authority == authority && it.pendingTrust == null
    }
    fun openMetrics(target: MetricTarget) {
        val inventory = state.value.inventory ?: return
        if (inventory.authority != target.authority) return
        val visible = if (target.kind == MetricKind.VM) inventory.guests.any { it.uuid == target.uuid }
            else inventory.hosts.any { it.uuid == target.uuid }
        if (!visible) return
        if (state.value.metricTarget != target) {
            depth.resetHistory() // A3 observability: charts never carry over to another object
            metricsGeneration++; metricsJob?.cancel()
            state.update { it.copy(metricTarget = target, metricSamples = emptyList(), metricsBusy = false, metricsProblem = null) }
        }
        refreshMetrics()
    }
    fun refreshMetrics() {
        if (state.value.depth.history.range != null) return // A3 observability: the history view replaces live polling
        if (!ready() || state.value.metricsBusy) return
        val profile = state.value.selectedProfile ?: return
        val target = state.value.metricTarget ?: return
        val request = ++metricsGeneration
        state.update { it.copy(metricsBusy = true) }
        metricsJob = scope.launch {
            try {
                val sample = gateway.metrics(profile, target)
                require(sample.target == target) { "Metric identity changed." }
                if (request == metricsGeneration && matches(profile, target.authority) && state.value.metricTarget == target) {
                    state.update { it.copy(metricSamples = MetricWindow.append(it.metricSamples, sample), metricsProblem = null) }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (request == metricsGeneration && matches(profile, target.authority)) state.update {
                    it.copy(metricsProblem = issue(error, "Metrics"))
                }
            } finally { if (request == metricsGeneration) state.update { it.copy(metricsBusy = false) } }
        }
    }
    fun refreshActivity() {
        if (!ready() || state.value.activityBusy) return
        val profile = state.value.selectedProfile ?: return
        val authority = state.value.inventory?.authority ?: return
        val request = ++activityGeneration
        state.update { it.copy(activityBusy = true) }
        activityJob = scope.launch {
            try {
                val activity = gateway.activity(profile, authority)
                require(activity.authority == authority) { "Pool identity changed." }
                if (request == activityGeneration && matches(profile, authority)) state.update { it.copy(poolActivity = activity, activityProblem = null) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (request == activityGeneration && matches(profile, authority)) state.update {
                    it.copy(activityProblem = issue(error, "Pool activity"))
                }
            } finally { if (request == activityGeneration) state.update { it.copy(activityBusy = false) } }
        }
    }
    // A4: adds the XAPI code or gateway reason to the sentence; see ErrorReasons.readIssue.
    private fun issue(error: Throwable, feature: String): String = ErrorReasons.readIssue(error, feature)
}
