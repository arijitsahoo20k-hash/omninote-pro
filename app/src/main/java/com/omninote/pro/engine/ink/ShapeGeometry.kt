package com.omninote.pro.engine.ink

import com.omninote.pro.core.model.PointMm
import com.omninote.pro.core.model.ShapePrimitive
import com.omninote.pro.core.model.ShapeType
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sign
import kotlin.math.sin

/** Pure geometry for snapped shapes: outlines, hit tests, handle positions and the live dimension readout. */
object ShapeGeometry {

    private const val ELLIPSE_SAMPLES = 72

    /** Four corners of a RECTANGLE/SQUARE from [corner, opposite corner, rotation reference]; same maths the painter uses. */
    fun rectangleCorners(v: List<PointMm>, square: Boolean): List<PointMm> {
        if (v.size < 2) return v
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
        return listOf(
            p0,
            PointMm(p0.x + ux * a, p0.y + uy * a),
            PointMm(p0.x + ux * a + vx * b, p0.y + uy * a + vy * b),
            PointMm(p0.x + vx * b, p0.y + vy * b)
        )
    }

    /** Closed outline as a polygon (circles and ellipses are sampled), or null for open shapes. */
    fun outlinePolygon(shape: ShapePrimitive): List<PointMm>? {
        val v = shape.vertices
        return when (shape.type) {
            ShapeType.TRIANGLE, ShapeType.PARALLELOGRAM, ShapeType.PENTAGON, ShapeType.HEXAGON -> v
            ShapeType.RECTANGLE -> rectangleCorners(v, false)
            ShapeType.SQUARE -> rectangleCorners(v, true)
            ShapeType.CIRCLE -> if (v.size < 2) null else {
                val r = v[0].distanceTo(v[1])
                List(ELLIPSE_SAMPLES) { i ->
                    val t = 2.0 * PI * i / ELLIPSE_SAMPLES
                    PointMm(v[0].x + r * cos(t).toFloat(), v[0].y + r * sin(t).toFloat())
                }
            }
            ShapeType.ELLIPSE -> if (v.size < 3) null else {
                val c = v[0]
                List(ELLIPSE_SAMPLES) { i ->
                    val t = 2.0 * PI * i / ELLIPSE_SAMPLES
                    val ct = cos(t).toFloat()
                    val st = sin(t).toFloat()
                    PointMm(
                        c.x + (v[1].x - c.x) * ct + (v[2].x - c.x) * st,
                        c.y + (v[1].y - c.y) * ct + (v[2].y - c.y) * st
                    )
                }
            }
            else -> null
        }
    }

    /** True if (x, y) is inside a closed shape. Always false for open shapes. */
    fun contains(shape: ShapePrimitive, x: Float, y: Float): Boolean {
        val v = shape.vertices
        when (shape.type) {
            ShapeType.CIRCLE -> return v.size >= 2 && hypot(x - v[0].x, y - v[0].y) <= v[0].distanceTo(v[1])
            ShapeType.ELLIPSE -> {
                if (v.size < 3) return false
                val ax = v[1].x - v[0].x
                val ay = v[1].y - v[0].y
                val bx = v[2].x - v[0].x
                val by = v[2].y - v[0].y
                val det = ax * by - ay * bx
                if (abs(det) < 1e-9f) return false
                val px = x - v[0].x
                val py = y - v[0].y
                val alpha = (px * by - py * bx) / det
                val beta = (ax * py - ay * px) / det
                return alpha * alpha + beta * beta <= 1f
            }
            else -> Unit
        }
        val poly = outlinePolygon(shape) ?: return false
        var inside = false
        var j = poly.size - 1
        for (i in poly.indices) {
            val a = poly[i]
            val b = poly[j]
            if ((a.y > y) != (b.y > y) && x < (b.x - a.x) * (y - a.y) / (b.y - a.y) + a.x) inside = !inside
            j = i
        }
        return inside
    }

    /** Index of the topmost filled-candidate shape under the point, searching [shapes] from last (top) to first. */
    fun hitClosedShape(shapes: List<ShapePrimitive>, x: Float, y: Float): ShapePrimitive? {
        for (k in shapes.indices.reversed()) {
            val s = shapes[k]
            if (s.canFill && contains(s, x, y)) return s
        }
        return null
    }

    /** Angle of the vector (dx, dy) in page space as the usual maths angle in degrees (y up), in (-180, 180]. */
    fun mathAngleDeg(dx: Float, dy: Float): Float = Math.toDegrees(atan2(-dy.toDouble(), dx.toDouble())).toFloat()

