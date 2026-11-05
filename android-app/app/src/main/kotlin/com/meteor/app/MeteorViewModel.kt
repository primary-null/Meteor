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
import java.util.UUID

data class PendingTrust(val profile: Profile, val certificate: ServerCertificate)
data class MeteorState(
    val profiles: List<Profile> = emptyList(), val selectedId: String? = null,
    val inventory: Inventory? = null, val loading: Boolean = false,
    val connected: Boolean = false, val message: String? = null,
    val appearance: Appearance = Appearance(), val pendingTrust: PendingTrust? = null,
    val vaultProblem: Boolean = false, val manualConnectionEvent: Int = 0,
    val selectedGuest: Guest? = null, val details: VmDetails? = null, val detailsBusy: Boolean = false,
    val pendingPower: PowerConfirmation? = null, val operation: PowerOperation? = null,
    val pendingReview: PowerReview? = null,
    val operationBusy: Boolean = false, val journalProblem: Boolean = true,
) {
    val selectedProfile get() = profiles.firstOrNull { it.id == selectedId }
}

class MeteorViewModel @JvmOverloads constructor(
    application: Application,
    private val gateway: MeteorGateway = XapiGateway(),
    private val vault: ProfileVault = ProfileVault(application),
    private val journal: OperationJournal = OperationJournal(application),
) : AndroidViewModel(application) {
    private val preferences = AppearanceStore(application)
    val customIcons = CustomIcons(application)
    private val power = PowerService(gateway, journal)
    private val mutable = MutableStateFlow(MeteorState())
    val state = mutable.asStateFlow()
    private var generation = 0L
    private var initialized = false
    private var detailGeneration = 0L

    init {
        viewModelScope.launch {
            runCatching { preferences.flow.collect { settings -> mutable.update { it.copy(appearance = settings) } } }
                .onFailure { mutable.update { it.copy(message = "Appearance settings could not be read.") } }
        }
        viewModelScope.launch {
            runCatching {
                vault.importDebugBootstrap(application)
                vault.load()
            }.onSuccess { document ->
                mutable.update { it.copy(profiles = document.profiles, selectedId = document.selectedId) }
                loadJournal()
                initialized = true
                document.profiles.firstOrNull { it.id == document.selectedId }?.let { connect(it, manual = false) }
                if (mutable.value.operation?.unresolved == true) checkOperation()
            }.onFailure {
                mutable.update { it.copy(vaultProblem = true,
                    message = "Saved profiles could not be opened. Existing encrypted data was preserved. New connections will be session-only.") }
                // The journal is stored separately. Loading it keeps a pending action visible and
                // reviewable from a session-only connection instead of silently locking power controls.
                loadJournal()
                initialized = true
            }
        }
    }

    private suspend fun loadJournal() {
        runCatching { journal.load() }.onSuccess { operations ->
            mutable.update { it.copy(journalProblem = false, operation = latest(operations)) }
        }.onFailure { mutable.update { it.copy(journalProblem = true,
            message = "Operation history could not be opened. Power controls are locked; existing data was preserved.") } }
    }
    private fun latest(operations: List<PowerOperation>) =
        operations.filter { it.unresolved }.maxByOrNull { it.updatedAt } ?: operations.maxByOrNull { it.updatedAt }

    fun inspect(label: String, input: String, username: String, password: String) {
        if (!initialized || mutable.value.loading || mutable.value.operationBusy) return
        val endpoint = runCatching { Endpoints.normalize(input) }.getOrElse {
            mutable.update { old -> old.copy(message = "Enter an HTTPS server address without a path or credentials.") }; return
        }
        if (username.isBlank() || password.isBlank()) {
            mutable.update { it.copy(message = "Enter a username and password.") }; return
        }
        val existing = mutable.value.profiles.firstOrNull { it.endpoint == endpoint && it.username == username.trim() }
        if (!profileCapacity(existing?.id)) return
        val draft = Profile(existing?.id ?: UUID.randomUUID().toString(), label.trim().ifBlank { "XCP-ng connection" },
            endpoint, username.trim(), password, "")
        val request = ++generation
        closeDetails()
        mutable.update { it.copy(loading = true, message = null, pendingTrust = null, pendingReview = null) }
        viewModelScope.launch {
            runCatching { ServerTrust.inspect(endpoint) }.onSuccess { certificate ->
                if (request == generation) mutable.update {
                    it.copy(loading = false, pendingTrust = PendingTrust(draft.copy(certificateSha256 = certificate.sha256), certificate))
                }
            }.onFailure { error ->
                if (request == generation) mutable.update { it.copy(loading = false, message = friendly(error)) }
            }
        }
    }

    fun cancelTrust() { generation++; mutable.update { it.copy(pendingTrust = null, loading = false) } }
    fun acceptTrust() {
        val pending = mutable.value.pendingTrust ?: return
        mutable.update { it.copy(pendingTrust = null) }
        connect(pending.profile, manual = true)
    }
    fun switchProfile(profile: Profile) { if (!mutable.value.loading && !mutable.value.operationBusy) connect(profile, manual = true) }
    fun refresh() { mutable.value.selectedProfile?.let { if (!mutable.value.loading && !mutable.value.operationBusy && mutable.value.pendingPower == null && mutable.value.pendingReview == null) connect(it, manual = false) } }
    fun refreshIfStale() {
        if (!initialized) return
        val observed = mutable.value.inventory?.observedAtMillis ?: 0
        if (System.currentTimeMillis() - observed >= 30_000) refresh()
    }

    private fun connect(profile: Profile, manual: Boolean) {
        if (!profileCapacity(profile.id)) return
        val request = ++generation
        val sameProfile = mutable.value.selectedProfile == profile
        // A details request belongs to the connection that started it, even when
        // two profiles refer to the same pool and VM.
        detailGeneration++
        mutable.update { it.copy(loading = true, connected = false,
            message = if (it.detailsBusy) "Connection refreshed. Review VM details before choosing a power action." else null,
            selectedGuest = if (sameProfile) it.selectedGuest else null, pendingReview = null,
            details = if (sameProfile) it.details else null, detailsBusy = false, pendingPower = null,
            inventory = if (sameProfile) it.inventory else null) }
        viewModelScope.launch {
            runCatching { gateway.inventory(profile) }.onSuccess { inventory ->
                if (request != generation) return@onSuccess
                val profiles = mutable.value.profiles.filterNot { it.id == profile.id } + profile
                val stored = if (mutable.value.vaultProblem) false else runCatching {
                    vault.save(ProfileDocument(profiles, profile.id)); true
                }.getOrDefault(false)
                if (request != generation) return@onSuccess
                if (mutable.value.selectedGuest?.poolUuid?.let { it != inventory.authority } == true ||
                    mutable.value.details?.authority?.let { it != inventory.authority } == true) closeDetails()
                mutable.update { it.copy(profiles = profiles, selectedId = profile.id, inventory = inventory,
                    connected = true, loading = false,
                    message = if (stored) it.message else "Connected for this session. Encrypted profile storage is unavailable.",
                    manualConnectionEvent = it.manualConnectionEvent + if (manual) 1 else 0) }
                if (mutable.value.details == null) mutable.value.selectedGuest?.let(::openDetails)
            }.onFailure { error ->
                if (request == generation) mutable.update {
                    it.copy(loading = false, connected = false, message = friendly(error))
                }
            }
        }
    }

    private fun profileCapacity(id: String?): Boolean {
        if (mutable.value.profiles.any { it.id == id } || mutable.value.profiles.size < ProfileDocument.MAX_PROFILES) return true
        mutable.update { it.copy(message = "Up to ${ProfileDocument.MAX_PROFILES} connections can be saved. Reconnect an existing profile.") }
        return false
    }

    fun openDetails(guest: Guest) {
        val profile = mutable.value.selectedProfile ?: return
        val authority = mutable.value.inventory?.authority ?: return
        if (guest.poolUuid != authority) return
        mutable.update { it.copy(selectedGuest = guest) }
        if (mutable.value.loading || !mutable.value.connected) return
        loadDetails(profile, authority, guest.uuid)
    }
    private fun loadDetails(profile: Profile, authority: String, uuid: String, action: PowerAction? = null) {
        if (mutable.value.operationBusy) return
        val request = ++detailGeneration
        val connectionRequest = generation
        mutable.update { it.copy(detailsBusy = true, pendingPower = null,
            details = it.details?.takeIf { detail -> detail.uuid == uuid && detail.authority == authority }) }
        viewModelScope.launch {
            runCatching {
                gateway.details(profile, authority, uuid).also {
                    require(it.uuid == uuid && it.authority == authority) { "VM identity changed." }
                }
            }.onSuccess { detail ->
                if (request != detailGeneration) return@onSuccess
                if (connectionRequest != generation || mutable.value.selectedProfile != profile ||
                    mutable.value.inventory?.authority != authority || !mutable.value.connected) {
                    closeDetails()
                    return@onSuccess
                }
                mutable.update {
                    it.copy(detailsBusy = false, details = detail,
                        pendingPower = action?.takeIf { selected -> PowerRules.eligible(detail, selected) }
                            ?.let { selected -> PowerConfirmation(profile.id, profile.origin(), detail, selected) },
                        message = if (action != null && !PowerRules.eligible(detail, action)) "This action is no longer available. VM details were refreshed." else it.message)
                }
            }.onFailure { error ->
                if (request == detailGeneration) mutable.update {
                    it.copy(details = null, detailsBusy = false, pendingPower = null, message = friendly(error))
                }
            }
        }
    }
    fun closeDetails() { detailGeneration++; mutable.update { it.copy(selectedGuest = null, details = null, detailsBusy = false, pendingPower = null) } }
    fun refreshDetails() {
        if (mutable.value.pendingPower != null || mutable.value.detailsBusy || mutable.value.loading || !mutable.value.connected) return
        if (mutable.value.details != null) mutable.value.selectedGuest?.let(::openDetails)
    }
    fun requestPower(action: PowerAction) {
        val state = mutable.value
        if (state.journalProblem || state.operationBusy || state.detailsBusy || state.loading || state.operation?.unresolved == true || !state.connected) return
        val detail = state.details ?: return
        val profile = state.selectedProfile ?: return
        loadDetails(profile, detail.authority, detail.uuid, action)
    }
    fun cancelPower() { mutable.update { it.copy(pendingPower = null) } }
    fun confirmPower() {
        val state = mutable.value
        val confirmation = state.pendingPower ?: return
        val profile = state.selectedProfile ?: return
        if (state.operationBusy || state.journalProblem || state.operation?.unresolved == true) return
        mutable.update { it.copy(pendingPower = null, operationBusy = true) }
        viewModelScope.launch {
            try {
                power.execute(profile, confirmation) { op -> mutable.update { it.copy(operation = op) } }
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
        if (state.operationBusy || state.journalProblem) return
        val profile = state.profiles.firstOrNull { it.id == op.profileId } ?: run {
            mutable.update { it.copy(message = "The original connection for this action is not available. Connect to the same pool, then review the pending action.") }
            return
        }
        if (profile.origin() != op.origin) {
            mutable.update { it.copy(message = "Automatic recovery requires the original trusted connection. Repair the connection, then review the pending action.") }
            return
        }
        mutable.update { it.copy(operationBusy = true, pendingPower = null) }
        viewModelScope.launch {
            try { power.reconcile(profile, op) { next -> mutable.update { it.copy(operation = next) } } }
            catch (error: Exception) { recoverJournalAfterError(error) }
            finally { mutable.update { it.copy(operationBusy = false) }; refresh(); refreshDetails() }
        }
    }
    fun reviewOperation() {
        val state = mutable.value
        val operation = state.operation?.takeIf { it.unresolved } ?: return
        val profile = state.selectedProfile ?: return
        if (!state.connected || state.loading || state.operationBusy || state.journalProblem) return
        mutable.update { it.copy(operationBusy = true, pendingPower = null, pendingReview = null) }
        viewModelScope.launch {
            runCatching { power.review(profile, operation) }
                .onSuccess { review -> mutable.update { it.copy(pendingReview = review) } }
                .onFailure { error -> mutable.update { it.copy(message = friendlyReview(error)) } }
            mutable.update { it.copy(operationBusy = false) }
        }
    }
    fun cancelReview() { mutable.update { it.copy(pendingReview = null) } }
    fun confirmReview(serverHistoryChecked: Boolean) {
        val state = mutable.value
        val review = state.pendingReview ?: return
        val profile = state.selectedProfile ?: return
        if (!serverHistoryChecked || state.operationBusy || state.journalProblem || state.loading || !state.connected) return
        mutable.update { it.copy(pendingReview = null, operationBusy = true) }
        viewModelScope.launch {
            try { power.resolveAfterReview(profile, review, serverHistoryChecked) { op -> mutable.update { it.copy(operation = op) } } }
            catch (error: Exception) { mutable.update { it.copy(message = friendlyReview(error)) } }
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
            mutable.update { it.copy(operation = latest(operations), message = reason) }
        }.onFailure { mutable.update { it.copy(journalProblem = true, message = "Operation history could not be read. Power controls are locked; no automatic retry will occur.") } }
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
            mutable.update { old -> old.copy(message = "That preference could not be saved. Try again.") }
        } }
    }
    fun dismissMessage() { mutable.update { it.copy(message = null) } }

    private fun friendly(error: Throwable): String {
        val causes = generateSequence(error) { it.cause }.take(8).toList()
        return when {
            causes.any { it is CertificateChanged } -> "The server certificate changed. Verify its fingerprint before connecting again."
            error is RpcFailure && error.code == "SESSION_AUTHENTICATION_FAILED" -> "Authentication failed. Check this profile's username and password."
            error is RpcFailure && error.code == "HOST_IS_SLAVE" -> "This address is a pool member. Connect to the pool coordinator."
            error is RpcFailure -> "The server refused this request (${error.code})."
            error is GatewayError && error.reason == "redirect-refused" -> "The server redirected the request. Verify the server address."
            else -> "Could not refresh securely. Check server reachability, certificate validity, and permissions."
        }
    }
}

