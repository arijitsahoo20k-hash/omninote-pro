package com.omninote.pro.engine.io

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.graphics.BlendMode

/**
 * A [Canvas] that draws nothing and writes SVG instead. Painters keep working unchanged: every `drawPath`,
 * `drawLine(s)`, `drawPoints`, `drawRect`, `drawCircle`, `drawRoundRect`, `drawText` and `clipPath` they issue is
 * converted to absolute page-millimetre SVG, using the transform tracked here.
 *
 * Paths are flattened per contour with `Path.approximate(0.01)`, so geometry is within 0.01 mm of the original.
 * Shaders (pencil grain, hatch) and dash effects cannot be expressed from a Paint and are dropped; colour, alpha,
 * stroke width, caps, joins and multiply/screen blending are kept.
 */
internal class SvgRecordingCanvas : Canvas() {

    val body = StringBuilder(64 * 1024)
    val defs = StringBuilder()

    private class Level(val matrix: Matrix, var clipGroups: Int = 0)

    private val levels = ArrayList<Level>().apply { add(Level(Matrix())) }
    private var cur: Level = levels[0]
    private var clipCounter = 0

    private val scratch = Path()
    private val xform = Path()
    private val segment = Path()
    private val pts = FloatArray(2)

    /** Current transform (page space to SVG user space is the identity: the root viewBox is page mm). */
    val currentMatrix: Matrix get() = cur.matrix

    fun raw(fragment: String) {
        body.append(fragment)
    }

    // ---- state ----

    override fun save(): Int {
        levels.add(Level(Matrix(cur.matrix)))
        cur = levels.last()
        return levels.size - 1
    }

    @Suppress("DEPRECATION")
    override fun saveLayerAlpha(bounds: RectF?, alpha: Int): Int = save()

    override fun restore() {
        if (levels.size <= 1) return
        repeat(cur.clipGroups) { body.append("</g>") }
        levels.removeAt(levels.size - 1)
        cur = levels.last()
    }

    override fun getSaveCount(): Int = levels.size

    override fun restoreToCount(saveCount: Int) {
        while (levels.size > saveCount.coerceAtLeast(1)) restore()
    }

    /** Closes any clip groups still open so the document is well formed. */
    fun finish() {
        while (levels.size > 1) restore()
        repeat(cur.clipGroups) { body.append("</g>") }
        cur.clipGroups = 0
    }

    override fun translate(dx: Float, dy: Float) {
        cur.matrix.preTranslate(dx, dy)
    }

    override fun scale(sx: Float, sy: Float) {
        cur.matrix.preScale(sx, sy)
    }

    override fun scale(sx: Float, sy: Float, px: Float, py: Float) {
        cur.matrix.preScale(sx, sy, px, py)
    }

    override fun rotate(degrees: Float) {
        cur.matrix.preRotate(degrees)
    }

    override fun rotate(degrees: Float, px: Float, py: Float) {
        cur.matrix.preRotate(degrees, px, py)
    }

    override fun concat(matrix: Matrix?) {
        if (matrix != null) cur.matrix.preConcat(matrix)
    }

    override fun clipRect(rect: RectF): Boolean = true

    override fun clipRect(left: Float, top: Float, right: Float, bottom: Float): Boolean = true

    override fun clipPath(path: Path): Boolean {
        val id = "clip${clipCounter++}"
        defs.append("<clipPath id=\"").append(id).append("\"><path d=\"")
        appendPath(defs, path, cur.matrix)
        defs.append("\"/></clipPath>")
        body.append("<g clip-path=\"url(#").append(id).append(")\">")
        cur.clipGroups++
        return true
    }

    // ---- drawing ----

    override fun drawPath(path: Path, paint: Paint) {
        body.append("<path d=\"")
        appendPath(body, path, cur.matrix)
        body.append('"')
        appendPaint(body, paint)
        body.append("/>")
    }

    override fun drawLine(startX: Float, startY: Float, stopX: Float, stopY: Float, paint: Paint) {
        body.append("<path d=\"M")
        appendPoint(body, startX, startY)
        body.append('L')
        appendPoint(body, stopX, stopY)
        body.append('"')
        appendPaint(body, strokeOf(paint))
        body.append("/>")
    }

