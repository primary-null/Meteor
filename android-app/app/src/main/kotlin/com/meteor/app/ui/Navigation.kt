package com.meteor.app.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Typed navigation for the Meteor shell. Pure Kotlin, so the model is unit-tested on the JVM.
 *
 * The [Navigator] is owned by `MeteorViewModel` (field `navigator`), so the back stack survives
 * configuration changes such as rotation and fold/unfold. `MeteorApp` also saves the stack's
 * [Destination.key]s in saved instance state and restores them after process death; destinations
 * that depend on in-memory data ([Destination.restorable] = false) are dropped on that restore.
 * The stack always starts at [Destination.Overview]; system Back pops it and leaves the app only
 * from that root.
 *
 * Extension points:
 * - **New destination** (A4 Alerts, B1 full-screen Console): add a `data object` to [Destination]
 *   (or a `data class` when it needs arguments, with `restorable = false` if those arguments only
 *   live in memory) and list it in [Destination.restore] when restorable. Render it in the
 *   exhaustive `when` in `MeteorApp.kt` → `DestinationContent`; the compiler reports the missing
 *   branch. Open it with `navigator.open(Destination.X)`. A destination that needs its own
 *   navigation-bar/rail item is also added to [NAV_ITEMS] (keep the list short for phones).
 * - **Polling while visible**: the shell's polling loop in `MeteorApp.kt` is keyed on the current
 *   destination; add a `current == Destination.X` check there instead of starting another loop.
 * - **VM details**: `ui/VmDetails.kt` → `VmActionsSlot` (A2 replaces the power buttons with
 *   `VmActionsPanel`) and `VmDetailSectionsSlot` (A3 adds detail sections).
 *   Host and storage details: `ui/InventoryDialogs.kt` → `HostDetailSectionsSlot` and
 *   `StorageDetailSectionsSlot`. The same content renders as a dialog on compact width and as a
 *   pane on medium/expanded width, so slot content must scroll with its parent (no fixed heights).
 * - **Adaptive layout**: `ui/Adaptive.kt` → [WidthClass], `LocalWidthClass`, `ListDetailLayout`.
 * - **Opening a VM from elsewhere** (for example an alert link): `navigator.open(Destination.Fleet)`
 *   then `model.openDetails(guest)`. Leaving Fleet closes the VM details so polling stops.
 */
sealed interface Destination {
    /** Stable identifier saved across process death. Never reuse a key for a different screen. */
    val key: String
    /** False when the screen depends on in-memory data that does not survive process death. */
    val restorable: Boolean get() = true

    data object Overview : Destination { override val key = "overview" }
    data object Fleet : Destination { override val key = "fleet" }
    data object Connections : Destination { override val key = "connections" }
    data object Settings : Destination { override val key = "settings" }
    data object History : Destination { override val key = "history" }
    /** The metric target and samples are memory-only, so performance is never restored. */
    data object Performance : Destination {
        override val key = "performance"
        override val restorable get() = false
    }
    data object PoolActivity : Destination { override val key = "pool-activity" }
    /** A4 alerts/events: XAPI message inbox with priority, object links and confirmed dismiss. */
    data object Alerts : Destination { override val key = "alerts" }
    /** A3 observability: read-only networks, PIFs, VLANs and bonds. */
    data object Networks : Destination { override val key = "networks" }
    // --- B1 console ---
    /**
     * The full-screen VM console (drawn as an immersive layer by `MeteorApp`). It names the VM only; the live
     * session belongs to the view model's `ConsoleController`. Never restored after process death.
     */
    data class Console(val profileId: String, val authority: String, val vmUuid: String, val vmName: String) : Destination {
        override val key = "console"
        override val restorable get() = false
    }
    // --- end B1 console ---

