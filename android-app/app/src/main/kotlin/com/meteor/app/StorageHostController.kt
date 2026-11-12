package com.meteor.app

import com.meteor.app.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * B2 storage/host view state. Every read is bound to the connection identity it was made with;
 * [bind] drops it when the selected connection changes. Confirmations are re-checked by the
 * service against a fresh read, so this state is display-only.
 */
data class StorageHostView(
    val profileId: String? = null, val origin: String? = null,
    val vm: VmTarget? = null, val vmBusy: Boolean = false, val vmProblem: String? = null,
    val host: HostState? = null, val hostBusy: Boolean = false, val hostProblem: String? = null,
    val pbds: List<PbdState>? = null, val pbdAuthority: String? = null, val pbdBusy: Boolean = false, val pbdProblem: String? = null,
    val isoRequest: IsoChoiceRequest? = null, val isoBusy: Boolean = false,
    val pending: TargetConfirmation? = null, val requestBusy: Boolean = false,
    val review: TargetReview? = null,
) {
    fun matches(profile: Profile?) = profile != null && profileId == profile.id && origin == profile.origin()
    fun bind(profile: Profile): StorageHostView = if (matches(profile)) this else StorageHostView(profileId = profile.id, origin = profile.origin())
}

/** The B2 view of the selected connection, or an empty view when it was read with another one. */
val MeteorState.storageView: StorageHostView get() = storageHost.takeIf { it.matches(selectedProfile) } ?: StorageHostView()

/**
 * B2 storage/host requests. Reads are fenced by connection generation, profile and pool; writes go
 * only through [PowerService.executeTarget] and share the journal, mutex and pending-action lock.
 */
