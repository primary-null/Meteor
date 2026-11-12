package com.meteor.app.data

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

data class FramePoint(val x: Int, val y: Int)

/** The part of the framebuffer to draw ([srcX] ...) and where to draw it in view pixels ([dstX] ...). */
data class DrawRegion(val srcX: Int, val srcY: Int, val srcWidth: Int, val srcHeight: Int,
    val dstX: Float, val dstY: Float, val dstWidth: Float, val dstHeight: Float)

/**
 * How the guest screen is placed in the view: fit-to-screen (centered, letterboxed, aspect ratio kept)
 * or zoomed with a pan offset. Immutable and free of Android types, so pointer mapping under zoom, pan
 * and letterboxing is tested on the JVM.
 *
 * In fit mode the scale is [fitScale]. Otherwise [zoom] is the scale in view pixels per guest pixel and
 * [panX]/[panY] place the image's top-left corner; [left] and [top] clamp that so the image never
 * leaves the view, and center it on an axis where it is smaller than the view.
 */
data class ViewTransform(
    val viewWidth: Float = 0f, val viewHeight: Float = 0f,
    val frameWidth: Int = 0, val frameHeight: Int = 0,
    val fit: Boolean = true, val zoom: Float = 1f, val panX: Float = 0f, val panY: Float = 0f,
) {
    companion object {
        const val MAX_ZOOM = 8f
        private const val FIT_SNAP = 0.03f
    }

    val ready: Boolean get() = viewWidth > 0f && viewHeight > 0f && frameWidth > 0 && frameHeight > 0
    val fitScale: Float get() = if (ready) min(viewWidth / frameWidth, viewHeight / frameHeight) else 1f
    val scale: Float get() = if (fit) fitScale else zoom
    val imageWidth: Float get() = frameWidth * scale
    val imageHeight: Float get() = frameHeight * scale
    val left: Float get() = axis(panX, viewWidth, imageWidth)
    val top: Float get() = axis(panY, viewHeight, imageHeight)
    /** True when the whole guest screen is visible, either fitted or zoomed out to 1:1 or less. */
    val showsWholeScreen: Boolean get() = imageWidth <= viewWidth + 0.5f && imageHeight <= viewHeight + 0.5f

    private fun axis(pan: Float, viewSize: Float, imageSize: Float): Float = when {
        fit || imageSize <= viewSize -> (viewSize - imageSize) / 2f
        else -> pan.coerceIn(viewSize - imageSize, 0f)
    }

    fun withViewport(width: Float, height: Float): ViewTransform = normalized(copy(viewWidth = width, viewHeight = height))

    /** A new guest resolution keeps fit mode; a zoomed view is re-clamped to the new size. */
    fun withFrame(width: Int, height: Int): ViewTransform = normalized(copy(frameWidth = width, frameHeight = height))

    /** Freezes the clamped position into the pan fields so later arithmetic starts from what is on screen. */
    private fun normalized(t: ViewTransform): ViewTransform =
        if (t.fit || !t.ready) t else t.copy(panX = t.left, panY = t.top)

    fun fitToScreen(): ViewTransform = copy(fit = true)

    /** Zooms by [factor] keeping the guest point under ([focusX], [focusY]) fixed. Pinching out to fit snaps back to fit mode. */
    fun zoomBy(factor: Float, focusX: Float, focusY: Float): ViewTransform {
        if (!ready || factor <= 0f || factor.isNaN()) return this
        val minScale = min(fitScale, 1f)
        val next = (scale * factor).coerceIn(minScale, max(MAX_ZOOM, fitScale))
        if (abs(next - fitScale) <= fitScale * FIT_SNAP) return copy(fit = true)
        return zoomedTo(next, focusX, focusY)
    }

    private fun zoomedTo(next: Float, focusX: Float, focusY: Float): ViewTransform {
        val current = scale
        val guestX = (focusX - left) / current
        val guestY = (focusY - top) / current
        return copy(fit = false, zoom = next, panX = focusX - guestX * next, panY = focusY - guestY * next).let { normalized(it) }
    }

    /** Moves a zoomed view by a finger delta. A fitted screen has nothing to pan. */
    fun panBy(dx: Float, dy: Float): ViewTransform =
        if (!ready || fit || showsWholeScreen) this else normalized(copy(panX = left + dx, panY = top + dy))

    /** Double tap: fit → 1:1 around the tap, otherwise back to fit. */
    fun toggleActualSize(focusX: Float, focusY: Float): ViewTransform = when {
        !ready -> this
        !fit -> copy(fit = true)
        abs(1f - fitScale) <= FIT_SNAP -> this
        else -> zoomedTo(1f, focusX, focusY)
    }

    /** Guest coordinates under a view point. Points in the letterbox or off the image clamp to the nearest edge pixel. */
    fun toFramebuffer(viewX: Float, viewY: Float): FramePoint {
        if (!ready) return FramePoint(0, 0)
        val s = scale
        return FramePoint(floor((viewX - left) / s).toInt().coerceIn(0, frameWidth - 1),
            floor((viewY - top) / s).toInt().coerceIn(0, frameHeight - 1))
    }

    /** Whether a view point lies on the guest image rather than the letterbox. */
    fun contains(viewX: Float, viewY: Float): Boolean =
        ready && viewX >= left && viewX < left + imageWidth && viewY >= top && viewY < top + imageHeight

    /** Top-left view position of a guest pixel. */
    fun toView(frameX: Int, frameY: Int): Pair<Float, Float> = (left + frameX * scale) to (top + frameY * scale)

    /** The visible slice of the guest image, so zoomed drawing never builds a huge destination rectangle. */
    fun visibleRegion(): DrawRegion? {
        if (!ready) return null
        val s = scale
        val x0 = floor(max(0f, -left / s)).toInt().coerceIn(0, frameWidth)
        val x1 = ceil(min(frameWidth.toFloat(), (viewWidth - left) / s)).toInt().coerceIn(x0, frameWidth)
        val y0 = floor(max(0f, -top / s)).toInt().coerceIn(0, frameHeight)
        val y1 = ceil(min(frameHeight.toFloat(), (viewHeight - top) / s)).toInt().coerceIn(y0, frameHeight)
        if (x1 <= x0 || y1 <= y0) return null
        return DrawRegion(x0, y0, x1 - x0, y1 - y0, left + x0 * s, top + y0 * s, (x1 - x0) * s, (y1 - y0) * s)
    }
}
