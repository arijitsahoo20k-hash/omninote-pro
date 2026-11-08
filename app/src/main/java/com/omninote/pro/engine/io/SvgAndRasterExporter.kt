package com.omninote.pro.engine.io

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.util.Base64
import com.omninote.pro.core.model.ImageElement
import com.omninote.pro.core.model.Layer
import com.omninote.pro.core.model.NotebookPage
import com.omninote.pro.core.model.PageElement
import com.omninote.pro.core.model.StrokeBounds
import com.omninote.pro.core.model.AudioClipElement
import com.omninote.pro.core.model.TextBoxElement
import com.omninote.pro.data.PageDao
import com.omninote.pro.data.PagePayloadStore
import com.omninote.pro.data.toPage
import com.omninote.pro.engine.ink.InkRenderContext
import com.omninote.pro.engine.ink.LayerObserver
import com.omninote.pro.engine.ink.PageExportOptions
import com.omninote.pro.engine.ink.TapeRenderMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

enum class RasterFormat { PNG, JPEG }

enum class RasterDpi(val dpi: Int) { DPI_150(150), DPI_300(300), DPI_600(600) }

data class RasterExportOptions(
    val dpi: RasterDpi = RasterDpi.DPI_300,
    val format: RasterFormat = RasterFormat.PNG,
    val jpegQuality: Int = 92,
    /** Skips the paper fill and template. JPEG has no alpha, so JPEG output is composited on white instead. */
    val transparentBackground: Boolean = false,
    val forcePrintWhite: Boolean = false,
    val includeHiddenLayers: Boolean = false,
    val tapeMode: TapeRenderMode = TapeRenderMode.KEEP_CURRENT
)

data class SvgExportOptions(
    val forcePrintWhite: Boolean = false,
    val transparentBackground: Boolean = false,
    val includeHiddenLayers: Boolean = false,
    val tapeMode: TapeRenderMode = TapeRenderMode.KEEP_CURRENT,
    /** Selection export: extra margin around the selection's bounds. */
    val selectionPaddingMm: Float = 2f
)

data class RasterResult(
    val widthPx: Int,
    val heightPx: Int,
    /** Actual resolution used; lower than requested when the pixel cap forced a downscale. */
    val effectiveDpi: Float,
    val bytesWritten: Long
)

data class SvgResult(val widthMm: Float, val heightMm: Float, val bytesWritten: Long)

/**
 * Editable SVG and high-DPI raster export of a single page or a selection of its elements.
 * All output goes through [com.omninote.pro.engine.ink.DryLayerRenderer.renderForExport], so it matches the PDF and
 * on-screen rendering.
 */
