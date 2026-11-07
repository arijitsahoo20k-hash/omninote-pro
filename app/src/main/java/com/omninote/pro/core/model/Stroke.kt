package com.omninote.pro.core.model

import kotlinx.serialization.Serializable

/** Axis-aligned bounding box in page space (mm). Used as the R-Tree key for viewport culling. */
@Serializable
data class StrokeBounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) * 0.5f
    val centerY: Float get() = (top + bottom) * 0.5f
    val area: Float get() = width * height

    fun intersects(o: StrokeBounds): Boolean =
        left <= o.right && right >= o.left && top <= o.bottom && bottom >= o.top

    fun contains(px: Float, py: Float): Boolean =
        px in left..right && py in top..bottom

    fun inflate(amount: Float): StrokeBounds =
        StrokeBounds(left - amount, top - amount, right + amount, bottom + amount)

    fun union(o: StrokeBounds): StrokeBounds =
        StrokeBounds(
            minOf(left, o.left), minOf(top, o.top),
            maxOf(right, o.right), maxOf(bottom, o.bottom)
        )

    companion object {
        val EMPTY = StrokeBounds(0f, 0f, 0f, 0f)

        fun of(points: List<StrokePoint>, padding: Float = 0f): StrokeBounds {
            if (points.isEmpty()) return EMPTY
            var l = Float.MAX_VALUE
            var t = Float.MAX_VALUE
            var r = -Float.MAX_VALUE
            var b = -Float.MAX_VALUE
            for (p in points) {
                if (p.x < l) l = p.x
                if (p.x > r) r = p.x
                if (p.y < t) t = p.y
                if (p.y > b) b = p.y
            }
            return StrokeBounds(l - padding, t - padding, r + padding, b + padding)
        }
    }
}

/**
 * A committed vector stroke. Immutable; edits produce copies so undo/redo can hold snapshots cheaply.
 *
 * @property audioOffsetMs offset into the notebook's synchronized audio track, or -1 if none.
 * @property semanticColorArgb original ink colour; [brush] colour may be display-inverted on dark paper
 *                             but exports use this value so PDFs on white paper stay standard ink.
 * @property isMasked Study Tape only: true = 100% opaque mask, false = 15% ghost reveal.
 */
@Serializable
data class Stroke(
    override val id: String,
    override val layerId: String,
    val brush: BrushConfig,
    val points: List<StrokePoint>,
    val createdAtMs: Long,
    val audioOffsetMs: Long = -1L,
    val semanticColorArgb: Int = brush.colorArgb,
    val isMasked: Boolean = true,
    val bounds: StrokeBounds = StrokeBounds.of(points, brush.widthMm * MAX_WIDTH_FACTOR)
) : PageElement {
    override val kind: SpatialItemKind get() = SpatialItemKind.STROKE
    override val spatialBounds: StrokeBounds get() = bounds

    val isEmpty: Boolean get() = points.isEmpty()

    fun withPoints(newPoints: List<StrokePoint>): Stroke =
        copy(points = newPoints, bounds = StrokeBounds.of(newPoints, brush.widthMm * MAX_WIDTH_FACTOR))

    fun withBrush(newBrush: BrushConfig): Stroke =
        copy(
            brush = newBrush,
            semanticColorArgb = newBrush.colorArgb,
            bounds = StrokeBounds.of(points, newBrush.widthMm * MAX_WIDTH_FACTOR)
        )

    companion object {
        /** Widest possible stroke multiplier (Brush Pen 2.8x, Pencil tilt shading 4x) so bounds never clip. */
        const val MAX_WIDTH_FACTOR = 4.0f
    }
}
