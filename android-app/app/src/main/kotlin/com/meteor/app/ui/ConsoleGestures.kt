package com.meteor.app.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.unit.dp
import com.meteor.app.data.ConsoleInput
import com.meteor.app.data.RfbProtocol
import com.meteor.app.data.ScrollAccumulator
import com.meteor.app.data.ViewTransform
import kotlin.math.abs

/** What one-finger drags do. Taps, long-presses and two-finger gestures work in every mode. */
internal enum class TouchMode(val label: String) {
    /** Drag moves the pointer with the left button held. */
    MOUSE("Mouse"),
    /** Drag sends the scroll wheel (buttons 4/5, and 6/7 sideways). */
    SCROLL("Scroll"),
    /** Drag pans a zoomed view. */
    PAN("Pan");

    fun next(): TouchMode = entries[(ordinal + 1) % entries.size]
}

/** The gesture handler's connection to the screen state. Lambdas read the latest value on every call. */
internal class ConsoleGestureHost(
    val transform: () -> ViewTransform,
    val setTransform: (ViewTransform) -> Unit,
    val input: ConsoleInput,
    val mode: () -> TouchMode,
    val doubleTapZoom: () -> Boolean,
    val haptic: () -> Unit,
)

private enum class Outcome { TAP, DRAG, LONG_PRESS, TWO_FINGER, CANCEL }

/**
 * Touch handling for the console surface.
 *
 * - tap: left click at the mapped guest coordinate; a second tap soon after toggles fit / 1:1 zoom (or is another
 *   click when double-tap zoom is turned off, so the guest receives a double-click);
 * - long press: right click (the button stays down while the finger does, so right-drags work);
 * - one-finger drag: by [TouchMode] either a button-held move, wheel scrolling, or panning;
 * - two fingers: pinching zooms around the fingers (and pans), moving them together scrolls the wheel.
 */
internal suspend fun PointerInputScope.consoleGestures(host: ConsoleGestureHost) {
    var lastTapTime = 0L
    var lastTapPosition = Offset.Zero
    val scrollStep = 36.dp.toPx()
    awaitEachGesture {
        val first = awaitFirstDown(requireUnconsumed = false)
        val slop = viewConfiguration.touchSlop
        val origin = first.position
        var outcome = Outcome.LONG_PRESS
        var upTime = 0L
        withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            while (true) {
                val event = awaitPointerEvent()
                if (event.changes.count { it.pressed } >= 2) { outcome = Outcome.TWO_FINGER; return@withTimeoutOrNull }
                val change = event.changes.firstOrNull { it.id == first.id }
                if (change == null) { outcome = Outcome.CANCEL; return@withTimeoutOrNull }
                if (!change.pressed) { change.consume(); upTime = change.uptimeMillis; outcome = Outcome.TAP; return@withTimeoutOrNull }
                if ((change.position - origin).getDistance() > slop) { outcome = Outcome.DRAG; return@withTimeoutOrNull }
            }
        }
        when (outcome) {
            Outcome.TAP -> {
                val doubled = host.doubleTapZoom() && lastTapTime != 0L &&
                    upTime - lastTapTime <= viewConfiguration.doubleTapTimeoutMillis &&
                    (origin - lastTapPosition).getDistance() <= slop * 4
                if (doubled) {
                    lastTapTime = 0L
                    host.setTransform(host.transform().toggleActualSize(origin.x, origin.y))
                } else {
                    lastTapTime = upTime; lastTapPosition = origin
                    val point = host.transform().toFramebuffer(origin.x, origin.y)
                    host.input.click(RfbProtocol.LEFT, point.x, point.y)
                }
            }
            Outcome.LONG_PRESS -> { host.haptic(); holdButton(host, first, origin, RfbProtocol.RIGHT) }
            Outcome.DRAG -> dragGesture(host, first, origin, scrollStep)
            Outcome.TWO_FINGER -> twoFingerGesture(host, scrollStep)
            Outcome.CANCEL -> Unit
        }
    }
}

