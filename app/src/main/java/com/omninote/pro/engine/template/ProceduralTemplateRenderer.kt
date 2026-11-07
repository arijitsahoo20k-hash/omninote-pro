package com.omninote.pro.engine.template

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.omninote.pro.core.model.HexOrientation
import com.omninote.pro.core.model.PaperColor
import com.omninote.pro.core.model.RuledVariant
import com.omninote.pro.core.model.SplitLayout
import com.omninote.pro.core.model.StrokeBounds
import com.omninote.pro.core.model.TemplateConfig
import com.omninote.pro.core.model.TemplateType
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Procedural vector page templates, drawn in page-space millimetres onto any [Canvas] (the live dry layer, vector
 * PDF export, SVG export). Nothing is rasterised: every line, dot, hexagon and label is a canvas primitive.
 *
 * Cost is proportional to what is visible: each template converts the visible viewport (clipped to the page) into
 * row/column index ranges, so zooming in or roaming an infinite canvas only touches the visible cells. When
 * [draw] is given [pxPerMm] it also keeps hairlines at least ~1px wide and drops minor lines that would be denser
 * than ~3.5px, keeping only the major ones.
 *
 * One instance per render thread. No allocation happens while drawing; labels come from small cached arrays.
 */
class ProceduralTemplateRenderer {

    private val paperPaint = Paint()
    private val minorPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val majorPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val clip = RectF()
    private val box = RectF()

    private val lineBuf = FloatArray(LINE_BATCH * 4)
    private var lineCount = 0
    private val dotBuf = FloatArray(DOT_BATCH * 2)
    private var dotCount = 0
    private var cv: Canvas? = null
    private var batchPaint: Paint? = null

    private val hourLabels = arrayOfNulls<String>(24)
    private val questionLabels = Array(8) { "Q${it + 1}" }

    // Per-draw derived values
    private var px = 0f
    private var ruleRgb = 0
    private var minorAlpha = 0
    private var majorAlpha = 0
    private var hairline = 0.15f
    private var accentRgb = 0
    private var dark = false

    /**
     * @param pageBoundsMm the page rectangle (use a very large box for infinite canvas).
     * @param visibleViewportMm what is on screen, in page mm.
     * @param pxPerMm current zoom for hairline and density control; 0 disables both (export).
     * @param ruleRgbOverride replaces the paper-derived rule colour (for example print grey on white).
     */
    fun draw(
        canvas: Canvas,
        config: TemplateConfig,
        pageBoundsMm: StrokeBounds,
        visibleViewportMm: StrokeBounds,
        pxPerMm: Float = 0f,
        ruleRgbOverride: Int? = null
    ) {
        val left = max(pageBoundsMm.left, visibleViewportMm.left)
        val top = max(pageBoundsMm.top, visibleViewportMm.top)
        val right = min(pageBoundsMm.right, visibleViewportMm.right)
        val bottom = min(pageBoundsMm.bottom, visibleViewportMm.bottom)
        if (left >= right || top >= bottom) return
        clip.set(left, top, right, bottom)

        cv = canvas
        px = pxPerMm
        dark = config.paperColor.isDark
        ruleRgb = (ruleRgbOverride ?: ruleColor(config.paperColor)) and 0xFFFFFF
        accentRgb = accentColor(dark) and 0xFFFFFF
        hairline = if (pxPerMm > 0f) max(config.lineWeightMm, 0.9f / pxPerMm) else config.lineWeightMm
        minorAlpha = (config.lineOpacity * 255f).toInt().coerceIn(0, 255)
        majorAlpha = (config.lineOpacity * 1.9f * 255f).toInt().coerceIn(0, 255)

        paperPaint.style = Paint.Style.FILL
        paperPaint.color = config.paperColor.argb
        canvas.drawRect(clip, paperPaint)

        if (config.type == TemplateType.BLANK) {
            cv = null
            return
        }

        canvas.save()
        canvas.clipRect(clip)
        configureStroke(minorPaint, minorAlpha, hairline)
        configureStroke(majorPaint, majorAlpha, hairline * 2f)
        when (config.type) {
            TemplateType.BLANK -> Unit
            TemplateType.RULED -> ruled(config, pageBoundsMm)
            TemplateType.GRID -> squared(config, pageBoundsMm)
            TemplateType.DOTTED_GRID -> dotted(config, pageBoundsMm)
            TemplateType.CROSS_GRID -> cross(config, pageBoundsMm)
            TemplateType.HEXAGONAL -> hexagonal(config)
            TemplateType.CORNELL -> cornell(config, pageBoundsMm)
            TemplateType.SPLIT_DERIVATION -> split(config, pageBoundsMm)
            TemplateType.ISOMETRIC -> isometric(config)
            TemplateType.POLAR -> polar(config, pageBoundsMm)
            TemplateType.MISTAKE_LOG -> mistakeLog(config, pageBoundsMm)
            TemplateType.DAILY_PLANNER -> planner(config, pageBoundsMm)
        }
        flushLines()
        flushDots()
        canvas.restore()
        cv = null
        batchPaint = null
    }

