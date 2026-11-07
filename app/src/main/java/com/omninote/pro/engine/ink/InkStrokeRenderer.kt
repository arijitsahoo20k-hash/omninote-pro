package com.omninote.pro.engine.ink

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.LruCache
import com.omninote.pro.core.model.BrushConfig
import com.omninote.pro.core.model.BrushType
import com.omninote.pro.core.model.Stroke

/**
 * Draws strokes of the ink instruments (everything except Laser and Eraser, which are transient overlays) onto a
 * page-space canvas. Used for the dry multi-buffered layer, the wet front buffer, and vector PDF export.
 *
 * Committed strokes cache their tessellated Path (page-space, zoom-independent), so panning and zooming a dense
 * page re-uses geometry instead of rebuilding it. One instance per render thread; not thread-safe.
 */
class InkStrokeRenderer(pathCacheEntries: Int = 4096) {

    private class Geo(
        val source: Stroke?,
        val path: Path,
        val meanPressure: Float,
        val meanTiltRad: Float
    )

    private val tessellator = StrokeRibbonTessellator()
    val shaders = BrushShaderFactory()

    private val paint = Paint()
    private val hatchPaint = Paint()
    private val outlinePaint = Paint()
    private val wetPath = Path()
    private val cache = LruCache<String, Geo>(pathCacheEntries)

    fun clearCache() {
        cache.evictAll()
    }

    /** Drops cached geometry for strokes that were split, removed or restyled. Safe from any thread. */
    fun evict(strokeIds: Collection<String>) {
        for (id in strokeIds) cache.remove(id)
    }

    // =====================================================================================
    // Committed strokes
    // =====================================================================================

    fun drawStroke(canvas: Canvas, stroke: Stroke, ctx: InkRenderContext) {
        if (!stroke.brush.type.isInk || stroke.points.isEmpty()) return
        val geo = geometryFor(stroke)
        drawGeometry(canvas, stroke.brush, stroke.semanticColorArgb, geo, ctx, stroke.isMasked, useBlend = true)
    }

    private fun geometryFor(stroke: Stroke): Geo {
        val cached = cache.get(stroke.id)
        if (cached != null && cached.source === stroke) return cached
        val geo = build(stroke.brush, PointArrays.of(stroke.points), null, finalStroke = true, target = Path(), source = stroke)
        cache.put(stroke.id, geo)
        return geo
    }

    /**
     * Vector geometry of a committed stroke for export: the page-mm outline plus how to paint it.
     * The returned path is the renderer's cached instance; read it, do not modify it.
     */
    class VectorGeometry(
        val path: Path,
        /** True: fill the path. False: stroke it with [strokeWidthMm]. */
        val filled: Boolean,
        val strokeWidthMm: Float,
        val roundCaps: Boolean
    )

    fun exportGeometry(stroke: Stroke): VectorGeometry? {
        if (!stroke.brush.type.isInk || stroke.points.isEmpty()) return null
        val geo = geometryFor(stroke)
        val b = stroke.brush
        return when (b.type) {
            BrushType.FINELINER -> VectorGeometry(geo.path, false, b.widthMm, true)
            BrushType.PROCEDURAL_LINE -> when (b.lineStyle) {
                com.omninote.pro.core.model.LineStyle.DOTTED -> VectorGeometry(geo.path, true, 0f, false)
                com.omninote.pro.core.model.LineStyle.DASHED -> VectorGeometry(geo.path, false, b.widthMm, false)
                com.omninote.pro.core.model.LineStyle.WAVY -> VectorGeometry(geo.path, false, b.widthMm, true)
            }
            else -> VectorGeometry(geo.path, true, 0f, false)
        }
    }

    // =====================================================================================
    // Wet stroke (live, every input frame)
    // =====================================================================================

    /** Draws the in-progress stroke: [committed] points plus the speculative predicted tip. No caching, no blend. */
    fun drawWet(
        canvas: Canvas,
        brush: BrushConfig,
        semanticArgb: Int,
        committed: PointArrays,
        predicted: PointArrays?,
        ctx: InkRenderContext
    ) {
        if (!brush.type.isInk || committed.count == 0) return
        val geo = build(brush, committed, predicted, finalStroke = false, target = wetPath, source = null)
        drawGeometry(canvas, brush, semanticArgb, geo, ctx, masked = true, useBlend = false)
    }

    // =====================================================================================

    private fun build(
        brush: BrushConfig,
        points: PointArrays,
        extra: PointArrays?,
        finalStroke: Boolean,
        target: Path,
        source: Stroke?
    ): Geo {
        val t = tessellator
        t.load(points, extra, PressureCurveLut.of(brush.pressureCurve))
        t.computeWidths(brush)
        if (finalStroke && brush.type == BrushType.BRUSH_PEN) t.appendFlickTail(brush.widthMm)
        when (brush.type) {
            BrushType.FINELINER -> t.buildSpine(target)
            BrushType.PROCEDURAL_LINE -> t.buildProcedural(target, brush)
            BrushType.HIGHLIGHTER, BrushType.STUDY_TAPE -> t.buildRibbon(target, roundCaps = false)
            else -> t.buildRibbon(target, roundCaps = true)
        }
        return Geo(source, target, t.meanEffectivePressure(), t.meanTiltRad())
    }

    private fun drawGeometry(
        canvas: Canvas,
        brush: BrushConfig,
        semanticArgb: Int,
        geo: Geo,
        ctx: InkRenderContext,
        masked: Boolean,
        useBlend: Boolean
    ) {
        when (brush.type) {
            BrushType.FINELINER -> {
                shaders.fineliner(paint, brush, semanticArgb, ctx)
                canvas.drawPath(geo.path, paint)
            }
            BrushType.PROCEDURAL_LINE -> {
                shaders.procedural(paint, brush, semanticArgb, ctx)
                canvas.drawPath(geo.path, paint)
            }
            BrushType.HIGHLIGHTER -> {
                shaders.highlighter(paint, brush, semanticArgb, ctx, useBlend)
                canvas.drawPath(geo.path, paint)
            }
            BrushType.STUDY_TAPE -> drawTape(canvas, brush, semanticArgb, geo, ctx, masked)
            else -> {
                shaders.ink(paint, brush, semanticArgb, ctx, geo.meanPressure, geo.meanTiltRad)
                canvas.drawPath(geo.path, paint)
            }
        }
    }

    private fun drawTape(
        canvas: Canvas,
        brush: BrushConfig,
        semanticArgb: Int,
        geo: Geo,
        ctx: InkRenderContext,
        masked: Boolean
    ) {
        shaders.tapeFill(paint, brush, semanticArgb, ctx, masked)
        canvas.drawPath(geo.path, paint)
        if (masked) {
            shaders.tapeHatch(hatchPaint, brush, semanticArgb, ctx)
            canvas.drawPath(geo.path, hatchPaint)
        } else {
            shaders.tapeGhostOutline(outlinePaint, brush, semanticArgb, ctx)
            canvas.drawPath(geo.path, outlinePaint)
        }
    }
}
