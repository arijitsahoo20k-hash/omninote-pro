package com.omninote.pro.engine.ink

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.omninote.pro.core.model.BrushType
import com.omninote.pro.core.model.Layer
import com.omninote.pro.core.model.NotebookPage
import com.omninote.pro.core.model.ShapePrimitive
import com.omninote.pro.core.model.Stroke
import com.omninote.pro.core.model.StrokeBounds
import com.omninote.pro.core.model.PaperColor
import kotlin.math.max
import kotlin.math.min
import com.omninote.pro.engine.io.PdfTileManager
import com.omninote.pro.engine.template.ProceduralTemplateRenderer
import com.omninote.pro.data.SpatialRTreeIndex

/** Paints everything behind the ink (ruled lines, grids, PDF raster). Supplied by the template/PDF engine. */
fun interface PageBackgroundPainter {
    fun draw(canvas: Canvas, page: NotebookPage, viewportMm: StrokeBounds, ctx: InkRenderContext)
}

/**
 * Renders committed page content onto a canvas whose matrix already maps page mm to pixels.
 *
 * Per visible layer, bottom to top, the pass order is:
 *  1. image elements locked to the background
 *  2. highlighter strokes (so ink always reads on top of them)
 *  3. remaining images, shapes, stencils, text boxes
 *  4. ink strokes (everything that is not highlighter or study tape)
 *  5. study tape (always on top, so a mask hides what is underneath)
 *
 * Culling is done with [SpatialRTreeIndex.queryViewport]; list order inside each category is preserved.
 * Layer opacity below 1 renders the layer into an offscreen group, in which highlighter MULTIPLY can only blend
 * with the layer's own content rather than the paper (a deliberate trade-off for a rarely used setting).
 */
