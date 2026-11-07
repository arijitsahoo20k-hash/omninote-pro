package com.omninote.pro.engine.ink

import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import com.omninote.pro.core.model.BrushConfig
import com.omninote.pro.core.model.BrushType
import com.omninote.pro.core.model.LineStyle
import com.omninote.pro.core.model.StrokePoint
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Turns stroke samples into drawable geometry in page space (mm). Reuses its scratch arrays between calls, so a
 * tessellator instance is NOT thread-safe: give each render thread its own.
 *
 * Typical use: [load] -> [computeWidths] -> (optional [appendFlickTail]) -> [buildRibbon] / [buildSpine] /
 * [buildProcedural].
 */
class StrokeRibbonTessellator {

    var count: Int = 0
        private set

    private var xs = FloatArray(0)
    private var ys = FloatArray(0)
    private var tm = FloatArray(0)
    private var pe = FloatArray(0)
    private var tl = FloatArray(0)
    private var ws = FloatArray(0)
    private var dir = FloatArray(0)
    private var spd = FloatArray(0)
    private var lx = FloatArray(0)
    private var ly = FloatArray(0)
    private var rx = FloatArray(0)
    private var ry = FloatArray(0)

    private val capRect = RectF()
    private val spine = Path()
    private val measure = PathMeasure()
    private val posTan = FloatArray(2)
    private val tanBuf = FloatArray(2)

    // =====================================================================================
    // Loading + kinematics
    // =====================================================================================

    /** Loads committed points [a] followed by optional predicted points [extra]; pressure goes through [lut]. */
    fun load(a: PointArrays, extra: PointArrays?, lut: PressureCurveLut) {
        ensureCapacity(a.count + (extra?.count ?: 0) + TAIL_POINTS + 2)
        count = 0
        appendSource(a, lut)
        if (extra != null) appendSource(extra, lut)
        computeKinematics()
    }

    private fun appendSource(s: PointArrays, lut: PressureCurveLut) {
        for (i in 0 until s.count) {
            if (count > 0) {
                val dx = s.x[i] - xs[count - 1]
                val dy = s.y[i] - ys[count - 1]
                if (dx * dx + dy * dy < MIN_STEP_MM * MIN_STEP_MM) {
                    if (i == s.count - 1) put(count - 1, s, i, lut)
                    continue
                }
            }
            put(count, s, i, lut)
            count++
        }
    }

    private fun put(slot: Int, s: PointArrays, i: Int, lut: PressureCurveLut) {
        xs[slot] = s.x[i]
        ys[slot] = s.y[i]
        tm[slot] = s.timeMs[i]
        pe[slot] = lut.mapPressure(s.pressure[i])
        tl[slot] = s.tilt[i]
    }

    private fun computeKinematics() {
        val n = count
        for (i in 0 until n) {
            val j0 = max(0, i - 2)
            val j1 = min(n - 1, i + 2)
            val dx = xs[j1] - xs[j0]
            val dy = ys[j1] - ys[j0]
            val dist = hypot(dx, dy)
            dir[i] = if (dist < 1e-5f) (if (i > 0) dir[i - 1] else 0f) else atan2(dy, dx)
            val dt = tm[j1] - tm[j0]
            spd[i] = if (dt > 0f) dist / dt else DEFAULT_SPEED
        }
    }

    fun meanEffectivePressure(): Float {
        if (count == 0) return 1f
        var sum = 0f
        for (i in 0 until count) sum += pe[i]
        return sum / count
    }

    fun meanTiltRad(): Float {
        if (count == 0) return 0f
        var sum = 0f
        for (i in 0 until count) sum += tl[i]
        return sum / count
    }

    fun exitSpeedMmPerMs(): Float {
        if (count == 0) return 0f
        val from = max(0, count - 3)
        var sum = 0f
        for (i in from until count) sum += spd[i]
        return sum / (count - from)
    }

    // =====================================================================================
    // Per-instrument widths (mm)
    // =====================================================================================

