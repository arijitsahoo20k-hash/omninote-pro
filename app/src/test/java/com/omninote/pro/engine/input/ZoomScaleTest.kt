package com.omninote.pro.engine.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ZoomScaleTest {
    private val min = 0.47f
    private val max = ViewportTransform.MAX_ZOOM

    @Test
    fun endpointsMapToFractionZeroAndOne() {
        assertEquals(0f, ZoomScale.fractionForZoom(min, min, max), 1e-6f)
        assertEquals(1f, ZoomScale.fractionForZoom(max, min, max), 1e-6f)
        assertEquals(min, ZoomScale.zoomForFraction(0f, min, max), 1e-5f)
        assertEquals(max, ZoomScale.zoomForFraction(1f, min, max), 1e-4f)
    }

    @Test
    fun roundTripReturnsTheSameZoom() {
        var z = min
        while (z <= max) {
            val back = ZoomScale.zoomForFraction(ZoomScale.fractionForZoom(z, min, max), min, max)
            assertEquals(z, back, z * 1e-4f)
            z *= 1.37f
        }
    }

    @Test
    fun mappingIsMonotonicInBothDirections() {
        var lastFraction = -1f
        var lastZoom = 0f
        for (i in 0..200) {
            val f = i / 200f
            val z = ZoomScale.zoomForFraction(f, min, max)
            assertTrue(z > lastZoom)
            lastZoom = z
            val back = ZoomScale.fractionForZoom(z, min, max)
            assertTrue(back >= lastFraction)
            lastFraction = back
        }
    }

    @Test
    fun equalSliderStepsAreEqualZoomRatios() {
        val a = ZoomScale.zoomForFraction(0.2f, min, max)
        val b = ZoomScale.zoomForFraction(0.3f, min, max)
        val c = ZoomScale.zoomForFraction(0.6f, min, max)
        val d = ZoomScale.zoomForFraction(0.7f, min, max)
        assertEquals(b / a, d / c, 1e-3f)
    }

    @Test
    fun outOfRangeInputsAreClamped() {
        assertEquals(0f, ZoomScale.fractionForZoom(0.01f, min, max), 0f)
        assertEquals(1f, ZoomScale.fractionForZoom(99f, min, max), 0f)
        assertEquals(max, ZoomScale.zoomForFraction(5f, min, max), 1e-4f)
        assertEquals(min, ZoomScale.zoomForFraction(-3f, min, max), 1e-5f)
    }

    @Test
    fun degenerateRangeIsSafe() {
        assertEquals(0f, ZoomScale.fractionForZoom(1f, 2f, 2f), 0f)
        assertEquals(2f, ZoomScale.zoomForFraction(0.5f, 2f, 2f), 0f)
    }
}