    // =====================================================================================
    // Simple lattices
    // =====================================================================================

    private fun ruled(c: TemplateConfig, page: StrokeBounds) {
        val sp = c.ruledVariant.spacingMm
        val firstRow = max(3, ceilIdx(clip.top - page.top, sp))
        val lastRow = min(floorIdx(page.bottom - page.top - sp, sp), floorIdx(clip.bottom - page.top, sp))
        if (lastRow >= firstRow && (px <= 0f || sp * px >= MIN_PITCH_PX)) {
            val step = strideFor(lastRow - firstRow + 1)
            beginLines(minorPaint)
            var r = firstRow
            while (r <= lastRow) {
                val y = page.top + r * sp
                line(clip.left, y, clip.right, y)
                r += step
            }
        }
        val legal = c.ruledVariant == RuledVariant.LEGAL
        if (c.showMargins || legal) {
            configureStroke(accentPaint, (majorAlpha * 0.9f).toInt(), hairline * 1.6f, accentRgb)
            beginLines(accentPaint)
            val mx = page.left + 25f
            line(mx, clip.top, mx, clip.bottom)
            if (legal) {
                line(mx + 1.3f, clip.top, mx + 1.3f, clip.bottom)
                val rx = page.right - 12f
                line(rx, clip.top, rx, clip.bottom)
            }
        }
    }

    private fun squared(c: TemplateConfig, page: StrokeBounds) {
        val sp = c.gridVariant.cellMm
        val showMinor = px <= 0f || sp * px >= MIN_PITCH_PX
        val showMajor = px <= 0f || sp * 5f * px >= MIN_PITCH_PX
        val c0 = ceilIdx(clip.left - page.left, sp)
        val c1 = floorIdx(clip.right - page.left, sp)
        val r0 = ceilIdx(clip.top - page.top, sp)
        val r1 = floorIdx(clip.bottom - page.top, sp)
        if (showMinor) {
            val sc = strideFor(c1 - c0 + 1)
            val sr = strideFor(r1 - r0 + 1)
            beginLines(minorPaint)
            var col = c0
            while (col <= c1) {
                if (col % 5 != 0) line(page.left + col * sp, clip.top, page.left + col * sp, clip.bottom)
                col += sc
            }
            var row = r0
            while (row <= r1) {
                if (row % 5 != 0) line(clip.left, page.top + row * sp, clip.right, page.top + row * sp)
                row += sr
            }
        }
        if (showMajor) {
            beginLines(majorPaint)
            val mc = strideFor((c1 - c0) / 5 + 1)
            val mr = strideFor((r1 - r0) / 5 + 1)
            var col = c0 + floorMod(-c0, 5)
            while (col <= c1) {
                val x = page.left + col * sp
                line(x, clip.top, x, clip.bottom)
                col += 5 * mc
            }
            var row = r0 + floorMod(-r0, 5)
            while (row <= r1) {
                val y = page.top + row * sp
                line(clip.left, y, clip.right, y)
                row += 5 * mr
            }
        }
    }

