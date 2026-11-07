package com.omninote.pro.engine.input

import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.view.MotionEvent
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/** Where the host view should send a touch event. */
sealed interface InputRouting {
    /** Stylus/eraser contact: feed pointer [pointerIndex] to the ink pipeline. */
    data class Ink(val pointerIndex: Int, val isEraser: Boolean) : InputRouting

    /** Valid finger input, already consumed by the gesture recognizer; results arrive on [PalmRejectionArbitrator.commands]. */
    data object Gesture : InputRouting

    /** Palm, hover-locked, cooling-down or non-finger/non-stylus input. Consume and ignore. */
    data object Rejected : InputRouting
}

data class PalmRejectionConfig(
    /** Single-finger touches are rejected for this long after the stylus lifts or leaves hover range. */
    val cooldownMs: Long = 400L,
    /** AXIS_TOUCH_MAJOR above this (converted to px) is a palm. */
    val palmMajorThresholdDp: Float = 18f,
    val tapSlopDp: Float = 10f,
    val tapTimeoutMs: Long = 300L,
    /**
     * Two-finger Undo is delayed by this window so a second tap can become Fit-to-Width instead.
     * Shorter = snappier Undo but a harder double-tap.
     */
    val doubleTapWindowMs: Long = 250L,
    val swipeThresholdDp: Float = 96f,
    /** Rotation within this many degrees of 0/90/180/270 snaps to it. */
    val detentSnapDeg: Float = 6f,
    /** Rotation is ignored until the fingers have twisted this far, so pinching never nudges the angle. */
    val rotationEngageDeg: Float = 5f,
    val flingMinSpeedDpPerSec: Float = 120f,
    /** Exponential velocity decay rate; higher stops sooner. */
    val flingFrictionPerSec: Float = 4f
)

/**
 * Decides who owns every pointer: ink pipeline, gesture recognizer, or the bin.
 *
 * Host wiring (UI thread only):
 *  - View.onTouchEvent -> [onTouchEvent]
 *  - View.onHoverEvent / onGenericMotionEvent -> [onHoverEvent]
 *  - View.onDetachedFromWindow -> [reset]
 *
 * Rejection rules:
 *  - A finger sequence that BEGINS while the stylus is hovering or within [PalmRejectionConfig.cooldownMs] of the
 *    last stylus contact/hover exit is rejected until every finger lifts.
 *  - Any finger contact with AXIS_TOUCH_MAJOR over the palm threshold rejects the whole sequence.
 *  - When the stylus lands during a finger gesture, the gesture is cancelled and remaining fingers are rejected.
 */