class SvgAndRasterExporter(
    private val kit: ExportRenderKit,
    private val pageDao: PageDao,
    private val payloadStore: PagePayloadStore
) {

    // =====================================================================================
    // SVG
    // =====================================================================================

    suspend fun exportPageToSvg(
        notebookId: String,
        pageId: String,
        options: SvgExportOptions,
        output: OutputStream
    ): SvgResult = withContext(Dispatchers.Default) {
        val page = loadPage(notebookId, pageId)
        val infinite = kit.isInfiniteCanvas(notebookId)
        val smart = smartRegion(page, infinite)
        val svg = buildSvg(page, options, null, smart, smart != null, infinite)
        write(output, svg, smart?.width ?: page.widthMm, smart?.height ?: page.heightMm)
    }

    suspend fun exportSelectionToSvg(
        notebookId: String,
        pageId: String,
        elementIds: Set<String>,
        options: SvgExportOptions,
        output: OutputStream
    ): SvgResult = withContext(Dispatchers.Default) {
        val page = loadPage(notebookId, pageId)
        val region = selectionBounds(page, elementIds, options.selectionPaddingMm)
            ?: throw IllegalArgumentException("None of the requested elements exist on this page")
        val svg = buildSvg(page, options.copy(transparentBackground = true), elementIds, region, false, false)
        write(output, svg, region.width, region.height)
    }

    private fun write(output: OutputStream, svg: String, w: Float, h: Float): SvgResult {
        val bytes = svg.toByteArray(Charsets.UTF_8)
        output.write(bytes)
        output.flush()
        return SvgResult(w, h, bytes.size.toLong())
    }

    private fun buildSvg(
        page: NotebookPage,
        options: SvgExportOptions,
        only: Set<String>?,
        region: StrokeBounds?,
        smart: Boolean,
        infinite: Boolean
    ): String {
        val renderer = kit.newRenderer()
        val images = SyncImageBitmapProvider(kit.filesDir)
        val canvas = SvgRecordingCanvas()
        val widthMm = region?.width ?: page.widthMm
        val heightMm = region?.height ?: page.heightMm
        if (region != null) canvas.translate(-region.left, -region.top)

        val ctx = InkRenderContext(pxPerMm = 10f, isExport = options.forcePrintWhite || options.transparentBackground, isPdfExport = false)
        var source: VectorPdfExporter.SourcePdf? = null

        val observer = object : LayerObserver {
            override fun onLayerBegin(layer: Layer) {
                canvas.raw("\n<g id=\"layer-${SvgRecordingCanvas.escape(layer.id)}\" data-name=\"${SvgRecordingCanvas.escape(layer.name)}\"")
                if (layer.opacity < 0.999f) canvas.raw(" opacity=\"${SvgRecordingCanvas.fmt(layer.opacity, 3)}\"")
                canvas.raw(">\n")
            }

            override fun onLayerEnd(layer: Layer) {
                canvas.raw("</g>\n")
            }
        }

        val hook: (PageElement) -> Boolean = { e ->
            when (e) {
                is TextBoxElement -> {
                    SvgText.write(canvas, e)
                    true
                }
                is ImageElement -> {
                    writeImage(canvas, e, images)
                    true
                }
                is AudioClipElement -> {
                    SvgAudioPin.write(canvas, e)
                    true
                }
                else -> false
            }
        }

        val drawBackground = !options.transparentBackground
        val underlay: (Canvas, StrokeBounds) -> Unit = { _, _ ->
            val pdfIdx = page.pdfPageIndex
            if (drawBackground && pdfIdx != null) {
                val src = source ?: VectorPdfExporter.SourcePdf.open(kit.pdfFile(page.notebookId))?.also { source = it }
                if (src != null) {
                    val png = renderPdfPagePng(src, pdfIdx, page.widthMm, page.heightMm)
                    canvas.raw(
                        "<image x=\"0\" y=\"0\" width=\"${SvgRecordingCanvas.fmt(page.widthMm)}\" height=\"${SvgRecordingCanvas.fmt(page.heightMm)}\" " +
                            "preserveAspectRatio=\"none\" xlink:href=\"data:image/png;base64,$png\"/>\n"
                    )
                }
            }
            canvas.raw("</g>\n")
        }

        canvas.raw("<g id=\"background\">\n")
        try {
            renderer.renderForExport(
                canvas,
                page,
                ctx,
                PageExportOptions(
                    drawBackground = drawBackground,
                    forcePrintWhite = options.forcePrintWhite,
                    includeHiddenLayers = options.includeHiddenLayers,
                    tapeMode = options.tapeMode,
                    onlyElementIds = only,
                    layerObserver = observer,
                    elementHook = hook,
                    underlay = underlay,
                    allowRegionOutsidePage = smart,
                    infiniteCanvas = infinite
                ),
                regionMm = region
            )
        } finally {
            source?.close()
        }
        canvas.finish()

        val sb = StringBuilder(canvas.body.length + canvas.defs.length + 512)
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" xmlns:xlink=\"http://www.w3.org/1999/xlink\" ")
        sb.append("width=\"").append(SvgRecordingCanvas.fmt(widthMm)).append("mm\" ")
        sb.append("height=\"").append(SvgRecordingCanvas.fmt(heightMm)).append("mm\" ")
        sb.append("viewBox=\"0 0 ").append(SvgRecordingCanvas.fmt(widthMm)).append(' ').append(SvgRecordingCanvas.fmt(heightMm)).append("\">\n")
        if (canvas.defs.isNotEmpty()) sb.append("<defs>").append(canvas.defs).append("</defs>\n")
        sb.append(canvas.body)
        sb.append("</svg>\n")
        return sb.toString()
    }

    private fun writeImage(canvas: SvgRecordingCanvas, image: ImageElement, provider: SyncImageBitmapProvider) {
        val bmp = provider.bitmapFor(image.assetRelativePath) ?: return
        val bos = ByteArrayOutputStream(64 * 1024)
        bmp.compress(Bitmap.CompressFormat.PNG, 100, bos)
        val data = Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)

        val b = image.bounds
        val crop = image.cropPolygonMm
        var clipped = false
        if (crop != null && crop.size >= 3) {
            val p = android.graphics.Path()
            p.moveTo(crop[0].x, crop[0].y)
            for (i in 1 until crop.size) p.lineTo(crop[i].x, crop[i].y)
            p.close()
            val saved = canvas.save()
            canvas.clipPath(p)
            clipped = true
            writeImageBody(canvas, image, b, data)
            canvas.restoreToCount(saved)
            return
        }
        if (!clipped) writeImageBody(canvas, image, b, data)
    }

    private fun writeImageBody(canvas: SvgRecordingCanvas, image: ImageElement, b: StrokeBounds, data: String) {
        val m = Matrix(canvas.currentMatrix)
        m.preRotate(image.rotationDeg, b.centerX, b.centerY)
        if (image.isMirrored) m.preScale(-1f, 1f, b.centerX, b.centerY)
        canvas.raw("<image id=\"${SvgRecordingCanvas.escape(image.id)}\" x=\"${SvgRecordingCanvas.fmt(b.left)}\" y=\"${SvgRecordingCanvas.fmt(b.top)}\" ")
        canvas.raw("width=\"${SvgRecordingCanvas.fmt(b.width)}\" height=\"${SvgRecordingCanvas.fmt(b.height)}\" preserveAspectRatio=\"none\"")
        if (image.opacity < 0.999f) canvas.raw(" opacity=\"${SvgRecordingCanvas.fmt(image.opacity, 3)}\"")
        canvas.raw(" transform=\"${matrixAttr(m)}\" xlink:href=\"data:image/png;base64,$data\"/>\n")
    }

    // =====================================================================================
    // Raster
    // =====================================================================================

    suspend fun exportPageToRaster(
        notebookId: String,
        pageId: String,
        options: RasterExportOptions,
        output: OutputStream
    ): RasterResult = withContext(Dispatchers.Default) {
        val page = loadPage(notebookId, pageId)
        val infinite = kit.isInfiniteCanvas(notebookId)
        val smart = smartRegion(page, infinite)
        renderRaster(page, options, null, smart, output, smart != null, infinite)
    }

    /**
     * Crops the given elements (plus a 1 mm margin) out of the page and writes them as a transparent-background PNG,
     * ready to be saved as a sticker or shared.
     */
    suspend fun exportElementsToStickerPng(
        notebookId: String,
        pageId: String,
        elementIds: Set<String>,
        dpi: RasterDpi,
        output: OutputStream
    ): RasterResult = withContext(Dispatchers.Default) {
        val page = loadPage(notebookId, pageId)
        val region = selectionBounds(page, elementIds, 1f)
            ?: throw IllegalArgumentException("None of the requested elements exist on this page")
        renderRaster(
            page,
            RasterExportOptions(dpi = dpi, format = RasterFormat.PNG, transparentBackground = true),
            elementIds,
            region,
            output,
            false,
            false
        )
    }

    private fun renderRaster(
        page: NotebookPage,
        options: RasterExportOptions,
        only: Set<String>?,
        region: StrokeBounds?,
        output: OutputStream,
        smart: Boolean,
        infinite: Boolean
    ): RasterResult {
        val widthMm = region?.width ?: page.widthMm
        val heightMm = region?.height ?: page.heightMm
        var pxPerMm = options.dpi.dpi / 25.4f
        val wanted = (widthMm * pxPerMm).toDouble() * (heightMm * pxPerMm).toDouble()
        if (wanted > MAX_RASTER_PIXELS) pxPerMm *= sqrt(MAX_RASTER_PIXELS / wanted).toFloat()
        val w = max(1, (widthMm * pxPerMm).toInt())
        val h = max(1, (heightMm * pxPerMm).toInt())

        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        var source: VectorPdfExporter.SourcePdf? = null
        try {
            val jpeg = options.format == RasterFormat.JPEG
            if (jpeg) bmp.eraseColor(Color.WHITE) else bmp.eraseColor(Color.TRANSPARENT)
            val canvas = Canvas(bmp)
            canvas.scale(w / widthMm, h / heightMm)
            if (region != null) canvas.translate(-region.left, -region.top)

            val ctx = InkRenderContext(pxPerMm = pxPerMm, isExport = options.forcePrintWhite || options.transparentBackground, isPdfExport = false)
            val drawBackground = !options.transparentBackground
            val pdfIdx = page.pdfPageIndex
            val underlay: ((Canvas, StrokeBounds) -> Unit)? =
                if (drawBackground && pdfIdx != null) {
                    val src = VectorPdfExporter.SourcePdf.open(kit.pdfFile(page.notebookId))?.also { source = it }
                    if (src == null) {
                        null
                    } else {
                        val draw: (Canvas, StrokeBounds) -> Unit = { c, _ -> src.drawPage(c, pdfIdx, page.widthMm, page.heightMm) }
                        draw
                    }
                } else {
                    null
                }

            kit.newRenderer().renderForExport(
                canvas,
                page,
                ctx,
                PageExportOptions(
                    drawBackground = drawBackground,
                    forcePrintWhite = options.forcePrintWhite,
                    includeHiddenLayers = options.includeHiddenLayers,
                    tapeMode = options.tapeMode,
                    onlyElementIds = only,
                    underlay = underlay,
                    allowRegionOutsidePage = smart,
                    infiniteCanvas = infinite
                ),
                regionMm = region
            )

            val counting = CountingOutputStream(output)
            val ok = if (jpeg) {
                bmp.compress(Bitmap.CompressFormat.JPEG, options.jpegQuality.coerceIn(1, 100), counting)
            } else {
                bmp.compress(Bitmap.CompressFormat.PNG, 100, counting)
            }
            if (!ok) throw java.io.IOException("Bitmap encoding failed")
            counting.flush()
            return RasterResult(w, h, pxPerMm * 25.4f, counting.count)
        } finally {
            source?.close()
            bmp.recycle()
        }
    }

    // =====================================================================================

    /** The smart export frame when it differs from the page rectangle, else null (plain whole-page export). */
    private fun smartRegion(page: NotebookPage, infinite: Boolean): StrokeBounds? {
        val b = computeSmartExportBoundsMm(page, infinite)
        val same = b.left == 0f && b.top == 0f && b.right == page.widthMm && b.bottom == page.heightMm
        return if (same) null else b
    }

    private suspend fun loadPage(notebookId: String, pageId: String): NotebookPage {
        payloadStore.flush(notebookId, pageId)
        val meta = pageDao.getById(pageId)
            ?: throw IllegalArgumentException("Page $pageId not found")
        require(meta.notebookId == notebookId) { "Page $pageId does not belong to notebook $notebookId" }
        val layers = payloadStore.load(notebookId, pageId) ?: emptyList()
        return meta.toPage(layers)
    }

    private fun selectionBounds(page: NotebookPage, ids: Set<String>, paddingMm: Float): StrokeBounds? {
        var acc: StrokeBounds? = null
        fun add(e: PageElement) {
            if (e.id !in ids) return
            val b = e.spatialBounds
            acc = acc?.union(b) ?: b
        }
        for (layer in page.layers) {
            layer.strokes.forEach(::add)
            layer.shapes.forEach(::add)
            layer.stencils.forEach(::add)
            layer.images.forEach(::add)
            layer.textBoxes.forEach(::add)
            layer.audioClips.forEach(::add)
        }
        val b = acc ?: return null
        val padded = b.inflate(paddingMm)
        return StrokeBounds(
            max(0f, padded.left), max(0f, padded.top),
            min(page.widthMm, padded.right), min(page.heightMm, padded.bottom)
        )
    }

    private fun renderPdfPagePng(src: VectorPdfExporter.SourcePdf, index: Int, widthMm: Float, heightMm: Float): String {
        val dpi = 150f
        val w = max(1, (widthMm / 25.4f * dpi).toInt())
        val h = max(1, (heightMm / 25.4f * dpi).toInt())
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        try {
            val c = Canvas(bmp)
            c.scale(w / widthMm, h / heightMm)
            src.drawPage(c, index, widthMm, heightMm)
            val bos = ByteArrayOutputStream(256 * 1024)
            bmp.compress(Bitmap.CompressFormat.PNG, 100, bos)
            return Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
        } finally {
            bmp.recycle()
        }
    }

    internal companion object {
        /** Cap on raster output size (about 160 MB of ARGB); larger requests are scaled down uniformly. */
        const val MAX_RASTER_PIXELS = 40_000_000.0

        fun matrixAttr(m: Matrix): String {
            val v = FloatArray(9)
            m.getValues(v)
            return "matrix(${f(v[Matrix.MSCALE_X])} ${f(v[Matrix.MSKEW_Y])} ${f(v[Matrix.MSKEW_X])} ${f(v[Matrix.MSCALE_Y])} " +
                "${f(v[Matrix.MTRANS_X])} ${f(v[Matrix.MTRANS_Y])})"
        }

        private fun f(x: Float): String = SvgRecordingCanvas.fmt(x, 3)
    }
}