    fun computeWidths(brush: BrushConfig) {
        val base = brush.widthMm
        val nibRad = Math.toRadians(brush.nibAngleDeg.toDouble()).toFloat()
        for (i in 0 until count) {
            val p = pe[i]
            ws[i] = when (brush.type) {
                BrushType.FOUNTAIN_PEN -> {
                    val flat = 1f - brush.nibFlatness * abs(sin(dir[i] - nibRad))
                    val pool = 1f + FOUNTAIN_POOL * (1f - smoothstep(0.01f, 0.06f, spd[i]))
                    base * (0.35f + 0.65f * p) * flat * pool
                }
                BrushType.BALLPOINT -> base * (0.85f + 0.30f * p)
                BrushType.BRUSH_PEN -> base * (0.1f + 2.7f * p.pow(BRUSH_GAMMA))
                BrushType.PENCIL -> {
                    val tiltDeg = Math.toDegrees(tl[i].toDouble()).toFloat()
                    val t = (smoothstep(25f, 65f, tiltDeg) * brush.pencilTiltSensitivity).coerceIn(0f, 1f)
                    base * (0.6f + 0.4f * p) * (1f + 3f * t)
                }
                BrushType.HIGHLIGHTER -> {
                    val delta = dir[i] - nibRad
                    base * abs(sin(delta)) + base * CHISEL_THICKNESS * abs(cos(delta))
                }
                else -> base
            }.coerceAtLeast(MIN_WIDTH_MM)
        }
    }

    /**
     * Brush Pen exit flick: when the pen leaves the paper fast, extends the stroke with a short tapered tail along
     * the exit direction. Call after [computeWidths] on FINAL strokes only. Returns true if a tail was added.
     */
    fun appendFlickTail(baseWidthMm: Float): Boolean {
        if (count < 3) return false
        val speed = exitSpeedMmPerMs()
        if (speed < FLICK_MIN_SPEED) return false
        val last = count - 1
        val length = min(speed * 6f, baseWidthMm * 6f + 3f)
        val angle = dir[last]
        val ux = cos(angle)
        val uy = sin(angle)
        val w0 = ws[last]
        val t0 = tm[last]
        for (j in 1..TAIL_POINTS) {
            val u = j.toFloat() / TAIL_POINTS
            val idx = count
            xs[idx] = xs[last] + ux * length * u
            ys[idx] = ys[last] + uy * length * u
            tm[idx] = t0 + j
            pe[idx] = pe[last]
            tl[idx] = tl[last]
            dir[idx] = angle
            spd[idx] = speed
            ws[idx] = max(w0 * (1f - u).pow(1.6f), MIN_WIDTH_MM)
            count++
        }
        return true
    }

    // =====================================================================================
    // Ribbon outline: left edge forward, right edge backward, Catmull-Rom as cubic Beziers
    // =====================================================================================

    fun buildRibbon(out: Path, roundCaps: Boolean) {
        out.rewind()
        val n = count
        if (n == 0) return
        if (n == 1) {
            out.addCircle(xs[0], ys[0], ws[0] * 0.5f, Path.Direction.CW)
            return
        }
        computeEdges(n)
        out.moveTo(lx[0], ly[0])
        appendCatmull(out, lx, ly, n, false)
        if (roundCaps) {
            val r = ws[n - 1] * 0.5f
            capRect.set(xs[n - 1] - r, ys[n - 1] - r, xs[n - 1] + r, ys[n - 1] + r)
            out.arcTo(capRect, deg(dir[n - 1]) + 90f, -180f, false)
        } else {
            out.lineTo(rx[n - 1], ry[n - 1])
        }
        appendCatmull(out, rx, ry, n, true)
        if (roundCaps) {
            val r = ws[0] * 0.5f
            capRect.set(xs[0] - r, ys[0] - r, xs[0] + r, ys[0] + r)
            out.arcTo(capRect, deg(dir[0]) - 90f, -180f, false)
        }
        out.close()
    }

    private fun computeEdges(n: Int) {
        for (i in 0 until n) {
            val nx = -sin(dir[i])
            val ny = cos(dir[i])
            val half = ws[i] * 0.5f
            lx[i] = xs[i] + nx * half
            ly[i] = ys[i] + ny * half
            rx[i] = xs[i] - nx * half
            ry[i] = ys[i] - ny * half
        }
    }

    /** Appends cubic segments through e[0..n-1] (or reversed). The path must already be at the first point. */
    private fun appendCatmull(out: Path, ex: FloatArray, ey: FloatArray, n: Int, reverse: Boolean) {
        for (k in 0 until n - 1) {
            val p0x = at(ex, max(k - 1, 0), n, reverse)
            val p0y = at(ey, max(k - 1, 0), n, reverse)
            val p1x = at(ex, k, n, reverse)
            val p1y = at(ey, k, n, reverse)
            val p2x = at(ex, k + 1, n, reverse)
            val p2y = at(ey, k + 1, n, reverse)
            val p3x = at(ex, min(k + 2, n - 1), n, reverse)
            val p3y = at(ey, min(k + 2, n - 1), n, reverse)
            out.cubicTo(
                p1x + (p2x - p0x) / 6f, p1y + (p2y - p0y) / 6f,
                p2x - (p3x - p1x) / 6f, p2y - (p3y - p1y) / 6f,
                p2x, p2y
            )
        }
    }