    override fun drawLines(pts: FloatArray, offset: Int, count: Int, paint: Paint) {
        if (count < 4) return
        body.append("<path d=\"")
        var i = offset
        val end = offset + count - 3
        while (i < end) {
            body.append('M')
            appendPoint(body, pts[i], pts[i + 1])
            body.append('L')
            appendPoint(body, pts[i + 2], pts[i + 3])
            i += 4
        }
        body.append('"')
        appendPaint(body, strokeOf(paint))
        body.append("/>")
    }

    override fun drawLines(pts: FloatArray, paint: Paint) {
        drawLines(pts, 0, pts.size, paint)
    }

    /** Points become zero-length round-capped segments, which every SVG renderer draws as dots of stroke-width size. */
    override fun drawPoints(pts: FloatArray, offset: Int, count: Int, paint: Paint) {
        if (count < 2) return
        body.append("<path d=\"")
        var i = offset
        val end = offset + count - 1
        while (i < end) {
            body.append('M')
            appendPoint(body, pts[i], pts[i + 1])
            body.append("h0")
            i += 2
        }
        body.append('"')
        appendPaint(body, strokeOf(paint), forceRoundCap = true)
        body.append("/>")
    }

    override fun drawPoints(pts: FloatArray, paint: Paint) {
        drawPoints(pts, 0, pts.size, paint)
    }

    override fun drawRect(rect: RectF, paint: Paint) {
        drawRect(rect.left, rect.top, rect.right, rect.bottom, paint)
    }

    override fun drawRect(left: Float, top: Float, right: Float, bottom: Float, paint: Paint) {
        scratch.rewind()
        scratch.addRect(left, top, right, bottom, Path.Direction.CW)
        drawPath(scratch, paint)
    }

    override fun drawRoundRect(rect: RectF, rx: Float, ry: Float, paint: Paint) {
        scratch.rewind()
        scratch.addRoundRect(rect, rx, ry, Path.Direction.CW)
        drawPath(scratch, paint)
    }

    override fun drawCircle(cx: Float, cy: Float, radius: Float, paint: Paint) {
        scratch.rewind()
        scratch.addCircle(cx, cy, radius, Path.Direction.CW)
        drawPath(scratch, paint)
    }

    override fun drawText(text: String, x: Float, y: Float, paint: Paint) {
        pts[0] = x
        pts[1] = y
        cur.matrix.mapPoints(pts)
        val scale = cur.matrix.mapRadius(1f)
        body.append("<text x=\"").append(fmt(pts[0])).append("\" y=\"").append(fmt(pts[1]))
            .append("\" font-family=\"sans-serif\" font-size=\"").append(fmt(paint.textSize * scale)).append('"')
        when (paint.textAlign) {
            Paint.Align.CENTER -> body.append(" text-anchor=\"middle\"")
            Paint.Align.RIGHT -> body.append(" text-anchor=\"end\"")
            else -> Unit
        }
        body.append(" fill=\"").append(hex(paint.color)).append('"')
        val a = (paint.color ushr 24) / 255f
        if (a < 0.999f) body.append(" fill-opacity=\"").append(fmt(a, 3)).append('"')
        body.append('>').append(escape(text)).append("</text>")
    }

    // ---- serialisation helpers ----

    private fun strokeOf(paint: Paint): Paint = paint

