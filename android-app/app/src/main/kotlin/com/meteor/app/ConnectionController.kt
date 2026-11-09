package com.meteor.app

import com.meteor.app.data.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Owns connection generations, trust review and serialized profile commits. No Android UI dependency. */
internal class ConnectionController(
    private val scope: CoroutineScope,
    private val gateway: MeteorGateway,
    private val vault: ProfileStorage,
    private val journal: OperationStore,
    private val inspector: CertificateInspector,
    private val mutable: MutableStateFlow<MeteorState>,
    private val closeDetails: () -> Unit,
    private val invalidateDetails: () -> Unit,
    private val openDetails: (Guest) -> Unit,
    private val friendly: (Throwable) -> String,
    private val connectionChanged: () -> Unit = {},
) {
    var generation = 0L
        private set
    var initialized = false
    private var connectionJob: Job? = null
    private val profileWrites = Mutex()

    fun editProfile(id: String) {
        if (!canManageProfiles() || mutable.value.loading || mutable.value.profiles.none { it.id == id }) return
        closeDetails()
        mutable.update { it.copy(editingProfileId = id, profileEditSession = it.profileEditSession + 1, pendingTrust = null, pendingReview = null) }
    }

    fun cancelEdit() {
        if (!canManageProfiles()) return
        generation++; connectionJob?.cancel()
        mutable.update { it.copy(editingProfileId = null, pendingTrust = null, loading = false) }
    }

    private fun canManageProfiles() = initialized && !mutable.value.operationBusy && !mutable.value.profileBusy
    private fun document() = ProfileDocument(mutable.value.profiles, mutable.value.selectedId)

    private fun draft(label: String, input: String, username: String, password: String, id: String?): Profile? {
        if (runCatching { Endpoints.normalize(input) }.isFailure) {
            mutable.update { it.copy(notice = UiNotice.error("Enter an HTTPS server address without a path or credentials.")) }
            return null
        }
        return runCatching { ConnectionProfiles.draft(document(), id, label, input, username, password) }
            .onFailure { error -> mutable.update { it.copy(notice = UiNotice.error(error.message ?: "Connection could not be prepared.")) } }.getOrNull()
    }

    fun saveConnection(label: String, input: String, username: String, password: String, id: String) {
        if (!canManageProfiles() || mutable.value.loading || mutable.value.pendingTrust != null) return
        val profile = draft(label, input, username, password, id) ?: return
        val original = mutable.value.profiles.single { it.id == id }
        if (profile.copy(label = original.label) == original) {
            // A rename works offline and does not replace a trusted identity or send credentials.
            mutable.update { it.copy(profileBusy = true) }
            scope.launch {
                try {
                    profileWrites.withLock {
                        val next = ConnectionProfiles.put(document(), profile, mutable.value.selectedId)
                        if (!mutable.value.vaultProblem) vault.save(next)
                        mutable.update { it.copy(profiles = next.profiles, editingProfileId = null,
                            notice = if (it.vaultProblem) UiNotice.warning("Connection renamed for this session. Existing encrypted data was preserved.") else UiNotice.info("Connection renamed.")) }
                    }
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    mutable.update { it.copy(notice = UiNotice.error("Connection could not be saved. The existing profile was preserved.")) }
                } finally { mutable.update { it.copy(profileBusy = false) } }
            }
        } else if (profile.certificateSha256.isNotEmpty()) connect(profile, manual = true)
        else inspect(label, input, username, password, id)
    }

    fun inspect(label: String, input: String, username: String, password: String, id: String? = null) {
        if (!canManageProfiles() || mutable.value.loading || mutable.value.pendingTrust != null) return
        val draft = draft(label, input, username, password, id) ?: return
        val existing = mutable.value.profiles.firstOrNull { it.id == draft.id }
        val request = ++generation
        connectionJob?.cancel()
        closeDetails()
        mutable.update { it.copy(loading = true, notice = null, pendingTrust = null, pendingReview = null) }
        connectionJob = scope.launch {
            runCatching { inspector.inspect(draft.endpoint) }.onSuccess { certificate ->
                if (request == generation) mutable.update {
                    it.copy(loading = false, pendingTrust = PendingTrust(draft.copy(certificateSha256 = certificate.sha256), certificate, existing))
                }
            }.onFailure { error ->
                if (request == generation) mutable.update { it.copy(loading = false, notice = UiNotice.error(friendly(error))) }
            }
        }
    }

    fun cancelTrust() {
        if (mutable.value.profileBusy || mutable.value.pendingTrust == null) return
        generation++; connectionJob?.cancel(); mutable.update { it.copy(pendingTrust = null, loading = false) }
    }
    fun acceptTrust() {
        if (!canManageProfiles()) return
        val pending = mutable.value.pendingTrust ?: return
        mutable.update { it.copy(pendingTrust = null) }
        connect(pending.profile, manual = true)
    }
    fun switchProfile(profile: Profile) { if (canManageProfiles() && !mutable.value.loading && mutable.value.pendingTrust == null) connect(profile, manual = true) }
    fun refresh() { mutable.value.selectedProfile?.let { if (canManageProfiles() && !mutable.value.loading && mutable.value.editingProfileId == null && mutable.value.pendingTrust == null && mutable.value.pendingPower == null && mutable.value.pendingReview == null) connect(it, manual = false) } }
    fun refreshIfStale() {
        if (!initialized) return
        val observed = mutable.value.inventory?.observedAtMillis ?: 0
        if (System.currentTimeMillis() - observed >= 30_000) refresh()
    }

    fun connect(profile: Profile, manual: Boolean) {
        if (!profileCapacity(profile.id)) return
        val request = ++generation
        connectionJob?.cancel()
        val sameProfile = mutable.value.selectedProfile == profile
        if (!sameProfile) connectionChanged()
        // A details request belongs to the connection that started it, even when
        // two profiles refer to the same pool and VM.
        invalidateDetails()
        mutable.update { it.copy(loading = true, connected = false,
            notice = if (it.detailsBusy) UiNotice.info("Connection refreshed. Review VM details before choosing a power action.") else null,
            selectedGuest = if (sameProfile) it.selectedGuest else null, pendingReview = null,
            details = if (sameProfile) it.details else null, detailsBusy = false, pendingPower = null,
            inventory = if (sameProfile) it.inventory else null) }
        connectionJob = scope.launch {
            try {
                val inventory = gateway.inventory(profile)
                profileWrites.withLock {
                    if (request != generation) return@withLock
                    // Once the atomic write starts, publish the same profile before permitting
                    // Cancel or Disconnect. Cancellation cannot split durable and visible state.
                    mutable.update { it.copy(profileBusy = true) }
                    try {
                        withContext(NonCancellable) {
                            val next = ConnectionProfiles.put(document(), profile)
                            val stored = if (mutable.value.vaultProblem) false else try { vault.save(next); true } catch (error: Exception) {
                                if (error is CancellationException) throw error
                                false
                            }
                            if (mutable.value.selectedGuest?.poolUuid?.let { it != inventory.authority } == true ||
                                mutable.value.details?.authority?.let { it != inventory.authority } == true) closeDetails()
                            if (mutable.value.inventory?.authority?.let { it != inventory.authority } == true) connectionChanged()
                            mutable.update { it.copy(profiles = next.profiles, selectedId = profile.id, inventory = inventory,
                                connected = true, loading = false, editingProfileId = null,
                                notice = if (stored) it.notice else UiNotice.warning("Connected for this session. Encrypted profile storage is unavailable."),
                                manualConnectionEvent = it.manualConnectionEvent + if (manual) 1 else 0) }
                        }
                    } finally { mutable.update { it.copy(profileBusy = false) } }
                    if (mutable.value.details == null) mutable.value.selectedGuest?.let(openDetails)
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (request == generation) mutable.update {
                    it.copy(loading = false, connected = false, notice = UiNotice.error(friendly(error)))
                }
            }
        }
    }

    fun disconnect() {
        if (!canManageProfiles()) return
        generation++; connectionJob?.cancel(); closeDetails(); connectionChanged()
        mutable.update { it.copy(selectedId = null, inventory = null, connected = false, loading = false,
            pendingTrust = null, pendingReview = null, editingProfileId = null, profileBusy = true,
            notice = if (it.vaultProblem) UiNotice.warning("Disconnected for this session. Encrypted storage is unavailable, so the preference cannot be saved.") else null) }
        scope.launch {
            try {
                // An older connection save must finish before the disconnected selection is written.
                profileWrites.withLock { if (!mutable.value.vaultProblem) vault.save(document()) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutable.update { it.copy(notice = UiNotice.error("Disconnected for this session. The preference could not be saved; Meteor may reconnect when reopened.")) }
            } finally { mutable.update { it.copy(profileBusy = false) } }
        }
    }

    fun forgetProfile(id: String) {
        if (!canManageProfiles() || mutable.value.loading || mutable.value.pendingTrust != null) return
        if (mutable.value.journalProblem) {
            mutable.update { it.copy(notice = UiNotice.error("Operation history must be readable before forgetting a connection.")) }; return
        }
        mutable.update { it.copy(profileBusy = true) }
        scope.launch {
            try {
                profileWrites.withLock {
                    val operations = try { journal.load() } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        mutable.update { it.copy(journalProblem = true) }; throw error
                    }
                    val next = ConnectionProfiles.forget(document(), id, operations)
                    if (!mutable.value.vaultProblem) vault.save(next)
                    val active = mutable.value.selectedId == id
                    if (active) { generation++; closeDetails(); connectionChanged() }
                    mutable.update { it.copy(profiles = next.profiles, selectedId = next.selectedId,
                        inventory = if (active) null else it.inventory, connected = !active && it.connected,
                        editingProfileId = it.editingProfileId?.takeUnless { editing -> editing == id }, pendingReview = null,
                        notice = if (it.vaultProblem) UiNotice.warning("Connection forgotten for this session. Existing encrypted data was preserved.") else UiNotice.info("Connection forgotten from this device. Server credentials were not revoked.")) }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutable.update { it.copy(notice = UiNotice.error((error as? IllegalArgumentException)?.message
                    ?: "Connection could not be forgotten. The existing profile and operation history were preserved.")) }
            } finally { mutable.update { it.copy(profileBusy = false) } }
        }
    }

    private fun profileCapacity(id: String?): Boolean {
        if (mutable.value.profiles.any { it.id == id } || mutable.value.profiles.size < ProfileDocument.MAX_PROFILES) return true
        mutable.update { it.copy(notice = UiNotice.error("Up to ${ProfileDocument.MAX_PROFILES} connections can be saved. Reconnect an existing profile.")) }
        return false
    }

}
