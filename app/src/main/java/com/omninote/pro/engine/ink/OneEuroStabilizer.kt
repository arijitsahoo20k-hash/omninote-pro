package com.omninote.pro.engine.ink

import com.omninote.pro.core.model.StrokePoint
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.pow

/**
 * Adaptive 1-Euro filter (Casiez et al.) for pen coordinates in page-space millimetres.
 *
 * The cutoff rises with pen speed: slow movement is smoothed hard (tremor disappears), fast flicks get a high
 * cutoff and therefore almost no phase lag. x and y share one speed estimate so smoothing never depends on axis.
 *
 * [streamlineAmount] 0 = bit-exact passthrough with zero latency; 1 = calligraphic smoothing.
 * Zero allocation per sample: read [x], [y], [tilt] after [filter].
 */
class OneEuroStabilizer(streamlineAmount: Float = 0f) {

    var streamline: Float = 0f
        private set
    var x: Float = 0f
        private set
    var y: Float = 0f
        private set
    var tilt: Float = 0f
        private set

    private var minCutoff = MAX_MIN_CUTOFF_HZ
    private var beta = MIN_BETA
    private var tiltAlphaScale = 1f

    private var initialized = false
    private var lastTimeMs = 0L
    private var prevRawX = 0f
    private var prevRawY = 0f
    private var derivX = 0f
    private var derivY = 0f

    init {
        setStreamline(streamlineAmount)
    }

    fun setStreamline(amount: Float) {
        val s = amount.coerceIn(0f, 1f)
        streamline = s
        minCutoff = MAX_MIN_CUTOFF_HZ * (MIN_MIN_CUTOFF_HZ / MAX_MIN_CUTOFF_HZ).pow(s)
        beta = MIN_BETA + (MAX_BETA - MIN_BETA) * s * s
        tiltAlphaScale = 1f - 0.8f * s
    }

    fun reset() {
        initialized = false
        derivX = 0f
        derivY = 0f
    }

    /** Filters one raw sample. Results are in [x], [y], [tilt]. */
    fun filter(rawX: Float, rawY: Float, rawTilt: Float, timeMs: Long) {
        if (!initialized || streamline <= 0f) {
            x = rawX
            y = rawY
            tilt = rawTilt
            prevRawX = rawX
            prevRawY = rawY
            derivX = 0f
            derivY = 0f
            lastTimeMs = timeMs
            initialized = true
            return
        }

        val dt = ((timeMs - lastTimeMs) / 1000f).coerceIn(MIN_DT_S, MAX_DT_S)
        lastTimeMs = timeMs

        val dAlpha = alpha(D_CUTOFF_HZ, dt)
        derivX += dAlpha * ((rawX - prevRawX) / dt - derivX)
        derivY += dAlpha * ((rawY - prevRawY) / dt - derivY)
        prevRawX = rawX
        prevRawY = rawY

        val cutoff = minCutoff + beta * hypot(derivX, derivY)
        val a = alpha(cutoff, dt)
        x += a * (rawX - x)
        y += a * (rawY - y)
        tilt += (a * tiltAlphaScale).coerceIn(0f, 1f) * (rawTilt - tilt)
    }

    /** Convenience wrapper that keeps pressure, orientation and time untouched (pressure stays raw). */
    fun filter(p: StrokePoint): StrokePoint {
        filter(p.x, p.y, p.tilt, p.timeMs)
        return p.copy(x = x, y = y, tilt = tilt)
    }

    private fun alpha(cutoffHz: Float, dt: Float): Float {
        val tau = 1f / (2f * PI.toFloat() * cutoffHz)
        return 1f / (1f + tau / dt)
    }

    private companion object {
        const val MAX_MIN_CUTOFF_HZ = 90f
        const val MIN_MIN_CUTOFF_HZ = 0.45f
        const val MIN_BETA = 0.04f
        const val MAX_BETA = 0.95f
        const val D_CUTOFF_HZ = 1.5f
        const val MIN_DT_S = 0.0005f
        const val MAX_DT_S = 0.05f
    }
}
