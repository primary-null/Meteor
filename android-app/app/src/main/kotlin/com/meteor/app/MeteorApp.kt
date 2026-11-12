package com.meteor.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.meteor.app.data.*
import com.meteor.app.ui.*
import kotlinx.coroutines.delay

/**
 * App shell: theme, adaptive navigation (bottom bar on compact width, rail on medium/expanded),
 * the typed back stack with system Back, visible-only polling, and app-wide dialogs.
 * Navigation model and extension points are documented in `ui/Navigation.kt`.
 */
@Composable
fun MeteorApp(model: MeteorViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    val navigator = model.navigator
    // The view model keeps the stack across configuration changes. Destination keys (never
    // credentials or drafts) are also saved so the stack comes back after process death.
    var savedStack by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    remember(navigator) { navigator.restore(savedStack); navigator }
    val stack by navigator.stack.collectAsStateWithLifecycle()
    // ArrayList keeps the saved value bundle-compatible.
    LaunchedEffect(stack) { savedStack = ArrayList(stack.keys) }
    val current = stack.current
    val navigate: (Navigator.() -> Unit) -> Unit = { action ->
        navigator.action()
        // VM details poll only while visible, so leaving Fleet closes the selected VM.
        if (navigator.current != Destination.Fleet && model.state.value.selectedGuest != null) model.closeDetails()
    }
    val vmPerformance: (Guest) -> Unit = { guest ->
        model.openMetrics(MetricTarget(guest.poolUuid, guest.uuid, MetricKind.VM, guest.name)); navigate { open(Destination.Performance) }
    }
    MeteorTheme(state.appearance) { dark ->
        val colors = MaterialTheme.colorScheme
        var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
        LaunchedEffect(state.inventory?.observedAtMillis, state.metricSamples.lastOrNull()?.observedAt, state.poolActivity?.observedAt,
            state.operation?.updatedAt) { // an operation closing between ticks must not look future-dated to the card rule
            // A receipt can arrive between five-second ticks; compare it with the current clock immediately.
            now = System.currentTimeMillis()
        }
        val owner = LocalLifecycleOwner.current
        LaunchedEffect(owner, current) {
            owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                var elapsed = 0
                while (true) {
                    now = System.currentTimeMillis()
                    if (elapsed % 30 == 0) model.refreshIfStale()
                    if (elapsed % 5 == 0) model.refreshDetails()
                    if (current == Destination.Performance) model.refreshMetrics()
                    if (current == Destination.Performance) model.refreshHistoryIfDue() // A3 observability: history polls only while visible
                    if (current == Destination.PoolActivity && elapsed % 15 == 0) model.refreshActivity()
                    // A4: events normally drive alert refreshes; 60 s fallback while they are healthy, 15 s otherwise.
                    if (current == Destination.Alerts && elapsed % (if (state.eventsHealthy) 60 else 15) == 0) model.refreshAlerts()
                    delay(5000); elapsed += 5
                }
            }
        }
        LaunchedEffect(state.manualConnectionEvent) {
            navigate { onManualConnection(state.manualConnectionEvent) }
            if (model.takeAlertsAfterConnect()) navigate { open(Destination.Alerts) } // B3: switch chosen from a notification
        }
        // Back returns to the previous destination; at the Overview root it leaves the app.
        BackHandler(enabled = stack.canGoBack) { navigate { back() } }
        // Hoisted so scroll positions survive fold/unfold and switching destinations.
        val overviewList = rememberLazyListState()
        val fleetList = rememberLazyListState()
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val width = WidthClass.of(maxWidth.value)
            // Landscape phones and the folded cover display in landscape have little height to spare.
            val shortHeight = maxHeight < 480.dp
            // A3 observability: history/detail state for the read-only depth composables.
            val depthUi = rememberDepthUi(state, model) { navigate { open(Destination.Networks) } }
            val poolsUi = rememberPoolsUi(state, model) // B3 background/summary
            CompositionLocalProvider(LocalWidthClass provides width, LocalDepthUi provides depthUi,
                // B2 storage/host: host and storage slots reach the view model through LocalMeteorModel.
                LocalMeteorModel provides model, LocalPoolsUi provides poolsUi) {
                Scaffold(containerColor = colors.background, bottomBar = {
                    if (!width.usesRail) NavigationBar(containerColor = colors.surface) {
                        NAV_ITEMS.forEach { item ->
                            NavigationBarItem(selected = stack.selectedItem == item.destination, onClick = { navigate { select(item.destination) } },
                                icon = { NavIcon(item, state) }, label = { Text(item.label) })
                        }
                    }
                }) { insets ->
                    Box(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets).imePadding()) {
                        if (state.appearance.texture) OrbitalTexture(dark)
                        Row(Modifier.fillMaxSize()) {
                            if (width.usesRail) NavigationRail(containerColor = colors.surface, windowInsets = WindowInsets(0)) {
                                Spacer(Modifier.height(12.dp))
                                NAV_ITEMS.forEach { item ->
                                    NavigationRailItem(selected = stack.selectedItem == item.destination, onClick = { navigate { select(item.destination) } },
                                        icon = { NavIcon(item, state) }, label = { Text(item.label) })
                                }
                            }
                            Column(Modifier.weight(1f).fillMaxHeight()) {
                                Header(state, model, compact = shortHeight)
                                if (!shortHeight) AmbientMessage(canRotate = current == Destination.Overview &&
                                    !state.operationBusy && state.operation?.unresolved != true && state.message == null &&
                                    state.pendingTrust == null && state.editingProfileId == null && state.selectedGuest == null &&
                                    state.pendingPower == null && state.pendingReview == null && state.pendingHostChoice == null,
                                    modifier = Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = 2.dp, bottom = 14.dp))
                                HorizontalDivider(color = colors.outlineVariant.copy(alpha = .35f))
                                if (state.loading || state.profileBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                                state.notice?.let { NoticeBanner(it, model::dismissMessage) }
                                // An unresolved operation is always shown; a closed one only briefly (OperationHistory.cardVisible).
                                // `now` is a five-second tick; a record newer than it is judged against the real clock for this frame.
                                state.operation?.takeIf { OperationHistory.cardVisible(it, if (it.updatedAt > now) System.currentTimeMillis() else now) }?.let { operation ->
                                    OperationCard(operation, state, model) { navigate { open(Destination.History) }; model.reloadHistory() }
                                }
                                DestinationContent(current, state, model, now, overviewList, fleetList, navigate, vmPerformance)
                            }
                        }
                    }
                }
                // --- B1 console --- The immersive console replaces the shell chrome while it is the current destination.
                (current as? Destination.Console)?.let { ConsoleHost(it, model) { navigate { back() } } }
                // --- end B1 console ---
                state.pendingTrust?.let { TrustDialog(it, state, model) }
                state.selectedGuest?.let { guest ->
                    // On wide screens Fleet shows details as a pane; everywhere else they are a full-screen dialog.
                    if (!(width.usesPanes && current == Destination.Fleet))
                        VmDetailsDialog(guest, state, model, { vmPerformance(guest) }, model::closeDetails)
                }
                // A2 operations: host picker and (typed-name) confirmation for every VM operation kind.
                VmOperationDialogs(state, onConfirm = model::confirmPower, onCancel = model::cancelPower,
                    onPickHost = model::chooseHost, onCancelHost = model::cancelHostChoice)
                MasterFollowDialog(state, model) // A4: HOST_IS_SLAVE offer, never an automatic switch
                // --- B2 storage/host --- snapshot, CD, host and PBD confirmations, ISO picker and review.
                StorageHostDialogs(state, model)
                // --- end B2 storage/host ---
                state.pendingReview?.let { ReviewDialog(it, state, model) }
            }
        }
    }
}

