package com.omninote.pro.ui.library

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** A bundled illustrated cover. [uri] is what gets stored in `NotebookEntity.coverImageUri`. */
data class IllustratedCover(val file: String, val label: String, val accentArgb: Int) {
    val uri: String get() = "asset:covers/$file"
}

internal val IllustratedCovers = listOf(
    IllustratedCover("cover_starry_night.jpg", "Starry Night", 0xFFF2C14E.toInt()),
    IllustratedCover("cover_apple_worm.jpg", "Cozy Apple", 0xFFE5533D.toInt()),
    IllustratedCover("cover_campus_dragon.jpg", "Campus Dragon", 0xFF4FB286.toInt()),
    IllustratedCover("cover_spider_lily.jpg", "Spider Lily", 0xFFE0453A.toInt()),
    IllustratedCover("cover_crimson_dahlia.jpg", "Crimson Dahlia", 0xFFC2334D.toInt()),
    IllustratedCover("cover_lily_valley.jpg", "Lily of the Valley", 0xFF8FC9A3.toInt()),
    IllustratedCover("cover_vintage_ivy.jpg", "Botanical Ivy", 0xFF6FA36B.toInt())
)

private val bitmapCache = object : android.util.LruCache<String, Bitmap>(24 * 1024 * 1024) {
    override fun sizeOf(key: String, value: Bitmap) = value.byteCount
}

/** Decodes a stored cover reference (asset or file path) down to roughly [maxEdgePx]. Null if it cannot be read. */
internal suspend fun loadCoverBitmap(context: Context, ref: String, maxEdgePx: Int): ImageBitmap? =
    withContext(Dispatchers.IO) {
        val key = "$ref@$maxEdgePx"
        bitmapCache.get(key)?.let { return@withContext it.asImageBitmap() }
        runCatching {
            fun open() = if (ref.startsWith("asset:")) context.assets.open(ref.removePrefix("asset:")) else File(ref).inputStream()
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            open().use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (bounds.outHeight / (sample * 2) >= maxEdgePx && bounds.outWidth / (sample * 2) >= maxEdgePx) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            open().use { BitmapFactory.decodeStream(it, null, opts) }
        }.getOrNull()?.also { bitmapCache.put(key, it) }?.asImageBitmap()
    }

@Composable
internal fun rememberCoverBitmap(ref: String?, maxEdgePx: Int = 640): ImageBitmap? {
    val context = LocalContext.current.applicationContext
    val state by produceState<ImageBitmap?>(null, ref, maxEdgePx) {
        value = if (ref == null) null else loadCoverBitmap(context, ref, maxEdgePx)
    }
    return state
}

/** Copies a gallery image into app storage (re-encoded, bounded) so the cover survives losing the picker's URI grant. */
internal suspend fun importCustomCover(context: Context, source: Uri): String? = withContext(Dispatchers.IO) {
    runCatching {
        val dir = File(context.filesDir, "covers").apply { mkdirs() }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(source)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outHeight / (sample * 2) >= 1200 && bounds.outWidth / (sample * 2) >= 900) sample *= 2
        val bmp = context.contentResolver.openInputStream(source)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return@runCatching null
        val out = File(dir, "custom_${UUID.randomUUID()}.jpg")
        out.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 86, it) }
        bmp.recycle()
        out.absolutePath
    }.getOrNull()
}
