package com.omninote.pro.engine.ink

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlendMode
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ComposeShader
import android.graphics.CornerPathEffect
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Shader
import com.omninote.pro.core.model.BrushConfig
import com.omninote.pro.core.model.BrushType
import com.omninote.pro.core.model.LineStyle
import com.omninote.pro.core.model.PencilGrade
import com.omninote.pro.core.model.TapeStyle
import java.nio.ByteBuffer
import java.util.Random
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Builds and caches the Paint setups, shaders and procedural textures for every instrument.
 * Not thread-safe: each render thread (wet front buffer, dry multi-buffer) owns its own factory.
 * All dimensions are millimetres because canvases are in page space.
 */
class BrushShaderFactory {

    private val grainBitmaps = HashMap<Int, Bitmap>()
    private val pencilShaders = HashMap<Long, Shader>()
    private val hatchShaders = HashMap<Long, Shader>()
    private val cornerEffects = HashMap<Int, CornerPathEffect>()
    private val blurFilters = HashMap<Int, BlurMaskFilter>()
    private val dashEffects = HashMap<Int, DashPathEffect>()

    // =====================================================================================
    // Pens: Fountain, Ballpoint, Brush, Pencil (filled ribbons)
    // =====================================================================================

    fun ink(
        paint: Paint,
        brush: BrushConfig,
        semanticArgb: Int,
        ctx: InkRenderContext,
        meanPressure: Float,
        meanTiltRad: Float
    ) {
        base(paint, Paint.Style.FILL)
        val color = ctx.displayColor(semanticArgb)
        var alpha = brush.opacity
        when (brush.type) {
            BrushType.BALLPOINT -> alpha *= 0.7f + 0.3f * meanPressure
            BrushType.PENCIL -> {
                val tiltDeg = Math.toDegrees(meanTiltRad.toDouble()).toFloat()
                val t = (StrokeRibbonTessellator.smoothstep(25f, 65f, tiltDeg) * brush.pencilTiltSensitivity).coerceIn(0f, 1f)
                alpha *= brush.pencilGrade.darkness * (1f - 0.55f * t)
                paint.shader = pencilShader(color and 0xFFFFFF, brush.pencilGrade, brush.pencilGrainIntensity)
            }
            else -> Unit
        }
        setColor(paint, color, alpha)
    }

    /** Fineliner: constant-width stroked spine, round caps and joins. */
    fun fineliner(paint: Paint, brush: BrushConfig, semanticArgb: Int, ctx: InkRenderContext) {
        base(paint, Paint.Style.STROKE)
        paint.strokeWidth = brush.widthMm
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        setColor(paint, ctx.displayColor(semanticArgb), brush.opacity)
    }

    // =====================================================================================
    // Highlighter
    // =====================================================================================

    /**
     * Multiply on light paper, screen on dark paper. [useBlend] false for the wet front buffer, whose transparent
     * surface has nothing to blend with (the dry pass applies the real blend on commit) and for PDF export.
     */
    fun highlighter(paint: Paint, brush: BrushConfig, semanticArgb: Int, ctx: InkRenderContext, useBlend: Boolean) {
        base(paint, Paint.Style.FILL)
        val color = ctx.displayColor(semanticArgb, highlighter = true)
        val alpha = DarkModeColorInverter.highlighterAlpha(brush.opacity, ctx.invertForDisplay)
        setColor(paint, color, alpha)
        if (ctx.isPdfExport) {
            paint.alpha = (255f * PDF_HIGHLIGHT_ALPHA).roundToInt()
            return
        }
        if (useBlend && !ctx.isExport) {
            paint.blendMode = if (ctx.invertForDisplay) BlendMode.SCREEN else BlendMode.MULTIPLY
        }
    }

    // =====================================================================================
    // Dashed / Dotted / Wavy
    // =====================================================================================

    fun procedural(paint: Paint, brush: BrushConfig, semanticArgb: Int, ctx: InkRenderContext) {
        val color = ctx.displayColor(semanticArgb)
        when (brush.lineStyle) {
            LineStyle.DOTTED -> base(paint, Paint.Style.FILL)
            LineStyle.DASHED -> {
                base(paint, Paint.Style.STROKE)
                paint.strokeWidth = brush.widthMm
                paint.strokeCap = Paint.Cap.BUTT
                paint.strokeJoin = Paint.Join.ROUND
            }
            LineStyle.WAVY -> {
                base(paint, Paint.Style.STROKE)
                paint.strokeWidth = brush.widthMm
                paint.strokeCap = Paint.Cap.ROUND
                paint.strokeJoin = Paint.Join.ROUND
            }
        }
        setColor(paint, color, brush.opacity)
    }

    // =====================================================================================
    // Study Tape
    // =====================================================================================

