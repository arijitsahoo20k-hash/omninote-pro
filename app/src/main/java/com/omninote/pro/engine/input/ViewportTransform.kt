package com.omninote.pro.engine.input

import kotlin.math.cos
import kotlin.math.sin

/**
 * Immutable page-space <-> screen-space mapping.
 *
 * screen = R(rotationDeg) * (page_mm * pxPerMm) + translation
 *
 * Pure math (no android.graphics.Matrix) so it is allocation-free on the 240Hz input path and unit-testable.
 */
data class ViewportTransform(
    val zoom: Float = 1f,
    val translationX: Float = 0f,
    val translationY: Float = 0f,
    val rotationDeg: Float = 0f,
    val basePxPerMm: Float = DEFAULT_BASE_PX_PER_MM
) {
    val pxPerMm: Float get() = basePxPerMm * zoom

    private val cosR: Float = cos(Math.toRadians(rotationDeg.toDouble())).toFloat()
    private val sinR: Float = sin(Math.toRadians(rotationDeg.toDouble())).toFloat()

    /** Writes page-space millimetres into out[0], out[1]. */
    fun screenToPage(sx: Float, sy: Float, out: FloatArray) {
        val dx = sx - translationX
        val dy = sy - translationY
        val s = pxPerMm
        out[0] = (dx * cosR + dy * sinR) / s
        out[1] = (-dx * sinR + dy * cosR) / s
    }

    /** Writes screen pixels into out[0], out[1]. */
    fun pageToScreen(pxMm: Float, pyMm: Float, out: FloatArray) {
        val s = pxPerMm
        val x = pxMm * s
        val y = pyMm * s
        out[0] = x * cosR - y * sinR + translationX
        out[1] = x * sinR + y * cosR + translationY
    }

    fun applyGesture(g: GestureCommand.Transform, minZoom: Float = MIN_ZOOM): ViewportTransform {
        val targetZoom = (zoom * g.scaleFactor).coerceIn(minOf(minZoom, MAX_ZOOM), MAX_ZOOM)
        val s = targetZoom / zoom
        val rad = Math.toRadians(g.rotationDeltaDeg.toDouble())
        val cr = cos(rad).toFloat()
        val sr = sin(rad).toFloat()
        val relX = translationX - g.focalX
        val relY = translationY - g.focalY
        return copy(
            zoom = targetZoom,
            translationX = g.focalX + g.panDx + s * (cr * relX - sr * relY),
            translationY = g.focalY + g.panDy + s * (sr * relX + cr * relY),
            rotationDeg = normalizeDegrees(rotationDeg + g.rotationDeltaDeg)
        )
    }

    /** Zoom so the page width fills the view, unrotated, left-aligned; vertical offset is preserved. */
    fun fitToWidth(viewWidthPx: Float, pageWidthMm: Float): ViewportTransform {
        val targetZoom = (viewWidthPx / (pageWidthMm * basePxPerMm)).coerceIn(MIN_ZOOM, MAX_ZOOM)
        return copy(zoom = targetZoom, translationX = 0f, rotationDeg = 0f)
    }

    /**
     * Keeps a paged sheet (and the stack of neighbouring sheets above and below it) on the desk.
     *
     *  - Horizontally: a sheet narrower than the view is centred; a wider one may not leave a gap on either side.
     *  - Vertically: the stack may not scroll past the first sheet's top or the last sheet's bottom, except that the
     *    last sheet may be pulled up by [overscrollBottomPx] (the "pull to add a page" gesture).
     *  - [smoothing] in (0, 1]: fraction of the correction applied per call. Use < 1 while a gesture is live so a small
     *    sheet glides to the centre, and 1 once it ends so the result is exact.
     *
     * Only applies to an unrotated viewport; a rotated one is returned unchanged.
     */
    fun constrainToPageBounds(
        viewWidthPx: Float,
        viewHeightPx: Float,
        pageWidthMm: Float,
        pageHeightMm: Float,
        hasPreviousSheet: Boolean,
        hasNextSheet: Boolean,
        marginPx: Float,
        overscrollBottomPx: Float = 0f,
        smoothing: Float = 1f
    ): ViewportTransform {
        if (rotationDeg != 0f || viewWidthPx <= 0f || viewHeightPx <= 0f) return this
        val pw = pageWidthMm * pxPerMm
        val ph = pageHeightMm * pxPerMm
        val k = smoothing.coerceIn(0.05f, 1f)

        val targetX = if (pw + 2f * marginPx <= viewWidthPx) {
            (viewWidthPx - pw) / 2f
        } else {
            translationX.coerceIn(viewWidthPx - pw - marginPx, marginPx)
        }
        val nx = if (pw + 2f * marginPx <= viewWidthPx) translationX + (targetX - translationX) * k else targetX

        var ty = translationY
        if (!hasPreviousSheet && !hasNextSheet && ph + 2f * marginPx <= viewHeightPx) {
            val centred = (viewHeightPx - ph) / 2f
            ty = translationY + (centred - translationY) * k
        } else {
            if (!hasPreviousSheet) ty = minOf(ty, marginPx)
            if (!hasNextSheet) {
                val floorY = viewHeightPx - ph - marginPx - overscrollBottomPx
                // A short last sheet stays pinned to the top instead of floating up.
                ty = maxOf(ty, minOf(floorY, marginPx - overscrollBottomPx))
            }
        }
        if (nx == translationX && ty == translationY) return this
        return copy(translationX = nx, translationY = ty)
    }

    companion object {
        const val MIN_ZOOM = 0.25f

        /** Smallest zoom in paged (PAGINATED / PDF) mode, relative to fit-to-width. */
        const val PAGED_MIN_FIT_FRACTION = 0.6f

        fun fitZoom(viewWidthPx: Float, pageWidthMm: Float, basePxPerMm: Float = DEFAULT_BASE_PX_PER_MM): Float =
            (viewWidthPx / (pageWidthMm * basePxPerMm)).coerceIn(MIN_ZOOM, MAX_ZOOM)

        fun pagedMinZoom(viewWidthPx: Float, pageWidthMm: Float, basePxPerMm: Float = DEFAULT_BASE_PX_PER_MM): Float =
            maxOf(MIN_ZOOM, PAGED_MIN_FIT_FRACTION * fitZoom(viewWidthPx, pageWidthMm, basePxPerMm))
        const val MAX_ZOOM = 10f

        /** Redmi Pad Pro 5G panel is roughly 249 ppi. */
        const val DEFAULT_BASE_PX_PER_MM = 249f / 25.4f

        fun basePxPerMmFor(xdpi: Float): Float = xdpi / 25.4f

        fun normalizeDegrees(d: Float): Float {
            val m = d % 360f
            return if (m < 0f) m + 360f else m
        }
    }
}
