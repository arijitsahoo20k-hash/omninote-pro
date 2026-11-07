package com.omninote.pro.engine.ink

import com.omninote.pro.core.model.NotebookPage
import com.omninote.pro.core.model.PointMm
import com.omninote.pro.core.model.ShapePrimitive
import com.omninote.pro.core.model.ShapeType
import com.omninote.pro.core.model.Stroke
import com.omninote.pro.core.model.StrokePoint
import com.omninote.pro.data.AddStroke
import com.omninote.pro.data.CanvasEditCommand
import com.omninote.pro.data.SnapStrokeToShape
import java.util.UUID
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Classifies a hand-drawn stroke into one of the 17 [ShapeType] primitives and returns clean, regularised control
 * vertices (meaning per type is documented on ShapePrimitive).
 *
 * Open strokes: LINE, ARROW_SINGLE/DOUBLE, AXES_3D, ARC, SINE_WAVE, PARABOLA, AXES_2D, POLYLINE.
 * Closed strokes: CIRCLE, ELLIPSE, TRIANGLE (equilateral / right / isosceles regularised), RECTANGLE, SQUARE,
 * PARALLELOGRAM, PENTAGON, HEXAGON.
 *
 * Directions snap magnetically to 0, 15, 30, 45, 60 and 90 degrees (mirrored into every quadrant) relative to the page.
 * Everything works in page millimetres and is independent of zoom.
 */
object GeometricSnapClassifier {

    class Result(val type: ShapeType, val vertices: List<PointMm>)

    private class V(val x: Float, val y: Float) {
        fun dist(o: V) = hypot(o.x - x, o.y - y)
        fun toMm() = PointMm(x, y)
    }

    private const val MIN_STROKE_MM = 5f
    private const val RESAMPLE_N = 96
    private const val DETENT_TOLERANCE_DEG = 4.5f
    private val DETENTS_DEG = floatArrayOf(0f, 15f, 30f, 45f, 60f, 90f)

    fun classify(points: List<StrokePoint>): Result? {
        val raw = dedupe(points)
        if (raw.size < 3) return null
        var length = 0f
        for (i in 1 until raw.size) length += raw[i - 1].dist(raw[i])
        if (length < MIN_STROKE_MM) return null

        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (p in raw) {
            minX = min(minX, p.x); maxX = max(maxX, p.x)
            minY = min(minY, p.y); maxY = max(maxY, p.y)
        }
        val diag = hypot(maxX - minX, maxY - minY)
        if (diag < 1e-3f) return null

        val closure = raw.first().dist(raw.last())
        val closed = length > 12f && closure <= max(0.18f * length, 2.5f) && diag > 4f
        return if (closed) classifyClosed(raw, length, diag) else classifyOpen(raw, length, diag)
    }

    // =====================================================================================
    // Open strokes
    // =====================================================================================

    private fun classifyOpen(raw: List<V>, length: Float, diag: Float): Result? {
        val start = raw.first()
        val end = raw.last()
        val chord = start.dist(end)

        if (chord > 0.5f * length && maxChordDeviation(raw, start, end) <= 0.045f * chord + 0.3f) {
            val snappedEnd = snapEnd(start, end)
            return Result(ShapeType.LINE, listOf(start.toMm(), snappedEnd.toMm()))
        }

        val simplified = rdp(raw, 0.045f * diag)
        axes3d(simplified)?.let { return it }
        arrow(simplified)?.let { return it }

        val rs = resample(raw, RESAMPLE_N, closed = false)
        val circle = fitCircle(rs)
        val arcScore = circle?.let { it.rms / it.r }
        val parabola = fitParabola(rs, diag)
        val sine = fitSine(rs, chord)

        if (sine != null) return sine
        if (parabola != null && (arcScore == null || parabola.score < 0.5f * arcScore)) return parabola.result
        if (circle != null && arcScore!! < 0.035f) arcResult(rs, circle)?.let { return it }

        if (simplified.size == 3) axes2d(simplified)?.let { return it }
        if (simplified.size in 3..8 && simplified.subList(1, simplified.size - 1).indices.all { i ->
                turnDeg(simplified[i], simplified[i + 1], simplified[i + 2]) >= 20f
            }
        ) {
            val snapped = ArrayList<PointMm>(simplified.size)
            snapped.add(simplified[0].toMm())
            for (i in 1 until simplified.size) snapped.add(snapEnd(simplified[i - 1], simplified[i]).toMm())
            return Result(ShapeType.POLYLINE, snapped)
        }
        return null
    }

