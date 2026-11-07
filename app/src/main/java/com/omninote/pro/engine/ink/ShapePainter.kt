package com.omninote.pro.engine.ink

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.omninote.pro.core.model.FillType
import com.omninote.pro.core.model.PointMm
import com.omninote.pro.core.model.ShapePrimitive
import com.omninote.pro.core.model.ShapeType
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sin

/**
 * Draws snapped [ShapePrimitive]s (all 17 types) in page space: optional solid or hatched fill under a
 * constant-width round-cap outline. Vertex meanings per type are documented on ShapePrimitive.
 */
class ShapePainter(private val shaders: BrushShaderFactory) {

    private val path = Path()
    private val strokePaint = Paint()
    private val fillPaint = Paint()
    private val hatchPaint = Paint()
    private val oval = RectF()

    fun draw(canvas: Canvas, shape: ShapePrimitive, ctx: InkRenderContext) {
        buildPath(shape, path)
        if (shape.canFill && shape.fill.type != FillType.NONE) drawFill(canvas, shape, ctx)
        shaders.fineliner(strokePaint, shape.brush, shape.semanticColorArgb, ctx)
        canvas.drawPath(path, strokePaint)
    }

    private fun drawFill(canvas: Canvas, shape: ShapePrimitive, ctx: InkRenderContext) {
        val fill = shape.fill
        val color = ctx.displayColor(fill.fillColorArgb)
        if (fill.type == FillType.SOLID) {
            fillPaint.reset()
            fillPaint.isAntiAlias = true
            fillPaint.style = Paint.Style.FILL
            fillPaint.color = color or (0xFF shl 24)
            fillPaint.alpha = (fill.opacity * 255f).toInt()
            canvas.drawPath(path, fillPaint)
            return
        }
        hatchPaint.reset()
        hatchPaint.isAntiAlias = true
        hatchPaint.style = Paint.Style.STROKE
        hatchPaint.strokeWidth = 0.2f
        hatchPaint.color = color or (0xFF shl 24)
        hatchPaint.alpha = (min(1f, fill.opacity * 2f) * 255f).toInt()

        val b = shape.bounds
        val radius = hypot(b.width, b.height) * 0.5f
        val spacing = max(fill.hatchSpacingMm, radius * 2f / MAX_HATCH_LINES)
        canvas.save()
        canvas.clipPath(path)
        canvas.rotate(fill.hatchAngleDeg, b.centerX, b.centerY)
        var y = -radius
        while (y <= radius) {
            canvas.drawLine(b.centerX - radius, b.centerY + y, b.centerX + radius, b.centerY + y, hatchPaint)
            y += spacing
        }
        canvas.restore()
    }

    // =====================================================================================
    // Geometry
    // =====================================================================================

    private fun buildPath(shape: ShapePrimitive, out: Path) {
        out.rewind()
        val v = shape.vertices
        val headLen = max(shape.brush.widthMm * 5f, 2.5f)
        when (shape.type) {
            ShapeType.LINE -> polyline(out, v.take(2), false)
            ShapeType.POLYLINE -> polyline(out, v, false)
            ShapeType.ARROW_SINGLE -> {
                polyline(out, v.take(2), false)
                if (v.size >= 2) arrowHead(out, v[1], v[0], headLen)
            }
            ShapeType.ARROW_DOUBLE -> {
                polyline(out, v.take(2), false)
                if (v.size >= 2) {
                    arrowHead(out, v[1], v[0], headLen)
                    arrowHead(out, v[0], v[1], headLen)
                }
            }
            ShapeType.ARC -> arc(out, v)
            ShapeType.CIRCLE -> if (v.size >= 2) out.addCircle(v[0].x, v[0].y, v[0].distanceTo(v[1]), Path.Direction.CW)
            ShapeType.ELLIPSE -> ellipse(out, v)
            ShapeType.RECTANGLE -> rectangle(out, v, square = false)
            ShapeType.SQUARE -> rectangle(out, v, square = true)
            ShapeType.TRIANGLE, ShapeType.PARALLELOGRAM, ShapeType.PENTAGON, ShapeType.HEXAGON ->
                polyline(out, v, v.size >= 3)
            ShapeType.PARABOLA -> parabola(out, v)
            ShapeType.SINE_WAVE -> sine(out, v)
            ShapeType.AXES_2D -> axes(out, v, 2, headLen)
            ShapeType.AXES_3D -> axes(out, v, 3, headLen)
        }
    }

