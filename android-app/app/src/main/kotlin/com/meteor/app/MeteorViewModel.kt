package com.meteor.app

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.meteor.app.data.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

class MeteorViewModel @JvmOverloads constructor(
    application: Application,
    private val gateway: MeteorGateway = XapiGateway(),
    private val vault: ProfileStorage = ProfileVault(application),
    private val journal: OperationJournal = OperationJournal(application),
    inspector: CertificateInspector = ServerTrust,
) : AndroidViewModel(application) {
    private val preferences = AppearanceStore(application)
    val customIcons = CustomIcons(application)
    private val power = PowerService(gateway, journal)
    private val mutable = MutableStateFlow(MeteorState())
    val state = mutable.asStateFlow()
    private var detailGeneration = 0L
    private val observations = ObservabilityController(viewModelScope, gateway, mutable)

    private val connections = ConnectionController(viewModelScope, gateway, vault, journal, inspector, mutable,
        ::closeDetails, { detailGeneration++ }, ::openDetails, ::friendly, observations::invalidate)

    init {
        viewModelScope.launch {
            runCatching { preferences.flow.collect { settings -> mutable.update { it.copy(appearance = settings) } } }
                .onFailure { mutable.update { it.copy(notice = UiNotice.error("Appearance settings could not be read.")) } }
        }
        viewModelScope.launch {
            runCatching {
                vault.importDebugBootstrap(application)
                vault.load()
            }.onSuccess { document ->
                mutable.update { it.copy(profiles = document.profiles, selectedId = document.selectedId) }
                loadJournal()
                connections.initialized = true
                document.profiles.firstOrNull { it.id == document.selectedId }?.let { connections.connect(it, manual = false) }
                if (mutable.value.operation?.unresolved == true) checkOperation()
            }.onFailure {
                mutable.update { it.copy(vaultProblem = true,
                    notice = UiNotice.error("Saved profiles could not be opened. Existing encrypted data was preserved. New connections will be session-only.")) }
                // The journal is stored separately. Loading it keeps a pending action visible and
                // reviewable from a session-only connection instead of silently locking power controls.
                loadJournal()
                connections.initialized = true
            }
        }
    }

    private suspend fun loadJournal() {
        runCatching { journal.load() }.onSuccess { operations ->
            publishJournal(operations)
        }.onFailure { mutable.update { it.copy(journalProblem = true,
            notice = UiNotice.error("Operation history could not be opened. Power controls are locked; existing data was preserved.")) } }
    }
    private fun publishJournal(operations: List<PowerOperation>, expectedRevision: Long? = null) {
        mutable.update { it.withJournal(operations, expectedRevision) }
    }
    private fun publishOperation(operation: PowerOperation) {
        mutable.update { it.withOperation(operation) }
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
    fun refresh() = connections.refresh()
    fun refreshIfStale() = connections.refreshIfStale()
    fun disconnect() = connections.disconnect()
    fun forgetProfile(id: String) = connections.forgetProfile(id)
    fun openMetrics(target: MetricTarget) { closeDetails(); observations.openMetrics(target) }
    fun refreshMetrics() = observations.refreshMetrics()
    fun refreshActivity() = observations.refreshActivity()

    fun openDetails(guest: Guest) {
        val profile = mutable.value.selectedProfile ?: return
        val authority = mutable.value.inventory?.authority ?: return
        if (guest.poolUuid != authority) return
        mutable.update { it.copy(selectedGuest = guest) }
        if (mutable.value.loading || !mutable.value.connected) return
        loadDetails(profile, authority, guest.uuid)
    }
    private fun loadDetails(profile: Profile, authority: String, uuid: String, action: PowerAction? = null, polling: Boolean = false) {
        if (mutable.value.operationBusy || mutable.value.profileBusy) return
        val request = ++detailGeneration
        val connectionRequest = connections.generation
        val previous = mutable.value.details
        mutable.update { it.copy(detailsBusy = true, pendingPower = null,
            details = it.details?.takeIf { detail -> detail.uuid == uuid && detail.authority == authority }) }
        viewModelScope.launch {
            runCatching {
                (if (polling && action == null && previous != null) gateway.pollDetails(profile, authority, uuid, previous)
                    else gateway.details(profile, authority, uuid)).also {
                    require(it.uuid == uuid && it.authority == authority) { "VM identity changed." }
                }
            }.onSuccess { detail ->
                if (request != detailGeneration) return@onSuccess
                if (connectionRequest != connections.generation || mutable.value.selectedProfile != profile ||
                    mutable.value.inventory?.authority != authority || !mutable.value.connected) {
                    closeDetails()
                    return@onSuccess
                }
                mutable.update {
                    it.copy(detailsBusy = false, details = detail,
                        pendingPower = action?.takeIf { selected -> PowerRules.eligible(detail, selected) }
                            ?.let { selected -> PowerConfirmation(profile.id, profile.origin(), detail, selected) },
                        notice = if (action != null && !PowerRules.eligible(detail, action)) UiNotice.warning("This action is no longer available. VM details were refreshed.") else it.notice)
                }
            }.onFailure { error ->
                if (request == detailGeneration) mutable.update {
                    it.copy(details = null, detailsBusy = false, pendingPower = null, notice = UiNotice.error(friendly(error)))
                }
            }
        }
    }
    fun closeDetails() { detailGeneration++; mutable.update { it.copy(selectedGuest = null, details = null, detailsBusy = false, pendingPower = null) } }
    fun refreshDetails() {
        if (mutable.value.pendingPower != null || mutable.value.detailsBusy || mutable.value.loading || !mutable.value.connected) return
        val current = mutable.value
        val profile = current.selectedProfile ?: return
        val detail = current.details ?: return
        if (current.selectedGuest?.uuid == detail.uuid) loadDetails(profile, detail.authority, detail.uuid, polling = true)
    }
    fun requestPower(action: PowerAction) {
        val state = mutable.value
        if (state.journalProblem || state.operationBusy || state.profileBusy || state.detailsBusy || state.loading || state.operation?.unresolved == true || !state.connected) return
        val detail = state.details ?: return
        val profile = state.selectedProfile ?: return
        loadDetails(profile, detail.authority, detail.uuid, action)
    }
    fun cancelPower() { mutable.update { it.copy(pendingPower = null) } }
    fun confirmPower() {
        val state = mutable.value
        val confirmation = state.pendingPower ?: return
        val profile = state.selectedProfile ?: return
        if (state.operationBusy || state.profileBusy || state.journalProblem || state.operation?.unresolved == true) return
        mutable.update { it.copy(pendingPower = null, operationBusy = true) }
        viewModelScope.launch {
            try {
                power.execute(profile, confirmation, ::publishOperation)
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
            try { power.reconcile(profile, op, ::publishOperation) }
            catch (error: Exception) { recoverJournalAfterError(error) }
            finally { mutable.update { it.copy(operationBusy = false) }; refresh(); refreshDetails() }
        }
    }
    fun reviewOperation() {
        val state = mutable.value
        val operation = state.operation?.takeIf { it.unresolved } ?: return
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
    private fun friendlyReview(error: Throwable): String =
        if (error is IllegalArgumentException) error.message ?: "Read the original VM and review the action again." else friendly(error)
    private suspend fun recoverJournalAfterError(error: Throwable) {
        // Explicit preconditions (expired confirmation, changed VM, pending record) explain themselves.
        val reason = (error as? IllegalArgumentException)?.message?.takeIf { it.isNotBlank() && it != "Failed requirement." }
            ?: "Action could not be completed. Review the operation status before trying again."
        runCatching { journal.load() }.onSuccess { operations ->
            publishJournal(operations)
            mutable.update { it.copy(notice = UiNotice.error(reason)) }
        }.onFailure { mutable.update { it.copy(journalProblem = true, notice = UiNotice.error("Operation history could not be read. Power controls are locked; no automatic retry will occur.")) } }
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
        viewModelScope.launch { runCatching { action() }.onFailure {
            mutable.update { old -> old.copy(notice = UiNotice.error("That preference could not be saved. Try again.")) }
        } }
    }
    fun dismissMessage() { mutable.update { it.copy(notice = null) } }

    private fun friendly(error: Throwable): String {
        val causes = generateSequence(error) { it.cause }.take(8).toList()
        return when {
            causes.any { it is CertificateChanged } -> "The server certificate changed. Verify its fingerprint before connecting again."
            causes.any { it is java.security.cert.CertificateExpiredException } -> "The server certificate has expired. Renew it on the server, then review its fingerprint before connecting."
            causes.any { it is java.security.cert.CertificateNotYetValidException } -> "The server certificate is not valid yet. Check the device clock and server certificate."
            error is RpcFailure && error.code == "SESSION_AUTHENTICATION_FAILED" -> "Authentication failed. Check this profile's username and password."
            error is RpcFailure && error.code == "HOST_IS_SLAVE" -> "This address is a pool member. Connect to the pool coordinator."
            error is RpcFailure -> "The server refused this request (${error.code})."
            error is GatewayError && error.reason == "redirect-refused" -> "The server redirected the request. Verify the server address."
            else -> "Could not refresh securely. Check server reachability, certificate validity, and permissions."
        }
    }
}

