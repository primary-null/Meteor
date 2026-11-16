package com.meteor.app.data

import kotlinx.coroutines.delay

/** Which on-screen keys repeat while held, and how fast. Modifiers and Ctrl+Alt+Del never do. */
object KeyRepeat {
    const val INITIAL_DELAY_MILLIS = 450L
    const val INTERVAL_MILLIS = 60L

    private val REPEATING = setOf(KeySyms.LEFT, KeySyms.UP, KeySyms.RIGHT, KeySyms.DOWN, KeySyms.BACKSPACE, KeySyms.DELETE,
        KeySyms.TAB, KeySyms.PAGE_UP, KeySyms.PAGE_DOWN)

    fun repeats(keysym: Int): Boolean = keysym in REPEATING
}

/**
 * Hold-to-repeat for one key-bar button. [run] waits [initialDelayMillis], then calls `fire` every [intervalMillis]
 * until its coroutine is cancelled (the finger lifts). A press that ends before the first repeat is an ordinary tap,
 * so the button's click handler sends the key once; after a repeat fired, [consumeTap] tells the click handler that
 * the key was already sent. [wait] is injectable so tests need no real time.
 */
class HoldRepeat(private val initialDelayMillis: Long = KeyRepeat.INITIAL_DELAY_MILLIS,
    private val intervalMillis: Long = KeyRepeat.INTERVAL_MILLIS,
    private val wait: suspend (Long) -> Unit = { delay(it) }) {
    @Volatile private var fired = false

    fun reset() { fired = false }

    suspend fun run(fire: () -> Unit) {
        wait(initialDelayMillis)
        while (true) {
            fired = true
            fire()
            wait(intervalMillis)
        }
    }

    /** True once when repeats already sent the key during this press, so the click that ends it must not send it again. */
    fun consumeTap(): Boolean { val was = fired; fired = false; return was }
}