    private fun maxChordDeviation(pts: List<V>, a: V, b: V): Float {
        val len = a.dist(b)
        if (len < 1e-4f) return Float.MAX_VALUE
        var worst = 0f
        for (p in pts) {
            val d = abs((b.x - a.x) * (a.y - p.y) - (a.x - p.x) * (b.y - a.y)) / len
            if (d > worst) worst = d
        }
        return worst
    }

    // ---------------- axes and arrows ----------------

    /** [E1, O, E2, O', E3]: three legs from one origin, visited by retracing. */
    private fun axes3d(s: List<V>): Result? {
        if (s.size !in 5..6) return null
        val o = s[1]
        val back = s[3]
        val legs = floatArrayOf(s[0].dist(o), s[2].dist(o), s[4].dist(back))
        val maxLeg = legs.max()
        if (maxLeg < 8f || legs.min() < 0.55f * maxLeg) return null
        if (o.dist(back) > 0.15f * maxLeg) return null
        val ends = listOf(s[0], s[2], s[4])
        val angles = ends.map { atan2(-(it.y - o.y), it.x - o.x) }
        for (i in 0 until 3) for (j in i + 1 until 3) {
            if (angleBetween(angles[i], angles[j]) < 35f) return null
        }
        val yEnd = ends.minByOrNull { it.y }!!
        val rest = ends.filter { it !== yEnd }
        val xEnd = rest.maxByOrNull { it.x }!!
        val zEnd = rest.first { it !== xEnd }
        return Result(ShapeType.AXES_3D, listOf(o.toMm(), xEnd.toMm(), yEnd.toMm(), zEnd.toMm()))
    }

    /** Longest segment is the shaft; vertices clustered behind an end on both sides make an arrow head. */
    private fun arrow(s: List<V>): Result? {
        if (s.size < 5) return null
        var m = 0
        var best = 0f
        for (i in 0 until s.size - 1) {
            val d = s[i].dist(s[i + 1])
            if (d > best) {
                best = d
                m = i
            }
        }
        if (best < 8f) return null
        val p = s[m]
        val q = s[m + 1]
        val others = s.filterIndexed { i, _ -> i != m && i != m + 1 }
        val headAtP = hasHead(p, q, best, others)
        val headAtQ = hasHead(q, p, best, others)
        return when {
            headAtP && headAtQ -> Result(ShapeType.ARROW_DOUBLE, fixDouble(p, q))
            headAtQ -> Result(ShapeType.ARROW_SINGLE, listOf(p.toMm(), snapEnd(p, q).toMm()))
            headAtP -> Result(ShapeType.ARROW_SINGLE, listOf(q.toMm(), snapEnd(q, p).toMm()))
            else -> null
        }
    }

    private fun fixDouble(p: V, q: V): List<PointMm> = listOf(p.toMm(), snapEnd(p, q).toMm())

    private fun hasHead(tip: V, other: V, shaft: Float, candidates: List<V>): Boolean {
        val ax = (other.x - tip.x) / shaft
        val ay = (other.y - tip.y) / shaft
        var left = false
        var right = false
        for (w in candidates) {
            val d = tip.dist(w)
            if (d < 0.08f * shaft || d > 0.55f * shaft) continue
            val along = ((w.x - tip.x) * ax + (w.y - tip.y) * ay) / d
            val ang = Math.toDegrees(kotlin.math.acos(along.coerceIn(-1f, 1f).toDouble())).toFloat()
            if (ang < 12f || ang > 70f) continue
            val cross = (w.x - tip.x) * ay - (w.y - tip.y) * ax
            if (cross > 0f) left = true else right = true
        }
        return left && right
    }

    private fun axes2d(s: List<V>): Result? {
        val e1 = s[0]
        val o = s[1]
        val e2 = s[2]
        val l1 = e1.dist(o)
        val l2 = e2.dist(o)
        if (min(l1, l2) < 0.35f * max(l1, l2)) return null
        if (abs(interior(e1, o, e2) - 90f) > 14f) return null
        fun axisAligned(e: V): Boolean {
            val a = Math.toDegrees(atan2(abs(e.y - o.y).toDouble(), abs(e.x - o.x).toDouble())).toFloat()
            return a < 14f || a > 76f
        }
        if (!axisAligned(e1) || !axisAligned(e2)) return null
        val horiz = if (abs(e1.x - o.x) >= abs(e2.x - o.x)) e1 else e2
        val vert = if (horiz === e1) e2 else e1
        val xEnd = V(horiz.x, o.y)
        val yEnd = V(o.x, vert.y)
        return Result(ShapeType.AXES_2D, listOf(o.toMm(), xEnd.toMm(), yEnd.toMm()))
    }

