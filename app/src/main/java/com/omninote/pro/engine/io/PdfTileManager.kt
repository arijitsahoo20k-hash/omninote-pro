package com.omninote.pro.engine.io

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Rect
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.LruCache
import com.omninote.pro.core.model.NotebookPage
import com.omninote.pro.core.model.StrokeBounds
import com.omninote.pro.core.model.TemplateConfig
import com.omninote.pro.core.model.TemplateType
import com.omninote.pro.data.NotebookDao
import com.omninote.pro.data.PageDao
import com.omninote.pro.data.PageMetaEntity
import com.omninote.pro.data.toJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * High-fps, memory-bounded PDF page and tile renderer for the dry layer.
 *
 * Threading: [PdfRenderer] allows one open page per file descriptor, so every `openPage`/`render` runs on ONE
 * dedicated thread under a [Mutex] that also guards open and close. [draw] is called from the render thread every
 * frame; it only reads caches and enqueues work, and never blocks on PDF rendering.
 *
 * Tier 1: a full-page bitmap about [BASE_WIDTH_PX] wide per page, in an [LruCache]; it is drawn first so scrolling or
 * zooming never shows blank paper.
 *
 * Tier 2: once zoom exceeds [TILE_ZOOM_THRESHOLD], the visible part of the page is covered with
 * [TILE_PX] x [TILE_PX] tiles rendered at a quantised on-screen scale (steps of 1.5x, so tiles are reused while
 * pinching). Tiles that are no longer wanted are cancelled before they start; [onTileReady] fires (from the PDF
 * thread) whenever a page or tile lands, so the view can redraw.
 */