    private fun dotted(c: TemplateConfig, page: StrokeBounds) {
        val sp = c.lineSpacingMm
        if (px > 0f && sp * px < MIN_PITCH_PX) return
        val diameter = max(c.lineWeightMm * 3.4f, if (px > 0f) 1.6f / px else 0.5f)
        configureStroke(accentPaint, (minorAlpha * 1.5f).toInt().coerceAtMost(255), diameter, ruleRgb)
        accentPaint.strokeCap = Paint.Cap.ROUND
        val c0 = ceilIdx(clip.left - page.left, sp)
        val c1 = floorIdx(clip.right - page.left, sp)
        val r0 = ceilIdx(clip.top - page.top, sp)
        val r1 = floorIdx(clip.bottom - page.top, sp)
        val sc = strideFor(c1 - c0 + 1)
        val sr = strideFor(r1 - r0 + 1)
        batchPaint = accentPaint
        var row = r0
        while (row <= r1) {
            val y = page.top + row * sp
            var col = c0
            while (col <= c1) {
                dot(page.left + col * sp, y)
                col += sc
            }
            row += sr
        }
    }

    private fun cross(c: TemplateConfig, page: StrokeBounds) {
        val sp = c.lineSpacingMm
        if (px > 0f && sp * px < MIN_PITCH_PX) return
        val arm = 0.8f
        configureStroke(accentPaint, (minorAlpha * 1.4f).toInt().coerceAtMost(255), hairline * 1.2f, ruleRgb)
        beginLines(accentPaint)
        val c0 = ceilIdx(clip.left - page.left - arm, sp)
        val c1 = floorIdx(clip.right - page.left + arm, sp)
        val r0 = ceilIdx(clip.top - page.top - arm, sp)
        val r1 = floorIdx(clip.bottom - page.top + arm, sp)
        val sc = strideFor(c1 - c0 + 1)
        val sr = strideFor(r1 - r0 + 1)
        var row = r0
        while (row <= r1) {
            val y = page.top + row * sp
            var col = c0
            while (col <= c1) {
                val x = page.left + col * sp
                line(x - arm, y, x + arm, y)
                line(x, y - arm, x, y + arm)
                col += sc
            }
            row += sr
        }
    }

    // =====================================================================================
    // Hexagonal, isometric, polar
    // =====================================================================================

    /**
     * Honeycomb with side length hexRadiusMm. Each hexagon contributes only three of its six edges (top-left,
     * top-right and right in pointy-top space) so shared edges are drawn exactly once. Flat-top is the transpose.
     */
    private fun hexagonal(c: TemplateConfig) {
        val s = c.hexRadiusMm
        if (px > 0f && s * px < MIN_PITCH_PX) return
        val horizontal = c.hexOrientation == HexOrientation.HORIZONTAL
        val w = (sqrt(3.0) * s).toFloat()
        val rowStep = 1.5f * s
        // Visible window in (u across, v along) space.
        val uMin = if (horizontal) clip.top else clip.left
        val uMax = if (horizontal) clip.bottom else clip.right
        val vMin = if (horizontal) clip.left else clip.top
        val vMax = if (horizontal) clip.right else clip.bottom
        val r0 = floorIdx(vMin - s, rowStep) - 1
        val r1 = ceilIdx(vMax + s, rowStep) + 1
        val c0 = floorIdx(uMin - w, w) - 1
        val c1 = ceilIdx(uMax + w, w) + 1
        if ((r1 - r0) * (c1 - c0) > MAX_HEX_CELLS) return
        val hx = w / 2f
        val hs = s / 2f
        beginLines(minorPaint)
        for (r in r0..r1) {
            val v = r * rowStep
            val offset = if (r and 1 != 0) hx else 0f
            for (col in c0..c1) {
                val u = col * w + offset
                // pointy-top vertices: top (u, v-s), upper-right (u+hx, v-hs), lower-right (u+hx, v+hs), upper-left (u-hx, v-hs)
                hexLine(horizontal, u - hx, v - hs, u, v - s)
                hexLine(horizontal, u, v - s, u + hx, v - hs)
                hexLine(horizontal, u + hx, v - hs, u + hx, v + hs)
            }
        }
    }