    // ---------------- curves ----------------

    private class Circle(val cx: Float, val cy: Float, val r: Float, val rms: Float)

    /** Algebraic (Kasa) least-squares circle fit. */
    private fun fitCircle(pts: List<V>): Circle? {
        val n = pts.size
        var mx = 0.0
        var my = 0.0
        for (p in pts) { mx += p.x; my += p.y }
        mx /= n; my /= n
        var suu = 0.0; var suv = 0.0; var svv = 0.0; var suuu = 0.0; var svvv = 0.0; var suvv = 0.0; var svuu = 0.0
        for (p in pts) {
            val u = p.x - mx
            val v = p.y - my
            suu += u * u; suv += u * v; svv += v * v
            suuu += u * u * u; svvv += v * v * v
            suvv += u * v * v; svuu += v * u * u
        }
        val det = suu * svv - suv * suv
        if (abs(det) < 1e-9) return null
        val b1 = 0.5 * (suuu + suvv)
        val b2 = 0.5 * (svvv + svuu)
        val uc = (b1 * svv - b2 * suv) / det
        val vc = (suu * b2 - suv * b1) / det
        val r = sqrt(uc * uc + vc * vc + (suu + svv) / n)
        val cx = (uc + mx).toFloat()
        val cy = (vc + my).toFloat()
        var sq = 0.0
        for (p in pts) {
            val d = hypot(p.x - cx, p.y - cy) - r
            sq += d * d
        }
        val rf = r.toFloat()
        if (rf < 2f || rf.isNaN()) return null
        return Circle(cx, cy, rf, sqrt(sq / n).toFloat())
    }

    private fun arcResult(rs: List<V>, c: Circle): Result? {
        var sweep = 0f
        var prev = atan2(rs[0].y - c.cy, rs[0].x - c.cx)
        for (i in 1 until rs.size) {
            val a = atan2(rs[i].y - c.cy, rs[i].x - c.cx)
            var d = a - prev
            while (d > PI) d -= (2 * PI).toFloat()
            while (d < -PI) d += (2 * PI).toFloat()
            sweep += d
            prev = a
        }
        val deg = Math.toDegrees(abs(sweep).toDouble()).toFloat()
        if (deg < 25f || deg > 340f) return null
        fun onCircle(p: V): PointMm {
            val d = hypot(p.x - c.cx, p.y - c.cy).coerceAtLeast(1e-4f)
            return PointMm(c.cx + (p.x - c.cx) / d * c.r, c.cy + (p.y - c.cy) / d * c.r)
        }
        return Result(ShapeType.ARC, listOf(onCircle(rs.first()), onCircle(rs[rs.size / 2]), onCircle(rs.last())))
    }

    private class Parabola(val score: Float, val result: Result)

