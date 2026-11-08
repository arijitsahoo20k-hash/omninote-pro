package com.omninote.pro.engine.io

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.omninote.pro.core.model.NotebookPage
import com.omninote.pro.core.model.StrokeBounds
import com.omninote.pro.data.PagePayloadStore
import com.omninote.pro.data.PageDao
import com.omninote.pro.data.toPage
import com.omninote.pro.engine.ink.InkRenderContext
import com.omninote.pro.engine.ink.PageExportOptions
import com.omninote.pro.engine.ink.TapeRenderMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.OutputStream
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Which pages go into the PDF. Indices are 0-based positions in the notebook's page order. */
sealed interface PageSelection {
    data object AllPages : PageSelection

    /** The page currently on screen. */
    data class CurrentPage(val pageIndex: Int) : PageSelection

    data object StarredOnly : PageSelection

    data class CustomRange(val pageIndices: List<Int>) : PageSelection
}

enum class PdfBackgroundMode {
    /** Configured paper colour, template, and dark-mode ink inversion. */
    AS_DISPLAYED,

    /** White paper, grey template lines, raw ink colours: AMOLED notes print as black ink on white. */
    FORCE_PRINT_WHITE,

    /** No paper and no template lines. Ink uses raw colours. */
    TRANSPARENT_NO_TEMPLATE
}

data class PdfExportOptions(
    val pageSelection: PageSelection = PageSelection.AllPages,
    val backgroundMode: PdfBackgroundMode = PdfBackgroundMode.AS_DISPLAYED,
    val studyTapeMode: TapeRenderMode = TapeRenderMode.KEEP_CURRENT,
    val includeHiddenLayers: Boolean = false
)

/**
 * Resolution-independent PDF export on [PdfDocument]. Page space is millimetres; each PDF page is the page size in
 * points (1 mm = 72 / 25.4 pt) and the canvas is scaled once, so every stroke outline, shape and template line is
 * emitted as vector geometry at its exact weight (templates draw with `pxPerMm = 0`, so no screen-LOD culling).
 *
 * The PdfDocument canvas backend does not reliably encode multiply blending, so highlighters export as SRC_OVER at 35%
 * alpha (`InkRenderContext.isPdfExport`). Pages built on an imported textbook PDF get that page rendered at 300 DPI
 * first, with the vector layers drawn over it.
 */