    private fun polyline(out: Path, pts: List<PointMm>, close: Boolean) {
        if (pts.isEmpty()) return
        out.moveTo(pts[0].x, pts[0].y)
        if (pts.size == 1) out.lineTo(pts[0].x + 0.001f, pts[0].y)
        for (i in 1 until pts.size) out.lineTo(pts[i].x, pts[i].y)
        if (close) out.close()
    }

    private fun arrowHead(out: Path, tip: PointMm, from: PointMm, requestedLen: Float) {
        val length = tip.distanceTo(from)
        if (length < 1e-4f) return
        val len = min(requestedLen, length * 0.4f)
        val angle = atan2(tip.y - from.y, tip.x - from.x)
        for (side in intArrayOf(-1, 1)) {
            val a = angle + side * ARROW_SPREAD
            out.moveTo(tip.x - len * cos(a), tip.y - len * sin(a))
            out.lineTo(tip.x, tip.y)
        }
    }

    private fun arc(out: Path, v: List<PointMm>) {
        if (v.size < 3) {
            polyline(out, v, false)
            return
        }
        val a = v[0]
        val b = v[1]
        val c = v[2]
        val d = 2f * (a.x * (b.y - c.y) + b.x * (c.y - a.y) + c.x * (a.y - b.y))
        if (abs(d) < 1e-6f) {
            polyline(out, listOf(a, c), false)
            return
        }
        val a2 = a.x * a.x + a.y * a.y
        val b2 = b.x * b.x + b.y * b.y
        val c2 = c.x * c.x + c.y * c.y
        val ux = (a2 * (b.y - c.y) + b2 * (c.y - a.y) + c2 * (a.y - b.y)) / d
        val uy = (a2 * (c.x - b.x) + b2 * (a.x - c.x) + c2 * (b.x - a.x)) / d
        val r = hypot(a.x - ux, a.y - uy)
        val a0 = atan2(a.y - uy, a.x - ux)
        val am = atan2(b.y - uy, b.x - ux)
        val a1 = atan2(c.y - uy, c.x - ux)
        val twoPi = (2.0 * PI).toFloat()
        val ccwSweep = positive(a1 - a0, twoPi)
        val ccwToMid = positive(am - a0, twoPi)
        val sweep = if (ccwToMid <= ccwSweep) ccwSweep else ccwSweep - twoPi
        oval.set(ux - r, uy - r, ux + r, uy + r)
        out.arcTo(oval, Math.toDegrees(a0.toDouble()).toFloat(), Math.toDegrees(sweep.toDouble()).toFloat(), true)
    }

    private fun positive(angle: Float, twoPi: Float): Float {
        val m = angle % twoPi
        return if (m < 0f) m + twoPi else m
    }

    /** Four cubics through the conjugate semi-axes A, B: exact for any affine image of a circle. */
    private fun ellipse(out: Path, v: List<PointMm>) {
        if (v.size < 3) return
        val cx = v[0].x
        val cy = v[0].y
        val ax = v[1].x - cx
        val ay = v[1].y - cy
        val bx = v[2].x - cx
        val by = v[2].y - cy
        val k = KAPPA
        out.moveTo(cx + ax, cy + ay)
        out.cubicTo(cx + ax + k * bx, cy + ay + k * by, cx + bx + k * ax, cy + by + k * ay, cx + bx, cy + by)
        out.cubicTo(cx + bx - k * ax, cy + by - k * ay, cx - ax + k * bx, cy - ay + k * by, cx - ax, cy - ay)
        out.cubicTo(cx - ax - k * bx, cy - ay - k * by, cx - bx - k * ax, cy - by - k * ay, cx - bx, cy - by)
        out.cubicTo(cx - bx + k * ax, cy - by + k * ay, cx + ax - k * bx, cy + ay - k * by, cx + ax, cy + ay)
        out.close()
    }