/** Navigation icon; Alerts carries the unread priority 1–3 badge (A4). */
@Composable
private fun NavIcon(item: NavItem, state: MeteorState) {
    if (item.destination == Destination.Alerts) AlertsNavIcon(state.unreadAlertBadge) else SpaceIcon(item.icon, Modifier.size(23.dp))
}

/** Renders one destination. Add a branch here for each new [Destination]; see `ui/Navigation.kt`. */
@Composable
private fun DestinationContent(destination: Destination, state: MeteorState, model: MeteorViewModel, now: Long,
    overviewList: LazyListState, fleetList: LazyListState, navigate: (Navigator.() -> Unit) -> Unit, vmPerformance: (Guest) -> Unit) {
    when (destination) {
        Destination.Overview -> OverviewScreen(state, now, overviewList,
            connect = { navigate { select(Destination.Connections) } },
            metrics = { target -> model.openMetrics(target); navigate { open(Destination.Performance) } },
            activity = { navigate { open(Destination.PoolActivity) }; model.refreshActivity() })
        Destination.Fleet -> FleetScreen(state, model, fleetList, vmPerformance)
        Destination.Connections -> ReadableWidth { ConnectionsScreen(state, model) }
        Destination.Settings -> ReadableWidth { SettingsScreen(state.appearance, model) { navigate { open(Destination.History) }; model.reloadHistory() } }
        Destination.History -> ReadableWidth { HistoryScreen(state, model::reloadHistory, { navigate { back() } }, { navigate { select(Destination.Overview) } }) }
        Destination.Performance -> ReadableWidth { // A3 observability: Live / 10 min / 2 h / 1 week selector
            val leave = { navigate { back() } }
            PerformanceWithHistory(state, now, leave) { PerformanceScreen(state, now, model::refreshMetrics, leave) }
        }
        Destination.Networks -> ReadableWidth { NetworksScreen { navigate { back() } } } // A3 observability
        Destination.Alerts -> ReadableWidth { // A4: an alert's VM opens in Fleet so wide screens show the pane
            Column(Modifier.fillMaxSize()) { // B3: a notification for another connection offers the normal switch above the inbox
                AlertsTargetCard(state)
                AlertsScreen(state, now, AlertsActions(model::reloadAlerts, model::markAlertsSeen, model::dismissAlerts,
                    { guest -> navigate { open(Destination.Fleet) }; model.openDetails(guest) }, { navigate { select(Destination.Overview) } }))
            }
        }
        Destination.PoolActivity -> ReadableWidth { PoolActivityScreen(state, now, model::refreshActivity) { navigate { back() } } }
        // B1 console: drawn by ConsoleHost as a full-screen layer, not inside the shell.
        is Destination.Console -> Unit
    }
}

