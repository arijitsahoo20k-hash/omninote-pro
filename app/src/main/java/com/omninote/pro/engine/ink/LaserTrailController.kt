package com.omninote.pro.engine.ink

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import com.omninote.pro.core.model.BrushConfig
import com.omninote.pro.core.model.LaserMode
import kotlin.math.max
import kotlin.math.min

/**
 * Transient laser pointer. Nothing here is ever committed to the page or the undo stack.
 *
 * Timeline after the pen lifts: in trail mode the trail fades linearly over [TRAIL_FADE_MS] (1.2 s); in dot mode the
 * dot disappears at once. While the pen is down the trail is fully visible; in PERSISTENCE_TRAIL
 * mode older samples also fade by age so the trail has a comet-like tail, in GLOWING_DOT mode only the tip shows.
 *
 * Thread model: [begin]/[add]/[end] are called on the UI thread, [draw] on the render thread; state is guarded by
 * a lock and [draw] works from a snapshot copied into reusable arrays.
 */
class LaserTrailController {

    private val lock = Any()
    private var xs = FloatArray(256)
    private var ys = FloatArray(256)
    private var ts = LongArray(256)
    private var count = 0
    private var penDown = false
    private var liftedAtMs = 0L
    private var brush: BrushConfig = BrushConfig.laser()

    private var sx = FloatArray(256)
    private var sy = FloatArray(256)
    private var st = LongArray(256)

    private val path = Path()
    private val bloom = Paint()
    private val core = Paint()

    val isActive: Boolean get() = synchronized(lock) { count > 0 }

    fun begin(config: BrushConfig, x: Float, y: Float, nowMs: Long) {
        synchronized(lock) {
            brush = config
            count = 0
            penDown = true
            append(x, y, nowMs)
        }
    }

    fun add(x: Float, y: Float, nowMs: Long) {
        synchronized(lock) {
            if (!penDown) return
            append(x, y, nowMs)
        }
    }

    fun end(nowMs: Long) {
        synchronized(lock) {
            penDown = false
            liftedAtMs = nowMs
        }
    }

    fun clear() {
        synchronized(lock) {
            count = 0
            penDown = false
        }
    }

    /** Opacity multiplier for the whole trail at [nowMs], 1 while drawing or holding, then easing to 0. */
    fun globalAlpha(nowMs: Long): Float = synchronized(lock) { globalAlphaLocked(nowMs) }

    /**
     * Draws the trail. Returns true while something is still visible so the caller keeps the frame loop running;
     * false once fully faded (the trail is cleared at that point).
     */
    fun draw(canvas: Canvas, shaders: BrushShaderFactory, ctx: InkRenderContext, nowMs: Long): Boolean {
        val n: Int
        val alpha: Float
        val cfg: BrushConfig
        synchronized(lock) {
            if (count == 0) return false
            alpha = globalAlphaLocked(nowMs)
            if (alpha <= 0f) {
                count = 0
                return false
            }
            n = count
            if (sx.size < n) {
                sx = FloatArray(xs.size); sy = FloatArray(xs.size); st = LongArray(xs.size)
            }
            System.arraycopy(xs, 0, sx, 0, n)
            System.arraycopy(ys, 0, sy, 0, n)
            System.arraycopy(ts, 0, st, 0, n)
            cfg = brush
        }

        if (cfg.laserMode == LaserMode.GLOWING_DOT) drawDot(canvas, shaders, cfg, ctx, sx[n - 1], sy[n - 1], alpha)
        else drawTrail(canvas, shaders, cfg, ctx, n, alpha, nowMs)
        return true
    }

    private fun drawDot(canvas: Canvas, shaders: BrushShaderFactory, cfg: BrushConfig, ctx: InkRenderContext, x: Float, y: Float, alpha: Float) {
        val r = max(cfg.widthMm, MIN_DOT_RADIUS_MM)
        // A zero-length round-cap stroke renders a disc and shares the exact bloom/core paints of the trail.
        path.rewind()
        path.moveTo(x, y)
        path.lineTo(x + 0.001f, y)
        val sized = cfg.copy(widthMm = r)
        shaders.laserBloom(bloom, sized, ctx, alpha)
        shaders.laserCore(core, sized, alpha)
        canvas.drawPath(path, bloom)
        canvas.drawPath(path, core)
    }

    private fun drawTrail(canvas: Canvas, shaders: BrushShaderFactory, cfg: BrushConfig, ctx: InkRenderContext, n: Int, alpha: Float, nowMs: Long) {
        // Draw in a few age bands so the tail fades without one draw call per segment.
        val bands = BANDS
        val span = max(1, n / bands + 1)
        var i = 0
        while (i < n) {
            val end = min(n - 1, i + span)
            val age = nowMs - st[end]
            val ageFactor = if (isPenDownSnapshot()) 1f - smooth(age / TAIL_MS.toFloat()) * 0.85f else 1f
            val a = alpha * ageFactor
            path.rewind()
            path.moveTo(sx[i], sy[i])
            if (end == i) path.lineTo(sx[i] + 0.001f, sy[i])
            for (k in i + 1..end) path.lineTo(sx[k], sy[k])
            shaders.laserBloom(bloom, cfg, ctx, a)
            shaders.laserCore(core, cfg, a)
            canvas.drawPath(path, bloom)
            canvas.drawPath(path, core)
            if (end == n - 1) break
            i = end
        }
    }

    private fun isPenDownSnapshot(): Boolean = synchronized(lock) { penDown }

    private fun globalAlphaLocked(nowMs: Long): Float {
        if (count == 0) return 0f
        if (penDown) return 1f
        // Dot mode only exists while the pen touches; trail mode fades out over TRAIL_FADE_MS right after the lift.
        if (brush.laserMode == LaserMode.GLOWING_DOT) return 0f
        val sinceLift = nowMs - liftedAtMs
        return (1f - sinceLift.toFloat() / TRAIL_FADE_MS).coerceIn(0f, 1f)
    }

    private fun append(x: Float, y: Float, nowMs: Long) {
        if (count == xs.size) {
            val n = xs.size * 2
            xs = xs.copyOf(n); ys = ys.copyOf(n); ts = ts.copyOf(n)
        }
        xs[count] = x
        ys[count] = y
        ts[count] = nowMs
        count++
    }

    private fun smooth(t: Float): Float {
        val c = t.coerceIn(0f, 1f)
        return c * c * (3f - 2f * c)
    }

    companion object {
        const val HOLD_MS = 0L
        const val TRAIL_FADE_MS = 1200L
        private const val TAIL_MS = 700L
        private const val BANDS = 12
        private const val MIN_DOT_RADIUS_MM = 1.2f
    }
}