    /** y = a t^2 + b t + c over the x range (vertical axis); accepted only if it truly turns around inside the range. */
    private fun fitParabola(pts: List<V>, diag: Float): Parabola? {
        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        for (p in pts) { minX = min(minX, p.x); maxX = max(maxX, p.x) }
        val range = maxX - minX
        if (range < 0.35f * diag) return null
        val n = pts.size
        var s0 = 0.0; var s1 = 0.0; var s2 = 0.0; var s3 = 0.0; var s4 = 0.0
        var t0 = 0.0; var t1 = 0.0; var t2 = 0.0
        for (p in pts) {
            val t = ((p.x - minX) / range).toDouble()
            val y = p.y.toDouble()
            s0 += 1; s1 += t; s2 += t * t; s3 += t * t * t; s4 += t * t * t * t
            t0 += y; t1 += t * y; t2 += t * t * y
        }
        // Solve the 3x3 normal equations for [c, b, a] with Cramer's rule.
        val m = arrayOf(doubleArrayOf(s0, s1, s2), doubleArrayOf(s1, s2, s3), doubleArrayOf(s2, s3, s4))
        val rhs = doubleArrayOf(t0, t1, t2)
        val det = det3(m)
        if (abs(det) < 1e-12) return null
        fun solveCol(col: Int): Double {
            val mm = Array(3) { r -> DoubleArray(3) { c -> if (c == col) rhs[r] else m[r][c] } }
            return det3(mm) / det
        }
        val c = solveCol(0)
        val b = solveCol(1)
        val a = solveCol(2)
        if (abs(a) < 1e-6) return null
        val tv = -b / (2 * a)
        if (tv < 0.2 || tv > 0.8) return null
        var sq = 0.0
        for (p in pts) {
            val t = ((p.x - minX) / range).toDouble()
            val d = a * t * t + b * t + c - p.y
            sq += d * d
        }
        val rms = sqrt(sq / n).toFloat()
        val score = rms / diag
        if (score > 0.03f) return null
        fun yAt(t: Double) = (a * t * t + b * t + c).toFloat()
        val left = PointMm(minX, yAt(0.0))
        val vertex = PointMm((minX + tv * range).toFloat(), yAt(tv))
        val right = PointMm(maxX, yAt(1.0))
        return Parabola(score, Result(ShapeType.PARABOLA, listOf(left, vertex, right)))
    }

    private fun det3(m: Array<DoubleArray>): Double =
        m[0][0] * (m[1][1] * m[2][2] - m[1][2] * m[2][1]) -
            m[0][1] * (m[1][0] * m[2][2] - m[1][2] * m[2][0]) +
            m[0][2] * (m[1][0] * m[2][1] - m[1][1] * m[2][0])

    /**
     * Sine in the chord frame: needs at least three zero crossings, monotonic progress along the chord and a
     * reasonable model fit. Output carries a 4th vertex marking one wavelength along the baseline.
     */
    private fun fitSine(rs: List<V>, chord: Float): Result? {
        if (chord < 10f) return null
        val first = rs.first()
        val last = rs.last()
        val ex = (last.x - first.x) / chord
        val ey = (last.y - first.y) / chord
        val nx = -ey
        val ny = ex
        val n = rs.size
        val u = FloatArray(n)
        val v = FloatArray(n)
        for (i in 0 until n) {
            u[i] = (rs[i].x - first.x) * ex + (rs[i].y - first.y) * ey
            v[i] = (rs[i].x - first.x) * nx + (rs[i].y - first.y) * ny
        }
        var forward = 0
        for (i in 1 until n) if (u[i] > u[i - 1]) forward++
        if (forward < 0.92f * (n - 1)) return null
        val vMax = v.max()
        val vMin = v.min()
        val amp = (vMax - vMin) * 0.5f
        val off = (vMax + vMin) * 0.5f
        if (amp < 0.04f * chord) return null

        val cross = ArrayList<Float>()
        var sign0 = 0f
        for (i in 1 until n) {
            val a = v[i - 1] - off
            val b = v[i] - off
            if (a == 0f || a * b < 0f) {
                val t = if (a == b) 0f else a / (a - b)
                cross.add(u[i - 1] + (u[i] - u[i - 1]) * t)
                if (cross.size == 1) sign0 = if (b > a) 1f else -1f
            }
        }
        if (cross.size < 3) return null
        val half = (cross.last() - cross.first()) / (cross.size - 1)
        if (half < 2f) return null
        val k = (PI / half).toFloat()
        var sq = 0f
        for (i in 0 until n) {
            val model = off + sign0 * amp * sin(k * (u[i] - cross.first()))
            val d = v[i] - model
            sq += d * d
        }
        if (sqrt(sq / n) / amp > 0.3f) return null

        fun base(uu: Float, vv: Float) = PointMm(first.x + ex * uu + nx * vv, first.y + ey * uu + ny * vv)
        val s = base(cross.first(), off)
        val e = base(cross.last(), off)
        val crest = base(cross.first() + half * 0.5f, off + sign0 * amp)
        val wave = base(cross.first() + half * 2f, off)
        return Result(ShapeType.SINE_WAVE, listOf(s, e, crest, wave))
    }

    // =====================================================================================
    // Closed strokes
    // =====================================================================================

