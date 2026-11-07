package com.omninote.pro.engine.ink

import com.omninote.pro.core.model.StrokePoint

/**
 * Per-draw settings shared by every renderer. All drawing happens in PAGE space (mm): the caller concatenates the
 * viewport matrix (mm -> px) onto the canvas, so geometry, widths and dash lengths are expressed in millimetres.
 * For vector PDF export, concatenate mm -> pt (72 / 25.4) instead and set [isExport].
 */
class InkRenderContext(
    var pxPerMm: Float = 9.8f,
    var paperIsDark: Boolean = false,
    var smartInversion: Boolean = true,
    var isExport: Boolean = false,
    /**
     * True when drawing onto an android.graphics.pdf.PdfDocument canvas, whose backend does not reliably encode
     * blend modes. Highlighters then use plain SRC_OVER at 35% alpha so they never turn opaque over text.
     */
    var isPdfExport: Boolean = false
) {
    /** Export always uses the saved semantic colours on white paper. */
    val invertForDisplay: Boolean get() = paperIsDark && smartInversion && !isExport

    fun displayColor(semanticArgb: Int, highlighter: Boolean = false): Int =
        DarkModeColorInverter.toDisplay(semanticArgb, invertForDisplay, true, highlighter)

    fun copyFrom(other: InkRenderContext) {
        pxPerMm = other.pxPerMm
        paperIsDark = other.paperIsDark
        smartInversion = other.smartInversion
        isExport = other.isExport
        isPdfExport = other.isPdfExport
    }
}

/** Immutable view of the first [count] points of a [PointBuffer]; safe to read from another thread. */
class PointArrays(
    val x: FloatArray,
    val y: FloatArray,
    val pressure: FloatArray,
    val tilt: FloatArray,
    val orientation: FloatArray,
    val timeMs: FloatArray,
    val count: Int
) {
    companion object {
        fun of(points: List<StrokePoint>): PointArrays {
            val n = points.size
            val x = FloatArray(n)
            val y = FloatArray(n)
            val p = FloatArray(n)
            val t = FloatArray(n)
            val o = FloatArray(n)
            val ms = FloatArray(n)
            for (i in 0 until n) {
                val s = points[i]
                x[i] = s.x; y[i] = s.y; p[i] = s.pressure; t[i] = s.tilt; o[i] = s.orientation
                ms[i] = s.timeMs.toFloat()
            }
            return PointArrays(x, y, p, t, o, ms, n)
        }
    }
}

/**
 * Append-only growable struct-of-arrays for the stroke being drawn. Entries below [size] are never rewritten, and
 * growth allocates fresh arrays, so a [freeze]n [PointArrays] stays valid while the UI thread keeps appending.
 * Create a new buffer per stroke; do not reuse one.
 */
class PointBuffer(initialCapacity: Int = 256) {
    private var x = FloatArray(initialCapacity)
    private var y = FloatArray(initialCapacity)
    private var pressure = FloatArray(initialCapacity)
    private var tilt = FloatArray(initialCapacity)
    private var orientation = FloatArray(initialCapacity)
    private var timeMs = FloatArray(initialCapacity)

    var size: Int = 0
        private set

    fun add(px: Float, py: Float, pPressure: Float, pTilt: Float, pOrientation: Float, pTimeMs: Float) {
        if (size == x.size) grow()
        x[size] = px
        y[size] = py
        pressure[size] = pPressure
        tilt[size] = pTilt
        orientation[size] = pOrientation
        timeMs[size] = pTimeMs
        size++
    }

    fun add(p: StrokePoint) = add(p.x, p.y, p.pressure, p.tilt, p.orientation, p.timeMs.toFloat())

    fun freeze(): PointArrays = PointArrays(x, y, pressure, tilt, orientation, timeMs, size)

    fun toStrokePoints(): List<StrokePoint> {
        val out = ArrayList<StrokePoint>(size)
        for (i in 0 until size) {
            out.add(StrokePoint(x[i], y[i], pressure[i], tilt[i], orientation[i], timeMs[i].toLong()))
        }
        return out
    }

    private fun grow() {
        val n = x.size * 2
        x = x.copyOf(n)
        y = y.copyOf(n)
        pressure = pressure.copyOf(n)
        tilt = tilt.copyOf(n)
        orientation = orientation.copyOf(n)
        timeMs = timeMs.copyOf(n)
    }
}
