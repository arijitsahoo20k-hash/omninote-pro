package com.omninote.pro.engine.ink

import com.omninote.pro.core.model.ImageElement
import com.omninote.pro.core.model.PageElement
import com.omninote.pro.core.model.PointMm
import com.omninote.pro.core.model.ShapePrimitive
import com.omninote.pro.core.model.StencilInstance
import com.omninote.pro.core.model.Stroke
import com.omninote.pro.core.model.StrokeBounds
import com.omninote.pro.core.model.AudioClipElement
import com.omninote.pro.core.model.TextBoxElement
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Narrow-phase tests for Lasso and Marquee selection. An element is selected when its outline (stroke spine, shape
 * outline, or frame of an image, text box or stencil) has a point inside the selection polygon or crosses its
 * boundary. A selection polygon lying entirely inside a big outline does not select it.
 */
internal object LassoGeometry {

    /** Simplifies a freehand loop: drops points closer than [minStepMm] and caps the vertex count. */
    fun simplify(xs: FloatArray, ys: FloatArray, count: Int, minStepMm: Float, maxVertices: Int): FloatArray {
        if (count == 0) return FloatArray(0)
        var step = minStepMm
        var outX = FloatArray(count)
        var outY = FloatArray(count)
        var n: Int
        while (true) {
            n = 0
            for (i in 0 until count) {
                if (n == 0 || dist(outX[n - 1], outY[n - 1], xs[i], ys[i]) >= step) {
                    outX[n] = xs[i]
                    outY[n] = ys[i]
                    n++
                }
            }
            if (n <= maxVertices) break
            step *= 1.5f
        }
        val result = FloatArray(n * 2)
        for (i in 0 until n) {
            result[i * 2] = outX[i]
            result[i * 2 + 1] = outY[i]
        }
        return result
    }

    fun hits(element: PageElement, poly: FloatArray): Boolean {
        val n = poly.size / 2
        if (n < 3) return false
        return when (element) {
            is Stroke -> strokeHits(element, poly, n)
            is ShapePrimitive -> shapeHits(element, poly, n)
            is ImageElement -> !element.isLockedToBackground && quadHits(element.bounds, element.rotationDeg, poly, n)
            is TextBoxElement -> quadHits(element.bounds, element.rotationDeg, poly, n)
            is StencilInstance -> quadHits(element.spatialBounds, 0f, poly, n)
            is AudioClipElement -> quadHits(element.spatialBounds, 0f, poly, n)
        }
    }

    private fun strokeHits(s: Stroke, poly: FloatArray, n: Int): Boolean {
        val pts = s.points
        if (pts.isEmpty()) return false
        if (pointInPolygon(pts[0].x, pts[0].y, poly, n)) return true
        for (i in 1 until pts.size) {
            val a = pts[i - 1]
            val b = pts[i]
            if (pointInPolygon(b.x, b.y, poly, n)) return true
            if (segmentHitsPolygonEdges(a.x, a.y, b.x, b.y, poly, n)) return true
        }
        return false
    }

    private fun shapeHits(s: ShapePrimitive, poly: FloatArray, n: Int): Boolean {
        val outline: List<PointMm> = ShapeGeometry.outlinePolygon(s)?.takeIf { it.size >= 2 } ?: s.vertices
        val closed = ShapeGeometry.outlinePolygon(s) != null
        if (outline.size == 1) return pointInPolygon(outline[0].x, outline[0].y, poly, n)
        for (i in outline.indices) {
            val a = outline[i]
            if (pointInPolygon(a.x, a.y, poly, n)) return true
            if (i + 1 < outline.size) {
                val b = outline[i + 1]
                if (segmentHitsPolygonEdges(a.x, a.y, b.x, b.y, poly, n)) return true
            } else if (closed) {
                val b = outline[0]
                if (segmentHitsPolygonEdges(a.x, a.y, b.x, b.y, poly, n)) return true
            }
        }
        return false
    }