private fun matrixAttr(m: Matrix): String = SvgAndRasterExporter.matrixAttr(m)

/** Text boxes as `<text>` with one `<tspan>` per wrapped line and per inline style run. */
internal object SvgText {
    private const val MM_PER_PT = 25.4f / 72f
    private const val MEASURE_SCALE = 10f

    private class Run(val text: String, val bold: Boolean, val italic: Boolean, val mono: Boolean)
    private class Line(val runs: List<Run>, val sizeMm: Float, val indentMm: Float)

    fun write(canvas: SvgRecordingCanvas, box: TextBoxElement) {
        val b = box.bounds
        val baseMm = box.fontSizePt * MM_PER_PT
        val family = when (box.fontFamily) {
            "serif" -> "serif"
            "mono" -> "monospace"
            else -> "sans-serif"
        }
        val measure = Paint(Paint.ANTI_ALIAS_FLAG)
        val lines = ArrayList<Line>()
        for (raw in box.markdownText.split('\n')) lines.addAll(wrap(layoutLine(raw, baseMm), b.width, measure, family))
        for (block in box.latexBlocks) lines.add(Line(listOf(Run(block, false, true, true)), baseMm, 0f))

        val m = Matrix(canvas.currentMatrix)
        m.preRotate(box.rotationDeg, b.centerX, b.centerY)
        val color = SvgRecordingCanvas.hex(box.semanticColorArgb)

        canvas.raw("<text id=\"${SvgRecordingCanvas.escape(box.id)}\" transform=\"${matrixAttr(m)}\" font-family=\"$family\" fill=\"$color\" xml:space=\"preserve\">")
        var y = b.top
        for (line in lines) {
            y += line.sizeMm * 1.15f
            if (y - line.sizeMm * 0.3f > b.bottom) break
            var first = true
            for (run in line.runs) {
                canvas.raw("<tspan")
                if (first) {
                    canvas.raw(" x=\"${SvgRecordingCanvas.fmt(b.left + line.indentMm)}\" y=\"${SvgRecordingCanvas.fmt(y)}\"")
                    first = false
                }
                canvas.raw(" font-size=\"${SvgRecordingCanvas.fmt(line.sizeMm, 3)}\"")
                if (run.bold) canvas.raw(" font-weight=\"bold\"")
                if (run.italic) canvas.raw(" font-style=\"italic\"")
                if (run.mono) canvas.raw(" font-family=\"monospace\"")
                canvas.raw(">${SvgRecordingCanvas.escape(run.text)}</tspan>")
            }
        }
        canvas.raw("</text>\n")
    }

