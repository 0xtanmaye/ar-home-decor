package com.arhomedecor.app.render

import com.google.ar.core.Plane
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
    fun lowestUpwardPlaneIsFloor() {
        assertEquals(PlaneKind.FLOOR, classifyPlane(Plane.Type.HORIZONTAL_UPWARD_FACING, -1.40f, -1.40f))
    }

    @Test
    fun planesJustAboveFloorStillCountAsFloor() {
        assertEquals(PlaneKind.FLOOR, classifyPlane(Plane.Type.HORIZONTAL_UPWARD_FACING, -1.33f, -1.40f))
    }

    @Test
    fun tableHeightPlaneIsRaisedSurface() {
        assertEquals(PlaneKind.RAISED_SURFACE, classifyPlane(Plane.Type.HORIZONTAL_UPWARD_FACING, -0.65f, -1.40f))
    }

    @Test
    fun upwardPlaneWithNoFloorYetIsFloor() {
        assertEquals(PlaneKind.FLOOR, classifyPlane(Plane.Type.HORIZONTAL_UPWARD_FACING, -0.65f, null))
    }

    @Test
    fun verticalAndDownwardPlanesKeepTheirKind() {
        assertEquals(PlaneKind.WALL, classifyPlane(Plane.Type.VERTICAL, 0f, -1.4f))
        assertEquals(PlaneKind.CEILING, classifyPlane(Plane.Type.HORIZONTAL_DOWNWARD_FACING, 1.2f, -1.4f))
    }
}
