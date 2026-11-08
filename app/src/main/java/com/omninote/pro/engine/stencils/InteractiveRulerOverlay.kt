package com.omninote.pro.engine.stencils

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import com.omninote.pro.engine.ink.GeometricSnapClassifier
import com.omninote.pro.engine.ink.InkRenderContext
import com.omninote.pro.engine.input.ViewportTransform
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

enum class InstrumentKind { RULER, PROTRACTOR, COMPASS }

/**
 * Floating translucent geometry instruments: straight ruler, 180/360 degree protractor and drawing compass.
 *
 *  - Finger manipulation: touching an instrument's body moves it; a second finger rotates it (0, 15, 30, 45, 60, 90
 *    degree magnetic detents) and resizes its length or radius.
 *  - Stylus constraint: a pen stroke that starts within 8dp of the ruler edge, protractor rim or baseline, or the
 *    compass pencil tip is locked onto that exact line or circle (see [beginStylus], [constrainStylusPoint]) while the
 *    swept length or angle is shown live.
 *
 * All geometry is in page millimetres. Mutations happen on the UI thread and are published as immutable snapshots
 * that [draw] reads on the render thread.
 */
class InteractiveRulerOverlay(
    private val density: Float,
    private val gridCellMm: () -> Float = { 5f },
    private val requestRedraw: () -> Unit
) {

    // ---- immutable poses (render thread reads) ----
    data class RulerPose(val visible: Boolean, val cx: Float, val cy: Float, val angleDeg: Float, val lengthMm: Float)
    data class ProtractorPose(val visible: Boolean, val cx: Float, val cy: Float, val angleDeg: Float, val radiusMm: Float, val full360: Boolean)
    data class CompassPose(val visible: Boolean, val px: Float, val py: Float, val angleDeg: Float, val radiusMm: Float)
    private class Snapshot(
        val ruler: RulerPose,
        val protractor: ProtractorPose,
        val compass: CompassPose,
        val hud: String?,
        val hudAnchor: FloatArray?,
        val sweepStartDeg: Float,
        val sweepDeg: Float
    )

    private var ruler = RulerPose(false, 105f, 120f, 0f, 150f)
    private var protractor = ProtractorPose(false, 105f, 160f, 0f, 45f, false)
    private var compass = CompassPose(false, 105f, 150f, -30f, 35f)
    private var hud: String? = null
    private var hudAnchor: FloatArray? = null
    private var sweepStart = 0f
    private var sweepDeg = 0f

    @Volatile
    private var snapshot: Snapshot = makeSnapshot()

    val hasVisible: Boolean get() = ruler.visible || protractor.visible || compass.visible

    fun rulerPose() = ruler
    fun protractorPose() = protractor
    fun compassPose() = compass

    fun setVisible(kind: InstrumentKind, visible: Boolean) {
        when (kind) {
            InstrumentKind.RULER -> ruler = ruler.copy(visible = visible)
            InstrumentKind.PROTRACTOR -> protractor = protractor.copy(visible = visible)
            InstrumentKind.COMPASS -> compass = compass.copy(visible = visible)
        }
        publish()
    }

    fun toggle(kind: InstrumentKind) {
        setVisible(
            kind,
            when (kind) {
                InstrumentKind.RULER -> !ruler.visible
                InstrumentKind.PROTRACTOR -> !protractor.visible
                InstrumentKind.COMPASS -> !compass.visible
            }
        )
    }

    /** Switches the protractor between 180 and 360 degree faces. */
    fun setProtractorFull(full: Boolean) {
        protractor = protractor.copy(full360 = full)
        publish()
    }

    /** Places instruments around [cx], [cy] (page mm), e.g. the viewport centre, the first time they are shown. */
    fun centerOn(cx: Float, cy: Float) {
        ruler = ruler.copy(cx = cx, cy = cy - 20f)
        protractor = protractor.copy(cx = cx, cy = cy + 25f)
        compass = compass.copy(px = cx, py = cy + 20f)
        publish()
    }

    // =====================================================================================
    // Finger manipulation
    // =====================================================================================

    private var captured: InstrumentKind? = null
    private var movedFirstId = -1
    private var prevMidX = 0f
    private var prevMidY = 0f
    private var prevDist = 0f
    private var prevAngle = 0f
    private var rawAngleDeg = 0f
    private var haveBaseline = false

    /** Set by the host so the toolbar state follows the on-instrument close button. */
    var onClosed: ((InstrumentKind) -> Unit)? = null

    private var knobKind: InstrumentKind? = null
    private var knobPointerId = -1

    /**
     * Returns true if the event belongs to an instrument manipulation and must not reach other handlers.
     *
     * Knobs (rotate/resize, close) answer to a finger OR the stylus, so one pointer can do everything. The body drags
     * with a finger; a stylus on the body is left alone because it draws along the instrument edge.
     */
    fun onTouch(event: MotionEvent, viewport: ViewportTransform): Boolean {
        val action = event.actionMasked
        val activeKnob = knobKind
        if (activeKnob != null) {
            val idx = event.findPointerIndex(knobPointerId)
            when (action) {
                MotionEvent.ACTION_MOVE -> if (idx >= 0) {
                    val out = FloatArray(2)
                    viewport.screenToPage(event.getX(idx), event.getY(idx), out)
                    dragKnob(activeKnob, out[0], out[1])
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_UP -> {
                    if (action != MotionEvent.ACTION_POINTER_UP || event.getPointerId(event.actionIndex) == knobPointerId) {
                        knobKind = null
                        knobPointerId = -1
                        clearHud()
                    }
                }
            }
            return true
        }
        if (action == MotionEvent.ACTION_DOWN && hasVisible) {
            val out = FloatArray(2)
            viewport.screenToPage(event.x, event.y, out)
            val hitR = max(KNOB_HIT_MM, KNOB_HIT_PX / viewport.pxPerMm)
            for (kind in InstrumentKind.values()) {
                if (!isVisible(kind)) continue
                val close = closeKnob(kind)
                if (hypot(out[0] - close[0], out[1] - close[1]) <= hitR) {
                    setVisible(kind, false)
                    onClosed?.invoke(kind)
                    return true
                }
                val rot = rotateKnob(kind)
                if (hypot(out[0] - rot[0], out[1] - rot[1]) <= hitR) {
                    knobKind = kind
                    knobPointerId = event.getPointerId(0)
                    dragKnob(kind, out[0], out[1])
                    return true
                }
            }
        }
        if (action == MotionEvent.ACTION_DOWN) {
            if (!hasVisible || event.getToolType(0) != MotionEvent.TOOL_TYPE_FINGER) return false
            val out = FloatArray(2)
            viewport.screenToPage(event.x, event.y, out)
            val hit = hitBody(out[0], out[1]) ?: return false
            captured = hit
            movedFirstId = event.getPointerId(0)
            haveBaseline = false
            rawAngleDeg = angleOf(hit)
            setBaseline(event, viewport)
            return true
        }
        val kind = captured ?: return false
        when (action) {
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                setBaseline(event, viewport, excludeIndex = if (action == MotionEvent.ACTION_POINTER_UP) event.actionIndex else -1)
            }
            MotionEvent.ACTION_MOVE -> apply(kind, event, viewport)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                captured = null
                haveBaseline = false
                clearHud()
            }
        }
        return true
    }

    private fun isVisible(kind: InstrumentKind): Boolean = when (kind) {
        InstrumentKind.RULER -> ruler.visible
        InstrumentKind.PROTRACTOR -> protractor.visible
        InstrumentKind.COMPASS -> compass.visible
    }

    private fun rotateKnob(kind: InstrumentKind): FloatArray = when (kind) {
        InstrumentKind.RULER -> {
            val a = Math.toRadians(ruler.angleDeg.toDouble())
            val d = ruler.lengthMm / 2f + KNOB_GAP_MM
            floatArrayOf(ruler.cx + d * cos(a).toFloat(), ruler.cy + d * sin(a).toFloat())
        }
        InstrumentKind.PROTRACTOR -> {
            val a = Math.toRadians(protractor.angleDeg.toDouble())
            val d = protractor.radiusMm + KNOB_GAP_MM
            floatArrayOf(protractor.cx + d * cos(a).toFloat(), protractor.cy + d * sin(a).toFloat())
        }
        InstrumentKind.COMPASS -> compassTip()
    }

    private fun closeKnob(kind: InstrumentKind): FloatArray = when (kind) {
        InstrumentKind.RULER -> {
            val a = Math.toRadians(ruler.angleDeg.toDouble())
            val d = ruler.lengthMm / 2f + KNOB_GAP_MM
            floatArrayOf(ruler.cx - d * cos(a).toFloat(), ruler.cy - d * sin(a).toFloat())
        }
        InstrumentKind.PROTRACTOR -> {
            val a = Math.toRadians(protractor.angleDeg.toDouble())
            val d = protractor.radiusMm + KNOB_GAP_MM
            floatArrayOf(protractor.cx - d * cos(a).toFloat(), protractor.cy - d * sin(a).toFloat())
        }
        InstrumentKind.COMPASS -> floatArrayOf(compass.px - KNOB_GAP_MM, compass.py - KNOB_GAP_MM)
    }

    /** One pointer on the rotate/resize knob: the instrument swings to point at the pointer and grows to reach it. */
    private fun dragKnob(kind: InstrumentKind, x: Float, y: Float) {
        when (kind) {
            InstrumentKind.RULER -> {
                val dx = x - ruler.cx
                val dy = y - ruler.cy
                val deg = snapDeg(Math.toDegrees(atan2(dy, dx).toDouble()).toFloat())
                ruler = ruler.copy(angleDeg = deg, lengthMm = (2f * (hypot(dx, dy) - KNOB_GAP_MM)).coerceIn(RULER_MIN_MM, RULER_MAX_MM))
                hud = fmtLen(ruler.lengthMm) + "  ∠ " + fmtDeg(-ruler.angleDeg)
            }
            InstrumentKind.PROTRACTOR -> {
                val dx = x - protractor.cx
                val dy = y - protractor.cy
                val deg = snapDeg(Math.toDegrees(atan2(dy, dx).toDouble()).toFloat())
                protractor = protractor.copy(angleDeg = deg, radiusMm = (hypot(dx, dy) - KNOB_GAP_MM).coerceIn(PROTRACTOR_MIN_MM, PROTRACTOR_MAX_MM))
                hud = "r " + fmtLen(protractor.radiusMm) + "  ∠ " + fmtDeg(-protractor.angleDeg)
            }
            InstrumentKind.COMPASS -> {
                val dx = x - compass.px
                val dy = y - compass.py
                val deg = snapDeg(Math.toDegrees(atan2(dy, dx).toDouble()).toFloat())
                compass = compass.copy(angleDeg = deg, radiusMm = hypot(dx, dy).coerceIn(COMPASS_MIN_MM, COMPASS_MAX_MM))
                hud = "r " + fmtLen(compass.radiusMm)
            }
        }
        hudAnchor = anchorFor(kind)
        publish()
    }

    private fun snapDeg(raw: Float): Float =
        Math.toDegrees(GeometricSnapClassifier.snapAngle(Math.toRadians(raw.toDouble()).toFloat()).toDouble()).toFloat()

    private fun setBaseline(event: MotionEvent, viewport: ViewportTransform, excludeIndex: Int = -1) {
        val idx = IntArray(2)
        var n = 0
        for (i in 0 until event.pointerCount) {
            if (i == excludeIndex) continue
            if (n < 2) idx[n++] = i
        }
        if (n == 0) {
            haveBaseline = false
            return
        }
        val x0 = event.getX(idx[0])
        val y0 = event.getY(idx[0])
        if (n == 1) {
            prevMidX = x0
            prevMidY = y0
            prevDist = 0f
        } else {
            val x1 = event.getX(idx[1])
            val y1 = event.getY(idx[1])
            prevMidX = (x0 + x1) / 2f
            prevMidY = (y0 + y1) / 2f
            prevDist = hypot(x1 - x0, y1 - y0)
            prevAngle = atan2(y1 - y0, x1 - x0)
        }
        haveBaseline = true
        rawAngleDeg = captured?.let { angleOf(it) } ?: 0f
    }

    private fun apply(kind: InstrumentKind, event: MotionEvent, viewport: ViewportTransform) {
        if (!haveBaseline) return
        val n = min(event.pointerCount, 2)
        val x0 = event.getX(0)
        val y0 = event.getY(0)
        var midX = x0
        var midY = y0
        var dist = 0f
        var angle = prevAngle
        if (n >= 2) {
            val x1 = event.getX(1)
            val y1 = event.getY(1)
            midX = (x0 + x1) / 2f
            midY = (y0 + y1) / 2f
            dist = hypot(x1 - x0, y1 - y0)
            angle = atan2(y1 - y0, x1 - x0)
        }
        val pxPerMm = viewport.pxPerMm
        // Screen pan expressed in page space (rotate by -viewport rotation).
        val cosR = cos(Math.toRadians(viewport.rotationDeg.toDouble())).toFloat()
        val sinR = sin(Math.toRadians(viewport.rotationDeg.toDouble())).toFloat()
        val sdx = midX - prevMidX
        val sdy = midY - prevMidY
        val dx = (sdx * cosR + sdy * sinR) / pxPerMm
        val dy = (-sdx * sinR + sdy * cosR) / pxPerMm

        var scale = 1f
        var dAngle = 0f
        if (n >= 2 && prevDist > 1f) {
            scale = dist / prevDist
            dAngle = Math.toDegrees(wrap(angle - prevAngle).toDouble()).toFloat()
        }
        prevMidX = midX
        prevMidY = midY
        if (n >= 2) {
            prevDist = dist
            prevAngle = angle
        }

        if (dAngle != 0f) rawAngleDeg += dAngle
        val snapped = Math.toDegrees(GeometricSnapClassifier.snapAngle(Math.toRadians(rawAngleDeg.toDouble()).toFloat()).toDouble()).toFloat()

        when (kind) {
            InstrumentKind.RULER -> ruler = ruler.copy(
                cx = ruler.cx + dx, cy = ruler.cy + dy,
                angleDeg = if (n >= 2) snapped else ruler.angleDeg,
                lengthMm = (ruler.lengthMm * scale).coerceIn(RULER_MIN_MM, RULER_MAX_MM)
            )
            InstrumentKind.PROTRACTOR -> protractor = protractor.copy(
                cx = protractor.cx + dx, cy = protractor.cy + dy,
                angleDeg = if (n >= 2) snapped else protractor.angleDeg,
                radiusMm = (protractor.radiusMm * scale).coerceIn(PROTRACTOR_MIN_MM, PROTRACTOR_MAX_MM)
            )
            InstrumentKind.COMPASS -> compass = compass.copy(
                px = compass.px + dx, py = compass.py + dy,
                angleDeg = if (n >= 2) snapped else compass.angleDeg,
                radiusMm = (compass.radiusMm * scale).coerceIn(COMPASS_MIN_MM, COMPASS_MAX_MM)
            )
        }
        hud = when (kind) {
            InstrumentKind.RULER -> fmtLen(ruler.lengthMm) + "  ∠ " + fmtDeg(-ruler.angleDeg)
            InstrumentKind.PROTRACTOR -> "r " + fmtLen(protractor.radiusMm) + "  ∠ " + fmtDeg(-protractor.angleDeg)
            InstrumentKind.COMPASS -> "r " + fmtLen(compass.radiusMm)
        }
        hudAnchor = anchorFor(kind)
        publish()
    }

    private fun angleOf(kind: InstrumentKind): Float = when (kind) {
        InstrumentKind.RULER -> ruler.angleDeg
        InstrumentKind.PROTRACTOR -> protractor.angleDeg
        InstrumentKind.COMPASS -> compass.angleDeg
    }

    private fun anchorFor(kind: InstrumentKind): FloatArray = when (kind) {
        InstrumentKind.RULER -> floatArrayOf(ruler.cx, ruler.cy - RULER_WIDTH_MM / 2f - 4f)
        InstrumentKind.PROTRACTOR -> floatArrayOf(protractor.cx, protractor.cy - protractor.radiusMm - 4f)
        InstrumentKind.COMPASS -> floatArrayOf(compass.px, compass.py - compass.radiusMm - 6f)
    }

    /** Called when the finger sequence ends so the readout disappears after a manipulation. */
    fun clearHud() {
        if (hud != null && stylus == null) {
            hud = null
            hudAnchor = null
            publish()
        }
    }

    private fun hitBody(x: Float, y: Float): InstrumentKind? {
        if (compass.visible && compassHit(x, y)) return InstrumentKind.COMPASS
        if (protractor.visible && protractorHit(x, y)) return InstrumentKind.PROTRACTOR
        if (ruler.visible && rulerHit(x, y)) return InstrumentKind.RULER
        return null
    }

    private fun rulerHit(x: Float, y: Float): Boolean {
        val a = Math.toRadians(ruler.angleDeg.toDouble())
        val ux = cos(a).toFloat()
        val uy = sin(a).toFloat()
        val along = (x - ruler.cx) * ux + (y - ruler.cy) * uy
        val across = -(x - ruler.cx) * uy + (y - ruler.cy) * ux
        return abs(along) <= ruler.lengthMm / 2f && abs(across) <= RULER_WIDTH_MM / 2f
    }

    private fun protractorHit(x: Float, y: Float): Boolean {
        val a = Math.toRadians(protractor.angleDeg.toDouble())
        val ux = cos(a).toFloat()
        val uy = sin(a).toFloat()
        val dx = x - protractor.cx
        val dy = y - protractor.cy
        if (hypot(dx, dy) > protractor.radiusMm) return false
        if (protractor.full360) return true
        val across = -dx * uy + dy * ux
        return across <= 0f
    }

    private fun compassHit(x: Float, y: Float): Boolean {
        val tip = compassTip()
        val hinge = compassHinge()
        val reach = 6f
        return segmentDistance(x, y, compass.px, compass.py, hinge[0], hinge[1]) <= reach ||
            segmentDistance(x, y, hinge[0], hinge[1], tip[0], tip[1]) <= reach
    }

    private fun compassTip(): FloatArray {
        val a = Math.toRadians(compass.angleDeg.toDouble())
        return floatArrayOf(compass.px + compass.radiusMm * cos(a).toFloat(), compass.py + compass.radiusMm * sin(a).toFloat())
    }

    private fun compassHinge(): FloatArray {
        val tip = compassTip()
        val mx = (compass.px + tip[0]) / 2f
        val my = (compass.py + tip[1]) / 2f
        val legs = max(compass.radiusMm * 1.15f, 24f)
        val half = compass.radiusMm / 2f
        val h = sqrt(max(legs * legs - half * half, 1f))
        val a = Math.toRadians(compass.angleDeg.toDouble())
        // Perpendicular to the pivot-tip line, on the screen-up side.
        var nx = -sin(a).toFloat()
        var ny = cos(a).toFloat()
        if (ny > 0f) { nx = -nx; ny = -ny }
        return floatArrayOf(mx + nx * h, my + ny * h)
    }

    // =====================================================================================
    // Stylus constraint
    // =====================================================================================

    private sealed interface Lock {
        class Line(val ox: Float, val oy: Float, val ux: Float, val uy: Float, val t0: Float, var t: Float) : Lock
        class Circle(val cx: Float, val cy: Float, val r: Float, val compassLock: Boolean, val start: Float, var last: Float, var accum: Float) : Lock
    }

    private var stylus: Lock? = null

    /**
     * Call when a pen stroke begins at ([x], [y]) page mm. Returns true if the whole stroke will be locked to an
     * instrument; the first point is already snapped, retrieve it with [constrainStylusPoint].
     */
    fun beginStylus(x: Float, y: Float, pxPerMm: Float): Boolean {
        stylus = null
        if (!hasVisible) return false
        val zone = 8f * density / pxPerMm

        if (compass.visible) {
            val tip = compassTip()
            if (hypot(x - tip[0], y - tip[1]) <= zone) {
                val a = atan2(y - compass.py, x - compass.px)
                stylus = Lock.Circle(compass.px, compass.py, compass.radiusMm, true, a, a, 0f)
                hudAnchor = anchorFor(InstrumentKind.COMPASS)
                return true
            }
        }
        if (protractor.visible) {
            val d = hypot(x - protractor.cx, y - protractor.cy)
            if (abs(d - protractor.radiusMm) <= zone) {
                val a = atan2(y - protractor.cy, x - protractor.cx)
                stylus = Lock.Circle(protractor.cx, protractor.cy, protractor.radiusMm, false, a, a, 0f)
                hudAnchor = anchorFor(InstrumentKind.PROTRACTOR)
                return true
            }
        }
        if (ruler.visible) {
            val a = Math.toRadians(ruler.angleDeg.toDouble())
            val ux = cos(a).toFloat()
            val uy = sin(a).toFloat()
            val along = (x - ruler.cx) * ux + (y - ruler.cy) * uy
            val across = -(x - ruler.cx) * uy + (y - ruler.cy) * ux
            if (abs(along) <= ruler.lengthMm / 2f + zone) {
                val edge = RULER_WIDTH_MM / 2f
                val side = when {
                    abs(across - edge) <= zone -> edge
                    abs(across + edge) <= zone -> -edge
                    else -> null
                }
                if (side != null) {
                    val ox = ruler.cx - uy * side
                    val oy = ruler.cy + ux * side
                    stylus = Lock.Line(ox, oy, ux, uy, along, along)
                    hudAnchor = anchorFor(InstrumentKind.RULER)
                    return true
                }
            }
        }
        if (protractor.visible) {
            val a = Math.toRadians(protractor.angleDeg.toDouble())
            val ux = cos(a).toFloat()
            val uy = sin(a).toFloat()
            val along = (x - protractor.cx) * ux + (y - protractor.cy) * uy
            val across = -(x - protractor.cx) * uy + (y - protractor.cy) * ux
            if (abs(across) <= zone && abs(along) <= protractor.radiusMm + zone) {
                stylus = Lock.Line(protractor.cx, protractor.cy, ux, uy, along, along)
                hudAnchor = anchorFor(InstrumentKind.PROTRACTOR)
                return true
            }
        }
        return false
    }

    /**
     * Maps a stylus point onto the active lock (exact line or circle) and updates the live length / sweep readout.
     * Returns false, leaving [out] untouched, when the stroke is not locked.
     */
    fun constrainStylusPoint(x: Float, y: Float, out: FloatArray): Boolean {
        when (val lock = stylus ?: return false) {
            is Lock.Line -> {
                val t = (x - lock.ox) * lock.ux + (y - lock.oy) * lock.uy
                lock.t = t
                out[0] = lock.ox + lock.ux * t
                out[1] = lock.oy + lock.uy * t
                val len = abs(t - lock.t0)
                hud = fmtLen(len)
                sweepDeg = 0f
            }
            is Lock.Circle -> {
                val a = atan2(y - lock.cy, x - lock.cx)
                var d = a - lock.last
                if (d > PI) d -= (2 * PI).toFloat()
                if (d < -PI) d += (2 * PI).toFloat()
                lock.accum += d
                lock.last = a
                out[0] = lock.cx + lock.r * cos(a)
                out[1] = lock.cy + lock.r * sin(a)
                val deg = Math.toDegrees(lock.accum.toDouble()).toFloat()
                hud = "Δθ " + fmtDeg(abs(deg)) + "  r " + fmtLen(lock.r)
                sweepStart = Math.toDegrees(lock.start.toDouble()).toFloat()
                sweepDeg = deg
                if (lock.compassLock) compass = compass.copy(angleDeg = Math.toDegrees(a.toDouble()).toFloat())
            }
        }
        publish()
        return true
    }

    val isStylusLocked: Boolean get() = stylus != null

    fun endStylus() {
        val had = stylus != null
        stylus = null
        if (had) {
            hud = null
            hudAnchor = null
            sweepDeg = 0f
            publish()
        }
    }

    // =====================================================================================
    // Rendering (render thread; canvas already has the viewport matrix applied)
    // =====================================================================================

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hudPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    fun draw(canvas: Canvas, ctx: InkRenderContext) {
        val s = snapshot
        val dark = ctx.paperIsDark
        val px = 1f / ctx.pxPerMm
        val ink = if (dark) 0xDDEADFD0.toInt() else 0xCC5B4636.toInt()
        if (s.ruler.visible) drawRuler(canvas, s.ruler, dark, ink, px)
        if (s.protractor.visible) drawProtractor(canvas, s.protractor, dark, ink, px)
        if (s.compass.visible) drawCompass(canvas, s.compass, s.sweepStartDeg, s.sweepDeg, dark, ink, px)
        drawKnobs(canvas, s, dark, px)
        val text = s.hud
        val at = s.hudAnchor
        if (text != null && at != null) drawHud(canvas, text, at[0], at[1], ctx.pxPerMm)
    }

    private val knobPath = android.graphics.Path()

    private fun drawKnobs(canvas: Canvas, s: Snapshot, dark: Boolean, px: Float) {
        if (s.ruler.visible) {
            val a = Math.toRadians(s.ruler.angleDeg.toDouble())
            val d = s.ruler.lengthMm / 2f + KNOB_GAP_MM
            val ux = cos(a).toFloat()
            val uy = sin(a).toFloat()
            drawRotateKnob(canvas, s.ruler.cx + d * ux, s.ruler.cy + d * uy, dark, px)
            drawCloseKnob(canvas, s.ruler.cx - d * ux, s.ruler.cy - d * uy, dark, px)
        }
        if (s.protractor.visible) {
            val a = Math.toRadians(s.protractor.angleDeg.toDouble())
            val d = s.protractor.radiusMm + KNOB_GAP_MM
            val ux = cos(a).toFloat()
            val uy = sin(a).toFloat()
            drawRotateKnob(canvas, s.protractor.cx + d * ux, s.protractor.cy + d * uy, dark, px)
            drawCloseKnob(canvas, s.protractor.cx - d * ux, s.protractor.cy - d * uy, dark, px)
        }
        if (s.compass.visible) {
            val a = Math.toRadians(s.compass.angleDeg.toDouble())
            val tx = s.compass.px + s.compass.radiusMm * cos(a).toFloat()
            val ty = s.compass.py + s.compass.radiusMm * sin(a).toFloat()
            drawRotateKnob(canvas, tx, ty, dark, px)
            drawCloseKnob(canvas, s.compass.px - KNOB_GAP_MM, s.compass.py - KNOB_GAP_MM, dark, px)
        }
    }

    private fun knobRadius(px: Float) = max(KNOB_R_MM, 15f * px)

    private fun drawRotateKnob(canvas: Canvas, cx: Float, cy: Float, dark: Boolean, px: Float) {
        val r = knobRadius(px)
        fillPaint.style = Paint.Style.FILL
        fillPaint.color = 0xFFD9845B.toInt()
        canvas.drawCircle(cx, cy, r, fillPaint)
        linePaint.style = Paint.Style.STROKE
        linePaint.strokeWidth = 1.6f * px
        linePaint.color = if (dark) 0xFFFFF1DC.toInt() else 0xFFFFFFFF.toInt()
        rect.set(cx - r * 0.55f, cy - r * 0.55f, cx + r * 0.55f, cy + r * 0.55f)
        canvas.drawArc(rect, 30f, 290f, false, linePaint)
        // arrow head at the arc's open end
        val ex = cx + r * 0.55f * cos(Math.toRadians(30.0)).toFloat()
        val ey = cy + r * 0.55f * sin(Math.toRadians(30.0)).toFloat()
        knobPath.reset()
        knobPath.moveTo(ex - r * 0.32f, ey - r * 0.05f)
        knobPath.lineTo(ex + r * 0.22f, ey - r * 0.2f)
        knobPath.lineTo(ex + r * 0.05f, ey + r * 0.34f)
        knobPath.close()
        fillPaint.color = linePaint.color
        canvas.drawPath(knobPath, fillPaint)
    }

    private fun drawCloseKnob(canvas: Canvas, cx: Float, cy: Float, dark: Boolean, px: Float) {
        val r = knobRadius(px)
        fillPaint.style = Paint.Style.FILL
        fillPaint.color = if (dark) 0xFF4A3F38.toInt() else 0xFF6B5A4C.toInt()
        canvas.drawCircle(cx, cy, r, fillPaint)
        linePaint.style = Paint.Style.STROKE
        linePaint.strokeWidth = 1.8f * px
        linePaint.strokeCap = Paint.Cap.ROUND
        linePaint.color = 0xFFFFF1DC.toInt()
        val k = r * 0.38f
        canvas.drawLine(cx - k, cy - k, cx + k, cy + k, linePaint)
        canvas.drawLine(cx - k, cy + k, cx + k, cy - k, linePaint)
        linePaint.strokeCap = Paint.Cap.BUTT
    }

    private fun glass(dark: Boolean) = if (dark) 0x30FFFFFF else 0x40FFF6E8
    private fun rim(dark: Boolean) = if (dark) 0x90EADFD0.toInt() else 0xAAB48C62.toInt()

    private fun drawRuler(canvas: Canvas, r: RulerPose, dark: Boolean, ink: Int, px: Float) {
        canvas.save()
        canvas.translate(r.cx, r.cy)
        canvas.rotate(r.angleDeg)
        val l = r.lengthMm
        val w = RULER_WIDTH_MM
        rect.set(-l / 2f, -w / 2f, l / 2f, w / 2f)
        fillPaint.style = Paint.Style.FILL
        fillPaint.color = glass(dark)
        canvas.drawRoundRect(rect, 2f, 2f, fillPaint)
        linePaint.style = Paint.Style.STROKE
        linePaint.strokeWidth = 1.2f * px
        linePaint.color = rim(dark)
        canvas.drawRoundRect(rect, 2f, 2f, linePaint)

        tickPaint.style = Paint.Style.STROKE
        tickPaint.color = ink
        tickPaint.strokeWidth = 0.9f * px
        textPaint.color = ink
        textPaint.textSize = 2.8f
        textPaint.textAlign = Paint.Align.CENTER
        // Millimetre scale along the top edge, zero at the left end.
        val mmCount = l.toInt()
        for (i in 0..mmCount) {
            val x = -l / 2f + i
            val len = if (i % 10 == 0) 5f else if (i % 5 == 0) 3.4f else 2f
            canvas.drawLine(x, -w / 2f, x, -w / 2f + len, tickPaint)
            if (i % 10 == 0 && i > 0 && i < mmCount - 2) canvas.drawText((i / 10).toString(), x, -w / 2f + 9f, textPaint)
        }
        // Grid-unit scale along the bottom edge.
        val cell = max(gridCellMm(), 0.5f)
        var gx = 0f
        var n = 0
        while (gx <= l) {
            val x = -l / 2f + gx
            canvas.drawLine(x, w / 2f, x, w / 2f - (if (n % 5 == 0) 4f else 2.4f), tickPaint)
            gx += cell
            n++
        }
        canvas.restore()
    }

    private fun drawProtractor(canvas: Canvas, p: ProtractorPose, dark: Boolean, ink: Int, px: Float) {
        canvas.save()
        canvas.translate(p.cx, p.cy)
        canvas.rotate(p.angleDeg)
        val r = p.radiusMm
        fillPaint.style = Paint.Style.FILL
        fillPaint.color = glass(dark)
        rect.set(-r, -r, r, r)
        if (p.full360) canvas.drawCircle(0f, 0f, r, fillPaint) else canvas.drawArc(rect, 180f, 180f, true, fillPaint)
        linePaint.style = Paint.Style.STROKE
        linePaint.strokeWidth = 1.2f * px
        linePaint.color = rim(dark)
        if (p.full360) canvas.drawCircle(0f, 0f, r, linePaint) else {
            canvas.drawArc(rect, 180f, 180f, false, linePaint)
            canvas.drawLine(-r, 0f, r, 0f, linePaint)
        }
        canvas.drawCircle(0f, 0f, 1.2f, linePaint)

        tickPaint.style = Paint.Style.STROKE
        tickPaint.color = ink
        tickPaint.strokeWidth = 0.8f * px
        textPaint.color = ink
        textPaint.textSize = 2.6f
        textPaint.textAlign = Paint.Align.CENTER
        val span = if (p.full360) 360 else 180
        for (d in 0 until span + (if (p.full360) 0 else 1)) {
            // Screen-up half: degrees increase counter-clockwise visually, so local angle is -d.
            val a = Math.toRadians(-d.toDouble())
            val len = if (d % 10 == 0) 4.5f else if (d % 5 == 0) 3.2f else 1.8f
            val c = cos(a).toFloat()
            val s = sin(a).toFloat()
            canvas.drawLine(r * c, r * s, (r - len) * c, (r - len) * s, tickPaint)
            if (d % 30 == 0 && r > 28f) {
                val tr = r - 8.5f
                canvas.drawText(d.toString(), tr * c, tr * s + 0.9f, textPaint)
            }
        }
        canvas.restore()
    }

    private fun drawCompass(canvas: Canvas, c: CompassPose, sweepStartDeg: Float, sweepDeg: Float, dark: Boolean, ink: Int, px: Float) {
        val a = Math.toRadians(c.angleDeg.toDouble())
        val tx = c.px + c.radiusMm * cos(a).toFloat()
        val ty = c.py + c.radiusMm * sin(a).toFloat()
        val mx = (c.px + tx) / 2f
        val my = (c.py + ty) / 2f
        val legs = max(c.radiusMm * 1.15f, 24f)
        val half = c.radiusMm / 2f
        val h = sqrt(max(legs * legs - half * half, 1f))
        var nx = -sin(a).toFloat()
        var ny = cos(a).toFloat()
        if (ny > 0f) { nx = -nx; ny = -ny }
        val hx = mx + nx * h
        val hy = my + ny * h

        // Trace circle guide and the arc swept by the current stroke.
        linePaint.style = Paint.Style.STROKE
        linePaint.strokeWidth = 0.8f * px
        linePaint.color = if (dark) 0x66EADFD0 else 0x66B48C62
        canvas.drawCircle(c.px, c.py, c.radiusMm, linePaint)
        if (sweepDeg != 0f) {
            linePaint.strokeWidth = 2.2f * px
            linePaint.color = ACCENT
            rect.set(c.px - c.radiusMm, c.py - c.radiusMm, c.px + c.radiusMm, c.py + c.radiusMm)
            canvas.drawArc(rect, sweepStartDeg, sweepDeg, false, linePaint)
        }

        linePaint.strokeWidth = 2.4f * px
        linePaint.color = rim(dark)
        linePaint.strokeCap = Paint.Cap.ROUND
        canvas.drawLine(c.px, c.py, hx, hy, linePaint)
        canvas.drawLine(hx, hy, tx, ty, linePaint)
        linePaint.strokeCap = Paint.Cap.BUTT
        fillPaint.style = Paint.Style.FILL
        fillPaint.color = glass(dark) or 0x30000000
        canvas.drawCircle(hx, hy, 2.4f, fillPaint)
        linePaint.strokeWidth = 1f * px
        canvas.drawCircle(hx, hy, 2.4f, linePaint)
        fillPaint.color = ink
        canvas.drawCircle(c.px, c.py, 1.0f, fillPaint)
        fillPaint.color = ACCENT
        canvas.drawCircle(tx, ty, 1.3f, fillPaint)
    }

    private fun drawHud(canvas: Canvas, text: String, ax: Float, ay: Float, pxPerMm: Float) {
        canvas.save()
        canvas.translate(ax, ay)
        canvas.scale(1f / pxPerMm, 1f / pxPerMm)
        val size = 12f * density
        textPaint.textSize = size
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.color = 0xFFFFFFFF.toInt()
        val w = textPaint.measureText(text) + size * 1.4f
        val h = size * 2f
        rect.set(-w / 2f, -h, w / 2f, 0f)
        hudPaint.style = Paint.Style.FILL
        hudPaint.color = 0xD91F2A2E.toInt()
        canvas.drawRoundRect(rect, h / 2f, h / 2f, hudPaint)
        canvas.drawText(text, 0f, -h / 2f + size * 0.35f, textPaint)
        canvas.restore()
    }

    // =====================================================================================

    private fun makeSnapshot() = Snapshot(ruler, protractor, compass, hud, hudAnchor, sweepStart, sweepDeg)

    private fun publish() {
        snapshot = makeSnapshot()
        requestRedraw()
    }

    private fun wrap(a: Float): Float {
        var d = a
        while (d > PI) d -= (2 * PI).toFloat()
        while (d < -PI) d += (2 * PI).toFloat()
        return d
    }

    private fun segmentDistance(x: Float, y: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax
        val dy = by - ay
        val l2 = dx * dx + dy * dy
        if (l2 < 1e-9f) return hypot(x - ax, y - ay)
        val t = (((x - ax) * dx + (y - ay) * dy) / l2).coerceIn(0f, 1f)
        return hypot(ax + dx * t - x, ay + dy * t - y)
    }

    private fun fmtLen(mm: Float) = String.format(Locale.US, "%.1f mm (%.1f u)", mm, mm / max(gridCellMm(), 0.5f))

    private fun fmtDeg(deg: Float): String {
        var d = deg % 360f
        if (d < 0f) d += 360f
        return String.format(Locale.US, "%.1f°", (d * 10f).roundToInt() / 10f)
    }

    private companion object {
        const val RULER_WIDTH_MM = 28f
        const val KNOB_GAP_MM = 8f
        const val KNOB_R_MM = 5f
        const val KNOB_HIT_MM = 9f
        const val KNOB_HIT_PX = 44f
        const val RULER_MIN_MM = 40f
        const val RULER_MAX_MM = 300f
        const val PROTRACTOR_MIN_MM = 20f
        const val PROTRACTOR_MAX_MM = 90f
        const val COMPASS_MIN_MM = 8f
        const val COMPASS_MAX_MM = 90f
        const val ACCENT = 0xFFE07A3F.toInt()
    }
}
