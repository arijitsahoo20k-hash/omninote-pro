package com.omninote.pro.engine.ink

import com.omninote.pro.core.model.PressureCurveConfig
import com.omninote.pro.core.model.StrokePoint
import kotlin.math.PI
import kotlin.math.hypot

/**
 * 4096-entry pressure response table baked from the cubic Bezier (0,0) -> (c1x,c1y) -> (c2x,c2y) -> (1,1).
 * [mapPressure] is O(1) with no allocation, so it is safe in the 240Hz input path and in draw loops.
 */
class PressureCurveLut(val config: PressureCurveConfig = PressureCurveConfig()) {

    private val table = FloatArray(SIZE)

    init {
        build()
    }

    /** raw hardware pressure [0,1] -> effective pressure [0,1]. */
    fun mapPressure(rawPressure: Float): Float {
        val p = if (rawPressure < 0f) 0f else if (rawPressure > 1f) 1f else rawPressure
        return table[(p * (SIZE - 1) + 0.5f).toInt()]
    }

    private fun build() {
        val c1x = config.c1x
        val c1y = config.c1y
        val c2x = config.c2x
        val c2y = config.c2y
        var prevX = 0f
        var prevY = 0f
        var i = 0
        for (step in 1..CURVE_STEPS) {
            val t = step.toFloat() / CURVE_STEPS
            val u = 1f - t
            val b1 = 3f * u * u * t
            val b2 = 3f * u * t * t
            val b3 = t * t * t
            val x = b1 * c1x + b2 * c2x + b3
            val y = b1 * c1y + b2 * c2y + b3
            while (i < SIZE && i.toFloat() / (SIZE - 1) <= x) {
                val target = i.toFloat() / (SIZE - 1)
                val frac = if (x > prevX) (target - prevX) / (x - prevX) else 1f
                table[i] = (prevY + (y - prevY) * frac).coerceIn(0f, 1f)
                i++
            }
            prevX = x
            prevY = y
        }
        while (i < SIZE) {
            table[i] = 1f
            i++
        }
    }

    companion object {
        const val SIZE = 4096
        private const val CURVE_STEPS = 8192
        private const val HALF_PI = (PI / 2.0).toFloat()
        private const val TWO_PI = (2.0 * PI).toFloat()

        private val cache = object : LinkedHashMap<PressureCurveConfig, PressureCurveLut>(8, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<PressureCurveConfig, PressureCurveLut>) =
                size > 16
        }

        /** Shared, cached table per curve config (thread-safe). */
        fun of(config: PressureCurveConfig): PressureCurveLut =
            synchronized(cache) { cache.getOrPut(config) { PressureCurveLut(config) } }

        /** Pen speed in mm/ms between two samples; 0 when the timestamps do not advance. */
        fun velocityMmPerMs(x0: Float, y0: Float, t0Ms: Float, x1: Float, y1: Float, t1Ms: Float): Float {
            val dt = t1Ms - t0Ms
            return if (dt <= 0f) 0f else hypot(x1 - x0, y1 - y0) / dt
        }

        fun velocityMmPerMs(a: StrokePoint, b: StrokePoint): Float =
            velocityMmPerMs(a.x, a.y, a.timeMs.toFloat(), b.x, b.y, b.timeMs.toFloat())

        /** AXIS_TILT radians (0 = upright, PI/2 = flat) -> 0..1. */
        fun normalizeTilt(tiltRad: Float): Float = (tiltRad / HALF_PI).coerceIn(0f, 1f)

        /** AXIS_ORIENTATION radians (any range) -> [0, 2*PI). */
        fun normalizeAzimuth(orientationRad: Float): Float {
            val m = orientationRad % TWO_PI
            return if (m < 0f) m + TWO_PI else m
        }
    }
}
