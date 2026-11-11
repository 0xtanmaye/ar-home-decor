package com.arhomedecor.app.ar

import com.arhomedecor.app.render.ndcToUvAffine
import org.junit.Assert.assertEquals
import org.junit.Test

class DepthMathTest {

    private fun apply(m: FloatArray, x: Float, y: Float) = Pair(m[0] * x + m[1] * y + m[2], m[3] * x + m[4] * y + m[5])

    @Test
    fun affineReproducesAllFourCornersOfARotatedCroppedImage() {
        // Portrait screen showing a landscape camera image rotated 90 degrees and cropped:
        // corners BL, BR, TL, TR as (u, v).
        val corners = floatArrayOf(1f, 0.9f, 1f, 0.1f, 0f, 0.9f, 0f, 0.1f)
        val m = ndcToUvAffine(corners)
        val ndc = listOf(-1f to -1f, 1f to -1f, -1f to 1f, 1f to 1f)
        ndc.forEachIndexed { i, (x, y) ->
            val (u, v) = apply(m, x, y)
            assertEquals("u at corner $i", corners[i * 2], u, 1e-6f)
            assertEquals("v at corner $i", corners[i * 2 + 1], v, 1e-6f)
        }
        val (cu, cv) = apply(m, 0f, 0f)
        assertEquals(0.5f, cu, 1e-6f)
        assertEquals(0.5f, cv, 1e-6f)
    }

    @Test
    fun planeOnTheRealFloorHasNoGap() {
        assertEquals(0f, planeOffsetFromRealSurface(cameraDistanceToPlane = 1.2f, planeDepth = 1.5f, realDepth = 1.5f), 1e-6f)
    }

    @Test
    fun planeCloserThanRealFloorIsAboveIt() {
        // Camera 1.2 m above the plane; the real floor is 5% farther along the ray.
        val gap = planeOffsetFromRealSurface(cameraDistanceToPlane = 1.2f, planeDepth = 2.0f, realDepth = 2.1f)
        assertEquals(0.06f, gap, 1e-5f)
    }

    @Test
    fun planeFartherThanRealFloorIsBelowIt() {
        val gap = planeOffsetFromRealSurface(cameraDistanceToPlane = 1.2f, planeDepth = 2.0f, realDepth = 1.9f)
        assertEquals(-0.06f, gap, 1e-5f)
    }

    @Test
    fun robustFitIgnoresObjectsInFrontOfTheSurface() {
        // 10 samples on the floor around +1 cm, 6 hitting a bed 50 cm in front of it.
        val onFloor = List(10) { 0.005f + it * 0.001f }
        val onBed = List(6) { -0.5f }
        val fit = robustFit(onFloor + onBed)!!
        assertEquals(0.0095f, fit.offsetMetres, 1e-6f)
        assertEquals(10, fit.samples)
        assertEquals(10f / 16f, fit.inlierFraction, 1e-6f)
    }

    @Test
    fun robustFitNeedsEnoughSamplesOnTheSurface() {
        assertEquals(null, robustFit(List(7) { 0.01f } + List(20) { -0.6f }))
    }

    @Test
    fun medianIgnoresOutliers() {
        assertEquals(0.02f, median(listOf(0.01f, 0.02f, 0.03f, 5f, -4f)), 1e-6f)
        assertEquals(0.025f, median(listOf(0.03f, 0.02f)), 1e-6f)
    }
}
