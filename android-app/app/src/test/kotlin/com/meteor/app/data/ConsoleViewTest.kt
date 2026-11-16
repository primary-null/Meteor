package com.meteor.app.data

import org.junit.Assert.*
import org.junit.Test

/** Pointer mapping and placement under letterboxing, zoom and pan. */
class ConsoleViewTest {
    // A 1920x1080 guest in a 1080x2000 portrait view: fit scale 0.5625, image 1080x607.5, centered vertically.
    private fun portrait() = ViewTransform().withViewport(1080f, 2000f).withFrame(1920, 1080)
    private fun landscape() = ViewTransform().withViewport(2000f, 1000f).withFrame(1920, 1080)

    @Test fun fitKeepsTheAspectRatioAndCentersTheImage() {
        val view = portrait()
        assertTrue(view.fit); assertEquals(0.5625f, view.scale, 1e-5f)
        assertEquals(0f, view.left, 1e-3f); assertEquals(696.25f, view.top, 1e-2f)
        assertEquals(1080f, view.imageWidth, 1e-2f); assertEquals(607.5f, view.imageHeight, 1e-2f)
        val wide = landscape()
        assertEquals(1000f / 1080f, wide.scale, 1e-5f)
        assertEquals((2000f - 1920f * wide.scale) / 2f, wide.left, 1e-2f); assertEquals(0f, wide.top, 1e-3f)
    }

    @Test fun pointerMapsThroughTheLetterbox() {
        val view = portrait()
        assertEquals(FramePoint(0, 0), view.toFramebuffer(0f, 696.25f))
        assertEquals(FramePoint(960, 540), view.toFramebuffer(540f, 1000f))
        assertEquals(FramePoint(1919, 1079), view.toFramebuffer(1079.9f, 1303.7f))
        assertEquals("Taps in the letterbox clamp to the nearest edge", FramePoint(960, 0), view.toFramebuffer(540f, 10f))
        assertEquals(FramePoint(960, 1079), view.toFramebuffer(540f, 1990f))
        assertEquals(FramePoint(0, 0), view.toFramebuffer(-50f, -50f))
        assertTrue(view.contains(540f, 1000f)); assertFalse(view.contains(540f, 10f))
    }

    @Test fun mappingRoundTripsThroughViewCoordinates() {
        val view = landscape()
        for ((x, y) in listOf(0 to 0, 100 to 37, 1919 to 1079, 960 to 540)) {
            val (vx, vy) = view.toView(x, y)
            assertEquals(FramePoint(x, y), view.toFramebuffer(vx + view.scale / 2f, vy + view.scale / 2f))
        }
    }

    @Test fun zoomKeepsTheGuestPointUnderTheFingersFixed() {
        val fit = landscape()
        val focusX = 1000f; val focusY = 600f
        val before = fit.toFramebuffer(focusX, focusY)
        val zoomed = fit.zoomBy(3f, focusX, focusY)
        assertFalse(zoomed.fit); assertEquals(fit.scale * 3f, zoomed.scale, 1e-4f)
        val after = zoomed.toFramebuffer(focusX, focusY)
        assertTrue("Point stays within a guest pixel", kotlin.math.abs(after.x - before.x) <= 1 && kotlin.math.abs(after.y - before.y) <= 1)
    }

    @Test fun zoomIsLimitedAndPinchingBackSnapsToFit() {
        val fit = portrait()
        assertEquals(ViewTransform.MAX_ZOOM, fit.zoomBy(1000f, 500f, 500f).scale, 1e-4f)
        val zoomed = fit.zoomBy(3f, 500f, 900f)
        assertTrue(zoomed.zoomBy(0.01f, 500f, 900f).fit)
        assertTrue("A tiny wobble around fit stays fit", fit.zoomBy(1.01f, 500f, 900f).fit)
        assertSame(fit, fit.zoomBy(Float.NaN, 0f, 0f)); assertSame(fit, fit.zoomBy(-1f, 0f, 0f))
    }

    @Test fun panIsClampedSoTheImageNeverLeavesTheView() {
        val zoomed = portrait().zoomBy(4f, 540f, 1000f)
        val far = zoomed.panBy(100000f, 100000f)
        assertEquals(0f, far.left, 1e-3f); assertEquals(0f, far.top, 1e-3f)
        val other = zoomed.panBy(-100000f, -100000f)
        assertEquals(1080f - other.imageWidth, other.left, 1e-2f); assertEquals(2000f - other.imageHeight, other.top, 1e-2f)
        assertEquals("Mapping after panning reaches the far corner", FramePoint(1919, 1079), other.toFramebuffer(1079f, 1999f))
        assertEquals("Fit mode has nothing to pan", portrait(), portrait().panBy(50f, 50f))
    }

    @Test fun axisSmallerThanTheViewStaysCenteredWhileTheOtherPans() {
        // 1:1 of a 1920x1080 guest in a 1080x2000 view: 1920 wide (pannable), 1080 tall (centered).
        val zoomed = portrait().zoomBy(1f / 0.5625f, 540f, 1000f)
        assertEquals(1f, zoomed.scale, 1e-3f)
        assertEquals((2000f - zoomed.imageHeight) / 2f, zoomed.top, 1e-2f)
        assertEquals(zoomed.top, zoomed.panBy(0f, 300f).top, 1e-3f)
        assertNotEquals(zoomed.left, zoomed.panBy(-200f, 0f).left)
    }