class VectorPdfExporter(
    private val kit: ExportRenderKit,
    private val pageDao: PageDao,
    private val payloadStore: PagePayloadStore
) {

    /**
     * Cold flow: nothing happens until collected, and cancelling the collector stops the export between pages.
     * The output stream is opened only after every page has rendered, so a cancelled or failed export never
     * leaves a partial file. The stream is closed by this function.
     */
    fun export(
        notebookId: String,
        options: PdfExportOptions,
        openOutput: () -> OutputStream
    ): Flow<ExportProgress> = flow {
        var doc: PdfDocument? = null
        var source: SourcePdf? = null
        try {
            payloadStore.flushAll()
            val metas = pageDao.getPages(notebookId).sortedBy { it.pageNumber }
            val indices = resolveSelection(options.pageSelection, metas.map { it.isStarred })
            if (indices.isEmpty()) {
                emit(ExportProgress.Failed(IllegalArgumentException("No pages match the selection")))
                return@flow
            }
            emit(ExportProgress.Started(indices.size))

            doc = PdfDocument()
            val renderer = kit.newRenderer()
            val ctx = InkRenderContext(
                pxPerMm = PT_PER_MM,
                isExport = options.backgroundMode != PdfBackgroundMode.AS_DISPLAYED,
                isPdfExport = true
            )
            val renderOptionsBase = options
            val infiniteCanvas = kit.isInfiniteCanvas(notebookId)
            for ((n, idx) in indices.withIndex()) {
                currentCoroutineContext().ensureActive()
                val meta = metas[idx]
                val layers = payloadStore.load(notebookId, meta.pageId) ?: emptyList()
                val page = meta.toPage(layers).copy(index = idx)

                // Smart framing: an infinite canvas (or ink that spilled past the sheet) is exported around its content.
                val infinite = infiniteCanvas
                val smart = computeSmartExportBoundsMm(page, infinite)
                val framed = !(smart.left == 0f && smart.top == 0f && smart.right == page.widthMm && smart.bottom == page.heightMm)
                val frame = if (framed) smart else StrokeBounds(0f, 0f, page.widthMm, page.heightMm)
                // PDF pages are limited to 14400 units; a very large canvas is scaled down to fit.
                val pdfScale = minOf(1f, PDF_MAX_PAGE_UNITS / max(frame.width * PT_PER_MM, frame.height * PT_PER_MM))
                val info = PdfDocument.PageInfo.Builder(
                    max(1, (frame.width * PT_PER_MM * pdfScale).roundToInt()),
                    max(1, (frame.height * PT_PER_MM * pdfScale).roundToInt()),
                    n + 1
                ).create()
                val pdfPage = doc.startPage(info)
                try {
                    val canvas = pdfPage.canvas
                    canvas.scale(PT_PER_MM * pdfScale, PT_PER_MM * pdfScale)
                    if (framed) canvas.translate(-frame.left, -frame.top)
                    val underlay: ((Canvas, StrokeBounds) -> Unit)? = page.pdfPageIndex?.let { pdfIdx ->
                        val src = source ?: SourcePdf.open(kit.pdfFile(notebookId))?.also { source = it }
                        if (src == null) {
                            null
                        } else {
                            val draw: (Canvas, StrokeBounds) -> Unit = { c, _ -> src.drawPage(c, pdfIdx, page.widthMm, page.heightMm) }
                            draw
                        }
                    }
                    renderer.renderForExport(
                        canvas,
                        page,
                        ctx,
                        PageExportOptions(
                            drawBackground = renderOptionsBase.backgroundMode != PdfBackgroundMode.TRANSPARENT_NO_TEMPLATE,
                            forcePrintWhite = renderOptionsBase.backgroundMode == PdfBackgroundMode.FORCE_PRINT_WHITE,
                            includeHiddenLayers = renderOptionsBase.includeHiddenLayers,
                            tapeMode = renderOptionsBase.studyTapeMode,
                            underlay = underlay,
                            allowRegionOutsidePage = framed,
                            infiniteCanvas = infinite
                        ),
                        regionMm = if (framed) frame else null
                    )
                } finally {
                    doc.finishPage(pdfPage)
                }
                emit(ExportProgress.Step(n + 1, indices.size, "Page ${idx + 1}"))
            }

            currentCoroutineContext().ensureActive()
            val counting = CountingOutputStream(openOutput())
            counting.use { doc.writeTo(it) }
            emit(ExportProgress.Finished(indices.size, counting.count))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            emit(ExportProgress.Failed(e))
        } finally {
            source?.close()
            doc?.close()
        }
    }.flowOn(Dispatchers.Default)

    private fun resolveSelection(sel: PageSelection, starred: List<Boolean>): List<Int> = when (sel) {
        PageSelection.AllPages -> starred.indices.toList()
        is PageSelection.CurrentPage -> listOf(sel.pageIndex).filter { it in starred.indices }
        PageSelection.StarredOnly -> starred.indices.filter { starred[it] }
        is PageSelection.CustomRange -> sel.pageIndices.filter { it in starred.indices }.distinct().sorted()
    }

    /** One open textbook PDF for the duration of an export. */
    internal class SourcePdf(private val pfd: ParcelFileDescriptor, private val renderer: PdfRenderer) {
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

        /** Draws PDF page [index] filling a [widthMm] x [heightMm] box at the origin, rasterised at up to 300 DPI. */
        fun drawPage(canvas: Canvas, index: Int, widthMm: Float, heightMm: Float) {
            if (index !in 0 until renderer.pageCount) return
            var dpi = TEXTBOOK_DPI
            val pixelsAt300 = (widthMm / 25.4f * dpi) * (heightMm / 25.4f * dpi)
            if (pixelsAt300 > MAX_UNDERLAY_PIXELS) dpi *= sqrt(MAX_UNDERLAY_PIXELS / pixelsAt300)
            val w = max(1, (widthMm / 25.4f * dpi).roundToInt())
            val h = max(1, (heightMm / 25.4f * dpi).roundToInt())
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            try {
                bmp.eraseColor(Color.WHITE)
                val page = renderer.openPage(index)
                try {
                    val m = Matrix()
                    m.setScale(w.toFloat() / page.width, h.toFloat() / page.height)
                    page.render(bmp, null, m, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                } finally {
                    page.close()
                }
                canvas.drawBitmap(bmp, null, RectF(0f, 0f, widthMm, heightMm), paint)
            } finally {
                bmp.recycle()
            }
        }

        fun close() {
            try {
                renderer.close()
            } catch (_: Exception) {
            }
            try {
                pfd.close()
            } catch (_: Exception) {
            }
        }

        companion object {
            fun open(file: java.io.File): SourcePdf? {
                if (!file.isFile) return null
                return try {
                    val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                    try {
                        SourcePdf(fd, PdfRenderer(fd))
                    } catch (e: Exception) {
                        fd.close()
                        null
                    }
                } catch (e: Exception) {
                    null
                }
            }
        }
    }

    companion object {
        /** Points per millimetre: 72 / 25.4. */
        const val PT_PER_MM = 72f / 25.4f

        /** The PDF format's practical page-size ceiling (user units). */
        const val PDF_MAX_PAGE_UNITS = 14400f
        private const val TEXTBOOK_DPI = 300f
        private const val MAX_UNDERLAY_PIXELS = 40_000_000f
    }
}

/** Counts bytes written so exporters can report sizes for SAF streams, which cannot be stat'ed. */
internal class CountingOutputStream(private val inner: OutputStream) : OutputStream() {
    var count: Long = 0L
        private set

    override fun write(b: Int) {
        inner.write(b)
        count++
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        inner.write(b, off, len)
        count += len
    }

    override fun flush() = inner.flush()
    override fun close() = inner.close()
}