    /** Masked: 100% opaque. Revealed: 15% ghost. */
    fun tapeFill(paint: Paint, brush: BrushConfig, semanticArgb: Int, ctx: InkRenderContext, masked: Boolean) {
        base(paint, Paint.Style.FILL)
        setColor(paint, ctx.displayColor(semanticArgb), if (masked) 1f else GHOST_ALPHA)
        paint.pathEffect = cornerEffect(brush.widthMm * 0.25f)
    }

    /** Diagonal stripes drawn over the opaque fill so nothing beneath can be read. */
    fun tapeHatch(paint: Paint, brush: BrushConfig, semanticArgb: Int, ctx: InkRenderContext) {
        base(paint, Paint.Style.FILL)
        val fill = ctx.displayColor(semanticArgb)
        val lightFill = luminance(fill) > 0.5f
        val strength = if (brush.tapeStyle == TapeStyle.STRIPED) 0x48 else 0x22
        paint.shader = hatchShader(lightFill, strength)
        paint.pathEffect = cornerEffect(brush.widthMm * 0.25f)
    }

    fun tapeGhostOutline(paint: Paint, brush: BrushConfig, semanticArgb: Int, ctx: InkRenderContext) {
        base(paint, Paint.Style.STROKE)
        paint.strokeWidth = 0.25f
        paint.strokeJoin = Paint.Join.ROUND
        setColor(paint, ctx.displayColor(semanticArgb), 0.85f)
        paint.pathEffect = dashEffect(1.2f, 0.8f)
    }

    // =====================================================================================
    // Laser + eraser overlays
    // =====================================================================================

    fun laserBloom(paint: Paint, brush: BrushConfig, ctx: InkRenderContext, alpha: Float) {
        base(paint, Paint.Style.STROKE)
        paint.strokeWidth = brush.widthMm * 4f
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        setColor(paint, brush.colorArgb or (0xFF shl 24), alpha * 0.6f)
        paint.maskFilter = blurFilter(BLOOM_BLUR_PX / ctx.pxPerMm)
    }

    fun laserCore(paint: Paint, brush: BrushConfig, alpha: Float) {
        base(paint, Paint.Style.STROKE)
        paint.strokeWidth = brush.widthMm
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        setColor(paint, mix(brush.colorArgb or (0xFF shl 24), Color.WHITE, 0.75f), alpha)
    }

    /** Stencil outlines: constant-width round-cap stroke in the stamp's colour. */
    fun stencilStroke(paint: Paint, widthMm: Float, semanticArgb: Int, ctx: InkRenderContext) {
        base(paint, Paint.Style.STROKE)
        paint.strokeWidth = widthMm
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        setColor(paint, ctx.displayColor(semanticArgb), 1f)
    }

    fun eraserRing(paint: Paint, ctx: InkRenderContext) {
        base(paint, Paint.Style.STROKE)
        paint.strokeWidth = 1.5f / ctx.pxPerMm
        setColor(paint, if (ctx.paperIsDark) 0xFFE0E0E0.toInt() else 0xFF455A64.toInt(), 0.9f)
    }

    fun eraserTrail(paint: Paint, ctx: InkRenderContext) {
        base(paint, Paint.Style.FILL)
        setColor(paint, if (ctx.paperIsDark) 0xFFE0E0E0.toInt() else 0xFF455A64.toInt(), 0.16f)
    }

    // =====================================================================================
    // Shader construction
    // =====================================================================================