    private fun at(a: FloatArray, k: Int, n: Int, reverse: Boolean): Float = if (reverse) a[n - 1 - k] else a[k]

    // =====================================================================================
    // Spine (centre line) and procedural lines
    // =====================================================================================

    /** Smooth centre line through the points (Catmull-Rom). A single point yields a hairline for round-cap dots. */
    fun buildSpine(out: Path) {
        out.rewind()
        val n = count
        if (n == 0) return
        out.moveTo(xs[0], ys[0])
        if (n == 1) {
            out.lineTo(xs[0] + 0.001f, ys[0])
            return
        }
        appendCatmull(out, xs, ys, n, false)
    }

    /**
     * Arc-length parameterised geometry along the spine for the Dashed / Dotted / Wavy pen.
     * DASHED -> open sub-paths to STROKE (butt caps); DOTTED -> filled circles to FILL; WAVY -> one polyline to STROKE.
     */
    fun buildProcedural(out: Path, brush: BrushConfig) {
        out.rewind()
        buildSpine(spine)
        measure.setPath(spine, false)
        val length = measure.length
        if (length <= 0f) return
        when (brush.lineStyle) {
            LineStyle.DASHED -> {
                val dash = max(brush.dashLengthMm, MIN_PATTERN_MM)
                val gap = max(brush.gapLengthMm, MIN_PATTERN_MM)
                var s = 0f
                while (s < length) {
                    val e = min(s + dash, length)
                    measure.getSegment(s, e, out, true)
                    s = e + gap
                }
            }
            LineStyle.DOTTED -> {
                val radius = brush.widthMm * 0.6f
                val step = max(brush.gapLengthMm, brush.widthMm * 1.6f) + brush.widthMm
                var s = 0f
                while (s <= length) {
                    measure.getPosTan(s, posTan, null)
                    out.addCircle(posTan[0], posTan[1], radius, Path.Direction.CW)
                    s += step
                }
            }
            LineStyle.WAVY -> {
                val wavelength = max(brush.waveLengthMm, MIN_WAVELENGTH_MM)
                val step = min(WAVE_STEP_MM, wavelength / 16f)
                val k = (2.0 * PI / wavelength).toFloat()
                var s = 0f
                var first = true
                while (s <= length + step * 0.5f) {
                    val at = min(s, length)
                    measure.getPosTan(at, posTan, tanBuf)
                    val off = brush.waveAmplitudeMm * sin(k * at)
                    val px = posTan[0] - tanBuf[1] * off
                    val py = posTan[1] + tanBuf[0] * off
                    if (first) out.moveTo(px, py) else out.lineTo(px, py)
                    first = false
                    s += step
                }
            }
        }
    }

    // =====================================================================================

    private fun ensureCapacity(n: Int) {
        if (xs.size >= n) return
        val size = max(max(n, xs.size * 2), 256)
        xs = FloatArray(size); ys = FloatArray(size); tm = FloatArray(size); pe = FloatArray(size)
        tl = FloatArray(size); ws = FloatArray(size); dir = FloatArray(size); spd = FloatArray(size)
        lx = FloatArray(size); ly = FloatArray(size); rx = FloatArray(size); ry = FloatArray(size)
    }

    private fun deg(rad: Float): Float = Math.toDegrees(rad.toDouble()).toFloat()

    companion object {
        private const val MIN_STEP_MM = 0.04f
        private const val MIN_WIDTH_MM = 0.02f
        private const val DEFAULT_SPEED = 0.15f
        private const val FOUNTAIN_POOL = 0.18f
        private const val BRUSH_GAMMA = 1.6f
        private const val CHISEL_THICKNESS = 0.3f
        private const val FLICK_MIN_SPEED = 0.5f
        private const val TAIL_POINTS = 6
        private const val MIN_PATTERN_MM = 0.05f
        private const val MIN_WAVELENGTH_MM = 0.5f
        private const val WAVE_STEP_MM = 0.2f

        fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
            val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }

        /** Auto-Straighten: collapses a held stroke to its first and last point (pressure and time averaged/kept). */
        fun straighten(points: List<StrokePoint>): List<StrokePoint> {
            if (points.size < 2) return points
            val first = points.first()
            val last = points.last()
            val avgPressure = points.sumOf { it.pressure.toDouble() }.toFloat() / points.size
            return listOf(first.copy(pressure = avgPressure), last.copy(pressure = avgPressure))
        }
    }
}
