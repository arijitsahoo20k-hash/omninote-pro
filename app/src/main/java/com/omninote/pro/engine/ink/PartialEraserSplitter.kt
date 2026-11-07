package com.omninote.pro.engine.ink

import com.omninote.pro.core.model.Stroke
import com.omninote.pro.core.model.StrokeBounds
import com.omninote.pro.core.model.StrokePoint
import java.util.UUID
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Exact geometry of the Pixel / Partial eraser. The swept eraser is a capsule: every point within [radius] of the
 * segment A to B (a plain disc when A == B, as on touch-down).
 *
 * A capsule is convex, so each straight spine segment of a stroke meets it in a single interval of the segment's
 * parameter t in [0, 1]. [segmentInterval] computes that interval exactly (line vs. two end discs and the middle
 * rectangle), and [split] cuts the spine at those exact entry and exit parameters, interpolating pressure, tilt,
 * azimuth and time at each cut with [StrokePoint.lerp].
 */
object PartialEraserSplitter {

    class Capsule(val ax: Float, val ay: Float, val bx: Float, val by: Float, val radius: Float) {
        val length: Float = hypot(bx - ax, by - ay)
        val centerX: Float get() = (ax + bx) * 0.5f
        val centerY: Float get() = (ay + by) * 0.5f

        fun withRadius(r: Float) = Capsule(ax, ay, bx, by, r)
    }

    private const val EPS = 1e-6f
    private const val EPS_T = 1e-5f
    private const val MIN_FRAGMENT_MM = 0.02f

    /**
     * Writes the [lo, hi] sub-interval of the spine segment P(0) to Q(1) that lies inside [cap] into [out]
     * (out[0] = lo, out[1] = hi). Returns false when the segment does not touch the capsule.
     */
    fun segmentInterval(px: Float, py: Float, qx: Float, qy: Float, cap: Capsule, out: FloatArray): Boolean {
        val dx = qx - px
        val dy = qy - py
        var lo = Float.MAX_VALUE
        var hi = -Float.MAX_VALUE

        fun take(a: Float, b: Float) {
            if (a < lo) lo = a
            if (b > hi) hi = b
        }

        // End discs.
        val d2 = dx * dx + dy * dy
        val r = cap.radius
        for (end in 0..1) {
            val cx = if (end == 0) cap.ax else cap.bx
            val cy = if (end == 0) cap.ay else cap.by
            val ox = px - cx
            val oy = py - cy
            val c = ox * ox + oy * oy - r * r
            if (d2 < EPS) {
                if (c <= 0f) take(0f, 1f)
                continue
            }
            val b = 2f * (dx * ox + dy * oy)
            val disc = b * b - 4f * d2 * c
            if (disc < 0f) continue
            val s = sqrt(disc)
            take((-b - s) / (2f * d2), (-b + s) / (2f * d2))
        }

        // Middle rectangle in the capsule's own frame: u along A to B in [0, L], v across in [-r, r].
        val len = cap.length
        if (len > EPS) {
            val ex = (cap.bx - cap.ax) / len
            val ey = (cap.by - cap.ay) / len
            val nx = -ey
            val ny = ex
            val u0 = (px - cap.ax) * ex + (py - cap.ay) * ey
            val v0 = (px - cap.ax) * nx + (py - cap.ay) * ny
            val du = dx * ex + dy * ey
            val dv = dx * nx + dy * ny
            var tLo = -Float.MAX_VALUE
            var tHi = Float.MAX_VALUE
            var empty = false
            fun clip(f0: Float, f1: Float, min: Float, max: Float) {
                if (abs(f1) < EPS) {
                    if (f0 < min || f0 > max) empty = true
                    return
                }
                var a = (min - f0) / f1
                var b = (max - f0) / f1
                if (a > b) {
                    val t = a; a = b; b = t
                }
                if (a > tLo) tLo = a
                if (b < tHi) tHi = b
            }
            clip(u0, du, 0f, len)
            clip(v0, dv, -r, r)
            if (!empty && tLo <= tHi) take(tLo, tHi)
        }

        if (hi < lo) return false
        val a = max(lo, 0f)
        val b = min(hi, 1f)
        if (a > b) return false
        out[0] = a
        out[1] = b
        return true
    }

    /** True if any part of [points] (a spine) comes within the capsule radius. */
    fun touches(points: List<StrokePoint>, cap: Capsule): Boolean {
        if (points.isEmpty()) return false
        val out = FloatArray(2)
        if (points.size == 1) {
            val p = points[0]
            return segmentInterval(p.x, p.y, p.x, p.y, cap, out)
        }
        for (i in 0 until points.size - 1) {
            val a = points[i]
            val b = points[i + 1]
            if (segmentInterval(a.x, a.y, b.x, b.y, cap, out)) return true
        }
        return false
    }

    /**
     * Cuts [stroke] against [cap] (whose radius should already include the stroke's half width).
     *
     * @return null if the stroke is untouched; an empty list if it is completely erased; otherwise the 1..N
     *         surviving pieces as new strokes (fresh ids, same brush, colour and audio offset, recomputed bounds).
     */
    fun split(stroke: Stroke, cap: Capsule): List<Stroke>? {
        val pts = stroke.points
        if (pts.isEmpty()) return null
        val runs = cutSpine(pts, cap) ?: return null
        val pad = stroke.brush.widthMm * Stroke.MAX_WIDTH_FACTOR
        val out = ArrayList<Stroke>(runs.size)
        for (run in runs) {
            out.add(
                stroke.copy(
                    id = UUID.randomUUID().toString(),
                    points = run,
                    bounds = StrokeBounds.of(run, pad)
                )
            )
        }
        return out
    }

    /** Spine-only cut; returns null if untouched, otherwise the kept runs (possibly none). */
    internal fun cutSpine(pts: List<StrokePoint>, cap: Capsule): List<List<StrokePoint>>? {
        val iv = FloatArray(2)
        if (pts.size == 1) {
            val p = pts[0]
            return if (segmentInterval(p.x, p.y, p.x, p.y, cap, iv)) emptyList() else null
        }

        val runs = ArrayList<List<StrokePoint>>()
        var run = ArrayList<StrokePoint>()
        var touched = false

        for (i in 0 until pts.size - 1) {
            val p = pts[i]
            val q = pts[i + 1]
            val hit = segmentInterval(p.x, p.y, q.x, q.y, cap, iv)
            val startKept = !hit || iv[0] > EPS_T

            if (!hit) {
                if (run.isEmpty()) run.add(p)
                run.add(q)
                continue
            }
            touched = true
            if (startKept) {
                if (run.isEmpty()) run.add(p)
                run.add(p.lerp(q, iv[0]))
            }
            closeRun(run, runs)
            run = ArrayList()
            if (iv[1] < 1f - EPS_T) {
                run.add(p.lerp(q, iv[1]))
                run.add(q)
            }
        }
        closeRun(run, runs)
        return if (touched) runs else null
    }

    private fun closeRun(run: ArrayList<StrokePoint>, runs: MutableList<List<StrokePoint>>) {
        if (run.size < 2) return
        var length = 0f
        for (i in 1 until run.size) length += run[i - 1].distanceTo(run[i])
        if (length >= MIN_FRAGMENT_MM) runs.add(run)
    }
}