    private fun classifyClosed(raw: List<V>, length: Float, diag: Float): Result? {
        val rs = resample(raw, RESAMPLE_N, closed = true)
        val n = rs.size
        var cx = 0f
        var cy = 0f
        for (p in rs) { cx += p.x; cy += p.y }
        cx /= n; cy /= n
        var sxx = 0f; var sxy = 0f; var syy = 0f
        for (p in rs) {
            val dx = p.x - cx
            val dy = p.y - cy
            sxx += dx * dx; sxy += dx * dy; syy += dy * dy
        }
        sxx /= n; sxy /= n; syy /= n
        val theta = 0.5f * atan2(2f * sxy, sxx - syy)
        val tr = sxx + syy
        val disc = sqrt(((sxx - syy) * 0.5f) * ((sxx - syy) * 0.5f) + sxy * sxy)
        val l1 = tr * 0.5f + disc
        val l2 = max(tr * 0.5f - disc, 1e-6f)
        val a = sqrt(2f * l1)
        val b = sqrt(2f * l2)
        val cosT = cos(theta)
        val sinT = sin(theta)

        var meanR = 0f
        val radii = FloatArray(n)
        for (i in 0 until n) {
            val dx = rs[i].x - cx
            val dy = rs[i].y - cy
            val u = (dx * cosT + dy * sinT) / a
            val v = (-dx * sinT + dy * cosT) / b
            radii[i] = hypot(u, v)
            meanR += radii[i]
        }
        meanR /= n
        var varR = 0f
        for (r in radii) varR += (r - meanR) * (r - meanR)
        val relDev = sqrt(varR / n) / meanR

        if (relDev < 0.075f) {
            val ea = a * meanR
            val eb = b * meanR
            return if (eb / ea > 0.88f) {
                val rad = (ea + eb) * 0.5f
                Result(ShapeType.CIRCLE, listOf(PointMm(cx, cy), PointMm(cx + rad, cy)))
            } else {
                var ang = theta
                val snapped = snapAngle(ang)
                if (abs(angleDiff(snapped, ang)) < Math.toRadians(5.0)) ang = snapped
                Result(
                    ShapeType.ELLIPSE,
                    listOf(
                        PointMm(cx, cy),
                        PointMm(cx + ea * cos(ang), cy + ea * sin(ang)),
                        PointMm(cx - eb * sin(ang), cy + eb * cos(ang))
                    )
                )
            }
        }

        val corners = polygonCorners(raw, diag) ?: return null
        return when (corners.size) {
            3 -> triangle(corners)
            4 -> quad(corners)
            5, 6 -> regularPolygon(corners)
            else -> null
        }
    }

    /** Corner vertices of a closed stroke: the smallest tolerance that yields 3..6 sharp corners. */
    private fun polygonCorners(raw: List<V>, diag: Float): List<V>? {
        for (f in floatArrayOf(0.035f, 0.05f, 0.07f, 0.1f)) {
            val c = closedRdp(raw, f * diag)
            if (c.size !in 3..6) continue
            var sharp = true
            for (i in c.indices) {
                val prev = c[(i + c.size - 1) % c.size]
                val next = c[(i + 1) % c.size]
                if (turnDeg(prev, c[i], next) < 25f) sharp = false
            }
            if (sharp) return c
        }
        return null
    }

