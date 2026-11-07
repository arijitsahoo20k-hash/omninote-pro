package com.omninote.pro.ui.workspace

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.omninote.pro.engine.ink.ImageBitmapProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

/**
 * Decodes page images off the render thread. [bitmapFor] returns null until the decode finishes, then [onLoaded] is
 * called (on the main thread) so the host can ask the ink view to redraw.
 */
class AsyncImageBitmapProvider(
    private val assetRoot: File,
    private val scope: CoroutineScope,
    private val maxDimensionPx: Int = 4096,
    cacheBytes: Int = 64 * 1024 * 1024,
    private val onLoaded: () -> Unit
) : ImageBitmapProvider {

    private val cache = object : LruCache<String, Bitmap>(cacheBytes) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val missing = ConcurrentHashMap.newKeySet<String>()

    override fun bitmapFor(assetRelativePath: String): Bitmap? {
        cache.get(assetRelativePath)?.let { return it }
        if (assetRelativePath in missing || !inFlight.add(assetRelativePath)) return null
        scope.launch(Dispatchers.IO) {
            try {
                val bmp = decode(File(assetRoot, assetRelativePath))
                if (bmp == null) missing.add(assetRelativePath) else cache.put(assetRelativePath, bmp)
            } finally {
                inFlight.remove(assetRelativePath)
            }
            scope.launch(Dispatchers.Main) { onLoaded() }
        }
        return null
    }

    fun clear() {
        cache.evictAll()
        missing.clear()
    }

    private fun decode(file: File): Bitmap? {
        if (!file.isFile) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > maxDimensionPx) sample *= 2
        return BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}