    private fun hexLine(horizontal: Boolean, u0: Float, v0: Float, u1: Float, v1: Float) {
        if (horizontal) line(v0, u0, v1, u1) else line(u0, v0, u1, v1)
    }

    /** Triangular lattice with 30/90/150 degree line families; all three meet at every vertex. */
    private fun isometric(c: TemplateConfig) {
        val side = c.lineSpacingMm
        val h = (sqrt(3.0) / 2.0 * side).toFloat()
        if (px > 0f && h * px < MIN_PITCH_PX) return
        beginLines(minorPaint)
        val half = 0.8660254f
        family(1f, 0f, h)
        family(-0.5f, half, h)
        family(-0.5f, -half, h)
    }

    /** Lines n . p = k * pitch for every k whose line crosses the clip rectangle. */
    private fun family(nx: Float, ny: Float, pitch: Float) {
        val d0 = nx * clip.left + ny * clip.top
        val d1 = nx * clip.right + ny * clip.top
        val d2 = nx * clip.left + ny * clip.bottom
        val d3 = nx * clip.right + ny * clip.bottom
        val lo = min(min(d0, d1), min(d2, d3))
        val hi = max(max(d0, d1), max(d2, d3))
        val k0 = ceilIdx(lo, pitch)
        val k1 = floorIdx(hi, pitch)
        val step = strideFor(k1 - k0 + 1)
        val reach = hypot(clip.width(), clip.height())
        val tx = -ny
        val ty = nx
        var k = k0
        while (k <= k1) {
            val bx = nx * k * pitch
            val by = ny * k * pitch
            // Anchor the segment at the point of the line nearest the clip centre to keep it short.
            val cxm = (clip.left + clip.right) * 0.5f
            val cym = (clip.top + clip.bottom) * 0.5f
            val along = (cxm - bx) * tx + (cym - by) * ty
            val ax = bx + tx * along
            val ay = by + ty * along
            line(ax - tx * reach, ay - ty * reach, ax + tx * reach, ay + ty * reach)
            k += step
        }
    }

    private fun polar(c: TemplateConfig, page: StrokeBounds) {
        val sp = c.lineSpacingMm
        val cx = (page.left + page.right) * 0.5f
        val cy = (page.top + page.bottom) * 0.5f
        val nearX = cx.coerceIn(clip.left, clip.right)
        val nearY = cy.coerceIn(clip.top, clip.bottom)
        val dMin = hypot(nearX - cx, nearY - cy)
        val dMax = max(
            max(hypot(clip.left - cx, clip.top - cy), hypot(clip.right - cx, clip.top - cy)),
            max(hypot(clip.left - cx, clip.bottom - cy), hypot(clip.right - cx, clip.bottom - cy))
        )
        val k0 = max(1, ceilIdx(dMin, sp))
        val k1 = floorIdx(dMax, sp)
        val showRings = px <= 0f || sp * px >= MIN_PITCH_PX
        val showMajorRings = px <= 0f || sp * 5f * px >= MIN_PITCH_PX
        val cvs = cv ?: return
        if (showRings) {
            val step = strideFor(k1 - k0 + 1)
            var k = k0
            while (k <= k1) {
                if (k % 5 != 0) cvs.drawCircle(cx, cy, k * sp, minorPaint)
                k += step
            }
        }
        if (showMajorRings) {
            var k = k0 + floorMod(-k0, 5)
            if (k == 0) k = 5
            val step = 5 * strideFor((k1 - k0) / 5 + 1)
            while (k <= k1) {
                cvs.drawCircle(cx, cy, k * sp, majorPaint)
                k += step
            }
        }
        // Radial spokes every 15 degrees; 30/45/90 multiples are emphasised.
        for (deg in 0 until 360 step 15) {
            val emphasized = deg % 30 == 0 || deg % 45 == 0
            val a = deg * PI.toFloat() / 180f
            val ca = cos(a)
            val sa = sin(a)
            beginLines(if (emphasized) majorPaint else minorPaint)
            val r0 = if (dMin > 0f) dMin else 0f
            line(cx + ca * r0, cy + sa * r0, cx + ca * dMax, cy + sa * dMax)
        }
    }