    private fun triangle(c: List<V>): Result {
        val s = floatArrayOf(c[0].dist(c[1]), c[1].dist(c[2]), c[2].dist(c[0]))
        val sMax = s.max()
        val sMin = s.min()
        val cx = (c[0].x + c[1].x + c[2].x) / 3f
        val cy = (c[0].y + c[1].y + c[2].y) / 3f

        if ((sMax - sMin) / sMax < 0.12f) {
            val side = s.average().toFloat()
            val rad = side / sqrt(3f)
            val phase = snapAngle(atan2(c[0].y - cy, c[0].x - cx))
            val dir = if (orientation(c[0], c[1], c[2]) >= 0f) 1f else -1f
            val pts = List(3) { i ->
                val ang = phase + dir * i * (2.0 * PI / 3.0).toFloat()
                PointMm(cx + rad * cos(ang), cy + rad * sin(ang))
            }
            return Result(ShapeType.TRIANGLE, pts)
        }
        for (i in 0 until 3) {
            val p = c[(i + 2) % 3]
            val q = c[i]
            val r = c[(i + 1) % 3]
            if (abs(interior(p, q, r) - 90f) < 8f) {
                val l1 = q.dist(p)
                val l2 = q.dist(r)
                val dir1 = snapDir(q, p)
                val rx = -(dir1.y - q.y)
                val ry = (dir1.x - q.x)
                val side = if ((r.x - q.x) * rx + (r.y - q.y) * ry >= 0f) 1f else -1f
                val ux = (dir1.x - q.x) / max(q.dist(dir1), 1e-6f)
                val uy = (dir1.y - q.y) / max(q.dist(dir1), 1e-6f)
                val pp = PointMm(q.x + ux * l1, q.y + uy * l1)
                val rr = PointMm(q.x - uy * l2 * side, q.y + ux * l2 * side)
                return Result(ShapeType.TRIANGLE, listOf(pp, q.toMm(), rr))
            }
        }
        for (i in 0 until 3) {
            val a = s[i]
            val b = s[(i + 1) % 3]
            if (abs(a - b) / max(a, b) < 0.09f) {
                // Sides i and i+1 meet at vertex (i+1); make them exactly equal.
                val apex = c[(i + 1) % 3]
                val p = c[i]
                val r = c[(i + 2) % 3]
                val leg = (a + b) * 0.5f
                val pp = scaleFrom(apex, p, leg)
                val rr = scaleFrom(apex, r, leg)
                val result = arrayOfNulls<PointMm>(3)
                result[i] = pp
                result[(i + 1) % 3] = apex.toMm()
                result[(i + 2) % 3] = rr
                return Result(ShapeType.TRIANGLE, result.map { it!! })
            }
        }
        return Result(ShapeType.TRIANGLE, c.map { it.toMm() })
    }

    private fun quad(c: List<V>): Result? {
        val ang = DoubleArray(4) { interior(c[(it + 3) % 4], c[it], c[(it + 1) % 4]).toDouble() }
        val sides = FloatArray(4) { c[it].dist(c[(it + 1) % 4]) }
        val cx = c.sumOf { it.x.toDouble() }.toFloat() / 4f
        val cy = c.sumOf { it.y.toDouble() }.toFloat() / 4f

        if (ang.all { abs(it - 90.0) < 14.0 }) {
            val w = (sides[0] + sides[2]) * 0.5f
            val h = (sides[1] + sides[3]) * 0.5f
            val dir = snapDir(c[0], c[1])
            var ux = dir.x - c[0].x
            var uy = dir.y - c[0].y
            val ul = hypot(ux, uy).coerceAtLeast(1e-6f)
            ux /= ul; uy /= ul
            // Perpendicular on the side the drawn shape actually extends to.
            var vx = -uy
            var vy = ux
            if ((c[3].x - c[0].x) * vx + (c[3].y - c[0].y) * vy < 0f) { vx = -vx; vy = -vy }
            val square = abs(w - h) / max(w, h) < 0.12f
            val ww = if (square) (w + h) * 0.5f else w
            val hh = if (square) ww else h
            val p0 = PointMm(cx - ux * ww / 2 - vx * hh / 2, cy - uy * ww / 2 - vy * hh / 2)
            val p1 = PointMm(cx + ux * ww / 2 + vx * hh / 2, cy + uy * ww / 2 + vy * hh / 2)
            val ref = PointMm(p0.x + ux, p0.y + uy)
            return Result(if (square) ShapeType.SQUARE else ShapeType.RECTANGLE, listOf(p0, p1, ref))
        }

        val d0 = lineAngleDeg(c[0], c[1])
        val d2 = lineAngleDeg(c[3], c[2])
        val d1 = lineAngleDeg(c[1], c[2])
        val d3 = lineAngleDeg(c[0], c[3])
        val parallel = angleDiffDeg(d0, d2) < 14f && angleDiffDeg(d1, d3) < 14f
        val similar = abs(sides[0] - sides[2]) / max(sides[0], sides[2]) < 0.2f &&
            abs(sides[1] - sides[3]) / max(sides[1], sides[3]) < 0.2f
        if (parallel && similar) {
            val p0 = c[0].toMm()
            val p1 = c[1].toMm()
            val p2 = c[2].toMm()
            return Result(ShapeType.PARALLELOGRAM, listOf(p0, p1, p2, PointMm(p0.x + p2.x - p1.x, p0.y + p2.y - p1.y)))
        }
        return null
    }