@Composable
private fun Header(state: MeteorState, model: MeteorViewModel, compact: Boolean) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = if (compact) 4.dp else 12.dp), verticalAlignment = Alignment.CenterVertically) {
        // Decorative: the wordmark beside it names the app.
        Image(painterResource(R.drawable.meteor_mark), null, Modifier.size(if (compact) 40.dp else 52.dp).clip(RoundedCornerShape(14.dp)), contentScale = ContentScale.Crop)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("meteor", fontSize = 28.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-1).sp)
            Text("SPARK  /  XCP-ng", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
        if (state.selectedProfile != null) TextButton(onClick = model::refresh,
            enabled = !state.loading && !state.profileBusy && !state.operationBusy && state.editingProfileId == null && state.pendingTrust == null) { Text("Refresh") }
    }
}

@Composable
private fun OperationCard(operation: PowerOperation, state: MeteorState, model: MeteorViewModel, viewHistory: () -> Unit) {
    Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(OperationHistory.title(operation), style = MaterialTheme.typography.labelMedium) // B2 storage/host: target-aware title
            Text(if (operation.unresolved) operation.message.ifBlank { operation.phase.name } else OperationHistory.status(operation),
                style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = viewHistory) { Text("View history") }
            if (operation.unresolved && !state.operationBusy) TextButton(onClick = model::checkOperation, enabled = !state.profileBusy) { Text("Check status") }
            if (operation.unresolved) {
                TextButton(onClick = model::reviewOperation,
                    enabled = state.connected && !state.loading && !state.profileBusy && !state.operationBusy && !state.journalProblem) { Text("Review pending action") }
            }
        }
    }
}