    // =====================================================================================
    // Structured sheets
    // =====================================================================================

    private fun cornell(c: TemplateConfig, page: StrokeBounds) {
        val x0 = page.left
        val y0 = page.top
        val x1 = page.right
        val w = page.width
        val h = page.height
        val header = min(24f, h * 0.1f)
        val headerBottom = y0 + header
        val summaryTop = y0 + h * (1f - c.cornellSummaryFraction)
        val cueX = x0 + w * c.cornellCueFraction

        // Notes area rules
        val sp = c.lineSpacingMm
        if (px <= 0f || sp * px >= MIN_PITCH_PX) {
            beginLines(minorPaint)
            val firstRow = max(2, ceilIdx(clip.top - headerBottom, sp))
            val lastRow = floorIdx(min(clip.bottom, summaryTop - sp * 0.5f) - headerBottom, sp)
            val step = strideFor(lastRow - firstRow + 1)
            var r = firstRow
            while (r <= lastRow) {
                val y = headerBottom + r * sp
                line(max(cueX, clip.left), y, min(x1, clip.right), y)
                r += step
            }
        }
        beginLines(majorPaint)
        line(x0, headerBottom, x1, headerBottom)
        line(x0, summaryTop, x1, summaryTop)
        line(cueX, headerBottom, cueX, summaryTop)

        // Header: title / date / topic lines
        beginLines(minorPaint)
        line(x0 + 10f, y0 + 11f, x0 + w * 0.62f, y0 + 11f)
        line(x0 + w * 0.66f, y0 + 11f, x1 - 10f, y0 + 11f)
        line(x0 + 10f, y0 + 21f, x1 - 10f, y0 + 21f)
        flushLines()
        label("TITLE", x0 + 10f, y0 + 9.4f, 2.6f)
        label("DATE", x0 + w * 0.66f, y0 + 9.4f, 2.6f)
        label("TOPIC", x0 + 10f, y0 + 19.4f, 2.6f)
        label("CUES", x0 + 8f, headerBottom + 6f, 3f)
        label("NOTES", cueX + 8f, headerBottom + 6f, 3f)
        label("SUMMARY", x0 + 8f, summaryTop + 6f, 3f)
    }

    private fun split(c: TemplateConfig, page: StrokeBounds) {
        val x0 = page.left
        val y0 = page.top
        val x1 = page.right
        val y1 = page.bottom
        val midX = (x0 + x1) * 0.5f
        val midY = (y0 + y1) * 0.5f
        val band = 8f
        beginLines(majorPaint)
        line(midX, y0, midX, y1)
        if (c.splitLayout == SplitLayout.FOUR_QUADRANT) {
            line(x0, midY, x1, midY)
            flushLines()
            band(x0, y0, midX, band, 0)
            band(midX, y0, x1, band, 1)
            band(x0, midY, midX, band, 2)
            band(midX, midY, x1, band, 3)
            return
        }
        val blocks = 4
        val blockH = (y1 - y0) / blocks
        beginLines(minorPaint)
        for (b in 1 until blocks) line(x0, y0 + b * blockH, x1, y0 + b * blockH)
        flushLines()
        for (b in 0 until blocks) {
            val y = y0 + b * blockH
            band(x0, y, midX, band, 2 * b)
            band(midX, y, x1, band, 2 * b + 1)
        }
    }

    private fun band(left: Float, top: Float, right: Float, height: Float, labelIndex: Int) {
        val cvs = cv ?: return
        fillPaint.style = Paint.Style.FILL
        fillPaint.color = accentRgb or (0x22 shl 24)
        box.set(left, top, right, top + height)
        cvs.drawRect(box, fillPaint)
        label(questionLabels[labelIndex], left + 3f, top + height * 0.7f, 3.4f)
    }