    private fun regularPolygon(c: List<V>): Result {
        val n = c.size
        var cx = 0f
        var cy = 0f
        for (p in c) { cx += p.x; cy += p.y }
        cx /= n; cy /= n
        val radii = FloatArray(n) { hypot(c[it].x - cx, c[it].y - cy) }
        val mean = radii.average().toFloat()
        var v = 0f
        for (r in radii) v += (r - mean) * (r - mean)
        val type = if (n == 5) ShapeType.PENTAGON else ShapeType.HEXAGON
        if (sqrt(v / n) / mean > 0.14f) return Result(type, c.map { it.toMm() })

        val dir = if (orientation(c[0], c[1], c[2]) >= 0f) 1f else -1f
        val step = (2.0 * PI / n).toFloat()
        var sx = 0f
        var sy = 0f
        for (i in 0 until n) {
            val a = atan2(c[i].y - cy, c[i].x - cx) - dir * i * step
            sx += cos(a); sy += sin(a)
        }
        val phase = atan2(sy, sx)
        return Result(
            type,
            List(n) { i ->
                val a = phase + dir * i * step
                PointMm(cx + mean * cos(a), cy + mean * sin(a))
            }
        )
    }

    // =====================================================================================
    // Magnetic detents
    // =====================================================================================

    /** Snaps [angleRad] (page space) to the nearest detent if within tolerance, else returns it unchanged. */
    fun snapAngle(angleRad: Float): Float {
        var deg = Math.toDegrees(angleRad.toDouble()).toFloat()
        deg %= 360f
        if (deg < 0f) deg += 360f
        val folded = deg % 180f
        val mirrored = folded > 90f
        val q = if (mirrored) 180f - folded else folded
        var best = q
        var bestD = DETENT_TOLERANCE_DEG
        for (d in DETENTS_DEG) {
            val dist = abs(q - d)
            if (dist <= bestD) {
                bestD = dist
                best = d
            }
        }
        val unfolded = if (mirrored) 180f - best else best
        val result = (deg - folded) + unfolded
        return Math.toRadians(result.toDouble()).toFloat()
    }

    private fun snapEnd(a: V, b: V): V {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val len = hypot(dx, dy)
        if (len < 1e-4f) return b
        val ang = snapAngle(atan2(dy, dx))
        return V(a.x + len * cos(ang), a.y + len * sin(ang))
    }

    /** Point at the same distance from [a] as [b] but with the snapped direction; used as a direction carrier. */
    private fun snapDir(a: V, b: V): V = snapEnd(a, b)

    // =====================================================================================
    // Geometry helpers
    // =====================================================================================

    private fun dedupe(points: List<StrokePoint>): List<V> {
        val out = ArrayList<V>(points.size)
        for (p in points) {
            val last = out.lastOrNull()
            if (last == null || hypot(p.x - last.x, p.y - last.y) >= 0.05f) out.add(V(p.x, p.y))
        }
        return out
    }

    private fun resample(pts: List<V>, n: Int, closed: Boolean): List<V> {
        val src = if (closed) pts + pts.first() else pts
        val cum = FloatArray(src.size)
        for (i in 1 until src.size) cum[i] = cum[i - 1] + src[i - 1].dist(src[i])
        val total = cum.last()
        val count = n
        val out = ArrayList<V>(count)
        var seg = 0
        for (k in 0 until count) {
            val target = if (closed) total * k / count else total * k / (count - 1)
            while (seg < src.size - 2 && cum[seg + 1] < target) seg++
            val span = cum[seg + 1] - cum[seg]
            val t = if (span < 1e-6f) 0f else ((target - cum[seg]) / span).coerceIn(0f, 1f)
            out.add(V(src[seg].x + (src[seg + 1].x - src[seg].x) * t, src[seg].y + (src[seg + 1].y - src[seg].y) * t))
        }
        return out
    }

    private fun rdp(pts: List<V>, eps: Float): List<V> {
        if (pts.size < 3) return pts
        val keep = BooleanArray(pts.size)
        keep[0] = true
        keep[pts.size - 1] = true
        rdpRange(pts, 0, pts.size - 1, eps, keep)
        return pts.filterIndexed { i, _ -> keep[i] }
    }

    private fun rdpRange(pts: List<V>, lo: Int, hi: Int, eps: Float, keep: BooleanArray) {
        if (hi <= lo + 1) return
        val a = pts[lo]
        val b = pts[hi]
        var worst = -1f
        var at = -1
        for (i in lo + 1 until hi) {
            val d = pointSegmentDistance(pts[i], a, b)
            if (d > worst) {
                worst = d
                at = i
            }
        }
        if (worst > eps && at >= 0) {
            keep[at] = true
            rdpRange(pts, lo, at, eps, keep)
            rdpRange(pts, at, hi, eps, keep)
        }
    }

