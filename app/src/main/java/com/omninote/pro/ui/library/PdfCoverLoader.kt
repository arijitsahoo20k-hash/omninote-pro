package com.omninote.pro.ui.library

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File

/**
 * First-page covers for imported PDFs. Uses its own read-only [PdfRenderer] so it never contends with the workspace's
 * tile manager. Two cache levels: a 12 MB in-memory LRU and JPEG files in `cacheDir/pdf_covers`, keyed by the notebook id
 * and the PDF file's last-modified time so a replaced PDF never shows a stale cover.
 */
object PdfCoverLoader {
    private const val MEMORY_BYTES = 12 * 1024 * 1024
    private const val JPEG_QUALITY = 85

    private val memory = object : LruCache<String, Bitmap>(MEMORY_BYTES) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    private val gate = Semaphore(2)

    /** Returns the first page of the notebook's PDF at about [widthPx] wide, or null if it cannot be rendered. */
    suspend fun load(context: Context, notebookId: String, widthPx: Int): Bitmap? = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val pdf = File(File(app.filesDir, "pdfs"), "$notebookId.pdf")
        if (!pdf.isFile) return@withContext null
        val stamp = pdf.lastModified()
        val memKey = "${notebookId}_${stamp}_$widthPx"
        memory.get(memKey)?.let { return@withContext it }

        gate.withPermit {
            memory.get(memKey)?.let { return@withPermit it }
            val dir = File(app.cacheDir, "pdf_covers").apply { mkdirs() }
            val file = File(dir, "${notebookId}_$stamp.jpg")
            val decoded = try {
                if (file.isFile) BitmapFactory.decodeFile(file.absolutePath) else null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            val bitmap = decoded ?: render(pdf, widthPx)?.also { saveJpeg(it, file, dir, notebookId) }
            bitmap?.also { memory.put(memKey, it) }
        }
    }

    private fun render(pdf: File, widthPx: Int): Bitmap? {
        var fd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        return try {
            fd = ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(fd)
            if (renderer.pageCount < 1) return null
            val page = renderer.openPage(0)
            try {
                val scale = widthPx.toFloat() / page.width.coerceAtLeast(1)
                val w = widthPx.coerceAtLeast(1)
                val h = (page.height * scale).toInt().coerceIn(1, 4096)
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(Color.WHITE)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bmp
            } finally {
                page.close()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } finally {
            try { renderer?.close() } catch (_: Exception) { }
            try { fd?.close() } catch (_: Exception) { }
        }
    }

    private fun saveJpeg(bmp: Bitmap, file: File, dir: File, notebookId: String) {
        try {
            // Drop covers of older versions of this PDF first.
            dir.listFiles { f -> f.name.startsWith("${notebookId}_") && f != file }?.forEach { it.delete() }
            file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
        } catch (e: Exception) {
            file.delete()
        }
    }
}

/** The PDF's first page as an image, or null while loading, when [enabled] is false, or when it cannot be rendered. */
@Composable
internal fun rememberPdfCover(notebookId: String, enabled: Boolean, widthPx: Int = 480): ImageBitmap? {
    val context = LocalContext.current.applicationContext
    val state by produceState<ImageBitmap?>(null, notebookId, enabled, widthPx) {
        value = if (!enabled) null else PdfCoverLoader.load(context, notebookId, widthPx)?.asImageBitmap()
    }
    return state
}