    private fun mistakeLog(c: TemplateConfig, page: StrokeBounds) {
        val x0 = page.left + 10f
        val x1 = page.right - 10f
        val w = x1 - x0
        val titleY = page.top + 16f
        val headTop = page.top + 22f
        val headH = 12f
        val tableTop = headTop + headH
        val rowH = max(c.lineSpacingMm * 3f, 12f)
        val tableBottom = page.bottom - 10f
        val rows = floorIdx(tableBottom - tableTop, rowH)
        val bottom = tableTop + rows * rowH

        val fractions = MISTAKE_COLUMNS
        val colX = FloatArray(fractions.size + 1)
        var acc = 0f
        colX[0] = x0
        for (i in fractions.indices) {
            acc += fractions[i]
            colX[i + 1] = x0 + w * acc
        }

        val cvs = cv ?: return
        label("MISTAKE LOG & REVISION TRACKER", x0, titleY, 5f)
        fillPaint.style = Paint.Style.FILL
        fillPaint.color = accentRgb or (0x1C shl 24)
        box.set(x0, headTop, x1, tableTop)
        cvs.drawRect(box, fillPaint)

        label("# / Source", colX[0] + 2.5f, headTop + 7.4f, 3f)
        label("Question & Concept", colX[1] + 2.5f, headTop + 7.4f, 3f)
        label("Error Type", colX[2] + 2.5f, headTop + 5f, 3f)
        label("Conceptual / Calc / Read", colX[2] + 2.5f, headTop + 9.4f, 2.1f)
        label("Fix & Key Takeaway", colX[3] + 2.5f, headTop + 7.4f, 3f)
        label("Re-solved", colX[4] + 2.5f, headTop + 7.4f, 3f)

        beginLines(majorPaint)
        line(x0, headTop, x1, headTop)
        line(x0, tableTop, x1, tableTop)
        line(x0, bottom, x1, bottom)
        for (i in colX.indices) line(colX[i], headTop, colX[i], bottom)

        val r0 = max(0, floorIdx(clip.top - tableTop, rowH))
        val r1 = min(rows - 1, floorIdx(clip.bottom - tableTop, rowH))
        beginLines(minorPaint)
        for (r in r0..r1) {
            val y = tableTop + (r + 1) * rowH
            if (r < rows - 1) line(x0, y, x1, y)
        }
        flushLines()

        // Error-type tick boxes and re-solve checkboxes per visible row.
        configureStroke(accentPaint, majorAlpha, hairline * 1.2f, ruleRgb)
        for (r in r0..r1) {
            val top = tableTop + r * rowH
            val bs = 2.4f
            val stack = floatArrayOf(0.22f, 0.5f, 0.78f)
            for (i in 0 until 3) {
                val by = top + rowH * stack[i] - bs / 2f
                box.set(colX[2] + 3f, by, colX[2] + 3f + bs, by + bs)
                cvs.drawRect(box, accentPaint)
                label(ERROR_LABELS[i], colX[2] + 3f + bs + 1.6f, by + bs - 0.2f, 2.2f)
            }
            val cw = colX[5] - colX[4]
            for (i in 0 until 3) {
                val cx = colX[4] + cw * (i + 0.5f) / 3f
                box.set(cx - 1.7f, top + rowH / 2f - 1.7f, cx + 1.7f, top + rowH / 2f + 1.7f)
                cvs.drawRect(box, accentPaint)
            }
        }
    }

