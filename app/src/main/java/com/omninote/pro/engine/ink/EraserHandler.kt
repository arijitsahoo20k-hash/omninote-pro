package com.omninote.pro.engine.ink

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import com.omninote.pro.core.model.EraserMode
import kotlin.math.hypot

/**
 * Receives eraser input in page space (mm). The ink view only draws the cursor; hit-testing and edits live behind
 * this interface so the whole drag can be one undo step. Phase 4 wires it to PartialEraserSplitter (PARTIAL) and
 * whole-stroke removal (STROKE).
 */
interface EraserHandler {
    /** Pen touched down. All coordinates are page-space mm. */
    fun onEraseBegin(mode: EraserMode, radiusMm: Float, x: Float, y: Float)

    /** Pen swept from the previous sample to the new one; the swept capsule has radius [radiusMm]. */
    fun onEraseMove(mode: EraserMode, radiusMm: Float, fromX: Float, fromY: Float, toX: Float, toY: Float)

    /** Pen lifted or the gesture was cancelled; the handler closes its undo group here. */
    fun onEraseEnd(cancelled: Boolean)
}

/** Handler that ignores everything; used until the Phase 4 eraser is attached. */
object NoOpEraserHandler : EraserHandler {
    override fun onEraseBegin(mode: EraserMode, radiusMm: Float, x: Float, y: Float) = Unit
    override fun onEraseMove(mode: EraserMode, radiusMm: Float, fromX: Float, fromY: Float, toX: Float, toY: Float) = Unit
    override fun onEraseEnd(cancelled: Boolean) = Unit
}

/**
 * Live eraser feedback on the front buffer: an outlined radius ring at the pen (also shown while hovering) and a
 * soft swept trail behind it. Trail samples fade out by age. Thread model matches [LaserTrailController].
 */
class EraserCursorOverlay {

    private val lock = Any()
    private var tx = FloatArray(MAX_TRAIL)
    private var ty = FloatArray(MAX_TRAIL)
    private var tTime = LongArray(MAX_TRAIL)
    private var head = 0
    private var size = 0

    private var cursorX = 0f
    private var cursorY = 0f
    private var radius = 2.5f
    private var hasCursor = false

    private val ring = Paint()
    private val trail = Paint()
    private val trailPath = Path()

    val isActive: Boolean get() = synchronized(lock) { hasCursor || size > 0 }

    fun beginSweep(x: Float, y: Float, radiusMm: Float, nowMs: Long) {
        synchronized(lock) {
            head = 0
            size = 0
            radius = radiusMm
            push(x, y, nowMs)
            moveCursorLocked(x, y)
        }
    }

    fun sweepTo(x: Float, y: Float, nowMs: Long) {
        synchronized(lock) {
            push(x, y, nowMs)
            moveCursorLocked(x, y)
        }
    }

    /** Hover or pre-touch position: ring only. */
    fun hoverAt(x: Float, y: Float, radiusMm: Float) {
        synchronized(lock) {
            radius = radiusMm
            moveCursorLocked(x, y)
        }
    }

    fun hideCursor() {
        synchronized(lock) { hasCursor = false }
    }

    fun clear() {
        synchronized(lock) {
            hasCursor = false
            size = 0
            head = 0
        }
    }

    /** Draws ring + trail. Returns true while the trail is still fading so the frame loop should continue. */
    fun draw(canvas: Canvas, shaders: BrushShaderFactory, ctx: InkRenderContext, nowMs: Long): Boolean {
        var cx = 0f
        var cy = 0f
        var r = 0f
        var show = false
        var animating = false
        trailPath.rewind()
        synchronized(lock) {
            cx = cursorX; cy = cursorY; r = radius; show = hasCursor
            // Drop samples older than the trail lifetime.
            while (size > 0 && nowMs - tTime[index(0)] > TRAIL_MS) {
                head = (head + 1) % MAX_TRAIL
                size--
            }
            var prevX = 0f
            var prevY = 0f
            for (i in 0 until size) {
                val k = index(i)
                if (i == 0) {
                    trailPath.addCircle(tx[k], ty[k], r, Path.Direction.CW)
                } else {
                    addCapsule(trailPath, prevX, prevY, tx[k], ty[k], r)
                }
                prevX = tx[k]
                prevY = ty[k]
            }
            animating = size > 0
        }
        if (animating) {
            shaders.eraserTrail(trail, ctx)
            canvas.drawPath(trailPath, trail)
        }
        if (show) {
            shaders.eraserRing(ring, ctx)
            canvas.drawCircle(cx, cy, r, ring)
        }
        return animating || show
    }

    private fun addCapsule(out: Path, x0: Float, y0: Float, x1: Float, y1: Float, r: Float) {
        val dx = x1 - x0
        val dy = y1 - y0
        val len = hypot(dx, dy)
        out.addCircle(x1, y1, r, Path.Direction.CW)
        if (len < 1e-4f) return
        val nx = -dy / len * r
        val ny = dx / len * r
        // Clockwise on screen (y-down), matching Path.Direction.CW circles so winding fill unions instead of cancelling.
        out.moveTo(x0 - nx, y0 - ny)
        out.lineTo(x1 - nx, y1 - ny)
        out.lineTo(x1 + nx, y1 + ny)
        out.lineTo(x0 + nx, y0 + ny)
        out.close()
    }

    private fun moveCursorLocked(x: Float, y: Float) {
        cursorX = x
        cursorY = y
        hasCursor = true
    }

    private fun index(i: Int): Int = (head + i) % MAX_TRAIL

    private fun push(x: Float, y: Float, nowMs: Long) {
        if (size == MAX_TRAIL) {
            head = (head + 1) % MAX_TRAIL
            size--
        }
        val k = index(size)
        tx[k] = x
        ty[k] = y
        tTime[k] = nowMs
        size++
    }

    private companion object {
        const val MAX_TRAIL = 64
        const val TRAIL_MS = 350L
    }
}
