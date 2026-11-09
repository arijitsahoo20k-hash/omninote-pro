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
 * High-fps, memory-bounded PDF page renderer for the dry layer, in two layers and no tile grid.
 *
 * Threading: [PdfRenderer] allows one open page per file descriptor, so every `openPage`/`render` runs on ONE
 * dedicated thread under a [Mutex] that also guards open and close. [draw] only reads caches and enqueues work; it
 * never blocks on PDF rendering.
 *
 * Layer 1: the whole page rendered once at [BASE_LONG_EDGE_PX] on its longest edge (ARGB_8888, pre-filled white) and
 * kept in an [LruCache]. It is drawn first, so the full page is always visible with nothing missing.
 *
 * Layer 2: once zoom exceeds [REGION_ZOOM_THRESHOLD] and the viewport has been still ([gestureActive] is false, which
 * the host clears ~120 ms after the last gesture frame), ONE bitmap covering the visible rectangle (plus a small
 * margin) is rendered at the on-screen resolution and swapped in atomically when finished. The previous region stays
 * on screen until the new one is ready, and nothing is ever recycled by hand: evicted bitmaps are left to the GC, so
 * a bitmap a frame still references can never be "used after recycle".
 */
class PdfTileManager(
    private val context: Context,
    private val scope: CoroutineScope,
    baseCacheBytes: Int = defaultBudget(0.10f, 72),
    tileCacheBytes: Int = defaultBudget(0.14f, 96)
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
    /** The high-resolution layer for one page: [bitmap] covers page-space rectangle [left,top]..[right,bottom] mm. */
    private class Region(
        val bitmap: Bitmap,
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        /** Raster density in px per mm. */
        val scale: Float
    )

    private data class RegionSpec(val left: Float, val top: Float, val right: Float, val bottom: Float, val scale: Float)

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
    private val regionCache = object : LruCache<PageKey, Region>(tileCacheBytes) {
        override fun sizeOf(key: PageKey, value: Region): Int = value.bitmap.allocationByteCount
    }
    /** Largest region raster, in pixels, that still leaves room for a second one in [regionCache]. */
    private val maxRegionPixels: Int = min(MAX_REGION_PIXELS, max(1_000_000, tileCacheBytes / 8))

    private val basePending = HashMap<PageKey, Job>()
    private val regionPending = HashMap<PageKey, Job>()
    private val regionWanted = HashMap<PageKey, RegionSpec>()

    /**
     * True while a pinch or pan is in progress (and for a short moment after). No new high-resolution region job is
     * started then, which keeps zooming smooth. The host clears it ~120 ms after the gesture settles and redraws.
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

        if (pxPerMm <= 0f || zoom <= 1f) {
            cancelRegion(baseKey)
            return
        }
        drawRegion(canvas, page, doc, baseKey, viewportMm, pxPerMm, zoom)
    }

    private fun drawRegion(
        canvas: Canvas,
        page: NotebookPage,
        doc: OpenPdf,
        key: PageKey,
        viewportMm: StrokeBounds,
        pxPerMm: Float,
        zoom: Float
    ) {
        val left = max(0f, viewportMm.left)
        val top = max(0f, viewportMm.top)
        val right = min(page.widthMm, viewportMm.right)
        val bottom = min(page.heightMm, viewportMm.bottom)
        if (left >= right || top >= bottom) return

        val current = regionCache.get(key)
        // Keep showing the existing region (it is correct, just maybe smaller or softer) until a better one lands.
        if (current != null) {
            dest.set(current.left, current.top, current.right, current.bottom)
            canvas.drawBitmap(current.bitmap, null, dest, bitmapPaint)
        }
        if (zoom <= REGION_ZOOM_THRESHOLD || gestureActive) return

        val covers = current != null &&
            current.left <= left + COVER_SLACK_MM && current.top <= top + COVER_SLACK_MM &&
            current.right >= right - COVER_SLACK_MM && current.bottom >= bottom - COVER_SLACK_MM &&
            current.scale >= pxPerMm * 0.8f
        if (covers) {
            cancelRegion(key)
            return
        }

        // Wanted region: the visible rectangle plus a margin so small pans stay covered, at on-screen resolution,
        // scaled down only if the bitmap would exceed the pixel budget.
        val mx = (right - left) * REGION_MARGIN
        val my = (bottom - top) * REGION_MARGIN
        val l = max(0f, left - mx)
        val t = max(0f, top - my)
        val r = min(page.widthMm, right + mx)
        val b = min(page.heightMm, bottom + my)
        var scale = pxPerMm
        val pixels = (r - l) * scale * (b - t) * scale
        if (pixels > maxRegionPixels) scale *= kotlin.math.sqrt(maxRegionPixels / pixels)
        requestRegion(key, doc, page.widthMm, page.heightMm, RegionSpec(l, t, r, b, scale))
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

    private fun requestRegion(key: PageKey, doc: OpenPdf, widthMm: Float, heightMm: Float, spec: RegionSpec) {
        synchronized(lock) {
            val previous = regionWanted[key]
            if (previous == spec && regionPending.containsKey(key)) return
            regionWanted[key] = spec
            // A stale job for a different rectangle is dropped before it starts rendering.
            regionPending.remove(key)?.cancel()
            regionPending[key] = scope.launch(renderThread) {
                try {
                    if (!isActive) return@launch
                    val bmp = mutex.withLock {
                        if (!isActive || synchronized(lock) { regionWanted[key] } != spec) null
                        else renderRegion(doc, key.pageIndex, widthMm, heightMm, spec)
                    } ?: return@launch
                    // Atomic swap: the new region replaces the old one in a single put.
                    regionCache.put(key, Region(bmp, spec.left, spec.top, spec.right, spec.bottom, spec.scale))
                    onTileReady?.invoke()
                } finally {
                    synchronized(lock) {
                        if (regionWanted[key] == spec) regionPending.remove(key)
                    }
                }
            }
        }
    }

    private fun cancelRegion(key: PageKey) {
        synchronized(lock) {
            regionWanted.remove(key)
            regionPending.remove(key)?.cancel()
        }
    }

    // =====================================================================================
    // Native rendering (PDF thread, mutex held)
    // =====================================================================================

    private fun renderBase(doc: OpenPdf, index: Int, widthMm: Float, heightMm: Float): Bitmap? {
        if (!isOpen(doc)) return null
        val longest = max(widthMm, heightMm)
        val k = BASE_LONG_EDGE_PX / longest
        val w = max(1, (widthMm * k).toInt())
        val h = max(1, (heightMm * k).toInt())
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

    private fun renderRegion(doc: OpenPdf, index: Int, widthMm: Float, heightMm: Float, spec: RegionSpec): Bitmap? {
        if (!isOpen(doc)) return null
        val w = max(1, ceil((spec.right - spec.left) * spec.scale).toInt())
        val h = max(1, ceil((spec.bottom - spec.top) * spec.scale).toInt())
        return try {
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            val page = doc.renderer.openPage(index)
            try {
                // Page points -> raster px at spec.scale px/mm, then shift so the region's corner is the bitmap origin.
                val sx = widthMm * spec.scale / doc.widthPts(index)
                val sy = heightMm * spec.scale / doc.heightPts(index)
                val m = Matrix()
                m.setScale(sx, sy)
                m.postTranslate(-spec.left * spec.scale, -spec.top * spec.scale)
                page.render(bmp, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            } finally {
                page.close()
            }
            bmp
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            regionCache.evictAll()
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
        regionCache.evictAll()
    }

    /** Closes one notebook's PDF and forgets its cached pixels. */
    fun closeNotebook(notebookId: String) {
        val doc = synchronized(lock) { docs.remove(notebookId) } ?: return
        baseCache.snapshot().keys.filter { it.notebookId == notebookId }.forEach { baseCache.remove(it) }
        regionCache.snapshot().keys.filter { it.notebookId == notebookId }.forEach { regionCache.remove(it) }
        synchronized(lock) {
            regionWanted.keys.removeAll { it.notebookId == notebookId }
            regionPending.entries.removeAll { (k, j) -> (k.notebookId == notebookId).also { hit -> if (hit) j.cancel() } }
        }
        closeDoc(notebookId, doc)
    }

    fun close() {
        synchronized(lock) {
            regionWanted.clear()
            regionPending.values.forEach { it.cancel() }
            regionPending.clear()
        }
        val all = synchronized(lock) {
            val copy = docs.entries.map { it.key to it.value }
            docs.clear()
            copy
        }
        baseCache.evictAll()
        regionCache.evictAll()
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
        const val BASE_LONG_EDGE_PX = 1600
        const val REGION_ZOOM_THRESHOLD = 1.5f
        const val REGION_MARGIN = 0.12f
        const val COVER_SLACK_MM = 0.6f
        const val MAX_REGION_PIXELS = 5_000_000
        const val MAX_OPEN_DOCS = 2

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
