package com.arhomedecor.app.render

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaneGeometryTest {

    @Test
    fun featheredTriangleHasInnerFaceAndThreeEdgeQuads() {
        val expected = shortArrayOf(
            3, 4, 5, // inner triangle
            0, 1, 3, 1, 4, 3, // edge 0-1
            1, 2, 4, 2, 5, 4, // edge 1-2
            2, 0, 5, 0, 3, 5, // edge 2-0 (wraps around)
        )
        assertArrayEquals(expected, featheredPolygonIndices(3))
    }

    @Test
    fun indexCountMatchesAllocationForAnySize() {
        for (n in 3..64) {
            val indices = featheredPolygonIndices(n)
            assertEquals(featheredIndexCount(n), indices.size)
            assertTrue("indices must stay within 2n vertices", indices.all { it in 0 until 2 * n })
        }
    }

    @Test
    fun everyOutlineEdgeIsCoveredByTheFadeBand() {
        val n = 7
        val triangles = featheredPolygonIndices(n).toList().chunked(3).map { it.toSet() }
        for (i in 0 until n) {
            val edge = setOf(i.toShort(), ((i + 1) % n).toShort())
            assertTrue("edge $edge has no triangle", triangles.any { it.containsAll(edge) })
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsDegeneratePolygons() {
        featheredPolygonIndices(2)
    }

    @Test
    fun dilationPushesEveryVertexOutByTheDistance() {
        // A 2 x 1 rectangle centred on (1, 0.5).
        val outline = floatArrayOf(0f, 0f, 2f, 0f, 2f, 1f, 0f, 1f)
        val dilated = dilateConvexOutline(outline, 0.3f)
        for (i in 0 until 4) {
            val before = kotlin.math.hypot(outline[i * 2] - 1f, outline[i * 2 + 1] - 0.5f)
            val after = kotlin.math.hypot(dilated[i * 2] - 1f, dilated[i * 2 + 1] - 0.5f)
            assertEquals(before + 0.3f, after, 1e-5f)
        }
    }

    @Test
    fun zeroDilationLeavesOutlineUnchanged() {
        val outline = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f)
        assertArrayEquals(outline, dilateConvexOutline(outline, 0f), 0f)
    }

    // Room A: floor at y = -1.40 around x = 0, a bed beside it. Room B: 6 m away, its floor
    // 18 cm lower (a step down, or drift between rooms).
    private val floorA = HorizontalPlaneInfo(y = -1.40f, x = 0f, z = 0f, radius = 1.5f)
    private val bedA = HorizontalPlaneInfo(y = -0.92f, x = 1.8f, z = 0f, radius = 0.9f)
    private val floorB = HorizontalPlaneInfo(y = -1.58f, x = 6f, z = 0f, radius = 1.0f)

    @Test
    fun anotherRoomsLowerFloorDoesNotAffectThisRoom() {
        val levels = localFloorLevels(listOf(floorA, bedA, floorB))
        assertEquals(-1.40f, levels[0], 0f)
        assertEquals(PlaneKind.FLOOR, classifyUpward(floorA.y, levels[0], wasFloor = null))
        assertEquals(PlaneKind.FLOOR, classifyUpward(floorB.y, levels[2], wasFloor = null))
    }

    @Test
    fun bedNextToTheFloorIsARaisedSurface() {
        val levels = localFloorLevels(listOf(floorA, bedA, floorB))
        assertEquals(-1.40f, levels[1], 0f)
        assertEquals(PlaneKind.RAISED_SURFACE, classifyUpward(bedA.y, levels[1], wasFloor = null))
    }

    @Test
    fun neighbourhoodUsesOutlinesNotJustCentres() {
        // Centres 3 m apart, but the outlines are only 0.5 m apart: still neighbours.
        val big = HorizontalPlaneInfo(y = -1.4f, x = 0f, z = 0f, radius = 1.5f)
        val table = HorizontalPlaneInfo(y = -0.7f, x = 3f, z = 0f, radius = 1.0f)
        assertEquals(-1.4f, localFloorLevels(listOf(big, table))[1], 0f)
    }

    @Test
    fun floorPiecesAtSlightlyDifferentHeightsAllCountAsFloor() {
        assertEquals(PlaneKind.FLOOR, classifyUpward(planeY = -1.33f, localFloorY = -1.40f, wasFloor = null))
    }

    @Test
    fun hysteresisStopsFlickerNearTheThreshold() {
        // 11 cm above the local floor: a floor stays floor, a raised surface stays raised.
        assertEquals(PlaneKind.FLOOR, classifyUpward(-1.29f, -1.40f, wasFloor = true))
        assertEquals(PlaneKind.RAISED_SURFACE, classifyUpward(-1.29f, -1.40f, wasFloor = false))
        // Clear changes still switch.
        assertEquals(PlaneKind.RAISED_SURFACE, classifyUpward(-1.20f, -1.40f, wasFloor = true))
        assertEquals(PlaneKind.FLOOR, classifyUpward(-1.35f, -1.40f, wasFloor = false))
    }
}
