package com.arhomedecor.app.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DepthNormalsTest {

    // Like a phone's depth image: 160 x 120 pixels, about 65 degrees across.
    private val width = 160
    private val height = 120
    private val intrinsics = floatArrayOf(125f, 125f, 80f, 60f)

    // Camera level and facing world -z, so camera axes are world axes.
    private val level = floatArrayOf(
        1f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        0f, 0f, 0f, 1f,
    )

    /** Depth of a floor [below] metres under the camera, seen below the horizon (row > centre). */
    private fun floorDepth(v: Int, below: Float): Float {
        val down = (v + 0.5f - intrinsics[3]) / intrinsics[1] // ray drop per metre forward
        return if (down > 0f) below / down else 0f
    }

    private fun image(depthAt: (u: Int, v: Int) -> Float) = FloatArray(width * height) { depthAt(it % width, it / width) }

    private fun upwardnessAt(depth: FloatArray, u: Int, v: Int): Float? {
        val normal = FloatArray(3)
        if (!DepthNormalEstimator().estimate(depth, width, height, u, v, 3, intrinsics, normal)) return null
        return upwardness(normal, level)
    }

    @Test
    fun floorFacesUp() {
        val floor = image { _, v -> floorDepth(v, 1.2f) }
        assertEquals(1f, upwardnessAt(floor, 80, 100)!!, 1e-4f)
    }

    @Test
    fun wallFacingTheCameraIsVertical() {
        val wall = image { _, _ -> 2f }
        assertEquals(0f, upwardnessAt(wall, 80, 60)!!, 1e-4f)
    }

    @Test
    fun tableTopKeepsItsOrientationAtItsEdge() {
        // A table top 0.5 m below the camera on the left, and the floor 1.2 m below beyond its
        // edge on the right: the depth jumps at u = 80.
        val scene = image { u, v -> if (u < 80) floorDepth(v, 0.5f) else floorDepth(v, 1.2f) }
        // Next to the edge, one neighbour of each is across it.
        assertEquals(1f, upwardnessAt(scene, 78, 100)!!, 1e-4f)
        assertEquals(1f, upwardnessAt(scene, 81, 100)!!, 1e-4f)
        // Taking both neighbours, as a plain central difference does, mixes the two surfaces.
        val normal = FloatArray(3)
        val left = FloatArray(3)
        val right = FloatArray(3)
        val up = FloatArray(3)
        val down = FloatArray(3)
        depthPixelToCamera(75, 100, scene[100 * width + 75], intrinsics, left, 0)
        depthPixelToCamera(81, 100, scene[100 * width + 81], intrinsics, right, 0)
        depthPixelToCamera(78, 97, scene[97 * width + 78], intrinsics, up, 0)
        depthPixelToCamera(78, 103, scene[103 * width + 78], intrinsics, down, 0)
        val a = FloatArray(3) { right[it] - left[it] }
        val b = FloatArray(3) { down[it] - up[it] }
        normal[0] = a[1] * b[2] - a[2] * b[1]
        normal[1] = a[2] * b[0] - a[0] * b[2]
        normal[2] = a[0] * b[1] - a[1] * b[0]
        assertTrue(upwardness(normal, level) < 0.7f)
    }

    @Test
    fun closerNeighboursStandInForUnmeasuredOnes() {
        // Raw depth is sparse: the pixels exactly three away are missing.
        val floor = image { u, v -> if (u == 77 || u == 83 || v == 97 || v == 103) 0f else floorDepth(v, 1.2f) }
        assertEquals(1f, upwardnessAt(floor, 80, 100)!!, 1e-4f)
    }

    @Test
    fun aPointWithNothingMeasuredAroundItHasNoOrientation() {
        val speck = image { u, v -> if (u == 80 && v == 100) 1f else 0f }
        assertEquals(null, upwardnessAt(speck, 80, 100))
        val normal = FloatArray(3)
        assertFalse(DepthNormalEstimator().estimate(speck, width, height, 0, 0, 3, intrinsics, normal))
    }

    @Test
    fun cameraTiltIsTakenIntoAccount() {
        // The camera pitched 90 degrees down (looking at the floor): a surface facing it is
        // horizontal in the world.
        val lookingDown = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 0f, -1f, 0f,
            0f, 1f, 0f, 0f,
            0f, 1.2f, 0f, 1f,
        )
        val normal = FloatArray(3)
        assertTrue(DepthNormalEstimator().estimate(image { _, _ -> 1.2f }, width, height, 80, 60, 3, intrinsics, normal))
        assertEquals(1f, upwardness(normal, lookingDown), 1e-4f)
    }
}