    @Test fun doubleTapTogglesBetweenFitAndOneToOneAroundTheTap() {
        val fit = portrait()
        val actual = fit.toggleActualSize(900f, 1000f)
        assertFalse(actual.fit); assertEquals(1f, actual.scale, 1e-4f)
        val tapped = fit.toFramebuffer(900f, 1000f)
        val kept = actual.toFramebuffer(900f, 1000f)
        assertTrue(kotlin.math.abs(kept.x - tapped.x) <= 1 && kotlin.math.abs(kept.y - tapped.y) <= 1)
        assertTrue(actual.toggleActualSize(0f, 0f).fit)
        val exact = ViewTransform().withViewport(800f, 600f).withFrame(800, 600)
        assertSame("A guest that already fits at 1:1 has nothing to toggle", exact, exact.toggleActualSize(1f, 1f))
    }

    @Test fun aGuestSmallerThanTheViewScalesUpInFitAndCanShowOneToOne() {
        val small = ViewTransform().withViewport(1600f, 1000f).withFrame(800, 600)
        assertEquals(1000f / 600f, small.scale, 1e-4f)
        val actual = small.toggleActualSize(800f, 500f)
        assertEquals(1f, actual.scale, 1e-4f)
        assertEquals("Centered and fully visible", (1600f - 800f) / 2f, actual.left, 1e-3f)
        assertTrue(actual.showsWholeScreen)
    }

    @Test fun aNewGuestResolutionKeepsFitAndReclampsAZoomedView() {
        val resized = portrait().withFrame(1280, 720)
        assertTrue(resized.fit); assertEquals(1080f / 1280f, resized.scale, 1e-4f)
        val zoomed = portrait().zoomBy(4f, 900f, 1000f).panBy(-5000f, 0f)
        val smaller = zoomed.withFrame(1024, 768)
        assertTrue(smaller.left <= 0f && smaller.left + smaller.imageWidth >= 1080f - 0.5f)
        val rotated = zoomed.withViewport(2000f, 1080f)
        assertTrue(rotated.left <= 0.5f)
    }

    @Test fun unreadyTransformIsHarmless() {
        val empty = ViewTransform()
        assertFalse(empty.ready); assertNull(empty.visibleRegion())
        assertEquals(FramePoint(0, 0), empty.toFramebuffer(10f, 10f))
        assertSame(empty, empty.zoomBy(2f, 0f, 0f)); assertSame(empty, empty.panBy(1f, 1f)); assertSame(empty, empty.toggleActualSize(0f, 0f))
    }

    @Test fun visibleRegionCropsToTheViewWhenZoomed() {
        val fit = portrait().visibleRegion()!!
        assertEquals(listOf(0, 0, 1920, 1080), listOf(fit.srcX, fit.srcY, fit.srcWidth, fit.srcHeight))
        assertEquals(1080f, fit.dstWidth, 1e-2f); assertEquals(696.25f, fit.dstY, 1e-2f)
        val zoomed = portrait().zoomBy(8f / 0.5625f, 540f, 1000f)
        val region = zoomed.visibleRegion()!!
        assertTrue(region.srcWidth < 1920 && region.srcHeight < 1080)
        assertTrue("The cropped destination stays near the view size", region.dstWidth < 1080f + 2 * zoomed.scale)
        assertEquals(region.srcWidth * zoomed.scale, region.dstWidth, 1e-2f)
    }

    // ---- zoom and pan across rotation ----

    @Test fun zoomAndPanSurviveARotationAndAreClampedToTheNewView() {
        val zoomed = ViewTransform().withViewport(400f, 800f).withFrame(1000, 500).zoomBy(2f, 100f, 100f).panBy(-50f, -30f)
        assertFalse(zoomed.fit)
        val restored = ViewTransform.fromSaved(zoomed.toSaved()).withViewport(800f, 400f).withFrame(1000, 500)
        assertFalse("still zoomed after rotating", restored.fit)
        assertEquals(zoomed.scale, restored.scale, 0.0001f)
        assertTrue(restored.left <= 0f && restored.left >= 800f - restored.imageWidth)
        assertTrue(restored.top <= 0f && restored.top >= 400f - restored.imageHeight)
    }

    @Test fun aFittedViewStaysFittedAndBadSavedDataGivesTheFittedView() {
        assertTrue(ViewTransform.fromSaved(ViewTransform().toSaved()).fit)
        assertEquals(ViewTransform(), ViewTransform.fromSaved(emptyList()))
        assertEquals(ViewTransform(), ViewTransform.fromSaved(listOf(0f, Float.NaN, 0f, 0f)))
        assertEquals(ViewTransform.MAX_ZOOM, ViewTransform.fromSaved(listOf(0f, 99f, 0f, 0f)).zoom, 0f)
    }
}