    /** [corner, opposite corner, rotation reference]; two points give an axis-aligned box. */
    private fun rectangle(out: Path, v: List<PointMm>, square: Boolean) {
        if (v.size < 2) return
        val p0 = v[0]
        val p1 = v[1]
        var ux = 1f
        var uy = 0f
        if (v.size >= 3) {
            val len = hypot(v[2].x - p0.x, v[2].y - p0.y)
            if (len > 1e-5f) {
                ux = (v[2].x - p0.x) / len
                uy = (v[2].y - p0.y) / len
            }
        }
        val vx = -uy
        val vy = ux
        val dx = p1.x - p0.x
        val dy = p1.y - p0.y
        var a = dx * ux + dy * uy
        var b = dx * vx + dy * vy
        if (square) {
            val s = max(abs(a), abs(b))
            a = (if (a == 0f) 1f else sign(a)) * s
            b = (if (b == 0f) 1f else sign(b)) * s
        }
        out.moveTo(p0.x, p0.y)
        out.lineTo(p0.x + ux * a, p0.y + uy * a)
        out.lineTo(p0.x + ux * a + vx * b, p0.y + uy * a + vy * b)
        out.lineTo(p0.x + vx * b, p0.y + vy * b)
        out.close()
    }

    /** y = vy + k (x - vx)^2 through the left end, sampled across x. */
    private fun parabola(out: Path, v: List<PointMm>) {
        if (v.size < 3) {
            polyline(out, v, false)
            return
        }
        val left = v[0]
        val vertex = v[1]
        val right = v[2]
        val span = if (abs(left.x - vertex.x) >= abs(right.x - vertex.x)) left else right
        val dxs = span.x - vertex.x
        if (abs(dxs) < 1e-5f) {
            polyline(out, listOf(left, right), false)
            return
        }
        val k = (span.y - vertex.y) / (dxs * dxs)
        val steps = PARABOLA_STEPS
        for (i in 0..steps) {
            val x = left.x + (right.x - left.x) * i / steps
            val y = vertex.y + k * (x - vertex.x) * (x - vertex.x)
            if (i == 0) out.moveTo(x, y) else out.lineTo(x, y)
        }
    }

    /** [start, end, crest(, wavelength marker)]: crest distance sets amplitude and side; the optional 4th vertex's projection on the baseline sets the wavelength (default 4x amplitude). */
    private fun sine(out: Path, v: List<PointMm>) {
        if (v.size < 3) {
            polyline(out, v, false)
            return
        }
        val s = v[0]
        val e = v[1]
        val length = s.distanceTo(e)
        if (length < 1e-4f) return
        val ux = (e.x - s.x) / length
        val uy = (e.y - s.y) / length
        val nx = -uy
        val ny = ux
        val amplitude = (v[2].x - s.x) * nx + (v[2].y - s.y) * ny
        val wavelength = if (v.size >= 4) {
            max(abs((v[3].x - s.x) * ux + (v[3].y - s.y) * uy), MIN_WAVELENGTH_MM)
        } else {
            max(4f * abs(amplitude), MIN_WAVELENGTH_MM)
        }
        val steps = (length / 0.3f).toInt().coerceIn(8, 1200)
        val k = (2.0 * PI / wavelength).toFloat()
        for (i in 0..steps) {
            val along = length * i / steps
            val off = amplitude * sin(k * along)
            val x = s.x + ux * along + nx * off
            val y = s.y + uy * along + ny * off
            if (i == 0) out.moveTo(x, y) else out.lineTo(x, y)
        }
    }

    /** [origin, x end, y end(, z end)] with arrowheads on every axis tip. */
    private fun axes(out: Path, v: List<PointMm>, axisCount: Int, headLen: Float) {
        if (v.size < axisCount + 1) {
            polyline(out, v, false)
            return
        }
        for (i in 1..axisCount) {
            out.moveTo(v[0].x, v[0].y)
            out.lineTo(v[i].x, v[i].y)
            arrowHead(out, v[i], v[0], headLen)
        }
    }

    private companion object {
        const val KAPPA = 0.5522847498f
        const val ARROW_SPREAD = 0.5f
        const val MAX_HATCH_LINES = 600f
        const val PARABOLA_STEPS = 64
        const val MIN_WAVELENGTH_MM = 4f
    }
}
