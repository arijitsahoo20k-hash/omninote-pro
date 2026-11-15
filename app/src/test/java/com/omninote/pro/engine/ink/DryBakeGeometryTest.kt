package com.omninote.pro.engine.ink

import com.omninote.pro.engine.input.ViewportTransform
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Random

class DryBakeGeometryTest {

    private fun randomViewport(r: Random) = ViewportTransform(
        zoom = 0.4f + r.nextFloat() * 6f,
        translationX = (r.nextFloat() - 0.5f) * 2000f,
        translationY = (r.nextFloat() - 0.5f) * 2000f,
        rotationDeg = if (r.nextBoolean()) 0f else (r.nextFloat() - 0.5f) * 90f,
        basePxPerMm = 249f / 25.4f
    )

    @Test
    fun bakedPixelLandsWhereLiveViewportPutsSamePagePoint() {
        val r = Random(42)
        val out = FloatArray(2)
        val live = FloatArray(2)
        repeat(500) {
            val a = randomViewport(r)
            val b = randomViewport(r)
            val mx = r.nextInt(400)
            val my = r.nextInt(300)
            val m = DryBakeGeometry.compensation(a, b, mx, my)
            val px = (r.nextFloat() - 0.5f) * 400f
            val py = (r.nextFloat() - 0.5f) * 600f
            // Where the bake put the page point: screen pixel under A, shifted by the over-scan margin.
            a.pageToScreen(px, py, out)
            val qx = out[0] + mx
            val qy = out[1] + my
            DryBakeGeometry.apply(m, qx, qy, out)
            b.pageToScreen(px, py, live)
            assertEquals(live[0], out[0], 0.05f + 1e-4f * kotlin.math.abs(live[0]))
            assertEquals(live[1], out[1], 0.05f + 1e-4f * kotlin.math.abs(live[1]))
        }
    }

    @Test
    fun identityWhenViewportsMatchIsPureMarginShift() {
        val v = ViewportTransform(zoom = 1.3f, translationX = 40f, translationY = -25f, rotationDeg = 0f, basePxPerMm = 9.8f)
        val m = DryBakeGeometry.compensation(v, v, 120, 80)
        val out = FloatArray(2)
        DryBakeGeometry.apply(m, 500f, 400f, out)
        assertEquals(380f, out[0], 1e-3f)
        assertEquals(320f, out[1], 1e-3f)
    }

    @Test
    fun marginsAreTwelvePercentWhenBitmapIsSmall() {
        val m = DryBakeGeometry.overscanMargins(1600, 900)
        assertEquals((1600 * DryBakeGeometry.OVERSCAN_FRACTION).toInt(), m.x)
        assertEquals((900 * DryBakeGeometry.OVERSCAN_FRACTION).toInt(), m.y)
        assertEquals(true, m.x in 190..192 && m.y in 107..108)
    }

    @Test
    fun marginsDropToZeroWhenBitmapExceedsBudget() {
        // 4000 x 3000 over-scanned is ~ 4960 x 3720 x 4 bytes = 74 MB > 48 MB.
        val m = DryBakeGeometry.overscanMargins(4000, 3000)
        assertEquals(0, m.x)
        assertEquals(0, m.y)
    }

    @Test
    fun degenerateViewGetsNoMargins() {
        assertEquals(0, DryBakeGeometry.overscanMargins(0, 100).x)
    }
}
