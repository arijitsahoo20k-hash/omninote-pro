package com.omninote.pro.engine.ink

import com.omninote.pro.engine.input.ViewportTransform
import kotlin.math.cos
import kotlin.math.sin

/**
 * Pure arithmetic for the over-scanned dry bitmap (no `android.graphics`, so it can be unit-tested).
 *
 * The dry layer is baked into a bitmap larger than the view by [OVERSCAN_FRACTION] on every side. The bake canvas is
 * translated by (+mx, +my) before the viewport is applied, so a page point that lands at screen pixel `s` under the
 * baked viewport sits at bitmap pixel `s + (mx, my)`.
 */
object DryBakeGeometry {
    const val OVERSCAN_FRACTION = 0.12f

    /** Over-scanned bitmaps above this size fall back to a margin of zero. */
    const val OVERSCAN_MAX_BYTES = 48L * 1024L * 1024L

    /** Per-side margins in pixels. */
    data class Margins(val x: Int, val y: Int)

    fun overscanMargins(viewWidth: Int, viewHeight: Int): Margins {
        if (viewWidth <= 0 || viewHeight <= 0) return Margins(0, 0)
        val mx = (viewWidth * OVERSCAN_FRACTION).toInt()
        val my = (viewHeight * OVERSCAN_FRACTION).toInt()
        val bytes = (viewWidth + 2L * mx) * (viewHeight + 2L * my) * 4L
        return if (bytes <= OVERSCAN_MAX_BYTES) Margins(mx, my) else Margins(0, 0)
    }

    /**
     * The affine map taking a pixel of a bitmap baked under [baked] (with margins [mx], [my]) to the screen pixel where
     * the same page point belongs under [live]. Returned in `android.graphics.Matrix.setValues` order:
     * `[scaleX, skewX, transX, skewY, scaleY, transY, 0, 0, 1]`.
     *
     * With `L = s_live R(rot_live) (s_baked R(rot_baked))^-1` the screen point is `L (q - m - t_baked) + t_live`.
     */
    fun compensation(baked: ViewportTransform, live: ViewportTransform, mx: Int, my: Int): FloatArray {
        val k = live.pxPerMm / baked.pxPerMm
        val phi = Math.toRadians((live.rotationDeg - baked.rotationDeg).toDouble())
        val a = (k * cos(phi)).toFloat()
        val b = (k * sin(phi)).toFloat()
        val ox = mx + baked.translationX
        val oy = my + baked.translationY
        return floatArrayOf(
            a, -b, live.translationX - (a * ox - b * oy),
            b, a, live.translationY - (b * ox + a * oy),
            0f, 0f, 1f
        )
    }

    /** Applies a [compensation] result to the bitmap pixel ([qx], [qy]); writes the screen pixel into [out]. */
    fun apply(m: FloatArray, qx: Float, qy: Float, out: FloatArray) {
        out[0] = m[0] * qx + m[1] * qy + m[2]
        out[1] = m[3] * qx + m[4] * qy + m[5]
    }
}