class PalmRejectionArbitrator(
    density: Float,
    private val rotationProvider: () -> Float,
    private val config: PalmRejectionConfig = PalmRejectionConfig()
) {
    private val _commands = MutableSharedFlow<GestureCommand>(
        extraBufferCapacity = COMMAND_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /** Ordered gesture output. Collect on the main thread. */
    val commands: SharedFlow<GestureCommand> = _commands.asSharedFlow()

    var isStylusHovering: Boolean = false
        private set

    /**
     * When true a single finger that moves past the tap slop pans the page. Off by default (strict palm rejection):
     * a resting palm that is not caught by the size filter must never scroll the canvas. Turned on by the Hand/Pan tool
     * or the "single finger pan" setting.
     */
    @Volatile
    var singleFingerPanEnabled: Boolean = false

    private val palmMajorPx = config.palmMajorThresholdDp * density
    private val tapSlopPx = config.tapSlopDp * density
    private val swipePx = config.swipeThresholdDp * density
    private val flingMinSpeedPx = config.flingMinSpeedDpPerSec * density

    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private val choreographer by lazy { Choreographer.getInstance() }

    // ---- stylus / lock state ----
    private var stylusDown = false
    private var lastStylusActivityMs = Long.MIN_VALUE / 4
    private var rejectSequence = false

    // ---- finger sequence state ----
    private var gestureActive = false
    private var sequenceStartMs = 0L
    private var maxFingers = 0
    private var tapDisqualified = false
    private var transforming = false
    private var swipeFired = false
    private val downX = FloatArray(MAX_POINTER_ID)
    private val downY = FloatArray(MAX_POINTER_ID)

    private val activeIdx = IntArray(MAX_ACTIVE)
    private var activeCount = 0

    private var baselineCount = 0
    private var prevCx = 0f
    private var prevCy = 0f
    private var prevDist = 0f
    private var prevAngle = 0f
    private var prevTimeMs = 0L
    private var lastMoveMs = 0L
    private var velX = 0f
    private var velY = 0f
    private var baseCx = 0f
    private var baseCy = 0f

    private var rotationStart = 0f
    private var gestureRotation = 0f
    private var shownRotation = 0f
    private var rotationEngaged = false

    // ---- two-finger double tap ----
    private var lastTwoFingerTapMs = NO_TIME
    private var pendingUndo = false
    private val undoRunnable = Runnable {
        pendingUndo = false
        emit(GestureCommand.Undo)
    }

    // ---- fling ----
    private var flingActive = false
    private var flingVx = 0f
    private var flingVy = 0f
    private var flingFocalX = 0f
    private var flingFocalY = 0f
    private var flingLastNanos = 0L
    private val flingCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!flingActive) return
            if (flingLastNanos == 0L) {
                flingLastNanos = frameTimeNanos
                choreographer.postFrameCallback(this)
                return
            }
            val dt = (frameTimeNanos - flingLastNanos) / 1_000_000_000f
            flingLastNanos = frameTimeNanos
            emit(GestureCommand.Transform(flingFocalX, flingFocalY, 1f, flingVx * dt, flingVy * dt, 0f))
            val decay = exp(-config.flingFrictionPerSec * dt)
            flingVx *= decay
            flingVy *= decay
            if (hypot(flingVx, flingVy) < flingMinSpeedPx * FLING_STOP_FRACTION) {
                flingActive = false
            } else {
                choreographer.postFrameCallback(this)
            }
        }
    }

    // =====================================================================================
    // Public API
    // =====================================================================================

    fun onTouchEvent(event: MotionEvent): InputRouting {
        val stylusIndex = findStylusIndex(event)
        return if (stylusIndex >= 0) routeStylus(event, stylusIndex) else routeFingers(event)
    }

    /** Returns true if the hover came from a stylus (and was tracked). */
    fun onHoverEvent(event: MotionEvent): Boolean {
        if (event.pointerCount == 0 || !isInkTool(event.getToolType(0))) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> isStylusHovering = true
            MotionEvent.ACTION_HOVER_EXIT -> {
                isStylusHovering = false
                lastStylusActivityMs = event.eventTime
            }
        }
        return true
    }

    /** Drop all state and pending callbacks. Call when the view detaches or the page changes. */
    fun reset() {
        handler.removeCallbacks(undoRunnable)
        pendingUndo = false
        lastTwoFingerTapMs = NO_TIME
        cancelFling()
        isStylusHovering = false
        stylusDown = false
        rejectSequence = false
        resetGesture()
    }

    // =====================================================================================
    // Stylus routing
    // =====================================================================================

    private fun routeStylus(event: MotionEvent, stylusIndex: Int): InputRouting {
        val action = event.actionMasked
        val now = event.eventTime

        if (!stylusDown) {
            stylusDown = true
            cancelFling()
            if (gestureActive) {
                abortGesture()
            }
            rejectSequence = hasFinger(event)
        }
        lastStylusActivityMs = now

        val actionIsInk = isInkTool(event.getToolType(event.actionIndex))
        when (action) {
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                stylusDown = false
                rejectSequence = false
            }
            MotionEvent.ACTION_POINTER_UP -> if (actionIsInk) stylusDown = false
        }

        val pointerEvent = action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_POINTER_UP
        if (pointerEvent && !actionIsInk) return InputRouting.Rejected

        return InputRouting.Ink(
            pointerIndex = stylusIndex,
            isEraser = event.getToolType(stylusIndex) == MotionEvent.TOOL_TYPE_ERASER
        )
    }

    // =====================================================================================
    // Finger routing + gesture recognition
    // =====================================================================================

    private fun routeFingers(event: MotionEvent): InputRouting {
        val action = event.actionMasked
        val now = event.eventTime

        if (action == MotionEvent.ACTION_DOWN) {
            cancelFling()
            rejectSequence = event.getToolType(0) != MotionEvent.TOOL_TYPE_FINGER ||
                isLocked(now) ||
                hasPalm(event)
            if (!rejectSequence) beginSequence(event)
        }

        if (rejectSequence) {
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) rejectSequence = false
            return InputRouting.Rejected
        }
        if (!gestureActive) return InputRouting.Rejected

        if (hasPalm(event)) {
            abortGesture()
            rejectSequence = action != MotionEvent.ACTION_UP && action != MotionEvent.ACTION_CANCEL
            return InputRouting.Rejected
        }

        if (action == MotionEvent.ACTION_POINTER_DOWN &&
            event.getToolType(event.actionIndex) == MotionEvent.TOOL_TYPE_FINGER
        ) {
            recordDown(event, event.actionIndex)
        }
        trackSlopAndCount(event)

        if (action == MotionEvent.ACTION_CANCEL) {
            abortGesture()
            return InputRouting.Gesture
        }
        if (action == MotionEvent.ACTION_UP) {
            finishSequence(now)
            return InputRouting.Gesture
        }

        gatherActiveFingers(event, if (action == MotionEvent.ACTION_POINTER_UP) event.actionIndex else -1)
        val n = activeCount

        if (n != baselineCount) {
            if (baselineCount == 2 && n < 2 && transforming) maybeStartFling(now)
            setBaselines(event, n, now)
        } else if (action == MotionEvent.ACTION_MOVE) {
            when (n) {
                1 -> handleOneFingerMove(event, now)
                2 -> handleTwoFingerMove(event, now)
                3 -> handleThreeFingerMove(event)
            }
        }
        return InputRouting.Gesture
    }

    private fun handleOneFingerMove(event: MotionEvent, now: Long) {
        if (!singleFingerPanEnabled) return
        val i = activeIdx[0]
        val x = event.getX(i)
        val y = event.getY(i)
        if (!transforming && tapDisqualified && !swipeFired) transforming = true
        if (transforming) {
            val dx = x - prevCx
            val dy = y - prevCy
            emit(GestureCommand.Transform(prevCx, prevCy, 1f, dx, dy, 0f))
            val dt = max(now - prevTimeMs, 1L) / 1000f
            velX = VELOCITY_SMOOTHING * (dx / dt) + (1f - VELOCITY_SMOOTHING) * velX
            velY = VELOCITY_SMOOTHING * (dy / dt) + (1f - VELOCITY_SMOOTHING) * velY
            lastMoveMs = now
        }
        prevCx = x
        prevCy = y
        prevTimeMs = now
    }

    private fun handleTwoFingerMove(event: MotionEvent, now: Long) {
        val i0 = activeIdx[0]
        val i1 = activeIdx[1]
        val x0 = event.getX(i0)
        val y0 = event.getY(i0)
        val x1 = event.getX(i1)
        val y1 = event.getY(i1)
        val cx = (x0 + x1) * 0.5f
        val cy = (y0 + y1) * 0.5f
        val dist = hypot(x1 - x0, y1 - y0)
        val angle = atan2(y1 - y0, x1 - x0)

        if (!transforming && tapDisqualified && !swipeFired) transforming = true

        if (transforming) {
            val dcx = cx - prevCx
            val dcy = cy - prevCy
            val scale = if (prevDist > MIN_PINCH_DIST_PX) dist / prevDist else 1f
            val dAngle = wrapDegrees(Math.toDegrees((angle - prevAngle).toDouble()).toFloat())

            gestureRotation += dAngle
            var rotationDelta = 0f
            if (!rotationEngaged && abs(gestureRotation) > config.rotationEngageDeg) rotationEngaged = true
            if (rotationEngaged) {
                val target = snapRotation(rotationStart + gestureRotation)
                rotationDelta = target - shownRotation
                shownRotation = target
            }

            emit(GestureCommand.Transform(prevCx, prevCy, scale, dcx, dcy, rotationDelta))

            val dt = max(now - prevTimeMs, 1L) / 1000f
            velX = VELOCITY_SMOOTHING * (dcx / dt) + (1f - VELOCITY_SMOOTHING) * velX
            velY = VELOCITY_SMOOTHING * (dcy / dt) + (1f - VELOCITY_SMOOTHING) * velY
            lastMoveMs = now
        }

        prevCx = cx
        prevCy = cy
        prevDist = dist
        prevAngle = angle
        prevTimeMs = now
    }

    private fun handleThreeFingerMove(event: MotionEvent) {
        var sx = 0f
        var sy = 0f
        for (k in 0 until 3) {
            sx += event.getX(activeIdx[k])
            sy += event.getY(activeIdx[k])
        }
        val dx = sx / 3f - baseCx
        val dy = sy / 3f - baseCy
        if (!swipeFired && abs(dx) > swipePx && abs(dx) > 2f * abs(dy)) {
            swipeFired = true
            tapDisqualified = true
            emit(if (dx < 0f) GestureCommand.NextPage else GestureCommand.PreviousPage)
        }
    }

    private fun finishSequence(now: Long) {
        val duration = now - sequenceStartMs
        val isTap = !tapDisqualified && !transforming && !swipeFired && duration <= config.tapTimeoutMs
        if (isTap) {
            when (maxFingers) {
                2 -> onTwoFingerTap(now)
                3 -> emit(GestureCommand.Redo)
            }
        }
        resetGesture()
    }

    private fun onTwoFingerTap(now: Long) {
        if (pendingUndo && now - lastTwoFingerTapMs <= config.doubleTapWindowMs) {
            handler.removeCallbacks(undoRunnable)
            pendingUndo = false
            lastTwoFingerTapMs = NO_TIME
            emit(GestureCommand.FitToWidth)
        } else {
            lastTwoFingerTapMs = now
            pendingUndo = true
            handler.postDelayed(undoRunnable, config.doubleTapWindowMs)
        }
    }

    private fun setBaselines(event: MotionEvent, n: Int, now: Long) {
        baselineCount = n
        velX = 0f
        velY = 0f
        prevTimeMs = now
        lastMoveMs = now
        if (n < 1) return

        var sx = 0f
        var sy = 0f
        for (k in 0 until n) {
            sx += event.getX(activeIdx[k])
            sy += event.getY(activeIdx[k])
        }
        prevCx = sx / n
        prevCy = sy / n
        if (n == 2) {
            val x0 = event.getX(activeIdx[0])
            val y0 = event.getY(activeIdx[0])
            val x1 = event.getX(activeIdx[1])
            val y1 = event.getY(activeIdx[1])
            prevDist = hypot(x1 - x0, y1 - y0)
            prevAngle = atan2(y1 - y0, x1 - x0)
            rotationStart = rotationProvider()
            shownRotation = rotationStart
            gestureRotation = 0f
            rotationEngaged = false
        }
        if (n == 3) {
            baseCx = prevCx
            baseCy = prevCy
        }
    }

    private fun snapRotation(raw: Float): Float {
        val nearest = (raw / 90f).roundToInt() * 90f
        return if (abs(raw - nearest) <= config.detentSnapDeg) nearest else raw
    }

    private fun wrapDegrees(d: Float): Float {
        var v = d
        while (v > 180f) v -= 360f
        while (v < -180f) v += 360f
        return v
    }

    // =====================================================================================
    // Fling
    // =====================================================================================

    private fun maybeStartFling(now: Long) {
        if (now - lastMoveMs > FLING_STALE_MS) return
        if (hypot(velX, velY) < flingMinSpeedPx) return
        flingVx = velX
        flingVy = velY
        flingFocalX = prevCx
        flingFocalY = prevCy
        flingLastNanos = 0L
        flingActive = true
        choreographer.postFrameCallback(flingCallback)
    }

    private fun cancelFling() {
        if (!flingActive) return
        flingActive = false
        choreographer.removeFrameCallback(flingCallback)
    }

    // =====================================================================================
    // Helpers
    // =====================================================================================

    private fun isLocked(now: Long): Boolean =
        isStylusHovering || now - lastStylusActivityMs < config.cooldownMs

    private fun beginSequence(event: MotionEvent) {
        resetGesture()
        gestureActive = true
        sequenceStartMs = event.eventTime
        recordDown(event, 0)
    }

    private fun resetGesture() {
        gestureActive = false
        maxFingers = 0
        tapDisqualified = false
        transforming = false
        swipeFired = false
        baselineCount = 0
        velX = 0f
        velY = 0f
        rotationEngaged = false
        gestureRotation = 0f
    }

    private fun abortGesture() {
        if (gestureActive && transforming) emit(GestureCommand.Cancelled)
        cancelFling()
        resetGesture()
    }

    private fun recordDown(event: MotionEvent, index: Int) {
        val id = event.getPointerId(index)
        if (id in 0 until MAX_POINTER_ID) {
            downX[id] = event.getX(index)
            downY[id] = event.getY(index)
        }
    }

    private fun trackSlopAndCount(event: MotionEvent) {
        var fingers = 0
        for (i in 0 until event.pointerCount) {
            if (event.getToolType(i) != MotionEvent.TOOL_TYPE_FINGER) continue
            fingers++
            val id = event.getPointerId(i)
            if (id in 0 until MAX_POINTER_ID &&
                hypot(event.getX(i) - downX[id], event.getY(i) - downY[id]) > tapSlopPx
            ) {
                tapDisqualified = true
            }
        }
        maxFingers = max(maxFingers, fingers)
    }

    /** Collects finger pointer indexes ordered by pointer id so pair geometry never flips between frames. */
    private fun gatherActiveFingers(event: MotionEvent, excludeIndex: Int) {
        activeCount = 0
        for (i in 0 until event.pointerCount) {
            if (i == excludeIndex || event.getToolType(i) != MotionEvent.TOOL_TYPE_FINGER) continue
            if (activeCount >= MAX_ACTIVE) break
            val id = event.getPointerId(i)
            var j = activeCount
            while (j > 0 && event.getPointerId(activeIdx[j - 1]) > id) {
                activeIdx[j] = activeIdx[j - 1]
                j--
            }
            activeIdx[j] = i
            activeCount++
        }
    }

    private fun hasPalm(event: MotionEvent): Boolean {
        for (i in 0 until event.pointerCount) {
            if (event.getToolType(i) == MotionEvent.TOOL_TYPE_FINGER && event.getTouchMajor(i) > palmMajorPx) {
                return true
            }
        }
        return false
    }

    private fun hasFinger(event: MotionEvent): Boolean {
        for (i in 0 until event.pointerCount) {
            if (event.getToolType(i) == MotionEvent.TOOL_TYPE_FINGER) return true
        }
        return false
    }

    private fun findStylusIndex(event: MotionEvent): Int {
        for (i in 0 until event.pointerCount) {
            if (isInkTool(event.getToolType(i))) return i
        }
        return -1
    }

    private fun isInkTool(toolType: Int): Boolean =
        toolType == MotionEvent.TOOL_TYPE_STYLUS || toolType == MotionEvent.TOOL_TYPE_ERASER

    private fun emit(command: GestureCommand) {
        _commands.tryEmit(command)
    }

    private companion object {
        const val COMMAND_BUFFER = 64
        const val MAX_POINTER_ID = 32
        const val MAX_ACTIVE = 16
        const val NO_TIME = Long.MIN_VALUE
        const val MIN_PINCH_DIST_PX = 1f
        const val VELOCITY_SMOOTHING = 0.6f
        const val FLING_STALE_MS = 80L
        const val FLING_STOP_FRACTION = 0.4f
    }
}