    /** Solid colour multiplied by tileable paper-grain alpha (DST_IN), fixed in page space so grain never swims. */
    private fun pencilShader(rgb: Int, grade: PencilGrade, intensity: Float): Shader {
        val level = grainLevel(intensity)
        val key = (rgb.toLong() shl 16) or (grade.ordinal.toLong() shl 8) or level.toLong()
        return pencilShaders.getOrPut(key) {
            val grain = BitmapShader(grainBitmap(grade, level), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
            grain.setLocalMatrix(Matrix().apply { setScale(GRAIN_MM_PER_TEXEL, GRAIN_MM_PER_TEXEL) })
            val opaque = rgb or (0xFF shl 24)
            val solid = LinearGradient(0f, 0f, 1f, 0f, opaque, opaque, Shader.TileMode.REPEAT)
            ComposeShader(solid, grain, PorterDuff.Mode.DST_IN)
        }
    }

    /** Intensity quantised to sixteen steps (0..16) so only a bounded number of textures is ever built. */
    private fun grainLevel(intensity: Float): Int = (intensity.coerceIn(0f, 2f) * 8f).roundToInt()

    private fun grainBitmap(grade: PencilGrade, level: Int): Bitmap = grainBitmaps.getOrPut(grade.ordinal * 100 + level) {
        val strength = grade.grain * level / 8f
        val n = GRAIN_SIZE
        val rnd = Random(0x9E3779B9L xor grade.ordinal.toLong())
        val octaves = intArrayOf(8, 16, 32, 64)
        val lattices = Array(octaves.size) { i -> FloatArray(octaves[i] * octaves[i]) { rnd.nextFloat() } }
        val amps = floatArrayOf(0.4f, 0.3f, 0.2f, 0.1f)
        val bytes = ByteArray(n * n)
        for (y in 0 until n) {
            for (x in 0 until n) {
                var v = 0f
                for (o in octaves.indices) v += amps[o] * tileableNoise(lattices[o], octaves[o], x, y, n)
                v = 0.65f * v + 0.35f * rnd.nextFloat()
                val alpha = (1f - strength * (1f - v) * 1.1f).coerceIn(0.05f, 1f)
                bytes[y * n + x] = (alpha * 255f).roundToInt().toByte()
            }
        }
        Bitmap.createBitmap(n, n, Bitmap.Config.ALPHA_8).also { it.copyPixelsFromBuffer(ByteBuffer.wrap(bytes)) }
    }

    private fun tileableNoise(lattice: FloatArray, size: Int, x: Int, y: Int, n: Int): Float {
        val gx = x * size / n.toFloat()
        val gy = y * size / n.toFloat()
        val x0 = floor(gx).toInt()
        val y0 = floor(gy).toInt()
        val fx = smooth(gx - x0)
        val fy = smooth(gy - y0)
        val x1 = (x0 + 1) % size
        val y1 = (y0 + 1) % size
        val xa = x0 % size
        val ya = y0 % size
        val top = lattice[ya * size + xa] * (1f - fx) + lattice[ya * size + x1] * fx
        val bottom = lattice[y1 * size + xa] * (1f - fx) + lattice[y1 * size + x1] * fx
        return top * (1f - fy) + bottom * fy
    }

    private fun smooth(t: Float): Float = t * t * (3f - 2f * t)

    private fun hatchShader(lightFill: Boolean, strength: Int): Shader {
        val key = (strength.toLong() shl 1) or (if (lightFill) 1L else 0L)
        return hatchShaders.getOrPut(key) {
            val size = HATCH_SIZE
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val c = Canvas(bitmap)
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = size / 6f
                color = Color.argb(strength, if (lightFill) 0 else 255, if (lightFill) 0 else 255, if (lightFill) 0 else 255)
            }
            val s = size.toFloat()
            c.drawLine(0f, s, s, 0f, p)
            c.drawLine(-s / 2f, s / 2f, s / 2f, -s / 2f, p)
            c.drawLine(s / 2f, s * 1.5f, s * 1.5f, s / 2f, p)
            BitmapShader(bitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT).also {
                it.setLocalMatrix(Matrix().apply { setScale(HATCH_MM_PER_TEXEL, HATCH_MM_PER_TEXEL) })
            }
        }
    }

    // =====================================================================================
    // Small helpers
    // =====================================================================================

    private fun base(paint: Paint, style: Paint.Style) {
        paint.reset()
        paint.isAntiAlias = true
        paint.style = style
    }

    private fun setColor(paint: Paint, argb: Int, alphaScale: Float) {
        paint.color = argb or (0xFF shl 24)
        paint.alpha = ((argb ushr 24) * alphaScale.coerceIn(0f, 1f)).roundToInt()
    }

    private fun luminance(argb: Int): Float =
        (0.2126f * Color.red(argb) + 0.7152f * Color.green(argb) + 0.0722f * Color.blue(argb)) / 255f

    private fun mix(a: Int, b: Int, t: Float): Int = Color.argb(
        255,
        (Color.red(a) + (Color.red(b) - Color.red(a)) * t).roundToInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * t).roundToInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).roundToInt()
    )

    private fun cornerEffect(radiusMm: Float): CornerPathEffect =
        cornerEffects.getOrPut((radiusMm * 100f).roundToInt()) { CornerPathEffect(radiusMm) }

    private fun blurFilter(radiusMm: Float): BlurMaskFilter =
        blurFilters.getOrPut((radiusMm * 1000f).roundToInt()) {
            BlurMaskFilter(radiusMm.coerceAtLeast(0.01f), BlurMaskFilter.Blur.NORMAL)
        }

    private fun dashEffect(on: Float, off: Float): DashPathEffect =
        dashEffects.getOrPut((on * 100f).roundToInt() * 10_000 + (off * 100f).roundToInt()) {
            DashPathEffect(floatArrayOf(on, off), 0f)
        }

    private companion object {
        const val PDF_HIGHLIGHT_ALPHA = 0.35f
        const val GRAIN_SIZE = 128
        const val GRAIN_MM_PER_TEXEL = 0.1f
        const val HATCH_SIZE = 16
        const val HATCH_MM_PER_TEXEL = 0.18f
        const val GHOST_ALPHA = 0.15f
        const val BLOOM_BLUR_PX = 7f
    }
}