class PdfTileManager(
    private val context: Context,
    private val scope: CoroutineScope,
    baseCacheBytes: Int = defaultBudget(0.06f, 48),
    tileCacheBytes: Int = defaultBudget(0.10f, 96)
) {
    /** Invoked on the PDF render thread when new pixels are available. Marshal to the UI thread and redraw. */
    @Volatile
    var onTileReady: (() -> Unit)? = null

    private class OpenPdf(val pfd: ParcelFileDescriptor, val renderer: PdfRenderer, val pagePts: IntArray) {
        val pageCount: Int get() = renderer.pageCount
        fun widthPts(i: Int) = pagePts[i * 2]
        fun heightPts(i: Int) = pagePts[i * 2 + 1]
    }

    private data class PageKey(val notebookId: String, val pageIndex: Int)
    private data class TileKey(val notebookId: String, val pageIndex: Int, val level: Int, val col: Int, val row: Int)

    private val renderThread: ExecutorCoroutineDispatcher =
        Executors.newSingleThreadExecutor { r -> Thread(r, "pdf-render").apply { priority = Thread.NORM_PRIORITY - 1 } }
            .asCoroutineDispatcher()
    private val mutex = Mutex()

    private val docs = LinkedHashMap<String, OpenPdf>(4, 0.75f, true)
    private val opening = HashSet<String>()
    private val lock = Any()

    private val baseCache = object : LruCache<PageKey, Bitmap>(baseCacheBytes) {
        override fun sizeOf(key: PageKey, value: Bitmap): Int = value.allocationByteCount
    }
    private val tileCache = object : LruCache<TileKey, Bitmap>(tileCacheBytes) {
        override fun sizeOf(key: TileKey, value: Bitmap): Int = value.allocationByteCount
    }

    private val basePending = HashMap<PageKey, Job>()
    private val tilePending = HashMap<TileKey, Job>()
    @Volatile
    private var wantedTiles: Set<TileKey> = emptySet()

    /**
     * True while a pinch or pan is in progress (and for a short moment after). The tile tier is suppressed then:
     * only the cached full-page bitmap is drawn and no new high-DPI render jobs are started, which keeps zooming
     * smooth. The host clears it ~120 ms after the gesture settles and redraws.
     */
    @Volatile
    var gestureActive: Boolean = false

    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val dest = RectF()
    private val src = Rect()

    fun pdfFile(notebookId: String): File = File(File(context.filesDir, "pdfs"), "$notebookId.pdf")

    // =====================================================================================
    // Drawing (render thread)
    // =====================================================================================

    /**
     * Draws the PDF page behind [page]'s ink. Does nothing for pages that are not PDF-backed. Call right after the
     * paper pass; pass the current [pxPerMm] and [zoom] (so the base scale is pxPerMm / zoom).
     */
    fun draw(canvas: Canvas, page: NotebookPage, viewportMm: StrokeBounds, pxPerMm: Float, zoom: Float) {
        val pdfIndex = page.pdfPageIndex ?: return
        val nb = page.notebookId
        val doc = synchronized(lock) { docs[nb] }
        if (doc == null) {
            requestOpen(nb)
            return
        }
        if (pdfIndex !in 0 until doc.pageCount) return

        val baseKey = PageKey(nb, pdfIndex)
        val base = baseCache.get(baseKey)
        if (base != null) {
            dest.set(0f, 0f, page.widthMm, page.heightMm)
            canvas.drawBitmap(base, null, dest, bitmapPaint)
        } else {
            requestBase(baseKey, doc, page.widthMm, page.heightMm)
        }

        if (zoom <= TILE_ZOOM_THRESHOLD || pxPerMm <= 0f) {
            cancelTilesNotIn(emptySet())
            return
        }
        if (gestureActive) return // Tier 1 only while the user is pinching or panning.
        drawTiles(canvas, page, doc, pdfIndex, viewportMm, pxPerMm, zoom)
    }

    private fun drawTiles(
        canvas: Canvas,
        page: NotebookPage,
        doc: OpenPdf,
        pdfIndex: Int,
        viewportMm: StrokeBounds,
        pxPerMm: Float,
        zoom: Float
    ) {
        val level = levelFor(zoom)
        val basePx = pxPerMm / zoom
        val scale = basePx * LEVEL_STEP.pow(level) // px per mm of the tile raster
        val tileMm = TILE_PX / scale

        val left = max(0f, viewportMm.left)
        val top = max(0f, viewportMm.top)
        val right = min(page.widthMm, viewportMm.right)
        val bottom = min(page.heightMm, viewportMm.bottom)
        if (left >= right || top >= bottom) {
            cancelTilesNotIn(emptySet())
            return
        }
        val c0 = floor(left / tileMm).toInt()
        val c1 = ceil(right / tileMm).toInt() - 1
        val r0 = floor(top / tileMm).toInt()
        val r1 = ceil(bottom / tileMm).toInt() - 1
        if ((c1 - c0 + 1) * (r1 - r0 + 1) > MAX_WANTED_TILES) {
            cancelTilesNotIn(emptySet())
            return
        }

        val cx = (left + right) * 0.5f
        val cy = (top + bottom) * 0.5f
        val wanted = ArrayList<TileKey>((c1 - c0 + 1) * (r1 - r0 + 1))
        for (r in r0..r1) for (c in c0..c1) wanted.add(TileKey(page.notebookId, pdfIndex, level, c, r))
        wanted.sortBy { k ->
            val dx = (k.col + 0.5f) * tileMm - cx
            val dy = (k.row + 0.5f) * tileMm - cy
            dx * dx + dy * dy
        }
        cancelTilesNotIn(wanted.toHashSet())

        for (k in wanted) {
            val bmp = tileCache.get(k)
            val tl = k.col * tileMm
            val tt = k.row * tileMm
            if (bmp == null) {
                requestTile(k, doc, page.widthMm, page.heightMm, scale)
                continue
            }
            // Edge tiles stick out past the page; draw only the part inside it.
            val visRight = min(tl + tileMm, page.widthMm)
            val visBottom = min(tt + tileMm, page.heightMm)
            src.set(0, 0, ((visRight - tl) * scale).toInt().coerceIn(1, TILE_PX), ((visBottom - tt) * scale).toInt().coerceIn(1, TILE_PX))
            dest.set(tl, tt, visRight, visBottom)
            canvas.drawBitmap(bmp, src, dest, bitmapPaint)
        }
    }

    // =====================================================================================
    // Requests
    // =====================================================================================

    /** Warms the base bitmap of a neighbouring page so a page turn never flashes blank. */
    fun prefetch(notebookId: String, pdfPageIndex: Int, pageWidthMm: Float, pageHeightMm: Float) {
        val doc = synchronized(lock) { docs[notebookId] } ?: run { requestOpen(notebookId); return }
        if (pdfPageIndex !in 0 until doc.pageCount) return
        val key = PageKey(notebookId, pdfPageIndex)
        if (baseCache.get(key) == null) requestBase(key, doc, pageWidthMm, pageHeightMm)
    }

    private fun requestOpen(notebookId: String) {
        synchronized(lock) {
            if (notebookId in opening || docs.containsKey(notebookId)) return
            opening.add(notebookId)
        }
        scope.launch(renderThread) {
            try {
                mutex.withLock { openLocked(notebookId) }
            } finally {
                synchronized(lock) { opening.remove(notebookId) }
            }
            onTileReady?.invoke()
        }
    }

    private fun openLocked(notebookId: String) {
        val file = pdfFile(notebookId)
        if (!file.isFile) return
        val fd = try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        } catch (e: Exception) {
            return
        }
        try {
            val renderer = PdfRenderer(fd)
            val sizes = IntArray(renderer.pageCount * 2)
            for (i in 0 until renderer.pageCount) {
                val p = renderer.openPage(i)
                try {
                    sizes[i * 2] = p.width
                    sizes[i * 2 + 1] = p.height
                } finally {
                    p.close()
                }
            }
            val evicted = synchronized(lock) {
                docs[notebookId] = OpenPdf(fd, renderer, sizes)
                if (docs.size > MAX_OPEN_DOCS) docs.entries.first().let { docs.remove(it.key); it.key to it.value } else null
            }
            evicted?.let { closeDoc(it.first, it.second) }
        } catch (e: Exception) {
            fd.close()
        }
    }

    private fun requestBase(key: PageKey, doc: OpenPdf, widthMm: Float, heightMm: Float) {
        synchronized(lock) {
            if (basePending.containsKey(key)) return
            basePending[key] = scope.launch(renderThread) {
                try {
                    if (!isActive) return@launch
                    val bmp = mutex.withLock { renderBase(doc, key.pageIndex, widthMm, heightMm) } ?: return@launch
                    baseCache.put(key, bmp)
                    onTileReady?.invoke()
                } finally {
                    synchronized(lock) { basePending.remove(key) }
                }
            }
        }
    }

    private fun requestTile(key: TileKey, doc: OpenPdf, widthMm: Float, heightMm: Float, scale: Float) {
        synchronized(lock) {
            if (tilePending.containsKey(key)) return
            tilePending[key] = scope.launch(renderThread) {
                try {
                    if (!isActive || key !in wantedTiles) return@launch
                    val bmp = mutex.withLock {
                        if (!isActive || key !in wantedTiles) null else renderTile(doc, key, widthMm, heightMm, scale)
                    } ?: return@launch
                    tileCache.put(key, bmp)
                    onTileReady?.invoke()
                } finally {
                    synchronized(lock) { tilePending.remove(key) }
                }
            }
        }
    }

    private fun cancelTilesNotIn(wanted: Set<TileKey>) {
        synchronized(lock) {
            wantedTiles = wanted
            if (tilePending.isEmpty()) return
            val it = tilePending.entries.iterator()
            while (it.hasNext()) {
                val e = it.next()
                if (e.key !in wanted) {
                    e.value.cancel()
                    it.remove()
                }
            }
        }
    }

    // =====================================================================================
    // Native rendering (PDF thread, mutex held)
    // =====================================================================================

    private fun renderBase(doc: OpenPdf, index: Int, widthMm: Float, heightMm: Float): Bitmap? {
        if (!isOpen(doc)) return null
        val w = BASE_WIDTH_PX
        val h = max(1, (BASE_WIDTH_PX * heightMm / widthMm).toInt())
        return try {
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            val page = doc.renderer.openPage(index)
            try {
                val m = Matrix()
                m.setScale(w.toFloat() / doc.widthPts(index), h.toFloat() / doc.heightPts(index))
                page.render(bmp, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            } finally {
                page.close()
            }
            bmp
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            baseCache.evictAll()
            null
        }
    }

    private fun renderTile(doc: OpenPdf, key: TileKey, widthMm: Float, heightMm: Float, scale: Float): Bitmap? {
        if (!isOpen(doc)) return null
        return try {
            val bmp = Bitmap.createBitmap(TILE_PX, TILE_PX, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            val page = doc.renderer.openPage(key.pageIndex)
            try {
                // Page points -> raster px at [scale] px/mm, then shift so this tile's corner is the bitmap origin.
                val sx = widthMm * scale / doc.widthPts(key.pageIndex)
                val sy = heightMm * scale / doc.heightPts(key.pageIndex)
                val m = Matrix()
                m.setScale(sx, sy)
                m.postTranslate(-(key.col * TILE_PX).toFloat(), -(key.row * TILE_PX).toFloat())
                page.render(bmp, Rect(0, 0, TILE_PX, TILE_PX), m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            } finally {
                page.close()
            }
            bmp
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            tileCache.evictAll()
            null
        }
    }

    private fun isOpen(doc: OpenPdf): Boolean = synchronized(lock) { docs.containsValue(doc) }

    // =====================================================================================
    // Lifecycle
    // =====================================================================================

    /** Drops every cached bitmap (call from onTrimMemory). In-flight work simply repopulates later. */
    fun trimMemory() {
        baseCache.evictAll()
        tileCache.evictAll()
    }

    /** Closes one notebook's PDF and forgets its cached pixels. */
    fun closeNotebook(notebookId: String) {
        val doc = synchronized(lock) { docs.remove(notebookId) } ?: return
        baseCache.snapshot().keys.filter { it.notebookId == notebookId }.forEach { baseCache.remove(it) }
        tileCache.snapshot().keys.filter { it.notebookId == notebookId }.forEach { tileCache.remove(it) }
        closeDoc(notebookId, doc)
    }

    fun close() {
        cancelTilesNotIn(emptySet())
        val all = synchronized(lock) {
            val copy = docs.entries.map { it.key to it.value }
            docs.clear()
            copy
        }
        baseCache.evictAll()
        tileCache.evictAll()
        for ((id, doc) in all) closeDoc(id, doc)
        renderThread.close()
    }

    private fun closeDoc(@Suppress("UNUSED_PARAMETER") notebookId: String, doc: OpenPdf) {
        scope.launch(Dispatchers.IO) {
            // Waiting for the mutex guarantees no render is mid-flight on this renderer.
            mutex.withLock {
                try {
                    doc.renderer.close()
                } catch (_: Exception) {
                }
                try {
                    doc.pfd.close()
                } catch (_: Exception) {
                }
            }
        }
    }

    private companion object {
        const val BASE_WIDTH_PX = 1080
        const val TILE_PX = 512
        const val TILE_ZOOM_THRESHOLD = 1.25f
        const val LEVEL_STEP = 1.5f
        const val MAX_WANTED_TILES = 80
        const val MAX_OPEN_DOCS = 2

        fun levelFor(zoom: Float): Int = max(1, ceil(ln(zoom) / ln(LEVEL_STEP)).toInt())

        fun defaultBudget(fractionOfHeap: Float, capMb: Int): Int =
            min((Runtime.getRuntime().maxMemory() * fractionOfHeap).toLong(), capMb * 1024L * 1024L).toInt()
    }
}

// =========================================================================================
// Import
// =========================================================================================

sealed interface PdfImportResult {
    data class Success(val file: File, val pages: List<PageMetaEntity>) : PdfImportResult
    data class Failed(val message: String) : PdfImportResult
}

/**
 * Copies the PDF at [uri] into `filesDir/pdfs/{notebookId}.pdf`, reads every page's size, and inserts one
 * [PageMetaEntity] per PDF page (blank template, `pdfPageIndex` = its index in the file) after the notebook's existing
 * pages. Page sizes keep the PDF's own aspect ratio (1 pt = 25.4/72 mm). Updates the notebook's page count and source
 * URI. A password-protected or unreadable PDF fails cleanly and leaves nothing behind.
 */
suspend fun importPdfIntoNotebook(
    context: Context,
    uri: Uri,
    notebookId: String,
    pageDao: PageDao,
    notebookDao: NotebookDao,
    idFactory: () -> String = { UUID.randomUUID().toString() },
    now: Long = System.currentTimeMillis()
): PdfImportResult = withContext(Dispatchers.IO) {
    val dir = File(context.filesDir, "pdfs").apply { mkdirs() }
    val target = File(dir, "$notebookId.pdf")
    val temp = File(dir, "$notebookId.pdf.part")
    try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            temp.outputStream().use { out -> input.copyTo(out) }
        } ?: return@withContext PdfImportResult.Failed("Could not open the selected file")

        val sizes = ArrayList<Pair<Float, Float>>()
        ParcelFileDescriptor.open(temp, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                for (i in 0 until renderer.pageCount) {
                    renderer.openPage(i).use { p ->
                        sizes.add(p.width * MM_PER_PT to p.height * MM_PER_PT)
                    }
                }
            }
        }
        if (sizes.isEmpty()) {
            temp.delete()
            return@withContext PdfImportResult.Failed("The PDF has no pages")
        }
        if (target.exists()) target.delete()
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }

        val template = TemplateConfig(type = TemplateType.BLANK).toJson()
        val first = pageDao.getMaxPageNumber(notebookId) + 1
        val pages = sizes.mapIndexed { i, (w, h) ->
            PageMetaEntity(
                pageId = idFactory(),
                notebookId = notebookId,
                pageNumber = first + i,
                widthMm = w,
                heightMm = h,
                templateConfigJson = template,
                pdfPageIndex = i,
                isStarred = false,
                audioFilePath = null,
                updatedAt = now
            )
        }
        pageDao.upsertAll(pages)
        notebookDao.updatePageStats(notebookId, first + pages.size, now)
        notebookDao.setPdfSource(notebookId, uri.toString(), now)
        PdfImportResult.Success(target, pages)
    } catch (e: SecurityException) {
        temp.delete()
        PdfImportResult.Failed("This PDF is password protected or cannot be read")
    } catch (e: Exception) {
        temp.delete()
        PdfImportResult.Failed(e.message ?: "PDF import failed")
    }
}

private const val MM_PER_PT = 25.4f / 72f
