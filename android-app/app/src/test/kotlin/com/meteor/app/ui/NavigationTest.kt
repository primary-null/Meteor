package com.meteor.app.ui

import com.meteor.app.ui.Destination.*
import org.junit.Assert.*
import org.junit.Test

class NavigationTest {
    @Test fun stackStartsAtOverviewAndBackLeavesOnlyFromTheRoot() {
        val navigator = Navigator()
        assertEquals(listOf(Overview), navigator.stack.value.entries)
        assertFalse(navigator.stack.value.canGoBack)
        assertFalse("Back at the root is left to the system, which exits", navigator.back())
        assertEquals(Overview, navigator.current)
    }

    @Test fun topLevelSelectionKeepsOverviewAsTheRoot() {
        val stack = BackStack().select(Fleet)
        assertEquals(listOf(Overview, Fleet), stack.entries)
        assertEquals(listOf(Overview, Connections), stack.select(Connections).entries)
        assertEquals(listOf(Overview), stack.select(Overview).entries)
        assertEquals(Overview, stack.back().current)
        assertFalse(stack.back().canGoBack)
    }

    @Test fun subDestinationsPushAndBackReturnsToTheOpener() {
        val fromFleet = BackStack().select(Fleet).open(Performance)
        assertEquals(listOf(Overview, Fleet, Performance), fromFleet.entries)
        assertEquals(Fleet, fromFleet.selectedItem)
        assertEquals(Fleet, fromFleet.back().current)
        val fromOverview = BackStack().open(Performance)
        assertEquals(Overview, fromOverview.selectedItem)
        assertEquals(Overview, fromOverview.back().current)
        val history = BackStack().select(Settings).open(History)
        assertEquals(Settings, history.selectedItem)
        assertEquals(Settings, history.back().current)
    }

    @Test fun reopeningADestinationReturnsToItInsteadOfDuplicating() {
        val stack = BackStack().select(Settings).open(History)
        assertEquals(stack, stack.open(History))
        val deeper = BackStack().select(Settings).open(History).open(Performance)
        assertEquals(listOf(Overview, Settings, History), deeper.open(History).entries)
        assertEquals(listOf(Overview, Fleet), deeper.open(Fleet).entries)
    }

    @Test fun invalidStacksAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { BackStack(listOf(Fleet)) }
        assertThrows(IllegalArgumentException::class.java) { BackStack(emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { BackStack(listOf(Overview, History, History)) }
        assertThrows(IllegalArgumentException::class.java) { BackStack().select(History) }
    }

    @Test fun savedKeysRestoreRestorableDestinationsOnly() {
        val saved = BackStack().select(Fleet).open(Performance).keys
        assertEquals(listOf("overview", "fleet", "performance"), saved)
        assertEquals(listOf(Overview, Fleet), BackStack.restore(saved).entries)
        assertEquals(listOf(Overview, Settings, History), BackStack.restore(listOf("overview", "settings", "history")).entries)
        assertEquals(listOf(Overview, PoolActivity), BackStack.restore(listOf("overview", "pool-activity")).entries)
        assertEquals(listOf(Overview), BackStack.restore(listOf("unknown", "performance")).entries)
        assertEquals(listOf(Overview), BackStack.restore(emptyList()).entries)
        assertEquals("Malformed saved stacks are normalized, not trusted", listOf(Overview, Settings, History),
            BackStack.restore(listOf("history", "settings", "history")).entries)
    }

    @Test fun keysAreUniqueAndStable() {
        val all = listOf(Overview, Fleet, Connections, Settings, History, Performance, PoolActivity)
        assertEquals(all.size, all.map { it.key }.toSet().size)
        all.filter { it.restorable }.forEach { assertEquals(it, Destination.restore(it.key)) }
        assertNull(Destination.restore(Performance.key))
    }

    @Test fun restoreAppliesOnlyToAFreshNavigator() {
        val restored = Navigator()
        restored.restore(listOf("overview", "settings", "history"))
        assertEquals(History, restored.current)
        restored.restore(listOf("overview", "fleet"))
        assertEquals("A second restore (activity recreation) keeps the live stack", History, restored.current)
        val used = Navigator()
        used.select(Connections)
        used.restore(listOf("overview", "fleet"))
        assertEquals(Connections, used.current)
    }

    @Test fun manualConnectionReturnsToOverviewOncePerEvent() {
        val navigator = Navigator()
        navigator.onManualConnection(0)
        navigator.select(Connections)
        navigator.onManualConnection(0)
        assertEquals("No connection event yet", Connections, navigator.current)
        navigator.onManualConnection(1)
        assertEquals(Overview, navigator.current)
        navigator.select(Fleet)
        navigator.onManualConnection(1)
        assertEquals("Recomposition after rotation must not replay the event", Fleet, navigator.current)
        navigator.onManualConnection(2)
        assertEquals(Overview, navigator.current)
    }

    @Test fun backPopsOneDestinationAtATime() {
        val navigator = Navigator()
        navigator.select(Settings)
        navigator.open(History)
        assertTrue(navigator.back())
        assertEquals(Settings, navigator.current)
        assertTrue(navigator.back())
        assertEquals(Overview, navigator.current)
        assertFalse(navigator.back())
    }

    @Test fun navigationItemsAreTheFourTopLevelDestinations() {
        assertEquals(listOf(Overview, Fleet, Alerts, Connections, Settings), Destination.topLevel)
        assertEquals(listOf("Overview", "VMs", "Alerts", "Connect", "Settings"), NAV_ITEMS.map { it.label })
    }

    @Test fun widthClassesFollowMaterialBreakpoints() {
        assertEquals(WidthClass.COMPACT, WidthClass.of(360f))   // Fold 3 cover display
        assertEquals(WidthClass.COMPACT, WidthClass.of(411f))
        assertEquals(WidthClass.COMPACT, WidthClass.of(599.9f))
        assertEquals(WidthClass.MEDIUM, WidthClass.of(600f))
        assertEquals(WidthClass.MEDIUM, WidthClass.of(768f))     // Fold 3 inner display, portrait-ish
        assertEquals(WidthClass.MEDIUM, WidthClass.of(839.9f))
        assertEquals(WidthClass.EXPANDED, WidthClass.of(840f))
        assertEquals(WidthClass.EXPANDED, WidthClass.of(884f))   // Fold 3 inner display, landscape
        assertEquals(WidthClass.COMPACT, WidthClass.of(Float.NaN))
        assertFalse(WidthClass.COMPACT.usesRail)
        assertFalse(WidthClass.COMPACT.usesPanes)
        assertTrue(WidthClass.MEDIUM.usesRail && WidthClass.MEDIUM.usesPanes)
        assertTrue(WidthClass.EXPANDED.usesRail && WidthClass.EXPANDED.usesPanes)
        WidthClass.entries.forEach { assertTrue(it.listFraction in .3f..1f) }
    }
}
