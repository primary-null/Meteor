package com.meteor.app.data

import kotlin.math.abs

/** Pointer decisions for the console surface, free of Compose types so they run on the JVM. */
object PointerRules {
    /** RFB button mask for Android's mouse buttons: primary is button 1, secondary button 3, tertiary button 2. */
    fun mouseMask(primary: Boolean, secondary: Boolean, tertiary: Boolean): Int =
        (if (primary) RfbProtocol.LEFT else 0) or (if (secondary) RfbProtocol.RIGHT else 0) or (if (tertiary) RfbProtocol.MIDDLE else 0)

    /** Guest coordinates for a pointer action that starts at a view point, or null when it starts outside the guest picture. */
    fun startPoint(transform: ViewTransform, viewX: Float, viewY: Float): FramePoint? =
        if (transform.contains(viewX, viewY)) transform.toFramebuffer(viewX, viewY) else null
}

/**
 * Turns a physical mouse into guest pointer events. [update] takes the buttons that are down after each press,
 * release, move or hover; [scroll] takes wheel events. A press that starts outside the guest picture is ignored
 * until every button is up; a drag that starts inside and leaves keeps following the nearest edge. Hover moves
 * are sent only over the picture. Touch input does not pass through here.
 */
class MouseTracker(private val input: ConsoleInput) {
    private var held = 0
    private var ignoring = false
    private val vertical = ScrollAccumulator(1f)
    private val horizontal = ScrollAccumulator(1f)

    /** [mask] is the RFB mask of the mouse buttons now down (see [PointerRules.mouseMask]). */
    fun update(mask: Int, viewX: Float, viewY: Float, transform: ViewTransform) {
        if (ignoring) {
            if (mask == 0) ignoring = false
            return
        }
        if (held == 0) {
            val start = PointerRules.startPoint(transform, viewX, viewY)
            if (mask == 0) { if (start != null) input.pointerMove(start.x, start.y); return }
            if (start == null) { ignoring = true; return }
        }
        val point = transform.toFramebuffer(viewX, viewY)
        val pressed = mask and held.inv()
        val released = held and mask.inv()
        if (pressed == 0 && released == 0) { input.pointerMove(point.x, point.y); return }
        for (button in BUTTONS) if (pressed and button != 0) input.buttonDown(button, point.x, point.y)
        for (button in BUTTONS) if (released and button != 0) input.buttonUp(button, point.x, point.y)
        held = mask
    }

    /** Wheel travel in notches: positive [dy] is down, positive [dx] is right. Ignored away from the picture. */
    fun scroll(dx: Float, dy: Float, viewX: Float, viewY: Float, transform: ViewTransform) {
        val point = PointerRules.startPoint(transform, viewX, viewY) ?: return
        val down = vertical.add(dy)
        if (down != 0) input.wheel(if (down > 0) RfbProtocol.WHEEL_DOWN else RfbProtocol.WHEEL_UP, abs(down), point.x, point.y)
        val right = horizontal.add(dx)
        if (right != 0) input.wheel(if (right > 0) RfbProtocol.WHEEL_RIGHT else RfbProtocol.WHEEL_LEFT, abs(right), point.x, point.y)
    }

    /** Releases whatever this mouse still holds in the guest, for example when the gesture handler is cancelled. */
    fun cancel() {
        for (button in BUTTONS) if (held and button != 0) input.buttonUp(button)
        held = 0; ignoring = false
        vertical.reset(); horizontal.reset()
    }

    private companion object {
        val BUTTONS = intArrayOf(RfbProtocol.LEFT, RfbProtocol.MIDDLE, RfbProtocol.RIGHT)
    }
}
