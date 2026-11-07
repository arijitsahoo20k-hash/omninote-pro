package com.omninote.pro.core.model

import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** A point in page space, millimetres from the page origin. */
@Serializable
data class PointMm(val x: Float, val y: Float) {
    fun distanceTo(o: PointMm): Float = hypot(o.x - x, o.y - y)
}

/** Every geometric primitive the Hold-to-Snap engine can produce (SPEC 4.1). */
@Serializable
enum class ShapeType {
    LINE,
    POLYLINE,
    ARROW_SINGLE,
    ARROW_DOUBLE,
    ARC,
    CIRCLE,
    ELLIPSE,
    TRIANGLE,
    RECTANGLE,
    SQUARE,
    PARALLELOGRAM,
    PENTAGON,
    HEXAGON,
    PARABOLA,
    SINE_WAVE,
    AXES_2D,
    AXES_3D;

    /** Closed outlines that accept the Fill Bucket (solid or hatched). */
    val isClosed: Boolean
        get() = when (this) {
            CIRCLE, ELLIPSE, TRIANGLE, RECTANGLE, SQUARE, PARALLELOGRAM, PENTAGON, HEXAGON -> true
            else -> false
        }
}

@Serializable
enum class FillType { NONE, SOLID, HATCHED }

/**
 * Fill for closed shapes. Hatching is generated as vector lines clipped to the outline,
 * which is what area-under-curve illustrations need.
 */
@Serializable
data class FillConfig(
    val type: FillType = FillType.NONE,
    val fillColorArgb: Int = 0xFF4F8F7A.toInt(),
    val hatchAngleDeg: Float = 45f,
    val hatchSpacingMm: Float = 2f,
    val opacity: Float = 0.35f
) {
    init {
        require(hatchSpacingMm > 0f) { "hatchSpacingMm must be positive" }
        require(opacity in 0f..1f) { "opacity must be within [0,1]" }
    }
}

/**
 * A snapped, editable vector shape. [vertices] are the draggable control nodes; their meaning per type:
 *
 * - LINE, ARROW_*: [start, end]
 * - POLYLINE, TRIANGLE, PENTAGON, HEXAGON, PARALLELOGRAM: outline vertices in order
 * - RECTANGLE, SQUARE: [corner, opposite corner, rotation reference] (3 points define a rotated rectangle)
 * - CIRCLE: [center, point on circumference]
 * - ELLIPSE: [center, end of first semi-axis, end of second semi-axis]
 * - ARC: [start, a point on the arc, end]
 * - PARABOLA: [left end, vertex, right end]
 * - SINE_WAVE: [start, end, crest control point] (amplitude = distance of crest point from the start-end line)
 * - AXES_2D: [origin, x-axis end, y-axis end]
 * - AXES_3D: [origin, x-axis end, y-axis end, z-axis end]
 */
