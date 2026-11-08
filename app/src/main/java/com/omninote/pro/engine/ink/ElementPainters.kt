package com.omninote.pro.engine.ink

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.AbsoluteSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.util.LruCache
import com.omninote.pro.core.model.AudioClipElement
import com.omninote.pro.core.model.ImageElement
import com.omninote.pro.core.model.StencilInstance
import com.omninote.pro.core.model.TextBoxElement
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Supplies the outline of a stencil. The path lives in a normalised box centred on the origin
 * (x and y both span -0.5..0.5) and may contain several contours; it is stroked, never filled.
 * Implemented by the Scientific Stencil Repository (Phase 4).
 */
fun interface StencilGeometryProvider {
    fun outline(stencilKey: String): Path?
}

/** Supplies decoded bitmaps without blocking the render thread. Returns null while an image is still loading. */
interface ImageBitmapProvider {
    fun bitmapFor(assetRelativePath: String): Bitmap?
}

/** Provider that never has an image; used by exports and tests that skip raster content. */
object NoImages : ImageBitmapProvider {
    override fun bitmapFor(assetRelativePath: String): Bitmap? = null
}

/**
 * Decodes images from [assetRoot] on a single background thread, down-sampled to [maxDimensionPx] on the long side,
 * and keeps them in a byte-bounded LRU. [onLoaded] fires on the main thread after each decode so the canvas can redraw.
 */
class FileImageBitmapProvider(
    private val assetRoot: File,
    private val maxDimensionPx: Int = 2560,
    cacheBytes: Int = 96 * 1024 * 1024,
    private val onLoaded: () -> Unit
) : ImageBitmapProvider {

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "omni-image-decode").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())
    private val pending = ConcurrentHashMap.newKeySet<String>()
    private val failed = ConcurrentHashMap.newKeySet<String>()
    private val cache = object : LruCache<String, Bitmap>(cacheBytes) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }

    override fun bitmapFor(assetRelativePath: String): Bitmap? {
        val hit = cache.get(assetRelativePath)
        if (hit != null) return hit
        if (failed.contains(assetRelativePath)) return null
        if (pending.add(assetRelativePath)) executor.execute { decode(assetRelativePath) }
        return null
    }

    fun evictAll() {
        cache.evictAll()
    }

    fun shutdown() {
        executor.shutdownNow()
    }

    private fun decode(path: String) {
        try {
            val file = File(assetRoot, path)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                failed.add(path)
                return
            }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxDimensionPx) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = BitmapFactory.decodeFile(file.absolutePath, opts)
            if (bmp == null) {
                failed.add(path)
                return
            }
            cache.put(path, bmp)
            main.post(onLoaded)
        } catch (e: Exception) {
            failed.add(path)
        } catch (e: OutOfMemoryError) {
            cache.evictAll()
            failed.add(path)
        } finally {
            pending.remove(path)
        }
    }
}

/** Draws placed raster images: rotation about the frame centre, mirroring, opacity and polygon crop. */
class ImagePainter(private val provider: ImageBitmapProvider) {

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val placeholder = Paint(Paint.ANTI_ALIAS_FLAG)
    private val crop = Path()
    private val dst = RectF()

    fun draw(canvas: Canvas, image: ImageElement, ctx: InkRenderContext) {
        val b = image.bounds
        canvas.save()
        if (image.cropPolygonMm != null && image.cropPolygonMm.size >= 3) {
            crop.rewind()
            crop.moveTo(image.cropPolygonMm[0].x, image.cropPolygonMm[0].y)
            for (i in 1 until image.cropPolygonMm.size) crop.lineTo(image.cropPolygonMm[i].x, image.cropPolygonMm[i].y)
            crop.close()
            canvas.clipPath(crop)
        }
        canvas.rotate(image.rotationDeg, b.centerX, b.centerY)
        if (image.isMirrored) canvas.scale(-1f, 1f, b.centerX, b.centerY)
        dst.set(b.left, b.top, b.right, b.bottom)

        val bmp = provider.bitmapFor(image.assetRelativePath)
        if (bmp != null) {
            paint.alpha = (image.opacity * 255f).toInt()
            canvas.drawBitmap(bmp, null, dst, paint)
        } else {
            placeholder.style = Paint.Style.FILL
            placeholder.color = ctx.displayColor(0xFF9CA3AF.toInt())
            placeholder.alpha = 40
            canvas.drawRect(dst, placeholder)
            placeholder.style = Paint.Style.STROKE
            placeholder.strokeWidth = 0.3f
            placeholder.alpha = 120
            canvas.drawRect(dst, placeholder)
        }
        canvas.restore()
    }
}