    private fun quadHits(frame: StrokeBounds, rotationDeg: Float, poly: FloatArray, n: Int): Boolean {
        val corners = frameCorners(frame, rotationDeg)
        for (i in 0 until 4) {
            if (pointInPolygon(corners[i * 2], corners[i * 2 + 1], poly, n)) return true
        }
        for (i in 0 until 4) {
            val j = (i + 1) % 4
            if (segmentHitsPolygonEdges(corners[i * 2], corners[i * 2 + 1], corners[j * 2], corners[j * 2 + 1], poly, n)) return true
        }
        return false
    }

    /** Corners (x0,y0,...,x3,y3) of [frame] rotated by [rotationDeg] about its centre. */
    fun frameCorners(frame: StrokeBounds, rotationDeg: Float): FloatArray {
        val rad = Math.toRadians(rotationDeg.toDouble())
        val c = cos(rad).toFloat()
        val s = sin(rad).toFloat()
        val hw = frame.width * 0.5f
        val hh = frame.height * 0.5f
        val cx = frame.centerX
        val cy = frame.centerY
        val lx = floatArrayOf(-hw, hw, hw, -hw)
        val ly = floatArrayOf(-hh, -hh, hh, hh)
        val out = FloatArray(8)
        for (i in 0 until 4) {
            out[i * 2] = cx + lx[i] * c - ly[i] * s
            out[i * 2 + 1] = cy + lx[i] * s + ly[i] * c
        }
        return out
    }

    fun pointInPolygon(x: Float, y: Float, poly: FloatArray, n: Int): Boolean {
        var inside = false
        var j = n - 1
        for (i in 0 until n) {
            val xi = poly[i * 2]
            val yi = poly[i * 2 + 1]
            val xj = poly[j * 2]
            val yj = poly[j * 2 + 1]
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside
            j = i
        }
        return inside
    }

    private fun segmentHitsPolygonEdges(ax: Float, ay: Float, bx: Float, by: Float, poly: FloatArray, n: Int): Boolean {
        var j = n - 1
        for (i in 0 until n) {
            if (segmentsIntersect(ax, ay, bx, by, poly[j * 2], poly[j * 2 + 1], poly[i * 2], poly[i * 2 + 1])) return true
            j = i
        }
        return false
    }

    fun segmentsIntersect(
        ax: Float, ay: Float, bx: Float, by: Float,
        cx: Float, cy: Float, dx: Float, dy: Float
    ): Boolean {
        val d1 = cross(cx, cy, dx, dy, ax, ay)
        val d2 = cross(cx, cy, dx, dy, bx, by)
        val d3 = cross(ax, ay, bx, by, cx, cy)
        val d4 = cross(ax, ay, bx, by, dx, dy)
        if (((d1 > 0f && d2 < 0f) || (d1 < 0f && d2 > 0f)) && ((d3 > 0f && d4 < 0f) || (d3 < 0f && d4 > 0f))) return true
        if (d1 == 0f && onSegment(cx, cy, dx, dy, ax, ay)) return true
        if (d2 == 0f && onSegment(cx, cy, dx, dy, bx, by)) return true
        if (d3 == 0f && onSegment(ax, ay, bx, by, cx, cy)) return true
        if (d4 == 0f && onSegment(ax, ay, bx, by, dx, dy)) return true
        return false
    }

    private fun cross(ax: Float, ay: Float, bx: Float, by: Float, px: Float, py: Float): Float =
        (bx - ax) * (py - ay) - (by - ay) * (px - ax)

    private fun onSegment(ax: Float, ay: Float, bx: Float, by: Float, px: Float, py: Float): Boolean =
        px >= min(ax, bx) && px <= max(ax, bx) && py >= min(ay, by) && py <= max(ay, by)

    fun dist(ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = ax - bx
        val dy = ay - by
        return sqrt(dx * dx + dy * dy)
    }

    fun union(a: StrokeBounds?, b: StrokeBounds): StrokeBounds =
        if (a == null) b else StrokeBounds(min(a.left, b.left), min(a.top, b.top), max(a.right, b.right), max(a.bottom, b.bottom))

    fun approxEq(a: Float, b: Float): Boolean = abs(a - b) < 1e-4f
}