    /** RDP on a closed loop: split at the point farthest from the start, simplify both halves, then merge a doubled seam. */
    private fun closedRdp(pts: List<V>, eps: Float): List<V> {
        var far = 0
        var best = -1f
        for (i in pts.indices) {
            val d = pts[0].dist(pts[i])
            if (d > best) {
                best = d
                far = i
            }
        }
        if (far < 2 || far > pts.size - 2) return pts
        val first = rdp(pts.subList(0, far + 1), eps)
        val second = rdp(pts.subList(far, pts.size) + pts[0], eps)
        val merged = ArrayList<V>(first)
        merged.addAll(second.subList(1, second.size - 1))
        return merged
    }

    private fun pointSegmentDistance(p: V, a: V, b: V): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val l2 = dx * dx + dy * dy
        if (l2 < 1e-9f) return p.dist(a)
        val t = (((p.x - a.x) * dx + (p.y - a.y) * dy) / l2).coerceIn(0f, 1f)
        return hypot(a.x + dx * t - p.x, a.y + dy * t - p.y)
    }

    /** Direction change at b in degrees (0 = straight on). */
    private fun turnDeg(a: V, b: V, c: V): Float = 180f - interior(a, b, c)

    private fun interior(a: V, b: V, c: V): Float =
        ShapeGeometry.interiorAngle(a.toMm(), b.toMm(), c.toMm())

    private fun orientation(a: V, b: V, c: V): Float = (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)

    private fun scaleFrom(origin: V, toward: V, length: Float): PointMm {
        val d = origin.dist(toward).coerceAtLeast(1e-6f)
        return PointMm(origin.x + (toward.x - origin.x) / d * length, origin.y + (toward.y - origin.y) / d * length)
    }

    private fun lineAngleDeg(a: V, b: V): Float = Math.toDegrees(atan2((b.y - a.y).toDouble(), (b.x - a.x).toDouble())).toFloat()

    private fun angleDiffDeg(a: Float, b: Float): Float {
        var d = abs(a - b) % 180f
        if (d > 90f) d = 180f - d
        return d
    }

    private fun angleBetween(a: Float, b: Float): Float {
        var d = abs(a - b)
        while (d > 2 * PI) d -= (2 * PI).toFloat()
        if (d > PI) d = (2 * PI).toFloat() - d
        return Math.toDegrees(d.toDouble()).toFloat()
    }

    private fun angleDiff(a: Float, b: Float): Float {
        var d = a - b
        while (d > PI) d -= (2 * PI).toFloat()
        while (d < -PI) d += (2 * PI).toFloat()
        return d
    }
}

/**
 * Hold-to-Snap for [FrontBufferedInkView]: when a pen stroke ends with the pen held still, classify it and replace
 * the raw stroke by a [ShapePrimitive]. Undo first reverts the snap (raw stroke returns), a second undo removes it.
 */
class GeometricSnapHook(
    private val postSnap: PostSnapEditController? = null,
    private val idFactory: () -> String = { UUID.randomUUID().toString() }
) : HoldToSnapHook {

    /** Id of the shape created by the most recent snap (mid-hold or at pen-up), or null before the first snap. */
    var lastSnappedShapeId: String? = null
        private set

    override fun onStrokeEnded(
        stroke: Stroke,
        heldStill: Boolean,
        page: NotebookPage,
        commit: (CanvasEditCommand) -> Unit
    ): Boolean {
        if (!heldStill) return false
        if (!stroke.brush.type.isInk || stroke.brush.type == com.omninote.pro.core.model.BrushType.STUDY_TAPE) return false
        val result = GeometricSnapClassifier.classify(stroke.points) ?: return false
        val shape = ShapePrimitive(
            id = idFactory(),
            layerId = stroke.layerId,
            type = result.type,
            vertices = result.vertices,
            brush = stroke.brush,
            createdAtMs = stroke.createdAtMs,
            semanticColorArgb = stroke.semanticColorArgb,
            audioOffsetMs = stroke.audioOffsetMs
        )
        commit(AddStroke(stroke))
        commit(SnapStrokeToShape(stroke.id, shape))
        lastSnappedShapeId = shape.id
        postSnap?.activate(shape.id)
        return true
    }
}