/** Draws stencil stamps by mapping the provider's normalised outline into page space and stroking it. */
class StencilPainter(private val provider: StencilGeometryProvider, private val shaders: BrushShaderFactory) {

    private val matrix = Matrix()
    private val placed = Path()
    private val paint = Paint()

    fun draw(canvas: Canvas, stencil: StencilInstance, ctx: InkRenderContext) {
        val outline = provider.outline(stencil.stencilKey) ?: return
        matrix.reset()
        matrix.postScale(stencil.baseWidthMm * stencil.scaleX, stencil.baseHeightMm * stencil.scaleY)
        matrix.postRotate(stencil.rotationDeg)
        matrix.postTranslate(stencil.center.x, stencil.center.y)
        placed.rewind()
        outline.transform(matrix, placed)
        shaders.stencilStroke(paint, stencil.strokeWidthMm, stencil.semanticColorArgb, ctx)
        canvas.drawPath(placed, paint)
    }
}

/**
 * Draws text boxes with light Markdown: `#`/`##`/`###` headings, `**bold**`, `*italic*`, `` `code` `` and `-` bullets.
 * LaTeX blocks are listed beneath the body as monospaced source lines. Layouts are built at 10x scale so integer
 * StaticLayout metrics stay precise at millimetre sizes, and cached per element.
 */
class TextBoxPainter {

    private class Cached(val key: String, val layout: StaticLayout)