    private fun layoutLine(raw: String, baseMm: Float): Line {
        var text = raw
        var size = baseMm
        var bold = false
        var indent = 0f
        when {
            text.startsWith("### ") -> { text = text.substring(4); size = baseMm * 1.15f; bold = true }
            text.startsWith("## ") -> { text = text.substring(3); size = baseMm * 1.35f; bold = true }
            text.startsWith("# ") -> { text = text.substring(2); size = baseMm * 1.6f; bold = true }
            text.startsWith("- ") || text.startsWith("* ") -> { text = "• " + text.substring(2); indent = baseMm * 0.5f }
        }
        return Line(inlineRuns(text, bold), size, indent)
    }

    /** Parses `**bold**`, `*italic*` and `` `code` `` into runs. */
    private fun inlineRuns(text: String, forceBold: Boolean): List<Run> {
        val out = ArrayList<Run>()
        val cur = StringBuilder()
        var bold = false
        var italic = false
        var mono = false
        fun flush() {
            if (cur.isNotEmpty()) {
                out.add(Run(cur.toString(), bold || forceBold, italic, mono))
                cur.setLength(0)
            }
        }
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '`' -> { flush(); mono = !mono; i++ }
                c == '*' && i + 1 < text.length && text[i + 1] == '*' -> { flush(); bold = !bold; i += 2 }
                c == '*' -> { flush(); italic = !italic; i++ }
                else -> { cur.append(c); i++ }
            }
        }
        flush()
        if (out.isEmpty()) out.add(Run("", forceBold, false, false))
        return out
    }

    /** Greedy word wrap to [widthMm], measuring with a platform sans/serif/mono face. */
    private fun wrap(line: Line, widthMm: Float, paint: Paint, family: String): List<Line> {
        paint.typeface = when (family) {
            "serif" -> android.graphics.Typeface.SERIF
            "monospace" -> android.graphics.Typeface.MONOSPACE
            else -> android.graphics.Typeface.SANS_SERIF
        }
        paint.textSize = line.sizeMm * MEASURE_SCALE
        val avail = (widthMm - line.indentMm) * MEASURE_SCALE
        val result = ArrayList<Line>()
        var current = ArrayList<Run>()
        var used = 0f
        for (run in line.runs) {
            val words = run.text.split(' ')
            for ((wi, word) in words.withIndex()) {
                val piece = if (wi < words.size - 1) "$word " else word
                if (piece.isEmpty()) continue
                val wpx = paint.measureText(piece)
                if (used + wpx > avail && used > 0f) {
                    result.add(Line(current, line.sizeMm, line.indentMm))
                    current = ArrayList()
                    used = 0f
                }
                current.add(Run(piece, run.bold, run.italic, run.mono))
                used += wpx
            }
        }
        result.add(Line(current.ifEmpty { listOf(Run("", false, false, false)) }, line.sizeMm, line.indentMm))
        return result
    }
}