    /** One-line dimensions for the floating badge: millimetres plus grid units of [cellMm]. */
    fun readout(shape: ShapePrimitive, cellMm: Float): String {
        val v = shape.vertices
        fun mm(len: Float) = String.format(Locale.US, "%.1f mm (%.1f u)", len, len / cellMm)
        fun deg(a: Float) = String.format(Locale.US, "%.1f°", a)
        return when (shape.type) {
            ShapeType.LINE, ShapeType.ARROW_SINGLE, ShapeType.ARROW_DOUBLE ->
                if (v.size < 2) "" else "${mm(v[0].distanceTo(v[1]))}  ∠ ${deg(mathAngleDeg(v[1].x - v[0].x, v[1].y - v[0].y))}"
            ShapeType.CIRCLE ->
                if (v.size < 2) "" else "r ${mm(v[0].distanceTo(v[1]))}"
            ShapeType.ELLIPSE ->
                if (v.size < 3) "" else "a ${mm(v[0].distanceTo(v[1]))}  b ${mm(v[0].distanceTo(v[2]))}"
            ShapeType.RECTANGLE, ShapeType.SQUARE -> {
                val c = rectangleCorners(v, shape.type == ShapeType.SQUARE)
                if (c.size < 4) "" else {
                    val w = c[0].distanceTo(c[1])
                    val h = c[0].distanceTo(c[3])
                    "${mm(w)} × ${mm(h)}  ∠ ${deg(mathAngleDeg(c[1].x - c[0].x, c[1].y - c[0].y))}"
                }
            }
            ShapeType.TRIANGLE ->
                if (v.size < 3) "" else String.format(
                    Locale.US, "%.1f / %.1f / %.1f mm  ∠ %.0f° %.0f° %.0f°",
                    v[0].distanceTo(v[1]), v[1].distanceTo(v[2]), v[2].distanceTo(v[0]),
                    interiorAngle(v[2], v[0], v[1]), interiorAngle(v[0], v[1], v[2]), interiorAngle(v[1], v[2], v[0])
                )
            ShapeType.ARC -> arcReadout(v, ::mm, ::deg)
            ShapeType.SINE_WAVE ->
                if (v.size < 3) "" else "length ${mm(v[0].distanceTo(v[1]))}"
            ShapeType.PARABOLA ->
                if (v.size < 3) "" else "span ${mm(v[0].distanceTo(v[2]))}"
            ShapeType.AXES_2D, ShapeType.AXES_3D ->
                if (v.size < 3) "" else "x ${mm(v[0].distanceTo(v[1]))}  y ${mm(v[0].distanceTo(v[2]))}"
            ShapeType.POLYLINE, ShapeType.PARALLELOGRAM, ShapeType.PENTAGON, ShapeType.HEXAGON -> {
                var perimeter = 0f
                val closed = shape.type != ShapeType.POLYLINE
                for (i in 0 until v.size - 1) perimeter += v[i].distanceTo(v[i + 1])
                if (closed && v.size > 2) perimeter += v.last().distanceTo(v.first())
                "${if (closed) "perimeter" else "length"} ${mm(perimeter)}"
            }
        }
    }

    /** Interior angle in degrees at vertex [b] between rays to [a] and [c]. */
    fun interiorAngle(a: PointMm, b: PointMm, c: PointMm): Float {
        val v1x = a.x - b.x
        val v1y = a.y - b.y
        val v2x = c.x - b.x
        val v2y = c.y - b.y
        val d = atan2(v1x * v2y - v1y * v2x, v1x * v2x + v1y * v2y)
        return Math.toDegrees(abs(d).toDouble()).toFloat()
    }

    private fun arcReadout(v: List<PointMm>, mm: (Float) -> String, deg: (Float) -> String): String {
        if (v.size < 3) return ""
        val a = v[0]
        val b = v[1]
        val c = v[2]
        val d = 2f * (a.x * (b.y - c.y) + b.x * (c.y - a.y) + c.x * (a.y - b.y))
        if (abs(d) < 1e-6f) return "length ${mm(a.distanceTo(c))}"
        val a2 = a.x * a.x + a.y * a.y
        val b2 = b.x * b.x + b.y * b.y
        val c2 = c.x * c.x + c.y * c.y
        val ux = (a2 * (b.y - c.y) + b2 * (c.y - a.y) + c2 * (a.y - b.y)) / d
        val uy = (a2 * (c.x - b.x) + b2 * (a.x - c.x) + c2 * (b.x - a.x)) / d
        val r = hypot(a.x - ux, a.y - uy)
        val twoPi = (2.0 * PI).toFloat()
        fun pos(x: Float): Float { val m = x % twoPi; return if (m < 0f) m + twoPi else m }
        val a0 = atan2(a.y - uy, a.x - ux)
        val ccw = pos(atan2(c.y - uy, c.x - ux) - a0)
        val toMid = pos(atan2(b.y - uy, b.x - ux) - a0)
        val sweep = if (toMid <= ccw) ccw else twoPi - ccw
        return "r ${mm(r)}  sweep ${deg(Math.toDegrees(sweep.toDouble()).toFloat())}"
    }
}
