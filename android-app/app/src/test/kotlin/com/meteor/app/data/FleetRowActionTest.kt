package com.meteor.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class FleetRowActionTest {
    private fun guest(power: String) = Guest("vm", "VM", power, "pool", null, 1, 1024)

    @Test fun aRunningVmOpensItsConsole() = assertEquals(FleetRowAction.CONSOLE, FleetRowAction.primary(guest("Running")))

    @Test fun aVmWithoutAConsoleOpensItsDetails() {
        for (power in listOf("Halted", "Paused", "Suspended", "Unknown", ""))
            assertEquals(power, FleetRowAction.DETAILS, FleetRowAction.primary(guest(power)))
    }

    @Test fun aListShownWhileDisconnectedOpensDetailsNotADeadConsoleTap() =
        assertEquals(FleetRowAction.DETAILS, FleetRowAction.primary(guest("Running"), connected = false))

    private fun other() = Guest("other", "Other", "Running", "pool", null, 1, 1024)

    @Test fun backFromAConsoleOpenedInTheDetailsReturnsToThoseDetails() =
        assertEquals("vm" to "pool", ConsoleReturn.target(true, guest("Running"), other()))

    @Test fun aRowTapBesideAnOpenPaneLeavesThatPaneAsItWas() =
        assertEquals("other" to "pool", ConsoleReturn.target(false, guest("Running"), other()))

    @Test fun aRowTapWithNoDetailsOpenReturnsToThePlainFleet() =
        assertEquals(null, ConsoleReturn.target(false, guest("Running"), null))
}
