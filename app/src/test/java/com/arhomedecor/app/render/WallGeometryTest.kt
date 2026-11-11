package com.arhomedecor.app.render

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WallGeometryTest {

    @Test
    fun wallRunsBetweenTheTapsAndFacesTheViewer() {
        // Wall along the x axis at z = -2; the user stands at the origin (in front, +z side).
        val wall = wallFromFloorPoints(floatArrayOf(-1f, 0f, -2f), floatArrayOf(1f, 0f, -2f), floatArrayOf(0f, 1.5f, 0f))
        assertNotNull(wall)
        wall!!
        assertArrayEquals(floatArrayOf(0f, 0f, -2f), wall.midpoint, 1e-6f)
        assertArrayEquals(floatArrayOf(1f, 0f, 0f), wall.alongWall, 1e-6f)
        assertArrayEquals(floatArrayOf(0f, 0f, 1f), wall.normal, 1e-6f)
        assertEquals(1f, wall.halfLength, 1e-6f)
    }

    @Test
    fun normalFlipsToFaceAViewerOnTheOtherSide() {
        val wall = wallFromFloorPoints(floatArrayOf(-1f, 0f, -2f), floatArrayOf(1f, 0f, -2f), floatArrayOf(0f, 1.5f, -5f))!!
        assertArrayEquals(floatArrayOf(0f, 0f, -1f), wall.normal, 1e-6f)
    }

    @Test
    fun tapHeightDifferencesDontTiltTheWall() {
        // Floor points from slightly different heights still give a horizontal direction.
        val wall = wallFromFloorPoints(floatArrayOf(0f, 0f, 0f), floatArrayOf(0f, 0.02f, -2f), floatArrayOf(1f, 1.5f, -1f))!!
        assertEquals(0f, wall.alongWall[1], 0f)
        assertEquals(0f, wall.normal[1], 0f)
    }

    @Test
    fun tapsTooCloseTogetherAreRejected() {
        assertNull(wallFromFloorPoints(floatArrayOf(0f, 0f, 0f), floatArrayOf(0.1f, 0f, 0f), floatArrayOf(0f, 1f, 1f)))
    }

    @Test
    fun outlineCoversTheTapsPlusMarginFromFloorToHeight() {
        assertArrayEquals(floatArrayOf(-1.5f, 0f, 1.5f, 0f, 1.5f, 2.8f, -1.5f, 2.8f), wallOutline(1f, 0.5f, 2.8f), 1e-6f)
    }

    @Test
    fun centreRayOfASymmetricProjectionPointsStraightAhead() {
        val projection = FloatArray(16).apply {
            this[0] = 1.5f
            this[5] = 0.75f
        }
        assertArrayEquals(floatArrayOf(0f, 0f, -1f), cameraRayDirection(0f, 0f, projection), 1e-6f)
        // At the right edge (ndc x = 1), x / depth = 1 / projection[0].
        assertEquals(1f / 1.5f, cameraRayDirection(1f, 0f, projection)[0], 1e-6f)
    }

    @Test
    fun offCentreProjectionShiftsTheRay() {
        // projection[8] != 0 shifts the image centre: ndc x = -projection[8] looks straight ahead.
        val projection = FloatArray(16).apply {
            this[0] = 1.5f
            this[5] = 0.75f
            this[8] = 0.1f
        }
        assertEquals(0f, cameraRayDirection(-0.1f, 0f, projection)[0], 1e-6f)
    }

    @Test
    fun rayHitsAWallInFront() {
        val t = intersectRayPlane(
            origin = floatArrayOf(0f, 1.5f, 0f),
            direction = floatArrayOf(0f, 0f, -1f),
            planePoint = floatArrayOf(0f, 0f, -2f),
            planeNormal = floatArrayOf(0f, 0f, 1f),
        )
        assertEquals(2f, t!!, 1e-6f)
    }

    @Test
    fun rayMissesWallsBehindOrParallel() {
        val origin = floatArrayOf(0f, 1.5f, 0f)
        val wallPoint = floatArrayOf(0f, 0f, -2f)
        val wallNormal = floatArrayOf(0f, 0f, 1f)
        assertNull(intersectRayPlane(origin, floatArrayOf(0f, 0f, 1f), wallPoint, wallNormal))
        assertNull(intersectRayPlane(origin, floatArrayOf(1f, 0f, 0f), wallPoint, wallNormal))
    }

    // Marked wall along x at z = -2, facing +z, spanning x = -2..2.
    private val markedWall = WallPlaneWorld(
        point = floatArrayOf(0f, 0f, -2f),
        normal = floatArrayOf(0f, 0f, 1f),
        along = floatArrayOf(1f, 0f, 0f),
        uMin = -2f,
        uMax = 2f,
    )

    @Test
    fun curtainHangingInFrontOfAMarkedWallLiesOnIt() {
        // 20 cm out from the wall, facing the room (ARCore may report either normal direction).
        assertTrue(liesOnWall(floatArrayOf(1f, 1.2f, -1.8f), floatArrayOf(0f, 0f, 1f), 0.5f, markedWall))
        assertTrue(liesOnWall(floatArrayOf(1f, 1.2f, -1.8f), floatArrayOf(0f, 0f, -1f), 0.5f, markedWall))
    }

    @Test
    fun wardrobeWellInFrontOfTheWallIsNotOnIt() {
        assertFalse(liesOnWall(floatArrayOf(0f, 1f, -1.4f), floatArrayOf(0f, 0f, 1f), 0.6f, markedWall))
    }

    @Test
    fun perpendicularWallIsNotOnIt() {
        // A side wall meeting the marked wall at the corner.
        assertFalse(liesOnWall(floatArrayOf(2f, 1.2f, -1.5f), floatArrayOf(1f, 0f, 0f), 0.5f, markedWall))
    }

    @Test
    fun planeBeyondTheMarkedWallsEndIsNotOnIt() {
        // Same plane as the wall, but 3 m past its end (radius 0.4 m doesn't reach back).
        assertFalse(liesOnWall(floatArrayOf(5f, 1.2f, -2f), floatArrayOf(0f, 0f, 1f), 0.4f, markedWall))
        // Overlapping the end does count.
        assertTrue(liesOnWall(floatArrayOf(2.3f, 1.2f, -2f), floatArrayOf(0f, 0f, 1f), 0.4f, markedWall))
    }
}