/** A voice-note pin as a plain SVG pill (rounded rect, play triangle, title and duration). */
internal object SvgAudioPin {
    fun write(canvas: SvgRecordingCanvas, clip: AudioClipElement) {
        val b = clip.spatialBounds
        val w = b.width
        val h = b.height
        val r = h / 2f
        val m = Matrix(canvas.currentMatrix)
        val duration = com.omninote.pro.engine.ink.AudioPinPainter.formatDuration(clip.durationMs)
        fun f(v: Float) = SvgRecordingCanvas.fmt(v, 3)
        canvas.raw("<g id=\"${SvgRecordingCanvas.escape(clip.id)}\" transform=\"${matrixAttr(m)}\" font-family=\"sans-serif\">")
        canvas.raw("<rect x=\"${f(b.left)}\" y=\"${f(b.top)}\" width=\"${f(w)}\" height=\"${f(h)}\" rx=\"${f(r)}\" fill=\"#EFF3FA\" stroke=\"#3B82F6\" stroke-width=\"0.35\"/>")
        canvas.raw("<circle cx=\"${f(b.left + r)}\" cy=\"${f(b.top + r)}\" r=\"${f(r * 0.74f)}\" fill=\"#3B82F6\"/>")
        canvas.raw("<polygon points=\"${f(b.left + r * 0.82f)},${f(b.top + r * 0.62f)} ${f(b.left + r * 1.42f)},${f(b.top + r)} ${f(b.left + r * 0.82f)},${f(b.top + r * 1.38f)}\" fill=\"#FFFFFF\"/>")
        canvas.raw("<text x=\"${f(b.left + r * 2f + 0.5f)}\" y=\"${f(b.top + r + 1f)}\" font-size=\"2.9\" fill=\"#1B2A48\">${SvgRecordingCanvas.escape(clip.title)}</text>")
        canvas.raw("<text x=\"${f(b.right - 1.6f)}\" y=\"${f(b.top + r + 1f)}\" font-size=\"2.9\" font-weight=\"bold\" text-anchor=\"end\" fill=\"#4A5568\">${SvgRecordingCanvas.escape(duration)}</text>")
        canvas.raw("</g>\n")
    }
}
