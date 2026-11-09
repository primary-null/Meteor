package com.meteor.app

import com.meteor.app.data.*

data class PendingTrust(val profile: Profile, val certificate: ServerCertificate, val previous: Profile? = null)
data class MeteorState(
    val profiles: List<Profile> = emptyList(), val selectedId: String? = null,
    val inventory: Inventory? = null, val loading: Boolean = false,
    val connected: Boolean = false, val notice: UiNotice? = null,
    val appearance: Appearance = Appearance(), val pendingTrust: PendingTrust? = null,
    val vaultProblem: Boolean = false, val manualConnectionEvent: Int = 0,
    val editingProfileId: String? = null, val profileEditSession: Long = 0, val profileBusy: Boolean = false,
    val history: List<PowerOperation> = emptyList(), val historyLoading: Boolean = false, val historyRevision: Long = 0,
    val selectedGuest: Guest? = null, val details: VmDetails? = null, val detailsBusy: Boolean = false,
    val pendingPower: PowerConfirmation? = null, val operation: PowerOperation? = null,
    val pendingReview: PowerReview? = null,
    val operationBusy: Boolean = false, val journalProblem: Boolean = true,
    val metricTarget: MetricTarget? = null, val metricSamples: List<MetricSnapshot> = emptyList(),
    val metricsBusy: Boolean = false, val metricsProblem: String? = null,
    val poolActivity: PoolActivity? = null, val activityBusy: Boolean = false, val activityProblem: String? = null,
) {
    val message get() = notice?.text
    val selectedProfile get() = profiles.firstOrNull { it.id == selectedId }
}

