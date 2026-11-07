package com.omninote.pro.engine.io

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.omninote.pro.core.model.ImageElement
import com.omninote.pro.core.model.PointMm
import com.omninote.pro.core.model.StrokeBounds
import com.omninote.pro.data.AddElement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.math.abs
import kotlin.math.cbrt
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

enum class SnippetBgMode {
    /** Keep the PDF exactly as rendered. */
    ORIGINAL,

    /** White paper becomes transparent; ink keeps its colour and anti-aliasing. For light notebook pages. */
    TRANSPARENT_LIGHT_INK,

    /** White paper becomes transparent and neutral dark ink turns off-white (#F3F4F6); coloured lines stay coloured. */
    TRANSPARENT_DARK_ADAPTED
}

/** A finished crop: the PNG on disk, the image element ready to place, and the command that adds it. */
class SnippetResult(
    val file: File,
    val element: ImageElement,
    val pixelWidth: Int,
    val pixelHeight: Int,
    /** The DPI actually used (lower than requested only when the crop was large enough to hit the size cap). */
    val effectiveDpi: Float
) {
    /** Commit with `undoManager.execute(result.addCommand, page, index)`. */
    val addCommand: AddElement get() = AddElement(element)
}

/**
 * Split-screen question and diagram cropper: renders a region of a PDF page at 300 DPI, optionally strips the white
 * paper (keeping anti-aliased edges), optionally recolours dark ink for dark notebook pages, saves a PNG under
 * `filesDir/images/{notebookId}/{imageId}.png` and returns an [ImageElement] for the target notebook page.
 *
 * Crop coordinates are page millimetres in the source PDF page's own box (1 pt = 25.4/72 mm), unless
 * [pageWidthMm]/[pageHeightMm] say the notebook page was stretched differently.
 */