internal class StorageHostController(
    private val scope: CoroutineScope, private val gateway: MeteorGateway, private val power: PowerService,
    private val state: MutableStateFlow<MeteorState>, private val generation: () -> Long,
    private val friendly: (Throwable) -> String, private val publish: suspend (PowerOperation) -> Unit,
    private val recover: suspend (Throwable) -> Unit, private val refresh: () -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var vmRequest = 0L
    private var hostRequest = 0L
    private var pbdRequest = 0L

    private fun view(change: (StorageHostView) -> StorageHostView) = state.update { it.copy(storageHost = change(it.storageHost)) }
    private fun readable(s: MeteorState) = s.connected && !s.loading && !s.profileBusy && s.pendingTrust == null
    /** Same conditions as the VM power controls, plus no storage/host request in flight. */
    fun canWrite(s: MeteorState) = readable(s) && !s.journalProblem && !s.operationBusy && s.operation?.unresolved != true &&
        !s.storageView.requestBusy && !s.storageView.isoBusy && !s.detailsBusy
    private fun current(profile: Profile, authority: String, connection: Long) = state.value.let {
        it.connected && it.selectedProfile == profile && it.inventory?.authority == authority && generation() == connection
    }
    private fun notice(text: UiNotice) = state.update { it.copy(notice = text) }

    // Reads

    fun loadVm(authority: String, uuid: String) {
        val s = state.value
        val profile = s.selectedProfile ?: return
        if (!readable(s) || s.inventory?.authority != authority) return
        val request = ++vmRequest
        val connection = generation()
        view { it.bind(profile).let { v -> v.copy(vmBusy = true, vmProblem = null, vm = v.vm?.takeIf { vm -> vm.uuid == uuid && vm.authority == authority }) } }
        scope.launch {
            try {
                val read = gateway.targetState(profile, authority, TargetKind.VM, uuid)
                if (request != vmRequest) return@launch
                if (!current(profile, authority, connection)) { view { it.copy(vmBusy = false, vm = null) }; return@launch }
                view { it.copy(vmBusy = false, vm = read as? VmTarget, vmProblem = if (read is MissingTarget) "This VM no longer exists." else null) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (request == vmRequest) view { it.copy(vmBusy = false, vmProblem = friendly(error)) }
            }
        }
    }
    /** Leaving a VM drops its storage view and any VM-scoped confirmation or ISO choice. */
    fun closeVm(uuid: String) {
        vmRequest++
        view { v -> v.copy(vm = v.vm?.takeUnless { it.uuid == uuid }, vmBusy = false, vmProblem = null,
            isoRequest = v.isoRequest?.takeUnless { it.drive.vmUuid == uuid },
            pending = v.pending?.takeUnless { it.state.vmUuid == uuid }) }
    }

    fun loadHost(authority: String, uuid: String) {
        val s = state.value
        val profile = s.selectedProfile ?: return
        if (!readable(s) || s.inventory?.authority != authority) return
        val request = ++hostRequest
        val connection = generation()
        view { it.bind(profile).let { v -> v.copy(hostBusy = true, hostProblem = null, host = v.host?.takeIf { h -> h.uuid == uuid && h.authority == authority }) } }
        scope.launch {
            try {
                val read = gateway.targetState(profile, authority, TargetKind.HOST, uuid)
                if (request != hostRequest) return@launch
                if (!current(profile, authority, connection)) { view { it.copy(hostBusy = false, host = null) }; return@launch }
                view { it.copy(hostBusy = false, host = read as? HostState, hostProblem = if (read is MissingTarget) "This host no longer exists." else null) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (request == hostRequest) view { it.copy(hostBusy = false, hostProblem = friendly(error)) }
            }
        }
    }
    fun closeHost(uuid: String) {
        hostRequest++
        view { v -> v.copy(host = v.host?.takeUnless { it.uuid == uuid }, hostBusy = false, hostProblem = null,
            pending = v.pending?.takeUnless { it.state.kind == TargetKind.HOST && it.state.uuid == uuid }) }
    }

    fun loadPbds(authority: String) {
        val s = state.value
        val profile = s.selectedProfile ?: return
        if (!readable(s) || s.inventory?.authority != authority) return
        val request = ++pbdRequest
        val connection = generation()
        view { it.bind(profile).copy(pbdBusy = true, pbdProblem = null) }
        scope.launch {
            try {
                val read = gateway.poolPbds(profile, authority)
                if (request != pbdRequest) return@launch
                if (!current(profile, authority, connection)) { view { it.copy(pbdBusy = false, pbds = null) }; return@launch }
                view { it.copy(pbdBusy = false, pbds = read, pbdAuthority = authority) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (request == pbdRequest) view { it.copy(pbdBusy = false, pbdProblem = friendly(error)) }
            }
        }
    }
    fun closePbds() {
        pbdRequest++
        view { v -> v.copy(pbds = null, pbdAuthority = null, pbdBusy = false, pbdProblem = null,
            pending = v.pending?.takeUnless { it.state.kind == TargetKind.PBD }) }
    }

    // Confirmations

    /** Reads the target again and opens a confirmation only when it is still eligible. */
    fun request(action: TargetAction, authority: String, kind: TargetKind, uuid: String, argument: IsoChoice? = null) {
        val s = state.value
        val profile = s.selectedProfile ?: return
        if (!canWrite(s) || s.inventory?.authority != authority || kind != action.targetKind) return
        val connection = generation()
        view { it.bind(profile).copy(requestBusy = true, pending = null) }
        scope.launch {
            try {
                val fresh = gateway.targetState(profile, authority, kind, uuid)
                if (!current(profile, authority, connection)) return@launch
                reflect(fresh)
                val reason = TargetRules.unavailableReason(fresh, action)
                if (reason != null) { notice(UiNotice.warning("${action.title} is no longer available: $reason")); return@launch }
                view { it.copy(pending = TargetConfirmation(profile.id, profile.origin(), action, fresh, argument = argument,
                    snapshotName = if (action == TargetAction.SNAPSHOT_TAKE) TargetRules.defaultSnapshotName(fresh.name, clock()) else null,
                    plan = if (action == TargetAction.SNAPSHOT_DELETE) TargetRules.deletePlan(fresh as SnapshotTarget) else emptyList())) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                notice(UiNotice.error(friendly(error)))
            } finally { view { it.copy(requestBusy = false) } }
        }
    }
    /** Shows a fresh read wherever the same object is displayed. */
    private fun reflect(fresh: TargetState) = view { v ->
        when (fresh) {
            is VmTarget -> if (v.vm?.uuid == fresh.uuid) v.copy(vm = fresh) else v
            is HostState -> if (v.host?.uuid == fresh.uuid) v.copy(host = fresh) else v
            is PbdState -> v.copy(pbds = v.pbds?.map { if (it.uuid == fresh.uuid) fresh else it })
            is CdDrive -> v.copy(vm = v.vm?.let { vm -> vm.copy(cdDrives = vm.cdDrives.map { if (it.uuid == fresh.uuid) fresh else it }) })
            else -> v
        }
    }
    fun requestIso(drive: CdDrive) {
        val s = state.value
        val profile = s.selectedProfile ?: return
        if (!canWrite(s) || s.inventory?.authority != drive.authority) return
        val connection = generation()
        view { it.bind(profile).copy(isoBusy = true, isoRequest = null) }
        scope.launch {
            try {
                val fresh = gateway.targetState(profile, drive.authority, TargetKind.VBD, drive.uuid)
                val catalog = gateway.isoCatalog(profile, drive.authority)
                if (!current(profile, drive.authority, connection)) return@launch
                reflect(fresh)
                val reason = TargetRules.unavailableReason(fresh, TargetAction.CD_INSERT)
                if (reason != null || fresh !is CdDrive) notice(UiNotice.warning("Insert ISO is no longer available: ${reason ?: "the drive changed"}"))
                else view { it.copy(isoRequest = IsoChoiceRequest(fresh, catalog, clock())) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                notice(UiNotice.error(friendly(error)))
            } finally { view { it.copy(isoBusy = false) } }
        }
    }
    fun chooseIso(iso: IsoChoice) {
        val request = state.value.storageView.isoRequest ?: return
        view { it.copy(isoRequest = null) }
        if (iso !in request.catalog.isos) return
        this.request(TargetAction.CD_INSERT, request.drive.authority, TargetKind.VBD, request.drive.uuid, argument = iso)
    }
    fun cancelIso() = view { it.copy(isoRequest = null) }
    fun cancel() = view { it.copy(pending = null) }

    fun confirm(typedName: String?, snapshotName: String?) {
        val s = state.value
        val pending = s.storageView.pending ?: return
        val confirmation = pending.copy(typedName = typedName, snapshotName = snapshotName ?: pending.snapshotName)
        val profile = s.selectedProfile ?: return
        if (!canWrite(s)) return
        // A typed confirmation that does not match, or an invalid snapshot name, never reaches the service.
        if (!TargetRules.typedNameMatches(confirmation)) return
        if (confirmation.action == TargetAction.SNAPSHOT_TAKE && TargetRules.snapshotNameProblem(confirmation.snapshotName) != null) return
        state.update { it.copy(operationBusy = true, storageHost = it.storageHost.copy(pending = null)) }
        scope.launch {
            try { power.executeTarget(profile, confirmation, publish) }
            catch (error: Exception) {
                if (error is CancellationException) throw error
                recover(error)
            } finally {
                state.update { it.copy(operationBusy = false) }
                refresh(); reloadOpenViews()
            }
        }
    }
    fun reloadOpenViews() {
        val v = state.value.storageView
        v.vm?.let { loadVm(it.authority, it.uuid) }
        v.host?.let { loadHost(it.authority, it.uuid) }
        v.pbdAuthority?.let { loadPbds(it) }
    }

    // Manual review of an uncertain storage/host record

    fun review(operation: PowerOperation) {
        val s = state.value
        val profile = s.selectedProfile ?: return
        if (!s.connected || s.loading || s.operationBusy || s.profileBusy || s.journalProblem) return
        state.update { it.copy(operationBusy = true, pendingPower = null, pendingReview = null, storageHost = it.storageHost.bind(profile).copy(review = null)) }
        scope.launch {
            try {
                val review = power.reviewTarget(profile, operation)
                view { it.copy(review = review) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                notice(UiNotice.error(reviewText(error)))
            } finally { state.update { it.copy(operationBusy = false) } }
        }
    }
    fun cancelReview() = view { it.copy(review = null) }
    fun confirmReview(serverHistoryChecked: Boolean) {
        val s = state.value
        val review = s.storageView.review ?: return
        val profile = s.selectedProfile ?: return
        if (!serverHistoryChecked || s.operationBusy || s.profileBusy || s.journalProblem || s.loading || !s.connected) return
        state.update { it.copy(operationBusy = true, storageHost = it.storageHost.copy(review = null)) }
        scope.launch {
            try { power.resolveTargetAfterReview(profile, review, serverHistoryChecked, publish) }
            catch (error: Exception) {
                if (error is CancellationException) throw error
                notice(UiNotice.error(reviewText(error)))
            } finally { state.update { it.copy(operationBusy = false) }; reloadOpenViews() }
        }
    }
    private fun reviewText(error: Throwable) =
        if (error is IllegalArgumentException) error.message ?: "Read the original object and review the action again." else friendly(error)
}
