package com.meteor.app

import com.meteor.app.data.*

// A4: followedFrom is the pool member's endpoint when this review is for the master it reported (HOST_IS_SLAVE).
data class PendingTrust(val profile: Profile, val certificate: ServerCertificate, val previous: Profile? = null, val followedFrom: String? = null)
data class MeteorState(
    val profiles: List<Profile> = emptyList(), val selectedId: String? = null,
    val inventory: Inventory? = null, val loading: Boolean = false,
    val connected: Boolean = false, val notice: UiNotice? = null,
    val appearance: Appearance = Appearance(), val pendingTrust: PendingTrust? = null,
    val vaultProblem: Boolean = false, val manualConnectionEvent: Int = 0,
    val editingProfileId: String? = null, val profileEditSession: Long = 0, val profileBusy: Boolean = false,
    val history: List<PowerOperation> = emptyList(), val historyLoading: Boolean = false, val historyRevision: Long = 0,
    // detailsBusy is user-visible loading only (opening, manual refresh, action submission): it disables controls.
    // detailsPolling is the automatic five-second refresh; it never disables a control. See DetailsController.
    val selectedGuest: Guest? = null, val details: VmDetails? = null, val detailsBusy: Boolean = false, val detailsPolling: Boolean = false,
    val pendingPower: PowerConfirmation? = null, val operation: PowerOperation? = null,
    val pendingReview: PowerReview? = null,
    val operationBusy: Boolean = false, val journalProblem: Boolean = true,
    val metricTarget: MetricTarget? = null, val metricSamples: List<MetricSnapshot> = emptyList(),
    val metricsBusy: Boolean = false, val metricsProblem: String? = null,
    val poolActivity: PoolActivity? = null, val activityBusy: Boolean = false, val activityProblem: String? = null,
    // --- A2 operations ---
    val pendingHostChoice: HostChoiceRequest? = null, val hostChoiceBusy: Boolean = false,
    val permissionView: PermissionView? = null,
    // --- end A2 operations ---
    // --- A3 observability --- history charts and read-only host/VM/network/storage detail
    val depth: DepthState = DepthState(),
    // --- A4 alerts/events ---
    val alerts: AlertInbox? = null, val alertsBusy: Boolean = false, val alertsProblem: String? = null,
    val alertDismissBusy: Boolean = false,
    /** True while the activity is at least STARTED. Live events run only then. */
    val foreground: Boolean = false, val events: EventStatus = EventStatus.OFF,
    val masterOffer: MasterOffer? = null,
    // --- end A4 ---
    // --- B2 storage/host ---
    val storageHost: StorageHostView = StorageHostView(),
    // --- end B2 storage/host ---
    // --- VM settings ---
    val vmSettings: VmSettingsView = VmSettingsView(),
    // --- end VM settings ---
    // --- B3 background/summary ---
    /** Per-profile background notification preferences (local DataStore, not the encrypted profile document). */
    val alertNotify: Map<String, AlertNotifySettings> = emptyMap(),
    val allPools: AllPoolsSummary = AllPoolsSummary(),
    /** A notification tap for a profile that is not the connected one; the user chooses whether to switch. */
    val alertsTarget: AlertsTarget? = null,
    val updateChecks: UpdateChecks = UpdateChecks(),
    // --- end B3 ---
    // --- background refresh ---
    /** A same-connection inventory re-read is in flight. Unlike [loading], it keeps [connected] and open dialogs. */
    val refreshing: Boolean = false,
    // --- end background refresh ---
    // --- offline and reconnect ---
    /** The phone has no default network. Shown instead of a server error; no automatic login is attempted meanwhile. */
    val offline: Boolean = false,
    /** Set while automatic reconnects are stopped until the user acts (rejected credentials, changed certificate, pool member). */
    val retryStop: RetryStop? = null,
    // --- end offline and reconnect ---
    // --- stop watching ---
    /**
     * The running operation (still [operationBusy]) has been sent and is only being observed by its saved task UUID.
     * Reads, browsing and connection changes that keep its identity are allowed meanwhile; writes stay locked. See [ControlGates].
     */
    val operationWatching: Boolean = false,
    /** "Stop watching" was chosen; the observation ends at its next safe point. */
    val operationStopping: Boolean = false,
    // --- end stop watching ---
    // --- vault recovery ---
    /** Why the saved connections are unreadable, and whether Try again / Start over is running. Meaningful while [vaultProblem] is set. */
    val vaultRecovery: VaultRecovery = VaultRecovery(),
    // --- end vault recovery ---
) {
    val message get() = notice?.text
    val selectedProfile get() = profiles.firstOrNull { it.id == selectedId }
    // --- A2 operations ---
    /** Advisory permissions read with the selected connection's exact identity, or null. */
    val selectedPermissions: SessionPermissions? get() = selectedProfile?.let { profile ->
        permissionView?.takeIf { it.profileId == profile.id && it.origin == profile.origin() }?.permissions
    }
    // --- end A2 operations ---
    // --- A4 alerts/events ---
    /** Alerts of the current connection only; an inbox from another profile or pool is never shown. */
    val currentAlerts: AlertInbox? get() = alerts?.takeIf { selectedProfile != null && it.scopeKey == AlertScope.key(selectedProfile!!, inventory?.authority) }
    /** Unread priority 1-3 alerts since the last view, for the navigation badge. */
    val unreadAlertBadge: Int get() = currentAlerts?.unreadUrgent ?: 0
    val eventsHealthy: Boolean get() = events == EventStatus.LIVE
    /** How long an inventory snapshot is called "Connected". See METEOR-A4-ALERTS-EVENTS.md. */
    val inventoryFreshMillis: Long get() = if (eventsHealthy) EventPolicy.HEALTHY_FRESH_WINDOW_MILLIS else EventPolicy.FRESH_WINDOW_MILLIS
    // --- end A4 ---
    // --- B3 background/summary ---
    /** Update checks of the current connection only. */
    val currentUpdateChecks: Map<String, UpdateCheckView> get() = selectedProfile?.let { profile ->
        updateChecks.takeIf { it.scopeKey == AlertScope.key(profile, inventory?.authority) }?.hosts
    }.orEmpty()
    // --- end B3 ---
}