/** Holds [button] at the touch point and follows the finger until it lifts. */
private suspend fun AwaitPointerEventScope.holdButton(host: ConsoleGestureHost, first: PointerInputChange, origin: Offset, button: Int) {
    val start = host.transform().toFramebuffer(origin.x, origin.y)
    host.input.buttonDown(button, start.x, start.y)
    try {
        while (true) {
            val change = awaitPointerEvent().changes.firstOrNull { it.id == first.id } ?: break
            change.consume()
            if (!change.pressed) break
            val point = host.transform().toFramebuffer(change.position.x, change.position.y)
            host.input.pointerMove(point.x, point.y)
        }
    } finally { host.input.buttonUp(button) }
}

private suspend fun AwaitPointerEventScope.dragGesture(host: ConsoleGestureHost, first: PointerInputChange, origin: Offset, scrollStep: Float) {
    when (host.mode()) {
        TouchMode.MOUSE -> holdButton(host, first, origin, RfbProtocol.LEFT)
        TouchMode.SCROLL -> {
            val vertical = ScrollAccumulator(scrollStep); val horizontal = ScrollAccumulator(scrollStep)
            val anchor = host.transform().toFramebuffer(origin.x, origin.y)
            while (true) {
                val change = awaitPointerEvent().changes.firstOrNull { it.id == first.id } ?: break
                change.consume()
                if (!change.pressed) break
                scroll(host, change.positionChange(), vertical, horizontal, anchor.x, anchor.y)
            }
        }
        TouchMode.PAN -> while (true) {
            val change = awaitPointerEvent().changes.firstOrNull { it.id == first.id } ?: break
            change.consume()
            if (!change.pressed) break
            val delta = change.positionChange()
            host.setTransform(host.transform().panBy(delta.x, delta.y))
        }
    }
}

/** Finger travel down means the content moves down, which is the wheel turning up. */
private fun scroll(host: ConsoleGestureHost, delta: Offset, vertical: ScrollAccumulator, horizontal: ScrollAccumulator, x: Int, y: Int) {
    if (abs(delta.x) > abs(delta.y)) {
        val ticks = horizontal.add(delta.x)
        if (ticks != 0) host.input.wheel(if (ticks > 0) RfbProtocol.WHEEL_LEFT else RfbProtocol.WHEEL_RIGHT, abs(ticks), x, y)
    } else {
        val ticks = vertical.add(delta.y)
        if (ticks != 0) host.input.wheel(if (ticks > 0) RfbProtocol.WHEEL_UP else RfbProtocol.WHEEL_DOWN, abs(ticks), x, y)
    }
}

private suspend fun AwaitPointerEventScope.twoFingerGesture(host: ConsoleGestureHost, scrollStep: Float) {
    var initialSpread = 0f; var previousSpread = 0f
    var initialCentroid = Offset.Zero; var previousCentroid = Offset.Zero
    var zooming: Boolean? = null
    val vertical = ScrollAccumulator(scrollStep); val horizontal = ScrollAccumulator(scrollStep)
    var anyPressed = true
    while (true) {
        val event = awaitPointerEvent()
        val pressed = event.changes.filter { it.pressed }
        event.changes.forEach { it.consume() }
        if (pressed.size < 2) { anyPressed = pressed.isNotEmpty(); break }
        val centroid = (pressed[0].position + pressed[1].position) / 2f
        val spread = (pressed[0].position - pressed[1].position).getDistance().coerceAtLeast(1f)
        if (initialSpread == 0f) {
            initialSpread = spread; previousSpread = spread; initialCentroid = centroid; previousCentroid = centroid
            continue
        }
        if (zooming == null) {
            val slop = viewConfiguration.touchSlop
            if (abs(spread / initialSpread - 1f) > 0.08f) zooming = true
            else if ((centroid - initialCentroid).getDistance() > slop * 1.5f) zooming = false
        }
        val delta = centroid - previousCentroid
        when (zooming) {
            true -> host.setTransform(host.transform().panBy(delta.x, delta.y).zoomBy(spread / previousSpread, centroid.x, centroid.y))
            false -> {
                val point = host.transform().toFramebuffer(centroid.x, centroid.y)
                scroll(host, delta, vertical, horizontal, point.x, point.y)
            }
            null -> Unit
        }
        previousSpread = spread; previousCentroid = centroid
    }
    // Let the remaining finger lift without it starting a new gesture.
    while (anyPressed) {
        val event = awaitPointerEvent()
        event.changes.forEach { it.consume() }
        anyPressed = event.changes.any { it.pressed }
    }
}