    private val cache = LruCache<String, Cached>(256)
    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG)

    fun draw(canvas: Canvas, box: TextBoxElement, ctx: InkRenderContext) {
        val color = ctx.displayColor(box.semanticColorArgb) or (0xFF shl 24)
        val layout = layoutFor(box, color)
        val b = box.bounds
        canvas.save()
        canvas.rotate(box.rotationDeg, b.centerX, b.centerY)
        canvas.clipRect(b.left, b.top, b.right, b.bottom)
        canvas.translate(b.left, b.top)
        canvas.scale(1f / SCALE, 1f / SCALE)
        layout.draw(canvas)
        canvas.restore()
    }

    private fun layoutFor(box: TextBoxElement, color: Int): StaticLayout {
        val widthPx = (box.bounds.width * SCALE).toInt().coerceAtLeast(1)
        val key = "${box.markdownText.hashCode()}|${box.latexBlocks.hashCode()}|${box.fontSizePt}|${box.fontFamily}|$widthPx|$color"
        val hit = cache.get(box.id)
        if (hit != null && hit.key == key) return hit.layout

        val sizePx = box.fontSizePt * MM_PER_PT * SCALE
        paint.reset()
        paint.isAntiAlias = true
        paint.color = color
        paint.textSize = sizePx
        paint.typeface = when (box.fontFamily) {
            "serif" -> Typeface.SERIF
            "mono" -> Typeface.MONOSPACE
            else -> Typeface.SANS_SERIF
        }
        val text = buildText(box, sizePx)
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, widthPx)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1.15f)
            .setIncludePad(false)
            .build()
        cache.put(box.id, Cached(key, layout))
        return layout
    }

    private fun buildText(box: TextBoxElement, sizePx: Float): CharSequence {
        val out = SpannableStringBuilder()
        val lines = box.markdownText.split('\n')
        for ((i, raw) in lines.withIndex()) {
            val start = out.length
            var line = raw
            var headingScale = 1f
            when {
                line.startsWith("### ") -> { headingScale = 1.15f; line = line.substring(4) }
                line.startsWith("## ") -> { headingScale = 1.35f; line = line.substring(3) }
                line.startsWith("# ") -> { headingScale = 1.6f; line = line.substring(2) }
                line.startsWith("- ") || line.startsWith("* ") -> line = "• " + line.substring(2)
            }
            appendInline(out, line)
            if (headingScale != 1f) {
                out.setSpan(AbsoluteSizeSpan((sizePx * headingScale).toInt(), false), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            if (i < lines.lastIndex) out.append('\n')
        }
        for (block in box.latexBlocks) {
            if (out.isNotEmpty()) out.append('\n')
            val start = out.length
            out.append(block)
            out.setSpan(TypefaceSpan("monospace"), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return out
    }

    /** Single-pass inline parser for `**bold**`, `*italic*` and `` `code` ``; unmatched markers stay literal. */
    private fun appendInline(out: SpannableStringBuilder, line: String) {
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (c == '*' && line.startsWith("**", i)) {
                val end = line.indexOf("**", i + 2)
                if (end > i + 2) {
                    span(out, line.substring(i + 2, end), StyleSpan(Typeface.BOLD))
                    i = end + 2
                    continue
                }
            }
            if (c == '*') {
                val end = line.indexOf('*', i + 1)
                if (end > i + 1) {
                    span(out, line.substring(i + 1, end), StyleSpan(Typeface.ITALIC))
                    i = end + 1
                    continue
                }
            }
            if (c == '`') {
                val end = line.indexOf('`', i + 1)
                if (end > i + 1) {
                    span(out, line.substring(i + 1, end), TypefaceSpan("monospace"))
                    i = end + 1
                    continue
                }
            }
            out.append(c)
            i++
        }
    }

    private fun span(out: SpannableStringBuilder, text: String, what: Any) {
        val start = out.length
        out.append(text)
        out.setSpan(what, start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private companion object {
        const val SCALE = 10f
        const val MM_PER_PT = 25.4f / 72f
    }
}


/**
 * Draws a voice-note pin as a rounded pill badge: a round play / pause button, the title and the duration.
 * While the clip plays ([InkRenderContext.playingAudioClipId]) the pill gets a soft blue glow and a pause glyph.
 * Everything is laid out at 10x scale (like [TextBoxPainter]) so text metrics stay precise at millimetre sizes.
 */
class AudioPinPainter {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val glyph = Path()

    fun draw(canvas: Canvas, clip: AudioClipElement, ctx: InkRenderContext) {
        val playing = !ctx.isExport && ctx.playingAudioClipId == clip.id
        val b = clip.spatialBounds
        canvas.save()
        canvas.translate(b.left, b.top)
        canvas.scale(1f / S, 1f / S)
        val w = AudioClipElement.PIN_WIDTH_MM * S
        val h = AudioClipElement.PIN_HEIGHT_MM * S
        val r = h / 2f

        if (playing) {
            for (i in 3 downTo 1) {
                fill.style = Paint.Style.FILL
                fill.color = ACCENT or ((0x16 + (4 - i) * 0x0C) shl 24)
                val pad = i * 0.7f * S
                rect.set(-pad, -pad, w + pad, h + pad)
                canvas.drawRoundRect(rect, r + pad, r + pad, fill)
            }
        }
        // body
        fill.style = Paint.Style.FILL
        fill.color = if (ctx.isExport) 0xFFEFF3FA.toInt() else 0xFF1B2A48.toInt()
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, r, r, fill)
        stroke.color = if (playing) ACCENT else 0x663B82F6
        stroke.strokeWidth = 0.35f * S
        rect.set(0.18f * S, 0.18f * S, w - 0.18f * S, h - 0.18f * S)
        canvas.drawRoundRect(rect, r, r, stroke)

        // round button
        val cx = r
        val cy = h / 2f
        fill.color = ACCENT
        canvas.drawCircle(cx, cy, r * 0.74f, fill)
        fill.color = 0xFFFFFFFF.toInt()
        glyph.reset()
        if (playing) {
            val bw = r * 0.16f
            val bh = r * 0.52f
            canvas.drawRect(cx - bw * 1.9f, cy - bh, cx - bw * 0.5f, cy + bh, fill)
            canvas.drawRect(cx + bw * 0.5f, cy - bh, cx + bw * 1.9f, cy + bh, fill)
        } else {
            glyph.moveTo(cx - r * 0.18f, cy - r * 0.38f)
            glyph.lineTo(cx + r * 0.42f, cy)
            glyph.lineTo(cx - r * 0.18f, cy + r * 0.38f)
            glyph.close()
            canvas.drawPath(glyph, fill)
        }

        // title and duration
        val textLeft = r * 2f + 0.5f * S
        val durationText = formatDuration(clip.durationMs)
        text.typeface = Typeface.DEFAULT_BOLD
        text.textSize = 2.9f * S
        val durW = text.measureText(durationText)
        text.color = if (ctx.isExport) 0xFF4A5568.toInt() else 0xFFB7C4DB.toInt()
        canvas.drawText(durationText, w - durW - 1.6f * S, cy + 1.0f * S, text)
        val room = (w - textLeft - durW - 2.6f * S).coerceAtLeast(1f)
        text.color = if (ctx.isExport) 0xFF1B2A48.toInt() else 0xFFFFFFFF.toInt()
        val shown = android.text.TextUtils.ellipsize(clip.title, text, room, android.text.TextUtils.TruncateAt.END).toString()
        canvas.drawText(shown, textLeft, cy + 1.0f * S, text)
        canvas.restore()
    }

    companion object {
        private const val S = 10f
        private const val ACCENT = 0xFF3B82F6.toInt()

        /** "0:42" / "12:05" / "1:02:09". */
        fun formatDuration(ms: Long): String {
            val total = (ms / 1000L).coerceAtLeast(0L)
            val h = total / 3600L
            val m = (total % 3600L) / 60L
            val s = total % 60L
            return if (h > 0) String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, s)
            else String.format(java.util.Locale.US, "%d:%02d", m, s)
        }
    }
}
