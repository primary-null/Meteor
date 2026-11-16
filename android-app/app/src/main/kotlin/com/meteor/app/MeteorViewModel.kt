package com.meteor.app

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.meteor.app.data.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

class MeteorViewModel @JvmOverloads constructor(
    application: Application,
    private val gateway: MeteorGateway = XapiGateway(),
    private val vault: ProfileStorage = ProfileVault(application),
    private val journal: OperationJournal = OperationJournal(application),
    inspector: CertificateInspector = ServerTrust,
    network: NetworkMonitor = AndroidNetworkMonitor(application), // offline and reconnect
) : AndroidViewModel(application) {
    private val networkMonitor = network
    private val preferences = AppearanceStore(application)
    val customIcons = CustomIcons(application)
    private val power = PowerService(gateway, journal)
    private val mutable = MutableStateFlow(MeteorState())
    val state = mutable.asStateFlow()
    private val observations = ObservabilityController(viewModelScope, gateway, mutable)
    // --- fold3 device findings --- VM detail reads: user-visible loads vs the automatic poll (detailsBusy vs detailsPolling).
    // A changed power state, host or boot marker also marks A3's read-only VM info stale so it is read again.
    private val details: DetailsController = DetailsController(viewModelScope, gateway, mutable, { connections.generation }, ::friendly,
        vmChanged = { authority, uuid -> observations.depth.markVmStale(authority, uuid) })
    // --- end fold3 device findings ---

    // --- A1 ---
    /** Typed back stack for the shell; lives here so it survives rotation and fold/unfold. */
    val navigator = com.meteor.app.ui.Navigator()
    // --- end A1 ---

    private val connections: ConnectionController = ConnectionController(viewModelScope, gateway, vault, journal, inspector, mutable,
        ::closeDetails, details::invalidate, ::openDetails, ::friendly, observations::invalidate, network)

    // --- A4 alerts/events ---
    private val alerts = AlertsController(viewModelScope, gateway, mutable, AlertSeenDataStore(application), notifications = SystemAlertNotifier(application))
    private val events = EventController(viewModelScope, gateway, mutable, object : EventActions {
        // An event-triggered inventory read is skipped when the snapshot is already that fresh.
        override fun refreshInventory() {
            val observed = mutable.value.inventory?.observedAtMillis ?: 0
            if (System.currentTimeMillis() - observed >= EventPolicy.MIN_REFRESH_GAP_MILLIS) connections.refresh(automatic = true)
        }
        override fun refreshDetails() = this@MeteorViewModel.refreshDetails()
        override fun refreshAlerts(deletedMessages: Set<String>, full: Boolean) = alerts.onMessageEvents(deletedMessages, full)
        override fun refreshActivity() { if (mutable.value.poolActivity != null) observations.refreshActivity() }
    })
    // --- end A4 ---
    // --- stop watching --- The running journaled operation and its "Stop watching" switch, shared by VM and storage/host writes.
    private val runs = OperationRuns(mutable, ::publishOperation)
    fun stopWatching() = runs.stop()
    // --- end stop watching ---
    // --- B2 storage/host ---
    /** Snapshots, CD drives, host maintenance and PBD repair. Writes share [power] and its journal. */
    internal val storageHost = StorageHostController(viewModelScope, gateway, power, mutable, { connections.generation }, ::friendly,
        ::publishOperation, ::recoverJournalAfterError, ::refresh, runs = runs)
    // --- end B2 storage/host ---
    // --- VM settings --- Name, tags, vCPUs, memory and start-up settings. Writes share [power] and its journal.
    internal val vmSettings = VmSettingsController(viewModelScope, gateway, power, mutable, { connections.generation }, ::friendly,
        ::recoverJournalAfterError, ::refresh, { refreshDetails() }, runs)
    // --- end VM settings ---

    // --- B3 background/summary ---
    private val backgroundAlerts: BackgroundAlertStore = BackgroundAlertDataStore(application)
    /** What Settings shows about the background checks: each profile's last check and the last run's note. */
    val backgroundChecks: kotlinx.coroutines.flow.Flow<BackgroundChecks> = backgroundAlerts.checks
    private val pools = PoolSummaryController(viewModelScope, gateway, mutable, AlertSeenDataStore(application))
    private val hostUpdates = HostUpdateController(viewModelScope, gateway, mutable)
    /** Set when the user chose "Switch" for a notification's profile, so the completed connection lands on Alerts. */
    private var alertsAfterConnect: AlertsTarget? = null
    init {
        viewModelScope.launch {
            try { backgroundAlerts.settings.collect { settings -> mutable.update { it.copy(alertNotify = settings) } } }
            catch (error: Exception) {
                if (error is CancellationException) throw error
                mutable.update { it.copy(notice = UiNotice.error("Notification settings could not be read.")) }
            }
        }
        // A notification's switch offer is spent once its profile is the selected one, however that happened.
        viewModelScope.launch {
            mutable.map { it.selectedId }.distinctUntilChanged().collect { id ->
                if (id != null) mutable.update { if (it.alertsTarget?.profileId == id) it.copy(alertsTarget = null) else it }
            }
        }
    }
    /** Called once the vault has loaded: preferences of forgotten profiles are removed, and the schedule follows. */
    private fun backgroundProfilesLoaded() {
        if (backgroundPruning) return // vault recovery: it can run again after a later successful open
        backgroundPruning = true
        viewModelScope.launch {
            mutable.map { it.profiles.map { profile -> profile.id }.toSet() to it.vaultProblem }.distinctUntilChanged().collect { (ids, vaultProblem) ->
                // Session-only profiles (unreadable vault) never prune stored preferences.
                if (vaultProblem) return@collect
                // Housekeeping only: a failure leaves harmless old entries and the next profile change prunes again.
                try { backgroundAlerts.retainOnly(ids) } catch (error: Exception) { if (error is CancellationException) throw error }
                // --- vault recovery --- The in-app "alert seen" values of forgotten profiles go the same way.
                try { alertSeen.retainOnly(ids) } catch (error: Exception) { if (error is CancellationException) throw error }
                // --- end vault recovery ---
                syncBackgroundSchedule()
            }
        }
    }
    private suspend fun syncBackgroundSchedule() {
        try { BackgroundAlertScheduler.sync(getApplication(), backgroundAlerts.allSettings().values) }
        catch (error: Exception) {
            if (error is CancellationException) throw error
            mutable.update { it.copy(notice = UiNotice.error("Background checks could not be scheduled. Change the notification setting again to retry.")) }
        }
    }
    // --- end B3 ---

    // --- vault recovery ---
    private val alertSeen = AlertSeenDataStore(application)
    private var backgroundPruning = false
    private val recovery = VaultRecoveryController(viewModelScope, vault, mutable,
        writes = { block -> connections.withProfileWrites(block) }, allowed = connections::canRecoverVault,
        open = { vault.importDebugBootstrap(application); vault.load() },
        retain = { ids -> backgroundAlerts.retainOnly(ids); alertSeen.retainOnly(ids); syncBackgroundSchedule() }, opened = ::vaultOpened)
    /** Connections screen: read the saved connections again. Transient failures recover without restarting the app. */
    fun retryVault() = recovery.retry()
    /** Connections screen, after the confirmation: set the unreadable vault aside and save again. The operation journal is untouched. */
    fun startOverVault() = recovery.startOver()
    /** The saved connections opened after an earlier failure: the steps start-up does after a good load. */
    private fun vaultOpened(connect: Profile?) {
        backgroundProfilesLoaded()
        connect?.let { connections.connect(it, manual = false) }
        // A pending action is rechecked only when its own connection is saved; otherwise the notice explains how to review it.
        mutable.value.operation?.takeIf { it.unresolved }?.let { op -> if (mutable.value.profiles.any { it.id == op.profileId }) checkOperation() }
    }
    // --- end vault recovery ---

    init {
        // --- A4 alerts/events ---
        // Alerts are read after every inventory snapshot (throttled) and dropped when the connection goes away.
        viewModelScope.launch {
            mutable.map { Triple(it.selectedId, it.inventory?.authority, it.inventory?.observedAtMillis) }.distinctUntilChanged()
                .collect { (id, authority, _) -> if (id == null || authority == null) alerts.invalidate() else alerts.refresh(minGapMillis = 10_000) }
        }
        events.start()
        // --- end A4 ---
        viewModelScope.launch {
            try { preferences.flow.collect { settings -> mutable.update { it.copy(appearance = settings) } } }
            catch (error: Exception) {
                if (error is CancellationException) throw error // clearing the view model is not a read failure
                mutable.update { it.copy(notice = UiNotice.error("Appearance settings could not be read.")) }
            }
        }
        viewModelScope.launch {
            val document = try {
                vault.importDebugBootstrap(application)
                vault.load()
            } catch (error: Exception) {
                if (error is CancellationException) throw error // clearing the view model must not report an unreadable vault
                // --- vault recovery --- The Connections screen and a banner explain it and offer Try again / Start over.
                recovery.loadFailed(error)
                // --- end vault recovery ---
                // The journal is stored separately. Loading it keeps a pending action visible and
                // reviewable from a session-only connection instead of silently locking power controls.
                loadJournal()
                connections.initialized = true
                return@launch
            }
            mutable.update { it.copy(profiles = document.profiles, selectedId = document.selectedId) }
            loadJournal()
            connections.initialized = true
            backgroundProfilesLoaded() // B3 background/summary
            document.profiles.firstOrNull { it.id == document.selectedId }?.let { connections.connect(it, manual = false) }
            if (mutable.value.operation?.unresolved == true) checkOperation()
        }
    }

    private suspend fun loadJournal() {
        val operations = try { journal.load() } catch (error: Exception) {
            if (error is CancellationException) throw error
            mutable.update { it.copy(journalProblem = true,
                notice = UiNotice.error("Operation history could not be opened. Power controls are locked; existing data was preserved.")) }
            return
        }
        publishJournal(operations)
    }
    private fun publishJournal(operations: List<PowerOperation>, expectedRevision: Long? = null) {
        mutable.update { it.withJournal(operations, expectedRevision) }
    }
    private fun publishOperation(operation: PowerOperation) {
        mutable.update { it.withOperation(operation) }
        // --- fold3 device findings --- A closed operation on the open VM (a migration, a restart) makes its read-only info stale.
        if (VmInfoReload.operationClosed(mutable.value.selectedGuest?.uuid, mutable.value.inventory?.authority, operation))
            observations.depth.markVmStale(operation.authority, operation.vmUuid)
        // --- end fold3 device findings ---
    }
    fun reloadHistory() {
        if (mutable.value.operationBusy || mutable.value.profileBusy || mutable.value.historyLoading) return
        val revision = mutable.value.historyRevision
        mutable.update { it.copy(historyLoading = true) }
        viewModelScope.launch {
            try {
                val operations = journal.load()
                publishJournal(operations, expectedRevision = revision)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutable.update { it.copy(journalProblem = true, notice = UiNotice.error("Operation history could not be read. Existing records were preserved; power controls are locked.")) }
            } finally { mutable.update { it.copy(historyLoading = false) } }
        }
    }

    fun editProfile(id: String) = connections.editProfile(id)
    fun cancelEdit() = connections.cancelEdit()
    fun saveConnection(label: String, input: String, username: String, password: String, id: String) =
        connections.saveConnection(label, input, username, password, id)
    fun inspect(label: String, input: String, username: String, password: String, id: String? = null) =
        connections.inspect(label, input, username, password, id)
    fun cancelTrust() = connections.cancelTrust()
    fun acceptTrust() = connections.acceptTrust()
    fun switchProfile(profile: Profile) = connections.switchProfile(profile)
    // --- background refresh --- Header Refresh, live events, the fallback poll and post-operation reads: a same-connection
    // re-read while connected; a full reconnect only when not connected. Explicit connections use switchProfile/acceptTrust.
    fun refresh() = connections.refresh()
    // A4: 30 s polling stays the fallback; it lengthens to 90 s while live events are healthy.
    fun refreshIfStale() = connections.refreshIfStale(if (mutable.value.eventsHealthy) EventPolicy.HEALTHY_STALE_MILLIS else EventPolicy.POLLING_STALE_MILLIS)
    fun disconnect() = connections.disconnect()
    fun forgetProfile(id: String) = connections.forgetProfile(id)
    fun openMetrics(target: MetricTarget) { closeDetails(); observations.openMetrics(target) }
    fun refreshMetrics() = observations.refreshMetrics()
    fun refreshActivity() = observations.refreshActivity()
    // --- A3 observability ---
    fun selectHistoryRange(range: HistoryRange?) = observations.depth.selectRange(range)
    fun refreshHistory() = observations.depth.refreshHistory()
    fun refreshHistoryIfDue() = observations.depth.refreshHistoryIfDue()
    fun loadVmInfo(uuid: String) = observations.depth.loadVm(uuid)
    fun loadHostInfo(uuid: String) = observations.depth.loadHost(uuid)
    fun loadStorageInfo(uuid: String) = observations.depth.loadStorage(uuid)
    fun loadNetworks() = observations.depth.loadNetworks()
    // --- end A3 observability ---
    // --- A4 alerts/events ---
    /** Called from the activity lifecycle: live events run only while it is at least STARTED. */
    fun setForeground(value: Boolean) {
        mutable.update { if (it.foreground == value) it else it.copy(foreground = value) }
        // offline and reconnect: the default-network callback is registered only while the app is in the foreground.
        if (value) networkMonitor.start() else networkMonitor.stop()
        // --- session reuse --- the shared XAPI session is logged out after a grace period in the background
        gateway.backgrounded(!value)
        // --- end session reuse ---
    }
    fun refreshAlerts() = alerts.refresh()
    /** Manual refresh: a full re-read, which also reveals deletions. */
    fun reloadAlerts() = alerts.refresh(full = true)
    fun markAlertsSeen() = alerts.markSeen()
    fun dismissAlerts(messages: List<AlertMessage>) = alerts.dismiss(messages)
    fun followMaster() = connections.followMaster()
    fun declineMaster() = connections.declineMaster()
    // --- end A4 ---
    // --- B3 background/summary ---
    /**
     * Stores one profile's background notification preference. Turning it on starts from now (old alerts are never
     * notified); turning it off forgets the watermark so a later re-enable starts fresh. The caller has already asked
     * for POST_NOTIFICATIONS. Session-only profiles cannot be checked in the background, so they cannot opt in.
     */
    fun setAlertNotify(profileId: String, requested: AlertNotifySettings) {
        val current = mutable.value
        if (current.vaultProblem || current.profiles.none { it.id == profileId }) return
        val previous = current.alertNotify[profileId] ?: AlertNotifySettings()
        val next = AlertNotifySettings.change(previous, requested, System.currentTimeMillis())
        viewModelScope.launch {
            try {
                if (AlertNotifySettings.restarts(previous, next)) backgroundAlerts.resetProgress(profileId)
                backgroundAlerts.setSettings(profileId, next)
                if (next.enabled) runCatching { AlertChannels.ensure(getApplication()) }
                syncBackgroundSchedule()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutable.update { it.copy(notice = UiNotice.error("The notification preference could not be saved. Try again.")) }
            }
        }
    }
    fun refreshAllPools() = pools.refresh()
    fun refreshAllPoolsIfStale() = pools.refreshIfStale()
    /** Same as choosing the profile in Connections. */
    fun openPool(profileId: String) = pools.open(profileId, connections::switchProfile)
    fun checkHostUpdates(hostUuid: String) = hostUpdates.check(hostUuid)
    /** A notification tap. It opens Alerts; a different profile is only offered, never connected automatically. */
    fun openAlertsFor(profileId: String) {
        navigator.open(com.meteor.app.ui.Destination.Alerts)
        if (mutable.value.selectedGuest != null) closeDetails() // leaving Fleet stops VM detail polling
        mutable.update { it.copy(alertsTarget = if (it.selectedId == profileId) null else AlertsTarget(profileId, System.currentTimeMillis())) }
    }
    fun switchToAlertsTarget() {
        val target = mutable.value.alertsTarget ?: return
        val profile = mutable.value.profiles.firstOrNull { it.id == target.profileId } ?: return
        alertsAfterConnect = target.copy(requestedAt = System.currentTimeMillis())
        mutable.update { it.copy(alertsTarget = null) }
        connections.switchProfile(profile)
    }
    fun dismissAlertsTarget() { mutable.update { it.copy(alertsTarget = null) } }
    /** True once, right after the manual connection that the user started from a notification's switch offer. */
    fun takeAlertsAfterConnect(): Boolean {
        val target = alertsAfterConnect ?: return false
        alertsAfterConnect = null
        return target.profileId == mutable.value.selectedId && System.currentTimeMillis() - target.requestedAt in 0..120_000
    }
    // --- end B3 ---

    /** User-initiated: opening the details, re-opening them and "Refresh details". Shows progress and disables controls while it reads. */
    fun openDetails(guest: Guest) {
        val profile = mutable.value.selectedProfile ?: return
        val authority = mutable.value.inventory?.authority ?: return
        if (guest.poolUuid != authority) return
        mutable.update { it.copy(selectedGuest = guest) }
        if (mutable.value.loading || !mutable.value.connected) return
        details.load(profile, authority, guest.uuid)
        // --- fold3 device findings --- opening or refreshing the VM also reads its read-only info again (A3).
        observations.depth.markVmStale(authority, guest.uuid)
        // --- end fold3 device findings ---
        refreshPermissions(profile) // A2 operations: advisory role read for this connection.
    }
    fun closeDetails() { details.close() }
    /** The automatic refresh (every five seconds, and after an action or event). It never disables a control: see [DetailsController]. */
    fun refreshDetails() { details.refresh() }
    fun requestPower(action: PowerAction) {
        val state = mutable.value
        if (state.journalProblem || state.operationBusy || state.profileBusy || state.detailsBusy || state.loading || state.operation?.unresolved == true || !state.connected) return
        val detail = state.details ?: return
        val profile = state.selectedProfile ?: return
        details.load(profile, detail.authority, detail.uuid, action)
    }
    fun cancelPower() { mutable.update { it.copy(pendingPower = null) } }
    fun confirmPower(typedName: String? = null) {
        val state = mutable.value
        val confirmation = state.pendingPower?.copy(typedName = typedName) ?: return
        val profile = state.selectedProfile ?: return
        if (state.operationBusy || state.profileBusy || state.journalProblem || state.operation?.unresolved == true) return
        // A2 operations: a typed confirmation that does not match never reaches the service.
        if (!PowerRules.typedNameMatches(confirmation)) return
        mutable.update { it.copy(pendingPower = null, operationBusy = true) }
        viewModelScope.launch {
            try {
                runs.run { changed -> power.execute(profile, confirmation, changed) } // stop watching
            } catch (error: Exception) {
                recoverJournalAfterError(error)
            } finally {
                mutable.update { it.copy(operationBusy = false) }
                refresh(); refreshDetails()
            }
        }
    }
    fun checkOperation() {
        val state = mutable.value
        val op = state.operation?.takeIf { it.unresolved } ?: return
        if (state.operationBusy || state.profileBusy || state.journalProblem) return
        val profile = state.profiles.firstOrNull { it.id == op.profileId } ?: run {
            mutable.update { it.copy(notice = UiNotice.error("The original connection for this action is not available. Connect to the same pool, then review the pending action.")) }
            return
        }
        if (profile.origin() != op.origin) {
            mutable.update { it.copy(notice = UiNotice.error("Automatic recovery requires the original trusted connection. Repair the connection, then review the pending action.")) }
            return
        }
        mutable.update { it.copy(operationBusy = true, pendingPower = null) }
        viewModelScope.launch {
            try { runs.run { changed -> power.reconcile(profile, op, changed) } } // stop watching
            catch (error: Exception) { recoverJournalAfterError(error) }
            finally {
                mutable.update { it.copy(operationBusy = false) }; refresh(); refreshDetails()
                // A settled settings record restarts an open editor from the server's values, as a finished save does.
                if (op.action == PowerAction.SETTINGS) vmSettings.afterRecordClosed(op.authority, op.vmUuid)
            }
        }
    }
    fun reviewOperation() {
        val state = mutable.value
        val operation = state.operation?.takeIf { it.unresolved } ?: return
        // --- B2 storage/host --- A storage/host record is reviewed from its own object, never by reading a VM.
        if (operation.targetAction != null && operation.foreign == null) { storageHost.review(operation); return }
        // --- end B2 storage/host ---
        val profile = state.selectedProfile ?: return
        if (!state.connected || state.loading || state.operationBusy || state.profileBusy || state.journalProblem) return
        mutable.update { it.copy(operationBusy = true, pendingPower = null, pendingReview = null) }
        viewModelScope.launch {
            runCatching { power.review(profile, operation) }
                .onSuccess { review -> mutable.update { it.copy(pendingReview = review) } }
                .onFailure { error -> mutable.update { it.copy(notice = UiNotice.error(friendlyReview(error))) } }
            mutable.update { it.copy(operationBusy = false) }
        }
    }
    fun cancelReview() { mutable.update { it.copy(pendingReview = null) } }
    fun confirmReview(serverHistoryChecked: Boolean) {
        val state = mutable.value
        val review = state.pendingReview ?: return
        val profile = state.selectedProfile ?: return
        if (!serverHistoryChecked || state.operationBusy || state.profileBusy || state.journalProblem || state.loading || !state.connected) return
        mutable.update { it.copy(pendingReview = null, operationBusy = true) }
        viewModelScope.launch {
            try { power.resolveAfterReview(profile, review, serverHistoryChecked, ::publishOperation) }
            catch (error: Exception) { mutable.update { it.copy(notice = UiNotice.error(friendlyReview(error))) } }
            finally { mutable.update { it.copy(operationBusy = false) } }
        }
    }
    // --- A2 operations ---
    /** Reads hosts fresh for a host-targeted kind. Choosing one re-reads the VM before confirmation. */
    fun requestHostChoice(action: PowerAction) {
        val state = mutable.value
        if (action.hostTarget == HostTarget.NONE || state.hostChoiceBusy) return
        if (state.journalProblem || state.operationBusy || state.profileBusy || state.detailsBusy || state.loading || state.operation?.unresolved == true || !state.connected) return
        val detail = state.details?.takeIf { PowerRules.eligible(it, action) } ?: return
        val profile = state.selectedProfile ?: return
        val connectionRequest = connections.generation
        mutable.update { it.copy(hostChoiceBusy = true, pendingHostChoice = null) }
        viewModelScope.launch {
            try {
                val hosts = gateway.hostChoices(profile, detail.authority, detail.uuid)
                // Display polling may refresh the same VM meanwhile; only a closed VM or changed connection drops the result.
                if (connectionRequest == connections.generation && mutable.value.selectedProfile == profile &&
                    mutable.value.details?.let { it.uuid == detail.uuid && it.authority == detail.authority } == true) mutable.update {
                    it.copy(pendingHostChoice = HostChoiceRequest(action, detail.authority, detail.uuid,
                        hosts.firstOrNull { host -> host.resident }?.uuid ?: detail.residentHostUuid, hosts, System.currentTimeMillis()))
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutable.update { it.copy(notice = UiNotice.error(friendly(error))) }
            } finally { mutable.update { it.copy(hostChoiceBusy = false) } }
        }
    }
    fun cancelHostChoice() { mutable.update { it.copy(pendingHostChoice = null) } }
    fun chooseHost(host: HostChoice) {
        val state = mutable.value
        val choice = state.pendingHostChoice ?: return
        val profile = state.selectedProfile
        val detail = state.details?.takeIf { it.uuid == choice.vmUuid && it.authority == choice.authority }
        if (profile == null || detail == null || host !in PowerRules.hostCandidates(choice.action, choice.hosts, choice.residentHostUuid)) {
            mutable.update { it.copy(pendingHostChoice = null) }
            return
        }
        // A blocked tap keeps the picker open (its buttons are disabled meanwhile) instead of silently dropping the choice.
        if (state.journalProblem || state.operationBusy || state.profileBusy || state.detailsBusy || state.loading || state.operation?.unresolved == true || !state.connected) return
        mutable.update { it.copy(pendingHostChoice = null) }
        details.load(profile, detail.authority, detail.uuid, choice.action, target = host)
    }
    private fun refreshPermissions(profile: Profile) {
        val origin = profile.origin()
        viewModelScope.launch {
            val permissions = try { gateway.permissions(profile) } catch (error: Exception) {
                if (error is CancellationException) throw error
                SessionPermissions.unavailable("read failed")
            }
            if (mutable.value.selectedProfile == profile) mutable.update { it.copy(permissionView = PermissionView(profile.id, origin, permissions)) }
        }
    }
    // --- end A2 operations ---
    private fun friendlyReview(error: Throwable): String =
        if (error is IllegalArgumentException) error.message ?: "Read the original VM and review the action again." else friendly(error)
    private suspend fun recoverJournalAfterError(error: Throwable) {
        // Explicit preconditions (expired confirmation, changed VM, pending record) explain themselves.
        val reason = (error as? IllegalArgumentException)?.message?.takeIf { it.isNotBlank() && it != "Failed requirement." }
            ?: "Action could not be completed. Review the operation status before trying again."
        val operations = try { journal.load() } catch (failure: Exception) {
            if (failure is CancellationException) throw failure // clearing the view model is not an unreadable journal
            mutable.update { it.copy(journalProblem = true, notice = UiNotice.error("Operation history could not be read. Power controls are locked; no automatic retry will occur.")) }
            return
        }
        publishJournal(operations)
        mutable.update { it.copy(notice = UiNotice.error(reason)) }
    }

    fun mode(value: String) = preference { preferences.setMode(value) }
    fun accent(value: String) = preference { preferences.setAccent(value) }
    fun texture(value: Boolean) = preference { preferences.setTexture(value) }
    fun icon(guest: Guest, value: String) {
        val authority = mutable.value.inventory?.authority ?: return
        if (guest.poolUuid != authority) return
        preference { replaceIcon(guest.identity(authority), value) }
    }
    fun importIcon(guest: Guest, uri: Uri) {
        val authority = mutable.value.inventory?.authority ?: return
        if (guest.poolUuid != authority) return
        preference {
            val token = customIcons.import(uri)
            try { replaceIcon(guest.identity(authority), token) }
            catch (error: Exception) { customIcons.delete(token); throw error }
        }
    }
    /** Imported images are owned by one VM identity, so a replaced image file is removed. */
    private suspend fun replaceIcon(identity: String, token: String) {
        val previous = preferences.setIcon(identity, token)
        if (previous != null && previous != token) customIcons.delete(previous)
    }
    private fun preference(action: suspend () -> Unit) {
        viewModelScope.launch {
            try { action() } catch (error: Exception) {
                if (error is CancellationException) throw error
                // A validation failure ("Choose an image under 3 MB.") explains itself; anything else stays generic.
                mutable.update { old -> old.copy(notice = UiNotice.error(ErrorReasons.preference(error))) }
            }
        }
    }
    fun dismissMessage() { mutable.update { it.copy(notice = null) } }

    // --- B1 console ---
    private var consoleOrigin: String? = null
    /**
     * The VM (uuid, pool) whose details to show again when the console is left. Opened from the details, that is the console's own VM;
     * opened from a fleet row beside an open detail pane, it is the VM that pane showed, so a tap never blanks or replaces the pane.
     * Null when no details were open, so Back lands on the plain fleet.
     */
    private var consoleReturnTo: Pair<String, String>? = null
    /** The one open console. Held here so its session survives rotation and fold/unfold. */
    val console = ConsoleController(viewModelScope,
        opener = { profile, target, encodings ->
            if (target.host) gateway.hostConsole(profile, target.authority, target.vmUuid, encodings)
            else gateway.console(profile, target.authority, target.vmUuid, encodings)
        },
        profileOf = { id -> mutable.value.profiles.firstOrNull { it.id == id } },
        approvePin = { profileId, authority, pin -> connections.approveMemberPin(profileId, authority, pin) })

    init {
        // The console follows the shell: disconnecting, switching profile or pool, or forgetting the profile closes it.
        viewModelScope.launch {
            mutable.map { state -> state.selectedProfile?.let { it.id to it.origin() } to state.inventory?.authority }
                .distinctUntilChanged().collect { (profile, authority) -> closeConsoleIfStale(profile, authority) }
        }
        // Leaving the console destination, by any route, closes the session and returns to the VM it came from.
        viewModelScope.launch {
            var previous: com.meteor.app.ui.Destination.Console? = null
            navigator.stack.collect { stack ->
                val now = stack.current as? com.meteor.app.ui.Destination.Console
                if (now == null) {
                    console.close()
                    if (previous != null && stack.current == com.meteor.app.ui.Destination.Fleet) consoleReturnTo?.let(::reopenDetails)
                }
                previous = now
            }
        }
        // Device finding: returning from VM performance to the fleet reopens the VM it was opened from, as the console does.
        viewModelScope.launch {
            var previous: com.meteor.app.ui.Destination? = null
            navigator.stack.collect { stack ->
                val now = stack.current
                if (previous == com.meteor.app.ui.Destination.Performance && now == com.meteor.app.ui.Destination.Fleet) {
                    val state = mutable.value
                    val target = state.metricTarget?.takeIf { it.kind == MetricKind.VM }
                    if (target != null && state.selectedGuest == null)
                        state.inventory?.guests?.firstOrNull { it.uuid == target.uuid && it.poolUuid == target.authority }?.let(::openDetails)
                }
                previous = now
            }
        }
    }

    /**
     * Opens the full-screen console for a running VM of the connected pool. It needs only the fleet row, not loaded details: the
     * console resolves the VM itself. [fromDetails] says the details were open, so Back returns to them; opened from the fleet row,
     * Back returns to the fleet. A console never journals or sends a power action.
     */
    fun openConsole(guest: Guest, fromDetails: Boolean = false) {
        val state = mutable.value
        val profile = state.selectedProfile ?: return
        if (!state.connected || guest.power != "Running" || guest.poolUuid != state.inventory?.authority) return
        val target = ConsoleTarget(profile.id, guest.poolUuid, guest.uuid, guest.name)
        consoleOrigin = profile.origin()
        consoleReturnTo = ConsoleReturn.target(fromDetails, guest, state.selectedGuest)
        // The details dialog would otherwise stay above the console, and its polling would keep running.
        closeDetails()
        console.open(target)
        navigator.open(com.meteor.app.ui.Destination.Console(target.profileId, target.authority, target.vmUuid, target.vmName))
    }

    /**
     * Opens the full-screen console of an XCP-ng host itself (its control domain's screen). It always opens view-only; the
     * controller asks before input is turned on. A host console never journals or sends a host or VM operation.
     */
    fun openHostConsole(host: Host) {
        val state = mutable.value
        val profile = state.selectedProfile ?: return
        val inventory = state.inventory ?: return
        if (!state.connected || inventory.hosts.none { it.uuid == host.uuid }) return
        val target = ConsoleTarget(profile.id, inventory.authority, host.uuid, host.name, ConsoleKind.HOST)
        consoleOrigin = profile.origin()
        consoleReturnTo = null // leaving a host console never reopens a VM's details
        console.open(target)
        navigator.open(com.meteor.app.ui.Destination.Console(target.profileId, target.authority, target.vmUuid, target.vmName, host = true))
    }

    /** Read-only: whether [host] offers a console Meteor can show, for the host details' button. */
    suspend fun hostConsoleOffer(host: Host): HostConsoleOffer? {
        val state = mutable.value
        val profile = state.selectedProfile ?: return null
        val inventory = state.inventory ?: return null
        if (!state.connected) return null
        return gateway.hostConsoleOffer(profile, inventory.authority, host.uuid)
    }

    private fun closeConsoleIfStale(profile: Pair<String, String>?, authority: String?) {
        val target = console.state.value.target ?: return
        val stale = profile == null || profile.first != target.profileId || consoleOrigin?.let { it != profile.second } == true ||
            (authority != null && authority != target.authority)
        if (!stale) return
        console.close()
        if (navigator.current is com.meteor.app.ui.Destination.Console) navigator.back()
    }

    private fun reopenDetails(vm: Pair<String, String>) {
        mutable.value.inventory?.guests?.firstOrNull { it.uuid == vm.first && it.poolUuid == vm.second }?.let(::openDetails)
    }

    /** From a console that failed: leave it and show the VM's details, where its state and the reason are. */
    fun leaveConsoleForDetails() {
        val target = console.state.value.target ?: return
        // A host console has no VM details to return to; Overview keeps its own host selection.
        consoleReturnTo = if (target.host) null else target.vmUuid to target.authority
        if (navigator.current is com.meteor.app.ui.Destination.Console) navigator.back() else console.close()
    }

    override fun onCleared() {
        networkMonitor.stop()
        console.shutdown()
        gateway.releaseSession() // session reuse: best-effort logout of the shared XAPI session
        super.onCleared()
    }
    // --- end B1 console ---

    // A4: the wording lives in ErrorReasons so it can be tested on the JVM; it adds the raw XAPI code or gateway reason.
    private fun friendly(error: Throwable): String = ErrorReasons.describe(error)
}

