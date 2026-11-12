package com.meteor.app.data

/**
 * The guest screen as ARGB pixels, shared between the single decoder thread (which writes) and the UI
 * thread (which copies changed regions into a bitmap). Pure Kotlin so decoders are tested on the JVM.
 *
 * Only the decoder thread writes [pixels] or calls [resize]. Every write is followed by [markDirty], and
 * [takeDelta] runs under the same lock, so a pixel written before `markDirty` is visible to the reader
 * that takes the delta. A reader may observe a rectangle that is still being written by a later
 * rectangle of the same update; that rectangle is marked dirty again when it completes.
 */
class Framebuffer(width: Int, height: Int) {
    companion object {
        /** Exclusive pixel limit (about 40 MB of ints). Larger guest screens are rejected. */
        const val MAX_PIXELS = 10_000_000L
        const val MAX_SIDE = 16_384
        fun validSize(width: Int, height: Int): Boolean =
            width in 1..MAX_SIDE && height in 1..MAX_SIDE && width.toLong() * height < MAX_PIXELS
    }

    /** What the UI needs to bring its bitmap up to date: a changed region, or the whole new surface. */
    class Delta(val width: Int, val height: Int, val pixels: IntArray, val resized: Boolean,
        val x: Int, val y: Int, val w: Int, val h: Int)

    private val lock = Any()
    @Volatile var width: Int = width; private set
    @Volatile var height: Int = height; private set
    @Volatile var pixels: IntArray = IntArray(checkedArea(width, height)); private set
    private var resizedPending = true
    private var left = 0; private var top = 0; private var right = width; private var bottom = height

    private fun checkedArea(width: Int, height: Int): Int {
        if (!validSize(width, height)) throw ConsoleError("Console framebuffer is too large or invalid.", ConsoleFailureKind.PROTOCOL)
        return width * height
    }

    /** Replaces the surface after a guest resolution change. The previous contents are discarded. */
    fun resize(newWidth: Int, newHeight: Int) {
        val fresh = IntArray(checkedArea(newWidth, newHeight))
        synchronized(lock) {
            width = newWidth; height = newHeight; pixels = fresh
            resizedPending = true; left = 0; top = 0; right = newWidth; bottom = newHeight
        }
    }

    fun markDirty(x: Int, y: Int, w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        synchronized(lock) {
            if (right <= left || bottom <= top) { left = x; top = y; right = x + w; bottom = y + h }
            else { left = minOf(left, x); top = minOf(top, y); right = maxOf(right, x + w); bottom = maxOf(bottom, y + h) }
        }
    }

    /** The accumulated change since the last call, or null when nothing changed. */
    fun takeDelta(): Delta? = synchronized(lock) {
        val whole = resizedPending
        if (!whole && (right <= left || bottom <= top)) return null
        val delta = if (whole) Delta(width, height, pixels, true, 0, 0, width, height)
        else Delta(width, height, pixels, false, left, top, right - left, bottom - top)
        resizedPending = false; left = 0; top = 0; right = 0; bottom = 0
        delta
    }

    /** The whole surface, without clearing pending changes. For a new view (rotation, fold) that has no bitmap yet. */
    fun fullDelta(): Delta = synchronized(lock) { Delta(width, height, pixels, true, 0, 0, width, height) }

    /** Fills a rectangle that the caller already validated against [width] and [height]. */
    fun fill(x: Int, y: Int, w: Int, h: Int, color: Int) {
        val target = pixels; val stride = width
        for (row in y until y + h) java.util.Arrays.fill(target, row * stride + x, row * stride + x + w, color)
    }

    /** Overlap-safe copy inside the surface, as CopyRect requires. */
    fun copy(srcX: Int, srcY: Int, w: Int, h: Int, dstX: Int, dstY: Int) {
        val target = pixels; val stride = width
        if (dstY > srcY) for (row in h - 1 downTo 0) System.arraycopy(target, (srcY + row) * stride + srcX, target, (dstY + row) * stride + dstX, w)
        else for (row in 0 until h) System.arraycopy(target, (srcY + row) * stride + srcX, target, (dstY + row) * stride + dstX, w)
    }
}
