package com.meteor.app.data

import org.junit.Assert.*
import org.junit.Test

/** Mouse button mapping, wheel events, hover and the rule that pointer actions starting outside the picture are ignored. */
class ConsolePointerRulesTest {
    private data class Event(val x: Int, val y: Int, val buttons: Int)
    private class Recorder : ConsoleSink {
        val events = mutableListOf<Event>()
        override fun key(keysym: Int, down: Boolean) = Unit
        override fun pointer(x: Int, y: Int, buttons: Int) { events += Event(x, y, buttons) }
    }

    // A 200 x 100 guest in a 400 x 400 view is fitted at scale 2 and centred: the picture spans x 0..400, y 100..300.
    private val transform = ViewTransform().withViewport(400f, 400f).withFrame(200, 100)
    private fun setup(): Triple<Recorder, ConsoleInput, MouseTracker> {
        val sink = Recorder(); val input = ConsoleInput(sink)
        return Triple(sink, input, MouseTracker(input))
    }
    private val left = RfbProtocol.LEFT; private val middle = RfbProtocol.MIDDLE; private val right = RfbProtocol.RIGHT

    // ---- start points ----

    @Test fun aStartInsideThePictureMapsToGuestCoordinatesAndOneInTheLetterboxHasNone() {
        assertEquals(FramePoint(50, 25), PointerRules.startPoint(transform, 100f, 150f))
        assertNull("above the picture", PointerRules.startPoint(transform, 100f, 50f))
        assertNull("below the picture", PointerRules.startPoint(transform, 100f, 350f))
        assertNull(PointerRules.startPoint(ViewTransform(), 10f, 10f))
        assertEquals("The conversion alone would clamp to an edge pixel", FramePoint(50, 0), transform.toFramebuffer(100f, 50f))
    }

    @Test fun theBoundaryPixelsBelongToThePictureAndTheFarEdgeDoesNot() {
        assertNotNull(PointerRules.startPoint(transform, 0f, 100f))
        assertNull(PointerRules.startPoint(transform, 400f, 150f))
        assertNull(PointerRules.startPoint(transform, 100f, 300f))
    }

    @Test fun aZoomedViewHasNoLetterboxWhereTheImageFillsTheView() {
        val zoomed = ViewTransform().withViewport(400f, 400f).withFrame(1000, 1000).zoomBy(2f, 200f, 200f)
        assertNotNull(PointerRules.startPoint(zoomed, 5f, 5f))
        assertNotNull(PointerRules.startPoint(zoomed, 395f, 395f))
    }

    // ---- mouse buttons ----

    @Test fun primarySecondaryAndTertiaryButtonsAreRfbButtons1To3And2() {
        assertEquals(left, PointerRules.mouseMask(primary = true, secondary = false, tertiary = false))
        assertEquals(right, PointerRules.mouseMask(primary = false, secondary = true, tertiary = false))
        assertEquals(middle, PointerRules.mouseMask(primary = false, secondary = false, tertiary = true))
        assertEquals(left or right, PointerRules.mouseMask(true, true, false))
        assertEquals(0, PointerRules.mouseMask(false, false, false))
        assertEquals(1, RfbProtocol.LEFT); assertEquals(2, RfbProtocol.MIDDLE); assertEquals(4, RfbProtocol.RIGHT)
    }

    @Test fun eachMouseButtonPressesAndReleasesItsOwnGuestButton() {
        for (button in listOf(left, middle, right)) {
            val (sink, _, mouse) = setup()
            mouse.update(button, 100f, 150f, transform)
            mouse.update(0, 100f, 150f, transform)
            assertEquals("button $button", listOf(Event(50, 25, button), Event(50, 25, 0)), sink.events)
        }
    }

    @Test fun twoButtonsAtOnceAreReleasedIndependently() {
        val (sink, input, mouse) = setup()
        mouse.update(left, 100f, 150f, transform)
        mouse.update(left or right, 100f, 150f, transform)
        mouse.update(right, 100f, 150f, transform)
        mouse.update(0, 100f, 150f, transform)
        assertEquals(listOf(left, left or right, right, 0), sink.events.map { it.buttons })
        assertFalse(input.anyHeld)
    }

    @Test fun hoverFollowsTheMouseWithoutAButtonAndDragMovesKeepTheButton() {
        val (sink, _, mouse) = setup()
        mouse.update(0, 20f, 120f, transform)
        mouse.update(0, 40f, 140f, transform)
        mouse.update(left, 40f, 140f, transform)
        mouse.update(left, 80f, 160f, transform)
        mouse.update(0, 80f, 160f, transform)
        assertEquals(listOf(Event(10, 10, 0), Event(20, 20, 0), Event(20, 20, left), Event(40, 30, left), Event(40, 30, 0)), sink.events)
    }

