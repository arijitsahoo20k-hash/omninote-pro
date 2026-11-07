package com.omninote.pro.engine.io

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.omninote.pro.engine.ink.DryLayerRenderer
import com.omninote.pro.engine.ink.ImageBitmapProvider
import com.omninote.pro.engine.ink.ImagePainter
import com.omninote.pro.engine.ink.InkStrokeRenderer
import com.omninote.pro.engine.ink.ShapePainter
import com.omninote.pro.engine.ink.StencilGeometryProvider
import com.omninote.pro.engine.ink.StencilPainter
import com.omninote.pro.engine.ink.TextBoxPainter
import java.io.File
import kotlin.math.max

/**
 * Decodes images on the calling thread. The live view's provider is asynchronous (it returns null until a
 * background decode lands), which would silently drop images from an export, so exporters use this one instead.
 * Images keep up to [maxDimensionPx] on their long side.
 */
class SyncImageBitmapProvider(
    private val assetRoot: File,
    private val maxDimensionPx: Int = 4096,
    cacheBytes: Int = 64 * 1024 * 1024
) : ImageBitmapProvider {

    private val cache = object : LruCache<String, Bitmap>(cacheBytes) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }
    private val missing = HashSet<String>()

    override fun bitmapFor(assetRelativePath: String): Bitmap? {
        cache.get(assetRelativePath)?.let { return it }
        if (assetRelativePath in missing) return null
        val file = File(assetRoot, assetRelativePath)
        if (!file.isFile) {
            missing.add(assetRelativePath)
            return null
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > maxDimensionPx) sample *= 2
        val bmp = BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample }
        )
        if (bmp == null) {
            missing.add(assetRelativePath)
            return null
        }
        cache.put(assetRelativePath, bmp)
        return bmp
    }
}

/**
 * Builds [DryLayerRenderer]s for export and flashcard rendering: synchronous image decoding, a private path cache per
 * renderer, and no PDF tile manager (exporters draw textbook pages themselves at print resolution).
 */
class ExportRenderKit(
    private val context: Context,
    private val stencilGeometry: StencilGeometryProvider
) {
    val filesDir: File get() = context.filesDir
    val cacheDir: File get() = context.cacheDir

    /** A fresh renderer; use one per export job and from one thread only. */
    fun newRenderer(): DryLayerRenderer {
        val strokes = InkStrokeRenderer()
        val shaders = strokes.shaders
        return DryLayerRenderer(
            strokes = strokes,
            shapes = ShapePainter(shaders),
            images = ImagePainter(SyncImageBitmapProvider(context.filesDir)),
            stencils = StencilPainter(stencilGeometry, shaders),
            texts = TextBoxPainter()
        )
    }

    fun pdfFile(notebookId: String): File = File(File(context.filesDir, "pdfs"), "$notebookId.pdf")
}
