package com.meteor.app.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Which key-bar keys repeat, and the hold-to-repeat timing, driven by a recorded clock instead of real waiting. */
class ConsoleKeyRepeatTest {
    @Test fun onlyNavigationEditingAndTabKeysRepeat() {
        val repeating = listOf(KeySyms.LEFT, KeySyms.UP, KeySyms.RIGHT, KeySyms.DOWN, KeySyms.BACKSPACE, KeySyms.DELETE,
            KeySyms.TAB, KeySyms.PAGE_UP, KeySyms.PAGE_DOWN)
        repeating.forEach { assertTrue("0x${it.toString(16)}", KeyRepeat.repeats(it)) }
        val never = listOf(KeySyms.SHIFT_L, KeySyms.CONTROL_L, KeySyms.ALT_L, KeySyms.SUPER_L, KeySyms.ESCAPE, KeySyms.RETURN,
            KeySyms.HOME, KeySyms.END, KeySyms.INSERT, KeySyms.function(5), 'a'.code)
        never.forEach { assertFalse("0x${it.toString(16)}", KeyRepeat.repeats(it)) }
    }

    /** Runs a hold until [count] fires have happened, then lifts the finger (cancels). */
    private fun hold(repeat: HoldRepeat, count: Int, fire: () -> Unit) {
        var n = 0
        runBlocking {
            try { repeat.run { fire(); if (++n == count) throw CancellationException("lifted") } }
            catch (_: CancellationException) {}
        }
    }

    @Test fun aHoldWaitsTheInitialDelayThenRepeatsAtTheInterval() {
        val waits = mutableListOf<Long>(); val fired = mutableListOf<Long>(); var now = 0L
        val repeat = HoldRepeat(initialDelayMillis = 400, intervalMillis = 50, wait = { waits += it; now += it })
        hold(repeat, 4) { fired += now }
        assertEquals(listOf(400L, 450L, 500L, 550L), fired)
        assertEquals(listOf(400L, 50L, 50L, 50L), waits)
    }

    @Test fun theDefaultTimingIsAShortDelayThenAFewKeysPerSecond() {
        val waits = mutableListOf<Long>()
        hold(HoldRepeat(wait = { waits += it }), 3) { }
        assertEquals(listOf(KeyRepeat.INITIAL_DELAY_MILLIS, KeyRepeat.INTERVAL_MILLIS, KeyRepeat.INTERVAL_MILLIS), waits)
        assertTrue(KeyRepeat.INITIAL_DELAY_MILLIS in 300L..600L && KeyRepeat.INTERVAL_MILLIS in 30L..100L)
    }

    @Test fun aTapThatEndsBeforeTheFirstRepeatIsLeftToTheClickHandler() {
        val repeat = HoldRepeat(wait = { throw CancellationException("lifted early") })
        runBlocking { try { repeat.run { fail("nothing may fire") } } catch (_: CancellationException) {} }
        assertFalse(repeat.consumeTap())
    }

    @Test fun theClickEndingARepeatedHoldIsSwallowedOnceAndTheNextPressStartsClean() {
        val repeat = HoldRepeat(wait = { })
        hold(repeat, 2) { }
        assertTrue("repeats already sent the key", repeat.consumeTap())
        assertFalse("only once", repeat.consumeTap())
        hold(repeat, 1) { }
        repeat.reset()
        assertFalse("a new press starts clean even if the old click never came", repeat.consumeTap())
    }

    @Test fun firingThroughConsoleInputPressesAndReleasesEachTimeAndSendsNothingInViewOnly() {
        val keys = mutableListOf<Pair<Int, Boolean>>()
        val input = ConsoleInput(object : ConsoleSink {
            override fun key(keysym: Int, down: Boolean) { keys += keysym to down }
            override fun pointer(x: Int, y: Int, buttons: Int) = Unit
        })
        hold(HoldRepeat(wait = { }), 3) { input.press(KeySyms.BACKSPACE) }
        assertEquals(List(3) { listOf(KeySyms.BACKSPACE to true, KeySyms.BACKSPACE to false) }.flatten(), keys)
        keys.clear(); input.viewOnly = true
        hold(HoldRepeat(wait = { }), 3) { input.press(KeySyms.BACKSPACE) }
        assertTrue(keys.isEmpty())
    }
}
