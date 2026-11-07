package com.omninote.pro.engine.ink

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

/**
 * Display-only ink colour mapping for dark paper, done in OKLab so perceived lightness is handled properly.
 * The saved Stroke.semanticColorArgb is never modified: this only decides what pixels to draw.
 *
 *  - pure black becomes crisp off-white #F3F4F6; other near-neutral darks follow the same lightness flip
 *  - coloured ink keeps its hue and is lifted to a high-contrast pastel lightness
 *  - highlighters keep a saturated mid-light tone and get a gentler alpha (see [highlighterAlpha])
 */
object DarkModeColorInverter {

    private const val OFF_WHITE = 0xF3F4F6
    private const val NEUTRAL_CHROMA = 0.03f
    private val memo = ConcurrentHashMap<Long, Int>()

    /**
     * @return [semanticArgb] unchanged unless [paperIsDark] and [smartInversion] are both true.
     *         Alpha is always preserved.
     */
    fun toDisplay(
        semanticArgb: Int,
        paperIsDark: Boolean,
        smartInversion: Boolean = true,
        highlighter: Boolean = false
    ): Int {
        if (!paperIsDark || !smartInversion) return semanticArgb
        val key = (semanticArgb.toLong() and 0xFFFFFFFFL) shl 1 or (if (highlighter) 1L else 0L)
        memo[key]?.let { return it }
        val result = compute(semanticArgb, highlighter)
        if (memo.size > MEMO_LIMIT) memo.clear()
        memo[key] = result
        return result
    }

    /** Highlighter opacity adapted to the paper: screen-blended light ink on dark paper reads stronger. */
    fun highlighterAlpha(baseAlpha: Float, paperIsDark: Boolean, smartInversion: Boolean = true): Float =
        if (paperIsDark && smartInversion) (baseAlpha * 0.8f).coerceIn(0.15f, 0.9f) else baseAlpha

    private const val MEMO_LIMIT = 1024

    private fun compute(argb: Int, highlighter: Boolean): Int {
        val alpha = argb ushr 24
        val rgb = argb and 0xFFFFFF
        if (rgb == 0 && !highlighter) return (alpha shl 24) or OFF_WHITE

        val lab = FloatArray(3)
        srgbToOklab(rgb, lab)
        val l = lab[0]
        val chroma = hypot(lab[1], lab[2])
        val hue = atan2(lab[2], lab[1])

        val newL: Float
        val newA: Float
        val newB: Float
        when {
            highlighter -> {
                newL = l.coerceIn(0.62f, 0.86f)
                newA = lab[1]
                newB = lab[2]
            }
            chroma < NEUTRAL_CHROMA -> {
                newL = 0.967f - 0.6f * l.pow(1.5f)
                newA = -0.0016f
                newB = -0.0037f
            }
            else -> {
                newL = max(l, 0.62f + 0.28f * (1f - l))
                val c = chroma * 0.85f
                newA = c * cos(hue)
                newB = c * sin(hue)
            }
        }
        return (alpha shl 24) or oklabToSrgbClipped(newL, newA, newB)
    }

    // ---- OKLab (Bjorn Ottosson) ----

    private fun srgbToLinear(c: Float): Float =
        if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)

    private fun linearToSrgb(c: Float): Float =
        if (c <= 0.0031308f) c * 12.92f else 1.055f * c.pow(1f / 2.4f) - 0.055f

    private fun srgbToOklab(rgb: Int, out: FloatArray) {
        val r = srgbToLinear(((rgb shr 16) and 0xFF) / 255f)
        val g = srgbToLinear(((rgb shr 8) and 0xFF) / 255f)
        val b = srgbToLinear((rgb and 0xFF) / 255f)
        val l = cbrt(0.4122214708f * r + 0.5363325363f * g + 0.0514459929f * b)
        val m = cbrt(0.2119034982f * r + 0.6806995451f * g + 0.1073969566f * b)
        val s = cbrt(0.0883024619f * r + 0.2817188376f * g + 0.6299787005f * b)
        out[0] = 0.2104542553f * l + 0.7936177850f * m - 0.0040720468f * s
        out[1] = 1.9779984951f * l - 2.4285922050f * m + 0.4505937099f * s
        out[2] = 0.0259040371f * l + 0.7827717662f * m - 0.8086757660f * s
    }

    /** Converts back; if out of sRGB gamut, shrinks chroma toward the grey axis until it fits. */
    private fun oklabToSrgbClipped(l: Float, a: Float, b: Float): Int {
        val rgb = FloatArray(3)
        if (!oklabToLinear(l, a, b, rgb)) {
            var lo = 0f
            var hi = 1f
            repeat(12) {
                val mid = (lo + hi) * 0.5f
                if (oklabToLinear(l, a * mid, b * mid, rgb)) lo = mid else hi = mid
            }
            oklabToLinear(l, a * lo, b * lo, rgb)
        }
        val r = (linearToSrgb(rgb[0].coerceIn(0f, 1f)) * 255f + 0.5f).toInt()
        val g = (linearToSrgb(rgb[1].coerceIn(0f, 1f)) * 255f + 0.5f).toInt()
        val bl = (linearToSrgb(rgb[2].coerceIn(0f, 1f)) * 255f + 0.5f).toInt()
        return (r shl 16) or (g shl 8) or bl
    }

    /** Fills linear RGB; returns true when every channel is within gamut. */
    private fun oklabToLinear(l: Float, a: Float, b: Float, out: FloatArray): Boolean {
        val l_ = l + 0.3963377774f * a + 0.2158037573f * b
        val m_ = l - 0.1055613458f * a - 0.0638541728f * b
        val s_ = l - 0.0894841775f * a - 1.2914855480f * b
        val lc = l_ * l_ * l_
        val mc = m_ * m_ * m_
        val sc = s_ * s_ * s_
        out[0] = 4.0767416621f * lc - 3.3077115913f * mc + 0.2309699292f * sc
        out[1] = -1.2684380046f * lc + 2.6097574011f * mc - 0.3413193965f * sc
        out[2] = -0.0041960863f * lc - 0.7034186147f * mc + 1.7076147010f * sc
        val eps = 0.0005f
        return out[0] >= -eps && out[0] <= 1f + eps &&
            out[1] >= -eps && out[1] <= 1f + eps &&
            out[2] >= -eps && out[2] <= 1f + eps
    }
}