    companion object {
        /** Destinations with a navigation-bar/rail item. Opening one resets the stack above Overview. */
        val topLevel: List<Destination> get() = NAV_ITEMS.map { it.destination }
        /** Maps a saved [key] back to a restorable destination, or null when unknown or not restorable. */
        fun restore(key: String): Destination? =
            listOf(Overview, Fleet, Connections, Settings, History, Performance, PoolActivity, Networks, Alerts)
                .firstOrNull { it.key == key }?.takeIf { it.restorable }
    }
}

/** A navigation-bar / rail entry. [icon] names a `SpaceIcon` drawing. */
data class NavItem(val destination: Destination, val label: String, val icon: String)

val NAV_ITEMS = listOf(
    NavItem(Destination.Overview, "Overview", "planet"),
    NavItem(Destination.Fleet, "VMs", "meteor"),
    NavItem(Destination.Alerts, "Alerts", "alerts"), // A4: drawn by AlertsNavIcon with the unread badge
    NavItem(Destination.Connections, "Connect", "orbit"),
    NavItem(Destination.Settings, "Settings", "spark"),
)

/** Immutable back stack. The root is always [Destination.Overview] and a destination appears once. */
data class BackStack(val entries: List<Destination> = listOf(Destination.Overview)) {
    init {
        require(entries.firstOrNull() == Destination.Overview) { "The back stack starts at Overview." }
        require(entries.distinct().size == entries.size) { "A destination appears once in the back stack." }
    }
    val current: Destination get() = entries.last()
    val canGoBack: Boolean get() = entries.size > 1
    /** The navigation item to highlight: the nearest top-level destination below the top. */
    val selectedItem: Destination get() = entries.lastOrNull { it in Destination.topLevel } ?: Destination.Overview
    val keys: List<String> get() = entries.map { it.key }

    /** Switch to a top-level item. Its stack is `[Overview]` or `[Overview, item]`. */
    fun select(item: Destination): BackStack {
        require(item in Destination.topLevel) { "Only navigation items can be selected." }
        return if (item == Destination.Overview) BackStack() else BackStack(listOf(Destination.Overview, item))
    }

    /** Open a destination. Top-level items are selected; an entry already on the stack is returned to. */
    fun open(destination: Destination): BackStack = when {
        destination in Destination.topLevel -> select(destination)
        destination in entries -> BackStack(entries.take(entries.indexOf(destination) + 1))
        else -> BackStack(entries + destination)
    }

    fun back(): BackStack = if (canGoBack) BackStack(entries.dropLast(1)) else this

    companion object {
        /** Rebuilds a saved stack, dropping unknown and non-restorable destinations. */
        fun restore(keys: List<String>): BackStack = keys.mapNotNull(Destination::restore)
            .fold(BackStack()) { stack, destination -> if (destination == Destination.Overview) stack else stack.open(destination) }
    }
}

/** Holds the back stack for the activity's lifetime, including configuration changes. */
class Navigator {
    private val mutable = MutableStateFlow(BackStack())
    val stack: StateFlow<BackStack> = mutable.asStateFlow()
    val current: Destination get() = mutable.value.current
    private var initialized = false
    private var handledConnectionEvent = 0

    fun open(destination: Destination) { initialized = true; mutable.update { it.open(destination) } }
    fun select(item: Destination) { initialized = true; mutable.update { it.select(item) } }

    /** Returns false at the root, where Back should leave the app. */
    fun back(): Boolean {
        initialized = true
        if (!mutable.value.canGoBack) return false
        mutable.update { it.back() }
        return true
    }

    /** Applies a stack saved before process death. Ignored once this navigator is in use. */
    fun restore(keys: List<String>) {
        if (initialized) return
        initialized = true
        mutable.value = BackStack.restore(keys)
    }

    /** A completed manual connection returns to Overview once per event, not again after recreation. */
    fun onManualConnection(event: Int) {
        if (event <= handledConnectionEvent) return
        handledConnectionEvent = event
        initialized = true
        mutable.value = BackStack()
    }
}