    private fun appendPaint(sb: StringBuilder, paint: Paint, forceRoundCap: Boolean = false) {
        val a = (paint.color ushr 24) / 255f
        val rgb = hex(paint.color)
        val style = if (forceRoundCap) Paint.Style.STROKE else paint.style
        val scale = cur.matrix.mapRadius(1f)
        when (style) {
            Paint.Style.FILL -> {
                sb.append(" fill=\"").append(rgb).append("\" stroke=\"none\"")
                if (a < 0.999f) sb.append(" fill-opacity=\"").append(fmt(a, 3)).append('"')
            }
            Paint.Style.STROKE -> appendStroke(sb, paint, rgb, a, scale, forceRoundCap)
            else -> {
                sb.append(" fill=\"").append(rgb).append('"')
                if (a < 0.999f) sb.append(" fill-opacity=\"").append(fmt(a, 3)).append('"')
                appendStroke(sb, paint, rgb, a, scale, forceRoundCap)
            }
        }
        when (paint.blendMode) {
            BlendMode.MULTIPLY -> sb.append(" style=\"mix-blend-mode:multiply\"")
            BlendMode.SCREEN -> sb.append(" style=\"mix-blend-mode:screen\"")
            else -> Unit
        }
    }

    private fun appendStroke(sb: StringBuilder, paint: Paint, rgb: String, a: Float, scale: Float, roundCap: Boolean) {
        if (paint.style == Paint.Style.STROKE || roundCap) sb.append(" fill=\"none\"")
        sb.append(" stroke=\"").append(rgb).append("\" stroke-width=\"").append(fmt(paint.strokeWidth * scale, 3)).append('"')
        if (a < 0.999f) sb.append(" stroke-opacity=\"").append(fmt(a, 3)).append('"')
        val cap = if (roundCap) Paint.Cap.ROUND else paint.strokeCap
        when (cap) {
            Paint.Cap.ROUND -> sb.append(" stroke-linecap=\"round\"")
            Paint.Cap.SQUARE -> sb.append(" stroke-linecap=\"square\"")
            else -> Unit
        }
        when (paint.strokeJoin) {
            Paint.Join.ROUND -> sb.append(" stroke-linejoin=\"round\"")
            Paint.Join.BEVEL -> sb.append(" stroke-linejoin=\"bevel\"")
            else -> Unit
        }
    }

    /** Writes [path] under [m] as `M x y L x y ... Z`, one subpath per contour. */
    fun appendPath(out: StringBuilder, path: Path, m: Matrix) {
        xform.rewind()
        xform.set(path)
        xform.transform(m)
        val measure = PathMeasure(xform, false)
        do {
            val length = measure.length
            if (length <= 0f) continue
            segment.rewind()
            if (!measure.getSegment(0f, length, segment, true)) continue
            val flat = segment.approximate(FLATTEN_TOLERANCE_MM)
            var i = 0
            while (i + 2 < flat.size) {
                out.append(if (i == 0) 'M' else 'L')
                appendPoint(out, flat[i + 1], flat[i + 2], mapped = true)
                i += 3
            }
            if (measure.isClosed) out.append('Z')
        } while (measure.nextContour())
    }

    private fun appendPoint(sb: StringBuilder, x: Float, y: Float, mapped: Boolean = false) {
        if (mapped) {
            sb.append(fmt(x)).append(' ').append(fmt(y))
            return
        }
        pts[0] = x
        pts[1] = y
        cur.matrix.mapPoints(pts)
        sb.append(fmt(pts[0])).append(' ').append(fmt(pts[1]))
    }

    companion object {
        const val FLATTEN_TOLERANCE_MM = 0.01f

        fun fmt(v: Float, decimals: Int = 2): String {
            if (v.isNaN() || v.isInfinite()) return "0"
            val factor = if (decimals == 3) 1000f else 100f
            val n = Math.round(v * factor)
            if (n == 0) return "0"
            val neg = n < 0
            val abs = Math.abs(n.toLong())
            val whole = abs / factor.toLong()
            val frac = (abs % factor.toLong()).toInt()
            val sb = StringBuilder(12)
            if (neg) sb.append('-')
            sb.append(whole)
            if (frac != 0) {
                var f = frac.toString().padStart(decimals, '0')
                f = f.trimEnd('0')
                sb.append('.').append(f)
            }
            return sb.toString()
        }

        fun hex(argb: Int): String = String.format("#%06X", argb and 0xFFFFFF)

        fun escape(s: String): String {
            val sb = StringBuilder(s.length + 8)
            for (c in s) when (c) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                else -> sb.append(c)
            }
            return sb.toString()
        }
    }
}