    // ---- outside the picture ----

    @Test fun hoverAndPressesInTheLetterboxSendNothing() {
        val (sink, input, mouse) = setup()
        mouse.update(0, 100f, 50f, transform)
        mouse.update(left, 100f, 50f, transform)
        mouse.update(left, 100f, 60f, transform)
        mouse.update(0, 100f, 60f, transform)
        assertTrue(sink.events.isEmpty()); assertFalse(input.anyHeld)
    }

    @Test fun aPressThatStartsOutsideStaysIgnoredEvenWhenTheDragCrossesThePicture() {
        val (sink, _, mouse) = setup()
        mouse.update(left, 100f, 50f, transform)
        mouse.update(left, 100f, 150f, transform)
        mouse.update(0, 100f, 150f, transform)
        assertTrue("The release over the picture is not a hover either", sink.events.isEmpty())
        mouse.update(0, 100f, 160f, transform)
        assertEquals("Hover works again once every button was up", listOf(Event(50, 30, 0)), sink.events)
    }

    @Test fun aDragThatStartsInsideAndLeavesKeepsClampingToTheEdge() {
        val (sink, input, mouse) = setup()
        mouse.update(left, 100f, 150f, transform)
        mouse.update(left, 100f, 20f, transform)
        mouse.update(0, 100f, 20f, transform)
        assertEquals(listOf(Event(50, 25, left), Event(50, 0, left), Event(50, 0, 0)), sink.events)
        assertFalse(input.anyHeld)
    }

    // ---- wheel ----

    @Test fun wheelNotchesBecomeScrollButtonsInTheRightDirections() {
        val (sink, _, mouse) = setup()
        mouse.scroll(0f, 1f, 100f, 150f, transform)
        assertEquals(listOf(Event(50, 25, 0), Event(50, 25, RfbProtocol.WHEEL_DOWN), Event(50, 25, 0)), sink.events)
        sink.events.clear(); mouse.scroll(0f, -2f, 100f, 150f, transform)
        assertEquals(listOf(0, RfbProtocol.WHEEL_UP, 0, RfbProtocol.WHEEL_UP, 0), sink.events.map { it.buttons })
        sink.events.clear(); mouse.scroll(1f, 0f, 100f, 150f, transform)
        assertEquals(RfbProtocol.WHEEL_RIGHT, sink.events.maxOf { it.buttons })
        sink.events.clear(); mouse.scroll(-1f, 0f, 100f, 150f, transform)
        assertEquals(RfbProtocol.WHEEL_LEFT, sink.events.maxOf { it.buttons })
    }

    @Test fun smoothWheelDeltasAddUpToWholeNotches() {
        val (sink, _, mouse) = setup()
        repeat(3) { mouse.scroll(0f, 0.4f, 100f, 150f, transform) }
        assertEquals("1.2 of a notch is one notch", 1, sink.events.count { it.buttons == RfbProtocol.WHEEL_DOWN })
    }

    @Test fun aWheelOverTheLetterboxIsIgnored() {
        val (sink, _, mouse) = setup()
        mouse.scroll(0f, 3f, 100f, 50f, transform)
        assertTrue(sink.events.isEmpty())
    }

    @Test fun cancellingReleasesButtonsThisMouseHoldsAndNothingElse() {
        val (sink, input, mouse) = setup()
        mouse.update(left or middle, 100f, 150f, transform)
        sink.events.clear()
        mouse.cancel()
        assertEquals(0, sink.events.last().buttons); assertFalse(input.anyHeld)
        sink.events.clear(); mouse.cancel()
        assertTrue(sink.events.isEmpty())
    }

    @Test fun viewOnlyDropsEveryMouseEvent() {
        val (sink, input, mouse) = setup()
        input.viewOnly = true
        mouse.update(0, 100f, 150f, transform); mouse.update(left, 100f, 150f, transform); mouse.update(0, 100f, 150f, transform)
        mouse.scroll(0f, 2f, 100f, 150f, transform)
        assertTrue(sink.events.isEmpty()); assertFalse(input.anyHeld)
    }

    // ---- touch (unchanged behaviour, same start rule) ----

    @Test fun aTouchTapInTheLetterboxHasNoGuestPointAndOneOnThePictureClicksTheGuestPixel() {
        assertNull(PointerRules.startPoint(transform, 200f, 10f))
        val (sink, input, _) = setup()
        PointerRules.startPoint(transform, 200f, 200f)?.let { input.click(RfbProtocol.MIDDLE, it.x, it.y) }
        assertEquals(listOf(Event(100, 50, 0), Event(100, 50, middle), Event(100, 50, 0)), sink.events)
    }
}
