package com.omninote.pro.core.model

import kotlinx.serialization.Serializable

/**
 * One unbatched hardware sample. Coordinates are in page space (millimetres from the
 * page origin) so strokes stay resolution-independent across zoom, export and devices.
 *
 * @property pressure raw hardware pressure in [0, 1] (4096 levels on Redmi Smart Pen 1S);
 *                    the pressure LUT is applied at render time, never baked in.
 * @property tilt AXIS_TILT in radians, 0 = perpendicular, PI/2 = flat.
 * @property orientation AXIS_ORIENTATION (azimuth) in radians.
 * @property timeMs event time relative to stroke start.
 */
@Serializable
data class StrokePoint(
    val x: Float,
    val y: Float,
    val pressure: Float = 1f,
    val tilt: Float = 0f,
    val orientation: Float = 0f,
    val timeMs: Long = 0L
) {
    fun distanceTo(other: StrokePoint): Float {
        val dx = other.x - x
        val dy = other.y - y
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    fun lerp(other: StrokePoint, t: Float): StrokePoint = StrokePoint(
        x = x + (other.x - x) * t,
        y = y + (other.y - y) * t,
        pressure = pressure + (other.pressure - pressure) * t,
        tilt = tilt + (other.tilt - tilt) * t,
        orientation = orientation + (other.orientation - orientation) * t,
        timeMs = timeMs + ((other.timeMs - timeMs) * t).toLong()
    )
}
