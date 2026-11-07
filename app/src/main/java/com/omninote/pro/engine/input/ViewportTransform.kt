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

    fun applyGesture(g: GestureCommand.Transform): ViewportTransform {
        val targetZoom = (zoom * g.scaleFactor).coerceIn(MIN_ZOOM, MAX_ZOOM)
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

    companion object {
        const val MIN_ZOOM = 0.25f
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