    private fun planner(c: TemplateConfig, page: StrokeBounds) {
        val cvs = cv ?: return
        val x0 = page.left + 10f
        val x1 = page.right - 10f
        val w = x1 - x0
        val top = page.top + 26f
        val bottom = page.bottom - 12f
        val leftW = w * 0.46f
        val rightX = x0 + w * 0.50f
        val slots = c.plannerHours * 2
        val slotH = (bottom - top) / slots

        label("DAILY STUDY PLANNER", x0, page.top + 14f, 5f)
        label("DATE", rightX, page.top + 14f, 2.6f)
        beginLines(minorPaint)
        line(rightX + 10f, page.top + 14.4f, x1, page.top + 14.4f)

        // Half-hourly time blocks
        val s0 = max(0, floorIdx(clip.top - top, slotH))
        val s1 = min(slots - 1, floorIdx(clip.bottom - top, slotH))
        for (s in s0..s1) {
            val y = top + s * slotH
            if (s % 2 == 0) continue
            line(x0 + 12f, y, x0 + leftW, y)
        }
        beginLines(majorPaint)
        for (s in s0..s1 + 1) {
            if (s > slots) break
            if (s % 2 != 0) continue
            val y = top + s * slotH
            line(x0, y, x0 + leftW, y)
        }
        line(x0 + 12f, top, x0 + 12f, bottom)
        line(x0 + leftW, top, x0 + leftW, bottom)
        line(x0, bottom, x0 + leftW, bottom)
        flushLines()
        for (s in s0..s1) {
            if (s % 2 != 0) continue
            label(hourLabel((c.plannerStartHour + s / 2) % 24), x0 + 0.8f, top + s * slotH + slotH * 0.78f, 2.4f)
        }

        // Right column sections
        val gap = 5f
        val span = bottom - top - 2 * gap
        val prioH = span * 0.38f
        val trackH = span * 0.30f
        val reviewH = span - prioH - trackH
        val prioTop = top
        val trackTop = prioTop + prioH + gap
        val reviewTop = trackTop + trackH + gap

        configureStroke(accentPaint, majorAlpha, hairline * 1.5f, ruleRgb)
        section("PRIORITY TARGETS", rightX, prioTop, x1, prioH)
        val items = 6
        val itemH = (prioH - 9f) / items
        beginLines(minorPaint)
        for (i in 0 until items) {
            val y = prioTop + 9f + (i + 1) * itemH
            line(rightX + 8f, y, x1 - 3f, y)
        }
        flushLines()
        for (i in 0 until items) {
            val y = prioTop + 9f + i * itemH + itemH / 2f
            box.set(rightX + 3f, y - 1.7f, rightX + 6.4f, y + 1.7f)
            cvs.drawRect(box, accentPaint)
        }

        section("SUBJECT HOUR TRACKER", rightX, trackTop, x1, trackH)
        val subjects = TRACKER_SUBJECTS
        val rowH = (trackH - 9f) / subjects.size
        val barLeft = rightX + 16f
        val barRight = x1 - 4f
        val cell = (barRight - barLeft) / TRACKER_HOURS
        for (i in subjects.indices) {
            val yc = trackTop + 9f + i * rowH + rowH / 2f
            label(subjects[i], rightX + 3f, yc + 1f, 2.6f)
            box.set(barLeft, yc - 2.2f, barRight, yc + 2.2f)
            cvs.drawRect(box, accentPaint)
            beginLines(minorPaint)
            for (k in 1 until TRACKER_HOURS) line(barLeft + k * cell, yc - 2.2f, barLeft + k * cell, yc + 2.2f)
            flushLines()
        }

        section("DAILY REVIEW", rightX, reviewTop, x1, reviewH)
        beginLines(minorPaint)
        val lines = 4
        val lineH = (reviewH - 9f) / lines
        for (i in 1..lines) {
            val y = reviewTop + 9f + i * lineH
            line(rightX + 3f, y, x1 - 3f, y)
        }
        flushLines()
    }

    private fun section(title: String, left: Float, top: Float, right: Float, height: Float) {
        val cvs = cv ?: return
        fillPaint.style = Paint.Style.FILL
        fillPaint.color = accentRgb or (0x1C shl 24)
        box.set(left, top, right, top + 7f)
        cvs.drawRect(box, fillPaint)
        box.set(left, top, right, top + height)
        cvs.drawRect(box, accentPaint)
        label(title, left + 3f, top + 5f, 2.8f)
    }