class DryLayerRenderer(
    val strokes: InkStrokeRenderer,
    private val shapes: ShapePainter,
    private val images: ImagePainter,
    private val stencils: StencilPainter,
    private val texts: TextBoxPainter,
    var background: PageBackgroundPainter? = null,
    var pdf: PdfTileManager? = null
) {

    private val paperPaint = Paint()
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val groupBounds = RectF()
    private val itemBounds = RectF()
    private var pageBounds = StrokeBounds(0f, 0f, 1f, 1f)
    private val templates = ProceduralTemplateRenderer()
    private var playbackMs: Long = -1L

    /**
     * @param viewportMm visible page rectangle in mm (already inflated by the caller if it wants a safety margin).
     * @param excludeIds element ids to skip (for example the strokes currently being erased or lifted by a gesture).
     * @param activeAudioPlaybackMs when non-null, audio-stamped content is replayed against this position: items
     * stamped after it draw as 22% ghosts, items stamped within the last [AUDIO_GLOW_MS] get an accent halo, and
     * earlier items draw normally. Items without a stamp (-1) always draw normally.
     * @param pxPerMm current zoom, used by the template renderer for hairline and density control (0 = ignore).
     * @param zoom viewport zoom; the PDF tile tier switches on above 1.25 (base scale is pxPerMm / zoom).
     */
    fun render(
        canvas: Canvas,
        page: NotebookPage,
        index: SpatialRTreeIndex,
        viewportMm: StrokeBounds,
        ctx: InkRenderContext,
        excludeIds: Set<String> = emptySet(),
        activeAudioPlaybackMs: Long? = null,
        pxPerMm: Float = 0f,
        zoom: Float = 1f
    ) {
        ctx.paperIsDark = page.template.paperColor.isDark
        playbackMs = activeAudioPlaybackMs ?: -1L
        paintPaper(canvas, page, viewportMm, pxPerMm)
        if (page.pdfPageIndex != null) pdf?.draw(canvas, page, viewportMm, pxPerMm, zoom)
        background?.draw(canvas, page, viewportMm, ctx)

        val layers = page.visibleLayers
        if (layers.isEmpty()) return
        val visibleIds = HashSet<String>(layers.size * 2)
        for (l in layers) visibleIds.add(l.id)
        val inView = HashSet<String>()
        for (ref in index.queryViewport(viewportMm, visibleIds)) inView.add(ref.id)
        if (excludeIds.isNotEmpty()) inView.removeAll(excludeIds)

        // Background-locked images from every layer sit under all ink, regardless of layer order.
        for (layer in layers) {
            for (img in layer.images) {
                if (img.isLockedToBackground && img.id in inView) images.draw(canvas, img, ctx)
            }
        }
        for (layer in layers) renderLayer(canvas, layer, inView, viewportMm, ctx)
    }

    /**
     * Draws a whole page (or [regionMm] of it) for export, with no R-Tree, no viewport culling and no replay effects.
     * The canvas must already be in page-mm space. The caller sets [ctx] flags: `isExport` for raw colours,
     * `isPdfExport` for PdfDocument canvases, and `pxPerMm` for anything scale-dependent.
     */
    fun renderForExport(
        canvas: Canvas,
        page: NotebookPage,
        ctx: InkRenderContext,
        options: PageExportOptions,
        regionMm: StrokeBounds? = null
    ) {
        playbackMs = -1L
        val full = StrokeBounds(0f, 0f, page.widthMm, page.heightMm)
        val region = regionMm?.let {
            StrokeBounds(
                max(0f, it.left), max(0f, it.top), min(full.right, it.right), min(full.bottom, it.bottom)
            )
        } ?: full
        if (region.left >= region.right || region.top >= region.bottom) return
        ctx.paperIsDark = page.template.paperColor.isDark && !options.forcePrintWhite

        canvas.save()
        canvas.clipRect(region.left, region.top, region.right, region.bottom)
        if (options.drawBackground) {
            if (options.forcePrintWhite) {
                templates.draw(
                    canvas,
                    page.template.copy(paperColor = PaperColor.PURE_WHITE),
                    full,
                    region,
                    0f,
                    PRINT_RULE_RGB
                )
            } else {
                templates.draw(canvas, page.template, full, region, 0f)
            }
        }
        options.underlay?.invoke(canvas, region)

        val layers = if (options.includeHiddenLayers) page.layers.sortedBy { it.zIndex } else page.visibleLayers
        val only = options.onlyElementIds
        for (layer in layers) {
            if (layer.isEmpty) continue
            val grouped = layer.opacity < 1f
            if (grouped) {
                groupBounds.set(region.left, region.top, region.right, region.bottom)
                canvas.saveLayerAlpha(groupBounds, (layer.opacity * 255f).toInt())
            }
            val hook = options.elementHook
            fun wanted(e: com.omninote.pro.core.model.PageElement) =
                (only == null || e.id in only) && !(hook != null && hook(e))
            options.layerObserver?.onLayerBegin(layer)
            for (s in layer.strokes) if (s.brush.type == BrushType.HIGHLIGHTER && wanted(s)) strokes.drawStroke(canvas, s, ctx)
            for (img in layer.images) if (wanted(img)) images.draw(canvas, img, ctx)
            for (shape in layer.shapes) if (wanted(shape)) shapes.draw(canvas, shape, ctx)
            for (st in layer.stencils) if (wanted(st)) stencils.draw(canvas, st, ctx)
            for (box in layer.textBoxes) if (wanted(box)) texts.draw(canvas, box, ctx)
            for (s in layer.strokes) {
                val type = s.brush.type
                if (type != BrushType.HIGHLIGHTER && type != BrushType.STUDY_TAPE && wanted(s)) strokes.drawStroke(canvas, s, ctx)
            }
            for (s in layer.strokes) {
                if (s.brush.type != BrushType.STUDY_TAPE || !wanted(s)) continue
                val masked = options.tapeMaskOverrides[s.id] ?: when (options.tapeMode) {
                    TapeRenderMode.KEEP_CURRENT -> s.isMasked
                    TapeRenderMode.MASK_ALL -> true
                    TapeRenderMode.REVEAL_ALL -> false
                }
                strokes.drawStroke(canvas, if (masked == s.isMasked) s else s.copy(isMasked = masked), ctx)
                if (s.id in options.tapeAccentIds) drawTapeAccent(canvas, s.bounds)
            }
            options.layerObserver?.onLayerEnd(layer)
            if (grouped) canvas.restore()
        }
        canvas.restore()
    }

    private fun drawTapeAccent(canvas: Canvas, b: StrokeBounds) {
        haloPaint.style = Paint.Style.STROKE
        haloPaint.strokeWidth = 0.5f
        haloPaint.color = 0xFFFF7A1A.toInt()
        itemBounds.set(b.left - 0.8f, b.top - 0.8f, b.right + 0.8f, b.bottom + 0.8f)
        canvas.drawRoundRect(itemBounds, 1.2f, 1.2f, haloPaint)
        haloPaint.style = Paint.Style.FILL
    }

    private fun paintPaper(canvas: Canvas, page: NotebookPage, viewportMm: StrokeBounds, pxPerMm: Float) {
        // Fill the whole viewport first so the area outside the page edge matches the paper, then lay the
        // procedural template over the visible part of the page.
        paperPaint.style = Paint.Style.FILL
        paperPaint.color = page.template.paperColor.argb or (0xFF shl 24)
        canvas.drawRect(viewportMm.left, viewportMm.top, viewportMm.right, viewportMm.bottom, paperPaint)
        if (pageBounds.right != page.widthMm || pageBounds.bottom != page.heightMm) {
            pageBounds = StrokeBounds(0f, 0f, page.widthMm, page.heightMm)
        }
        templates.draw(canvas, page.template, pageBounds, viewportMm, pxPerMm)
    }

    /** Draws one audio-stamped item per the replay rules; unstamped items or no active replay draw normally. */
    private inline fun audioAware(
        canvas: Canvas,
        offsetMs: Long,
        bounds: StrokeBounds,
        ctx: InkRenderContext,
        draw: () -> Unit
    ) {
        val pos = playbackMs
        if (pos < 0L || offsetMs < 0L) {
            draw()
            return
        }
        if (offsetMs > pos) {
            itemBounds.set(bounds.left, bounds.top, bounds.right, bounds.bottom)
            canvas.saveLayerAlpha(itemBounds, GHOST_ALPHA)
            draw()
            canvas.restore()
            return
        }
        val age = pos - offsetMs
        if (age <= AUDIO_GLOW_MS) {
            val k = 1f - age / AUDIO_GLOW_MS.toFloat()
            val pad = 0.9f + 0.8f * k
            haloPaint.style = Paint.Style.FILL
            haloPaint.color = GLOW_RGB or (((0.20f + 0.30f * k) * 255f).toInt() shl 24)
            itemBounds.set(bounds.left - pad, bounds.top - pad, bounds.right + pad, bounds.bottom + pad)
            canvas.drawRoundRect(itemBounds, 2f + pad, 2f + pad, haloPaint)
        }
        draw()
    }

    private fun renderLayer(
        canvas: Canvas,
        layer: Layer,
        inView: Set<String>,
        viewportMm: StrokeBounds,
        ctx: InkRenderContext
    ) {
        if (layer.isEmpty) return
        val grouped = layer.opacity < 1f
        if (grouped) {
            groupBounds.set(viewportMm.left, viewportMm.top, viewportMm.right, viewportMm.bottom)
            canvas.saveLayerAlpha(groupBounds, (layer.opacity * 255f).toInt())
        }

        for (s in layer.strokes) {
            if (s.brush.type == BrushType.HIGHLIGHTER && s.id in inView) drawStrokeAudio(canvas, s, ctx)
        }
        for (img in layer.images) {
            if (!img.isLockedToBackground && img.id in inView) images.draw(canvas, img, ctx)
        }
        for (shape in layer.shapes) if (shape.id in inView) drawShapeAudio(canvas, shape, ctx)
        for (st in layer.stencils) if (st.id in inView) stencils.draw(canvas, st, ctx)
        for (box in layer.textBoxes) if (box.id in inView) texts.draw(canvas, box, ctx)
        for (s in layer.strokes) {
            val type = s.brush.type
            if (type != BrushType.HIGHLIGHTER && type != BrushType.STUDY_TAPE && s.id in inView) {
                drawStrokeAudio(canvas, s, ctx)
            }
        }
        for (s in layer.strokes) {
            if (s.brush.type == BrushType.STUDY_TAPE && s.id in inView) strokes.drawStroke(canvas, s, ctx)
        }

        if (grouped) canvas.restore()
    }

    private fun drawStrokeAudio(canvas: Canvas, s: Stroke, ctx: InkRenderContext) =
        audioAware(canvas, s.audioOffsetMs, s.bounds, ctx) { strokes.drawStroke(canvas, s, ctx) }

    private fun drawShapeAudio(canvas: Canvas, shape: ShapePrimitive, ctx: InkRenderContext) =
        audioAware(canvas, shape.audioOffsetMs, shape.spatialBounds, ctx) { shapes.draw(canvas, shape, ctx) }

    private companion object {
        const val PRINT_RULE_RGB = 0x9CA3AF
        const val GHOST_ALPHA = 56 // 22% of 255
        const val AUDIO_GLOW_MS = 1200L
        const val GLOW_RGB = 0x00FFB74D // warm amber
    }
}
