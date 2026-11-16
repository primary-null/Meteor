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
    // --- offline and reconnect ---
    private val network: NetworkMonitor = NetworkMonitor.AlwaysOnline,
    /** Monotonic milliseconds; injected so backoff is tested without waiting. */
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    // --- end offline and reconnect ---
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

    // Stop watching: an operation that is only being observed no longer holds profile management; a send or review still does.
    private fun canManageProfiles() = initialized && !ControlGates.operationExclusive(mutable.value) && !mutable.value.profileBusy
    /** Stop watching: refuses, with a notice, a change to the identity of the running operation's own connection. */
    private fun identityHeld(id: String?): Boolean {
        if (!ControlGates.identityHeld(mutable.value, id)) return false
        mutable.update { it.copy(notice = UiNotice.error(ControlGates.IDENTITY_HELD)) }
        return true
    }
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
        } else if (identityHeld(id)) return // stop watching
        else if (profile.certificateSha256.isNotEmpty()) connect(profile, manual = true)
        else inspect(label, input, username, password, id)
    }

    fun inspect(label: String, input: String, username: String, password: String, id: String? = null) {
        if (!canManageProfiles() || mutable.value.loading || mutable.value.pendingTrust != null) return
        if (identityHeld(id)) return // stop watching
        val draft = draft(label, input, username, password, id) ?: return
        startInspection(draft)
    }

    fun cancelTrust() {
        if (mutable.value.profileBusy || mutable.value.pendingTrust == null) return
        generation++; connectionJob?.cancel(); mutable.update { it.copy(pendingTrust = null, loading = false) }
    }
    fun acceptTrust() {
        if (!canManageProfiles()) return
        val pending = mutable.value.pendingTrust ?: return
        if (identityHeld(pending.profile.id)) return // stop watching
        mutable.update { it.copy(pendingTrust = null) }
        connect(pending.profile, manual = true)
    }
    fun switchProfile(profile: Profile) { if (canManageProfiles() && !mutable.value.loading && mutable.value.pendingTrust == null) connect(profile, manual = true) }
    /**
     * Background and header refresh. While the app is connected to the selected profile with the identity it
     * connected with, this is a same-connection inventory read ([backgroundRefresh]) that leaves the connection,
     * open dialogs and the vault alone. Otherwise (not connected, failed, or the origin changed) it is the full
     * reconnect it has always been.
     */
    // --- offline and reconnect --- [automatic] is the 30 s poll, live events and a returning network. A user action (the
    // Refresh button, a read after an action) is not automatic: it always tries, and it restarts the backoff and lifts any stop.
    fun refresh(automatic: Boolean = false) {
        val current = mutable.value
        val profile = current.selectedProfile ?: return
        if (!canManageProfiles() || current.loading || current.editingProfileId != null || current.pendingTrust != null) return
        if (automatic && !automaticAllowed(current)) return
        // --- end offline and reconnect ---
        // --- background refresh ---
        if (sameConnection(current, profile)) { backgroundRefresh(profile, automatic); return }
        // --- end background refresh ---
        if (current.pendingPower == null && current.pendingReview == null) connect(profile, manual = false, automatic = automatic)
    }
    // A4: the caller passes a longer threshold while live events keep the snapshot honest.
    fun refreshIfStale(thresholdMillis: Long = 30_000) {
        if (!initialized) return
        val observed = mutable.value.inventory?.observedAtMillis ?: 0
        if (System.currentTimeMillis() - observed >= thresholdMillis) refresh(automatic = true)
    }

    fun connect(profile: Profile, manual: Boolean, automatic: Boolean = !manual) {
        if (!profileCapacity(profile.id)) return
        val request = ++generation
        val startedAt = clock() // offline and reconnect: the next automatic attempt is scheduled from here
        if (!automatic) resetRetry()
        connectionJob?.cancel()
        // --- session reuse --- this connection's exact identity owns the shared XAPI session; any other one is ended
        gateway.keepSession(profile)
        // --- end session reuse ---
        cancelBackgroundRefresh() // background refresh: an explicit connection supersedes it
        val sameProfile = mutable.value.selectedProfile == profile
        if (!sameProfile) connectionChanged()
        // A details request belongs to the connection that started it, even when
        // two profiles refer to the same pool and VM.
        invalidateDetails()
        mutable.update { it.copy(loading = true, connected = false,
            notice = if (it.detailsBusy) UiNotice.info("Connection refreshed. Review VM details before choosing a power action.")
                else if (automatic && it.notice != null && it.notice == failureNotice) it.notice else null, // an automatic retry keeps the failure notice instead of clearing and re-posting it
            selectedGuest = if (sameProfile) it.selectedGuest else null, pendingReview = null,
            details = if (sameProfile) it.details else null, detailsBusy = false, detailsPolling = false, pendingPower = null,
            inventory = if (sameProfile) it.inventory else null,
            masterOffer = if (manual) null else it.masterOffer) } // A4: background retries keep an open master offer
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
                            val staleNotice = failureNotice
                            resetRetry() // offline and reconnect: a good connection restarts the backoff and lifts any stop
                            mutable.update { it.copy(profiles = next.profiles, selectedId = profile.id, inventory = inventory,
                                connected = true, loading = false, editingProfileId = null, masterOffer = null, // A4
                                notice = if (stored) it.notice?.takeUnless { notice -> notice == staleNotice } else UiNotice.warning("Connected for this session. Encrypted profile storage is unavailable."),
                                manualConnectionEvent = it.manualConnectionEvent + if (manual) 1 else 0) }
                            connectedProfile = profile // background refresh: the identity later refreshes must match
                        }
                    } finally { mutable.update { it.copy(profileBusy = false) } }
                    if (mutable.value.details == null) mutable.value.selectedGuest?.let(openDetails)
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (request == generation) {
                    val failure = recordFailure(error, automatic, startedAt)
                    mutable.update {
                        it.copy(loading = false, connected = false, notice = failure.notice, retryStop = failure.stop,
                            masterOffer = masterOfferFor(profile, error, manual)) // A4: HOST_IS_SLAVE offers the master, never switches
                    }
                }
            }
        }
    }

    fun disconnect() {
        if (!canManageProfiles()) return
        generation++; connectionJob?.cancel(); cancelBackgroundRefresh(); closeDetails(); connectionChanged(); resetRetry()
        gateway.releaseSession() // session reuse: disconnect logs the shared XAPI session out
        mutable.update { it.copy(selectedId = null, inventory = null, connected = false, loading = false, masterOffer = null,
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
                    gateway.releaseSession(id) // session reuse: a forgotten profile's shared session is logged out
                    val active = mutable.value.selectedId == id
                    if (active) { generation++; closeDetails(); connectionChanged(); resetRetry() }
                    mutable.update { it.copy(profiles = next.profiles, selectedId = next.selectedId,
                        inventory = if (active) null else it.inventory, connected = !active && it.connected,
                        editingProfileId = it.editingProfileId?.takeUnless { editing -> editing == id }, pendingReview = null,
                        masterOffer = it.masterOffer?.takeUnless { offer -> offer.profileId == id }, // A4
                        notice = if (it.vaultProblem) UiNotice.warning("Connection forgotten for this session. Existing encrypted data was preserved.") else UiNotice.info("Connection forgotten from this device. Server credentials were not revoked.")) }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutable.update { it.copy(notice = UiNotice.error((error as? IllegalArgumentException)?.message
                    ?: "Connection could not be forgotten. The existing profile and operation history were preserved.")) }
            } finally { mutable.update { it.copy(profileBusy = false) } }
        }
    }

    // --- vault recovery ---
    /** Runs [block] with the profile-write lock held, so no connection or pin save runs meanwhile. */
    internal suspend fun <T> withProfileWrites(block: suspend () -> T): T = profileWrites.withLock { block() }
    /** Whether Try again / Start over may begin: the profile-change gate, with nothing connecting or awaiting certificate review. */
    internal fun canRecoverVault() = initialized && VaultRecoveryRules.ready(mutable.value)
    // --- end vault recovery ---

    // --- B1 console ---
    /**
     * Stores a member-host pin the user approved for console connections. Serialized with every other profile
     * write; the profile keeps its id, endpoint, credentials and pool-coordinator pin. Throws when the pin
     * cannot be saved, so the caller never treats an unsaved approval as trusted.
     */
    suspend fun approveMemberPin(profileId: String, authority: String, pin: String) {
        profileWrites.withLock {
            withContext(NonCancellable) {
                val current = mutable.value.profiles.firstOrNull { it.id == profileId }
                    ?: throw IllegalArgumentException("This connection is no longer available.")
                val next = ConnectionProfiles.put(document(), MemberPins.approve(current, authority, pin), mutable.value.selectedId)
                if (!mutable.value.vaultProblem) vault.save(next)
                mutable.update { it.copy(profiles = next.profiles,
                    notice = if (it.vaultProblem) UiNotice.warning("Member host trusted for this session. Encrypted profile storage is unavailable.") else it.notice) }
            }
        }
    }
    // --- end B1 console ---

    private fun profileCapacity(id: String?): Boolean {
        if (mutable.value.profiles.any { it.id == id } || mutable.value.profiles.size < ProfileDocument.MAX_PROFILES) return true
        mutable.update { it.copy(notice = UiNotice.error("Up to ${ProfileDocument.MAX_PROFILES} connections can be saved. Reconnect an existing profile.")) }
        return false
    }

    // --- background refresh ---
    /** The profile of the last successful explicit connection. A background refresh needs the same identity. */
    private var connectedProfile: Profile? = null
    private var refreshGeneration = 0L
    private var refreshJob: Job? = null

    private fun sameIdentity(a: Profile, b: Profile) = a.id == b.id && a.origin() == b.origin() && a.password == b.password
    private fun sameConnection(current: MeteorState, profile: Profile) = current.connected && current.inventory != null &&
        connectedProfile?.let { sameIdentity(it, profile) } == true

    private fun cancelBackgroundRefresh() {
        refreshGeneration++; refreshJob?.cancel(); refreshJob = null
        if (mutable.value.refreshing) mutable.update { it.copy(refreshing = false) }
    }

    /**
     * Re-reads the inventory of the connection that is already open. It does not bump [generation], so detail,
     * host-choice and storage/host requests of this connection stay valid; it keeps `connected`, `loading`, open
     * confirmations, the selected VM and its details, and never writes the vault. A response is applied only while
     * no explicit connection, disconnect or newer refresh has started and the selected identity is unchanged.
     * Confirmations stay safe across it because confirming re-reads and re-validates the target before journaling.
     */
    private fun backgroundRefresh(profile: Profile, automatic: Boolean = true) {
        val connection = generation
        val request = ++refreshGeneration
        val startedAt = clock()
        if (!automatic) resetRetry()
        refreshJob?.cancel()
        fun current() = connection == generation && request == refreshGeneration &&
            mutable.value.let { it.connected && it.selectedProfile?.let { selected -> sameIdentity(selected, profile) } == true }
        mutable.update { it.copy(refreshing = true) }
        refreshJob = scope.launch {
            try {
                val inventory = gateway.inventory(profile)
                if (!current()) return@launch
                val before = mutable.value
                val poolChanged = before.inventory?.authority?.let { it != inventory.authority } == true
                // The same safety reactions as an explicit connection when the server now answers for another pool.
                if (poolChanged || before.selectedGuest?.poolUuid?.let { it != inventory.authority } == true ||
                    before.details?.authority?.let { it != inventory.authority } == true) closeDetails()
                if (poolChanged) { invalidateDetails(); connectionChanged() }
                resetRetry()
                mutable.update { it.copy(inventory = inventory, connected = true, masterOffer = null,
                    pendingReview = if (poolChanged) null else it.pendingReview) }
                if (mutable.value.details == null && !mutable.value.detailsBusy) mutable.value.selectedGuest?.let(openDetails)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (!current()) return@launch
                connectedProfile = null
                val failure = recordFailure(error, automatic, startedAt)
                // The next refresh is a full reconnect. A VM confirmation is not kept against a connection that just failed.
                mutable.update { it.copy(connected = false, notice = failure.notice, retryStop = failure.stop, pendingPower = null, pendingHostChoice = null,
                    masterOffer = masterOfferFor(profile, error, manual = false)) } // A4: HOST_IS_SLAVE offers the master, never switches
            } finally {
                if (request == refreshGeneration) mutable.update { it.copy(refreshing = false) }
            }
        }
    }
    // --- end background refresh ---

    // --- offline and reconnect ---
    private val backoff = ReconnectBackoff()
    /** The error notice this controller last posted, so an automatic retry can leave it standing instead of clearing and re-posting it. */
    private var failureNotice: UiNotice? = null
    private var lastNetwork: NetworkStatus? = null

    private class Failure(val notice: UiNotice?, val stop: RetryStop?)

    private fun automaticAllowed(current: MeteorState) = !current.offline && current.retryStop == null && backoff.due(clock())

    /** A user action or a good connection: restart the backoff and lift any stop. */
    private fun resetRetry() {
        backoff.reset(); failureNotice = null
        if (mutable.value.retryStop != null) mutable.update { it.copy(retryStop = null) }
    }

    /**
     * Schedules the next automatic attempt and decides what the user sees. While the phone is offline the failure is
     * the network's, not the server's: no server error, and the backoff is not advanced. A rejected password
     * sets a persistent stop (shown by the shell) rather than a notice the retry loop would re-post.
     */
    private fun recordFailure(error: Throwable, automatic: Boolean, startedAt: Long): Failure {
        if (mutable.value.offline) { failureNotice = null; return Failure(null, null) }
        backoff.failed(startedAt)
        val stop = ConnectionFailures.stopFor(error)
        if (automatic && stop == RetryStop.AUTHENTICATION) { failureNotice = null; return Failure(null, stop) }
        return Failure(UiNotice.error(friendly(error)).also { failureNotice = it }, stop)
    }

    /**
     * Default-network changes. Going offline only records it (the shell shows "No network connection"). Regaining a
     * network, or switching to a different one, makes one ordinary automatic attempt with a fresh backoff; a stop
     * (rejected credentials, changed certificate, pool member) is deliberately NOT lifted by it. While connected this is
     * the same-connection read, never a teardown.
     */
    private fun networkChanged(status: NetworkStatus) {
        val previous = lastNetwork; lastNetwork = status
        mutable.update { if (it.offline == !status.online) it else it.copy(offline = !status.online) }
        if (previous == null || !status.online) return
        if (previous.online && previous.changes == status.changes) return
        backoff.reset()
        refresh(automatic = true)
    }
    // --- end offline and reconnect ---

    // --- A4 alerts/events ---
    private var declinedOffer: String? = null

    /** The certificate review that precedes every credentialed connection. [followedFrom] marks a master-follow review. */
    private fun startInspection(draft: Profile, followedFrom: String? = null) {
        val existing = mutable.value.profiles.firstOrNull { it.id == draft.id }
        val request = ++generation
        connectionJob?.cancel()
        closeDetails()
        mutable.update { it.copy(loading = true, notice = null, pendingTrust = null, pendingReview = null) }
        connectionJob = scope.launch {
            runCatching { inspector.inspect(draft.endpoint) }.onSuccess { certificate ->
                if (request == generation) mutable.update {
                    it.copy(loading = false, pendingTrust = PendingTrust(draft.copy(certificateSha256 = certificate.sha256), certificate, existing, followedFrom))
                }
            }.onFailure { error ->
                if (request == generation) mutable.update { it.copy(loading = false, notice = UiNotice.error(friendly(error))) }
            }
        }
    }

    /** A background retry that fails again must not re-open an offer the user already declined or started. */
    private fun masterOfferFor(profile: Profile, error: Throwable, manual: Boolean): MasterOffer? {
        val offer = MasterFollow.offer(profile, error) ?: return null
        if (manual) declinedOffer = null
        return offer.takeUnless { declinedOffer == it.key }
    }

    /**
     * The user chose "Connect to master". Nothing is sent to the master yet: the existing certificate review runs
     * first (no credentials), and the profile (same id, label, username and saved password) is rewritten only after
     * the user trusts the master's certificate and the login succeeds.
     */
    fun followMaster() {
        if (!canManageProfiles() || mutable.value.loading || mutable.value.pendingTrust != null) return
        val offer = mutable.value.masterOffer ?: return
        if (identityHeld(offer.profileId)) return // stop watching
        declinedOffer = offer.key
        if (mutable.value.profiles.any { it.id != offer.profileId && it.endpoint == offer.masterEndpoint && it.username == offer.draft.username }) {
            mutable.update { it.copy(masterOffer = null, notice = UiNotice.error(
                "A saved connection already uses the pool master (${offer.masterAddress}) with this username. Connect with that connection instead.")) }
            return
        }
        mutable.update { it.copy(masterOffer = null) }
        startInspection(offer.draft, followedFrom = offer.memberEndpoint)
    }

    fun declineMaster() {
        val offer = mutable.value.masterOffer ?: return
        declinedOffer = offer.key
        mutable.update { it.copy(masterOffer = null) }
    }
    // --- end A4 ---

    // --- offline and reconnect --- last member, so every property above exists before the first status is handled.
    init { scope.launch { network.status.collect(::networkChanged) } }
    // --- end offline and reconnect ---
}