    // =====================================================================================
    // Primitives
    // =====================================================================================

    private fun label(text: String, x: Float, y: Float, size: Float) {
        if (px > 0f && size * px < 4f) return
        val cvs = cv ?: return
        flushLines()
        textPaint.textSize = size
        textPaint.color = ruleRgb or (min(255, majorAlpha + 30) shl 24)
        textPaint.textAlign = Paint.Align.LEFT
        cvs.drawText(text, x, y, textPaint)
    }

    private fun hourLabel(hour: Int): String {
        var s = hourLabels[hour]
        if (s == null) {
            s = (if (hour < 10) "0" else "") + hour + ":00"
            hourLabels[hour] = s
        }
        return s
    }

    private fun beginLines(paint: Paint) {
        if (batchPaint !== paint) flushLines()
        batchPaint = paint
    }

    private fun line(x0: Float, y0: Float, x1: Float, y1: Float) {
        if (lineCount == LINE_BATCH) flushLines()
        val i = lineCount * 4
        lineBuf[i] = x0
        lineBuf[i + 1] = y0
        lineBuf[i + 2] = x1
        lineBuf[i + 3] = y1
        lineCount++
    }

    private fun flushLines() {
        if (lineCount == 0) return
        val p = batchPaint
        val c = cv
        if (p != null && c != null) c.drawLines(lineBuf, 0, lineCount * 4, p)
        lineCount = 0
    }

    private fun dot(x: Float, y: Float) {
        if (dotCount == DOT_BATCH) flushDots()
        dotBuf[dotCount * 2] = x
        dotBuf[dotCount * 2 + 1] = y
        dotCount++
    }

    private fun flushDots() {
        if (dotCount == 0) return
        val p = batchPaint
        val c = cv
        if (p != null && c != null) c.drawPoints(dotBuf, 0, dotCount * 2, p)
        dotCount = 0
    }

    private fun configureStroke(p: Paint, alpha: Int, width: Float, rgb: Int = ruleRgb) {
        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.BUTT
        p.strokeWidth = width
        p.color = rgb or (0xFF shl 24)
        p.alpha = alpha.coerceIn(0, 255)
    }

    private fun ceilIdx(v: Float, step: Float): Int = ceil(v / step).toInt()
    private fun floorIdx(v: Float, step: Float): Int = floor(v / step).toInt()
    private fun floorMod(a: Int, b: Int): Int = ((a % b) + b) % b

    /** Stride that keeps a line family at no more than [MAX_LINES] lines when zoomed far out. */
    private fun strideFor(count: Int): Int = max(1, ceil(count / MAX_LINES.toFloat()).toInt())

    private fun ruleColor(paper: PaperColor): Int = when (paper) {
        PaperColor.PURE_WHITE -> 0xFF5B7BA6.toInt()
        PaperColor.WARM_IVORY -> 0xFF7A8FA6.toInt()
        PaperColor.EYE_CARE_SEPIA -> 0xFF8B6B4A.toInt()
        PaperColor.NORDIC_GRAY -> 0xFF8FA3B8.toInt()
        PaperColor.AMOLED_BLACK -> 0xFF9AA5B1.toInt()
        PaperColor.BLUEPRINT_NAVY -> 0xFFBFDFFF.toInt()
    }

    private fun accentColor(darkPaper: Boolean): Int = if (darkPaper) 0xFFE57373.toInt() else 0xFFD9534F.toInt()

    private companion object {
        const val LINE_BATCH = 512
        const val DOT_BATCH = 1024
        const val MIN_PITCH_PX = 3.5f
        const val MAX_LINES = 500
        const val MAX_HEX_CELLS = 40000
        const val TRACKER_HOURS = 12
        val TRACKER_SUBJECTS = arrayOf("PHY", "CHE", "MAT", "OTH")
        val ERROR_LABELS = arrayOf("Conceptual", "Calc", "Read")
        val MISTAKE_COLUMNS = floatArrayOf(0.17f, 0.31f, 0.17f, 0.25f, 0.10f)
    }
}
