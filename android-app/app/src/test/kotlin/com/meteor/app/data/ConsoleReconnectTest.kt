package com.meteor.app.data

import org.junit.Assert.*
import org.junit.Test
import java.io.Closeable

/** The automatic reconnect rules and the abort handle that makes the connect deadline real. */
class ConsoleReconnectTest {
    @Test fun theBackoffIsTwoFiveTenTwentySecondsThenItStops() {
        val steps = (0..4).map { ReconnectPolicy.next(ConsoleFailureKind.LOST, false, it) }
        assertEquals(listOf(2_000L, 5_000L, 10_000L, 20_000L), steps.take(4).map { it!!.delayMillis })
        assertEquals(listOf(1, 2, 3, 4), steps.take(4).map { it!!.attempt })
        assertTrue(steps.take(4).all { it!!.attempts == 4 })
        assertNull(steps[4])
    }

    @Test fun onlyLossesThatCanHealAreRetried() {
        val retried = ConsoleFailureKind.entries.filter { ReconnectPolicy.retryable(it) }.toSet()
        assertEquals(setOf(ConsoleFailureKind.LOST, ConsoleFailureKind.NETWORK, ConsoleFailureKind.TIMEOUT,
            ConsoleFailureKind.NO_CONSOLE, ConsoleFailureKind.PROTOCOL), retried)
        for (never in listOf(ConsoleFailureKind.AUTH, ConsoleFailureKind.CERTIFICATE, ConsoleFailureKind.MEMBER_TRUST,
            ConsoleFailureKind.NOT_RUNNING, ConsoleFailureKind.UNSUPPORTED, ConsoleFailureKind.BACKGROUND, ConsoleFailureKind.FAILED)) {
            assertNull("$never", ReconnectPolicy.next(never, false, 0))
        }
    }

    @Test fun anUndecodableConsoleIsRetriedOnceAndInCompatibilityMode() {
        val first = ReconnectPolicy.next(ConsoleFailureKind.PROTOCOL, safeMode = false, used = 0)!!
        assertTrue(first.safeMode)
        assertNull("Already in compatibility mode: retrying would only repeat the failure", ReconnectPolicy.next(ConsoleFailureKind.PROTOCOL, true, 1))
        assertFalse(ReconnectPolicy.next(ConsoleFailureKind.LOST, false, 0)!!.safeMode)
        assertTrue("An earlier compatibility mode stays", ReconnectPolicy.next(ConsoleFailureKind.LOST, true, 0)!!.safeMode)
    }

    private class Handle : Closeable { var closed = false; override fun close() { closed = true } }

    @Test fun abortingClosesWhatAttemptsRegisteredAndLateRegistrationsAreClosedAtOnce() {
        val abort = ConsoleAbort()
        val a = Handle(); val b = Handle()
        assertTrue(abort.register(a)); assertTrue(abort.register(b))
        abort.abort()
        assertTrue(a.closed); assertTrue(b.closed)
        val late = Handle()
        assertFalse(abort.register(late)); assertTrue(late.closed)
    }

    @Test fun aSocketHandedOverToItsSessionIsNotClosedByALaterAbort() {
        val abort = ConsoleAbort()
        val socket = Handle()
        abort.register(socket); abort.release(socket)
        abort.abort()
        assertFalse(socket.closed)
    }

    @Test fun afterTheAttemptFinishedAbortingDoesNothing() {
        val abort = ConsoleAbort()
        val socket = Handle()
        abort.register(socket); abort.finish(); abort.abort()
        assertFalse(socket.closed)
    }
}