class PdfSnippetCropper(
    private val context: Context,
    private val idFactory: () -> String = { UUID.randomUUID().toString() }
) {

    /**
     * @param cropBoundsMm region to render (clipped to the page).
     * @param cropPolygonMm optional outline inside the bounds; pixels outside it become transparent.
     * @param layerId layer of the target page that will own the image.
     * @param placeCenterMm where the image's centre lands on the target page.
     * @param placedWidthMm placed width on the target page; defaults to the crop's physical width.
     * @return null if the PDF/page cannot be opened or the crop is empty.
     */
    suspend fun crop(
        pdfFile: File,
        pdfPageIndex: Int,
        cropBoundsMm: StrokeBounds,
        notebookId: String,
        layerId: String,
        placeCenterMm: PointMm,
        cropPolygonMm: List<PointMm>? = null,
        bgMode: SnippetBgMode = SnippetBgMode.ORIGINAL,
        placedWidthMm: Float? = null,
        pageWidthMm: Float? = null,
        pageHeightMm: Float? = null,
        dpi: Float = DEFAULT_DPI
    ): SnippetResult? = withContext(Dispatchers.Default) {
        if (!pdfFile.isFile) return@withContext null
        var bitmap: Bitmap? = null
        var effectiveDpi = dpi
        try {
            ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    if (pdfPageIndex !in 0 until renderer.pageCount) return@withContext null
                    renderer.openPage(pdfPageIndex).use { page ->
                        val widthMm = pageWidthMm ?: (page.width * MM_PER_PT)
                        val heightMm = pageHeightMm ?: (page.height * MM_PER_PT)
                        val left = max(0f, cropBoundsMm.left)
                        val top = max(0f, cropBoundsMm.top)
                        val right = min(widthMm, cropBoundsMm.right)
                        val bottom = min(heightMm, cropBoundsMm.bottom)
                        val cw = right - left
                        val ch = bottom - top
                        if (cw < MIN_CROP_MM || ch < MIN_CROP_MM) return@withContext null

                        var pxPerMm = dpi / 25.4f
                        val longest = max(cw, ch) * pxPerMm
                        val pixels = cw * pxPerMm * ch * pxPerMm
                        val cap = min(MAX_DIMENSION_PX / longest, sqrt(MAX_PIXELS / pixels))
                        if (cap < 1f) {
                            pxPerMm *= cap
                            effectiveDpi = pxPerMm * 25.4f
                        }
                        val w = max(1, (cw * pxPerMm).toInt())
                        val h = max(1, (ch * pxPerMm).toInt())
                        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        bitmap = bmp
                        bmp.eraseColor(Color.WHITE)
                        val m = Matrix()
                        m.setScale(widthMm * pxPerMm / page.width, heightMm * pxPerMm / page.height)
                        m.postTranslate(-left * pxPerMm, -top * pxPerMm)
                        page.render(bmp, null, m, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)

                        when (bgMode) {
                            SnippetBgMode.ORIGINAL -> Unit
                            SnippetBgMode.TRANSPARENT_LIGHT_INK -> SnippetPixels.process(bmp, darkAdapt = false)
                            SnippetBgMode.TRANSPARENT_DARK_ADAPTED -> SnippetPixels.process(bmp, darkAdapt = true)
                        }
                        if (cropPolygonMm != null && cropPolygonMm.size >= 3) {
                            applyPolygonMask(bmp, cropPolygonMm, left, top, pxPerMm)
                        }
                    }
                }
            }
            val bmp = bitmap ?: return@withContext null
            val imageId = idFactory()
            val relative = "images/$notebookId/$imageId.png"
            val file = File(context.filesDir, relative)
            file.parentFile?.mkdirs()
            file.outputStream().use { out -> bmp.compress(Bitmap.CompressFormat.PNG, 100, out) }

            val widthOnPage = placedWidthMm ?: (bmp.width / (effectiveDpi / 25.4f))
            val heightOnPage = widthOnPage * bmp.height / bmp.width
            val element = ImageElement(
                id = idFactory(),
                layerId = layerId,
                assetRelativePath = relative,
                bounds = StrokeBounds(
                    placeCenterMm.x - widthOnPage * 0.5f,
                    placeCenterMm.y - heightOnPage * 0.5f,
                    placeCenterMm.x + widthOnPage * 0.5f,
                    placeCenterMm.y + heightOnPage * 0.5f
                ),
                hasTransparentBg = bgMode != SnippetBgMode.ORIGINAL || cropPolygonMm != null
            )
            val result = SnippetResult(file, element, bmp.width, bmp.height, effectiveDpi)
            bmp.recycle()
            result
        } catch (e: SecurityException) {
            null
        } catch (e: java.io.IOException) {
            null
        } catch (e: IllegalStateException) {
            null
        } catch (e: OutOfMemoryError) {
            null
        }
    }

    /** Clears every pixel outside the polygon (page mm), keeping the polygon edge anti-aliased. */
    private fun applyPolygonMask(bmp: Bitmap, polygonMm: List<PointMm>, left: Float, top: Float, pxPerMm: Float) {
        val mask = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ALPHA_8)
        val path = Path()
        polygonMm.forEachIndexed { i, p ->
            val x = (p.x - left) * pxPerMm
            val y = (p.y - top) * pxPerMm
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.BLACK }
        Canvas(mask).drawPath(path, fill)
        val keep = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
        Canvas(bmp).drawBitmap(mask, 0f, 0f, keep)
        mask.recycle()
    }

    private companion object {
        const val DEFAULT_DPI = 300f
        const val MIN_CROP_MM = 2f
        const val MAX_DIMENSION_PX = 6000f
        const val MAX_PIXELS = 20_000_000f
        const val MM_PER_PT = 25.4f / 72f
    }
}

/**
 * Per-pixel background removal and dark-page adaptation.
 *
 * Background removal is colour-to-alpha against white: for each pixel the smallest alpha that could produce it from
 * a single ink colour over white is `max(255 - r, 255 - g, 255 - b) / 255`, and the ink colour is recovered from
 * it. That keeps anti-aliasing exact, so thin lines do not get jagged or haloed. Neutral near-white pixels
 * (luminance above 0.90, low chroma) are forced fully transparent so JPEG-ish speckle in scans disappears.
 *
 * Dark adaptation runs in OKLab on the recovered ink colour: neutrals (chroma below ~0.03) become off-white #F3F4F6
 * (slightly dimmed by how light the original ink was), coloured ink keeps its hue and chroma and only has its
 * lightness lifted to stay legible on a dark page.
 */
internal object SnippetPixels {

    private const val PAPER_LUMINANCE = 0.90f
    private const val PAPER_MAX_CHROMA = 0.12f
    private const val BAND_ROWS = 128

    private const val OFF_WHITE_R = 0xF3
    private const val OFF_WHITE_G = 0xF4
    private const val OFF_WHITE_B = 0xF6

