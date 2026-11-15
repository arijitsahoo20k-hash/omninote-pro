package com.omninote.pro.engine.input

import kotlin.math.ln
import kotlin.math.pow

/**
 * Logarithmic mapping between a zoom factor and a 0..1 slider fraction, so every doubling of zoom takes the same
 * slider distance. Pure maths; no Android types.
 */
object ZoomScale {
    /** Slider fraction (0 at [minZoom], 1 at [maxZoom]) for [zoom]; out-of-range zooms are clamped. */
    fun fractionForZoom(zoom: Float, minZoom: Float, maxZoom: Float): Float {
        if (maxZoom <= minZoom) return 0f
        val z = zoom.coerceIn(minZoom, maxZoom)
        return (ln(z / minZoom) / ln(maxZoom / minZoom)).coerceIn(0f, 1f)
    }

    /** The zoom factor at slider [fraction] (clamped to 0..1). */
    fun zoomForFraction(fraction: Float, minZoom: Float, maxZoom: Float): Float {
        if (maxZoom <= minZoom) return minZoom
        return minZoom * (maxZoom / minZoom).pow(fraction.coerceIn(0f, 1f))
    }
}