@Serializable
data class ShapePrimitive(
    override val id: String,
    override val layerId: String,
    val type: ShapeType,
    val vertices: List<PointMm>,
    val brush: BrushConfig,
    val createdAtMs: Long,
    val semanticColorArgb: Int = brush.colorArgb,
    val fill: FillConfig = FillConfig(),
    val audioOffsetMs: Long = -1L,
    val bounds: StrokeBounds = computeBounds(type, vertices, brush)
) : PageElement {
    override val kind: SpatialItemKind get() = SpatialItemKind.SHAPE
    override val spatialBounds: StrokeBounds get() = bounds

    init {
        require(vertices.isNotEmpty()) { "A shape needs at least one control vertex" }
    }

    val isClosed: Boolean get() = type.isClosed

    val canFill: Boolean get() = type.isClosed

    fun withVertices(newVertices: List<PointMm>): ShapePrimitive =
        copy(vertices = newVertices, bounds = computeBounds(type, newVertices, brush))

    fun withBrush(newBrush: BrushConfig): ShapePrimitive =
        copy(
            brush = newBrush,
            semanticColorArgb = newBrush.colorArgb,
            bounds = computeBounds(type, vertices, newBrush)
        )

    fun withFill(newFill: FillConfig): ShapePrimitive {
        require(newFill.type == FillType.NONE || canFill) { "$type cannot be filled" }
        return copy(fill = newFill)
    }

    companion object {
        private const val ARC_SAMPLES = 32

        fun computeBounds(type: ShapeType, vertices: List<PointMm>, brush: BrushConfig): StrokeBounds {
            val padding = brush.widthMm * Stroke.MAX_WIDTH_FACTOR
            val core = when (type) {
                ShapeType.CIRCLE -> circleBounds(vertices)
                ShapeType.ELLIPSE -> ellipseBounds(vertices)
                ShapeType.ARC -> arcBounds(vertices)
                ShapeType.SINE_WAVE -> sineBounds(vertices)
                else -> pointBounds(vertices)
            }
            return core.inflate(padding)
        }

        private fun pointBounds(v: List<PointMm>): StrokeBounds {
            var l = Float.MAX_VALUE
            var t = Float.MAX_VALUE
            var r = -Float.MAX_VALUE
            var b = -Float.MAX_VALUE
            for (p in v) {
                if (p.x < l) l = p.x
                if (p.x > r) r = p.x
                if (p.y < t) t = p.y
                if (p.y > b) b = p.y
            }
            return StrokeBounds(l, t, r, b)
        }

        private fun circleBounds(v: List<PointMm>): StrokeBounds {
            if (v.size < 2) return pointBounds(v)
            val r = v[0].distanceTo(v[1])
            return StrokeBounds(v[0].x - r, v[0].y - r, v[0].x + r, v[0].y + r)
        }

        /** Points are c + a*cos(t) + b*sin(t); the extent along each axis is hypot of the components. */
        private fun ellipseBounds(v: List<PointMm>): StrokeBounds {
            if (v.size < 3) return pointBounds(v)
            val c = v[0]
            val ax = v[1].x - c.x
            val ay = v[1].y - c.y
            val bx = v[2].x - c.x
            val by = v[2].y - c.y
            val ex = hypot(ax, bx)
            val ey = hypot(ay, by)
            return StrokeBounds(c.x - ex, c.y - ey, c.x + ex, c.y + ey)
        }

        private fun sineBounds(v: List<PointMm>): StrokeBounds {
            if (v.size < 3) return pointBounds(v)
            val base = pointBounds(v.subList(0, 2))
            val a = v[0]
            val b = v[1]
            val c = v[2]
            val len = a.distanceTo(b)
            val amp = if (len < 1e-6f) a.distanceTo(c)
            else abs((b.x - a.x) * (a.y - c.y) - (a.x - c.x) * (b.y - a.y)) / len
            return base.inflate(amp)
        }

        /** Arc through start, a middle point and end: sample the circumscribed circle along the swept side. */
        private fun arcBounds(v: List<PointMm>): StrokeBounds {
            if (v.size < 3) return pointBounds(v)
            val a = v[0]
            val b = v[1]
            val c = v[2]
            val d = 2f * (a.x * (b.y - c.y) + b.x * (c.y - a.y) + c.x * (a.y - b.y))
            if (abs(d) < 1e-6f) return pointBounds(v)
            val a2 = a.x * a.x + a.y * a.y
            val b2 = b.x * b.x + b.y * b.y
            val c2 = c.x * c.x + c.y * c.y
            val ux = (a2 * (b.y - c.y) + b2 * (c.y - a.y) + c2 * (a.y - b.y)) / d
            val uy = (a2 * (c.x - b.x) + b2 * (a.x - c.x) + c2 * (b.x - a.x)) / d
            val radius = hypot(a.x - ux, a.y - uy)
            val a0 = atan2(a.y - uy, a.x - ux)
            val am = atan2(b.y - uy, b.x - ux)
            val a1 = atan2(c.y - uy, c.x - ux)
            val twoPi = (2.0 * PI).toFloat()
            val ccwSweep = normalizePositive(a1 - a0, twoPi)
            val ccwToMid = normalizePositive(am - a0, twoPi)
            val sweep = if (ccwToMid <= ccwSweep) ccwSweep else ccwSweep - twoPi
            var l = Float.MAX_VALUE
            var t = Float.MAX_VALUE
            var r = -Float.MAX_VALUE
            var bt = -Float.MAX_VALUE
            for (i in 0..ARC_SAMPLES) {
                val ang = a0 + sweep * i / ARC_SAMPLES
                val px = ux + radius * cos(ang)
                val py = uy + radius * sin(ang)
                if (px < l) l = px
                if (px > r) r = px
                if (py < t) t = py
                if (py > bt) bt = py
            }
            return StrokeBounds(l, t, r, bt)
        }

        private fun normalizePositive(angle: Float, twoPi: Float): Float {
            val m = angle % twoPi
            return if (m < 0f) m + twoPi else m
        }
    }
}