    private val toLinear = FloatArray(256) { i ->
        val c = i / 255f
        if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)
    }
    private const val ENCODE_STEPS = 4096
    private val encode = IntArray(ENCODE_STEPS + 1) { i ->
        val l = i / ENCODE_STEPS.toFloat()
        val c = if (l <= 0.0031308f) l * 12.92f else 1.055f * l.pow(1f / 2.4f) - 0.055f
        (c * 255f + 0.5f).toInt().coerceIn(0, 255)
    }

    fun process(bmp: Bitmap, darkAdapt: Boolean) {
        val w = bmp.width
        val h = bmp.height
        val band = IntArray(w * BAND_ROWS)
        var y = 0
        while (y < h) {
            val rows = min(BAND_ROWS, h - y)
            bmp.getPixels(band, 0, w, 0, y, w, rows)
            for (i in 0 until w * rows) band[i] = convert(band[i], darkAdapt)
            bmp.setPixels(band, 0, w, 0, y, w, rows)
            y += rows
        }
    }

    private fun convert(argb: Int, darkAdapt: Boolean): Int {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        val luminance = (0.2126f * r + 0.7152f * g + 0.0722f * b) / 255f
        val chroma = (mx - mn) / 255f
        if (luminance > PAPER_LUMINANCE && chroma < PAPER_MAX_CHROMA) return 0

        val alpha = (255 - mn) / 255f
        if (alpha <= 0.004f) return 0
        // Ink colour that, drawn at [alpha] over white, gives this pixel.
        val inkR = ((r - 255f * (1f - alpha)) / alpha).coerceIn(0f, 255f)
        val inkG = ((g - 255f * (1f - alpha)) / alpha).coerceIn(0f, 255f)
        val inkB = ((b - 255f * (1f - alpha)) / alpha).coerceIn(0f, 255f)
        val a255 = (alpha * 255f + 0.5f).toInt().coerceIn(0, 255)
        if (!darkAdapt) return pack(a255, inkR.toInt(), inkG.toInt(), inkB.toInt())
        return adaptForDark(a255, inkR.toInt(), inkG.toInt(), inkB.toInt())
    }

    private fun adaptForDark(a: Int, r: Int, g: Int, b: Int): Int {
        val lr = toLinear[r]
        val lg = toLinear[g]
        val lb = toLinear[b]
        val l = cbrt(0.4122214708f * lr + 0.5363325363f * lg + 0.0514459929f * lb)
        val m = cbrt(0.2119034982f * lr + 0.6806995451f * lg + 0.1073969566f * lb)
        val s = cbrt(0.0883024619f * lr + 0.2817188376f * lg + 0.6299787005f * lb)
        val okL = 0.2104542553f * l + 0.7936177850f * m - 0.0040720468f * s
        val okA = 1.9779984951f * l - 2.4285922050f * m + 0.4505937099f * s
        val okB = 0.0259040371f * l + 0.7827717662f * m - 0.8086757660f * s
        val c = hypot(okA, okB)

        // Neutral branch: off-white, dimmed a little for lighter greys so grey rules stay secondary.
        val dim = 1f - 0.35f * okL
        val nr = OFF_WHITE_R * dim
        val ng = OFF_WHITE_G * dim
        val nb = OFF_WHITE_B * dim
        val t = smoothstep(0.03f, 0.08f, c)
        if (t <= 0f) return pack(a, nr.toInt(), ng.toInt(), nb.toInt())

        // Coloured branch: keep hue and chroma, lift lightness, pull chroma in until it fits sRGB.
        val targetL = max(okL, 0.62f)
        var chromaScale = 1f
        var cr = 0
        var cg = 0
        var cb = 0
        for (step in 0..6) {
            val aa = okA * chromaScale
            val bb = okB * chromaScale
            val l2 = targetL + 0.3963377774f * aa + 0.2158037573f * bb
            val m2 = targetL - 0.1055613458f * aa - 0.0638541728f * bb
            val s2 = targetL - 0.0894841775f * aa - 1.2914855480f * bb
            val l3 = l2 * l2 * l2
            val m3 = m2 * m2 * m2
            val s3 = s2 * s2 * s2
            val rl = 4.0767416621f * l3 - 3.3077115913f * m3 + 0.2309699292f * s3
            val gl = -1.2684380046f * l3 + 2.6097574011f * m3 - 0.3413193965f * s3
            val bl = -0.0041960863f * l3 - 0.7034186147f * m3 + 1.7076147010f * s3
            val inGamut = rl in 0f..1f && gl in 0f..1f && bl in 0f..1f
            if (inGamut || step == 6) {
                cr = encodeLinear(rl)
                cg = encodeLinear(gl)
                cb = encodeLinear(bl)
                break
            }
            chromaScale *= 0.85f
        }
        val outR = (nr + (cr - nr) * t).toInt()
        val outG = (ng + (cg - ng) * t).toInt()
        val outB = (nb + (cb - nb) * t).toInt()
        return pack(a, outR, outG, outB)
    }

    private fun encodeLinear(v: Float): Int = encode[(v.coerceIn(0f, 1f) * ENCODE_STEPS + 0.5f).toInt()]

    private fun smoothstep(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun pack(a: Int, r: Int, g: Int, b: Int): Int =
        (a shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
}
