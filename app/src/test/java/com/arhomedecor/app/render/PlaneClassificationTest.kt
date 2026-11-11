package com.arhomedecor.app.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaneClassificationTest {

    // Floor at y = -1.2 (camera height 0 at the session start); desk top 0.8 m above it.
    private val floorY = -1.2f
    private val deskY = -0.4f

    @Test
    fun deskSeenBeforeAnyFloorIsNotTheFloor() {
        // The desk is its own local floor, but the camera was never more than 0.45 m above it.
        assertEquals(PlaneKind.RAISED_SURFACE, classifyUpward(deskY, deskY, wasFloor = null, floorMaxCameraHeight = 0.45f))
        // Another piece of the same desk, measured against the first, is not floor either.
        assertEquals(PlaneKind.RAISED_SURFACE, classifyUpward(deskY + 0.007f, deskY, wasFloor = null, floorMaxCameraHeight = 0.45f))
    }

    @Test
    fun floorSeenBeforeAnythingElseIsTheFloor() {
        assertEquals(PlaneKind.FLOOR, classifyUpward(floorY, floorY, wasFloor = null, floorMaxCameraHeight = 1.2f))
        assertEquals(PlaneKind.FLOOR, classifyUpward(-1.18f, floorY, wasFloor = null, floorMaxCameraHeight = 1.2f))
    }

    @Test
    fun withARealFloorNearbyHeightAboveItDecides() {
        assertEquals(PlaneKind.RAISED_SURFACE, classifyUpward(deskY, floorY, wasFloor = null, floorMaxCameraHeight = 1.3f))
    }

    private val desk = RaisedFootprint(deskY, floatArrayOf(0f, 0f, 1.2f, 0f, 1.2f, 0.6f, 0f, 0.6f))

    /** A vertical panel along the desk's front edge (z = 0), from [bottom] to [top]. */
    private fun panel(bottom: Float, top: Float, z: Float = 0f) =
        VerticalPlaneExtent(bottom, top, floatArrayOf(0.1f, z), floatArrayOf(1.1f, z))

    @Test
    fun deskFrontPanelIsAFurnitureSide() {
        assertTrue(isFurnitureSide(panel(floorY, deskY - 0.06f), listOf(desk), wasFurnitureSide = false))
        // Drawer fronts that stop short of the floor, a little in front of ARCore's desk outline.
        assertTrue(isFurnitureSide(panel(floorY + 0.16f, deskY + 0.04f, z = -0.3f), listOf(desk), wasFurnitureSide = false))
    }

    @Test
    fun wallBehindTheDeskIsNotAFurnitureSide() {
        // Rises well above the desk.
        assertFalse(isFurnitureSide(panel(floorY, floorY + 2.4f, z = 0.7f), listOf(desk), wasFurnitureSide = false))
        // Starts at the desk top and goes up (wall seen above the desk).
        assertFalse(isFurnitureSide(panel(deskY, deskY + 1f, z = 0.7f), listOf(desk), wasFurnitureSide = false))
    }

    @Test
    fun panelFarFromAnyRaisedSurfaceIsAWall() {
        assertFalse(isFurnitureSide(panel(floorY, deskY, z = -2f), listOf(desk), wasFurnitureSide = false))
        assertFalse(isFurnitureSide(panel(floorY, deskY), emptyList(), wasFurnitureSide = false))
    }

    @Test
    fun recognisedFurnitureSideIsKeptThroughSmallChanges() {
        // ARCore refines the panel: its top now sits 20 cm below the desk top.
        val refined = panel(floorY, deskY - 0.2f, z = -0.5f)
        assertFalse(isFurnitureSide(refined, listOf(desk), wasFurnitureSide = false))
        assertTrue(isFurnitureSide(refined, listOf(desk), wasFurnitureSide = true))
    }

    @Test
    fun panelBelowADeskTopIsAFurnitureSideEvenWithoutAFloor() {
        // Drawer fronts that start well below the top's front edge, or the part ARCore has found
        // so far: beside the desk and entirely below its top.
        assertTrue(isFurnitureSide(panel(floorY + 0.1f, deskY - 0.17f), listOf(desk), wasFurnitureSide = false))
        assertTrue(isFurnitureSide(panel(floorY + 0.1f, deskY - 0.4f), listOf(desk), wasFurnitureSide = false))
        assertEquals(PlaneKind.FURNITURE_SIDE, classifyVertical(panel(floorY + 0.1f, deskY - 0.17f), listOf(desk), null, false))
        // Rising clearly above the desk top: a wall.
        assertFalse(isFurnitureSide(panel(floorY + 0.1f, deskY + 0.3f), listOf(desk), wasFurnitureSide = false))
    }

    @Test
    fun lowVerticalPlaneIsHeldBackUntilItReachesWallHeight() {
        // A desk's drawer panel found before the desk top: no raised surface to match yet.
        assertEquals(PlaneKind.FURNITURE_SIDE, classifyVertical(panel(floorY + 0.1f, deskY + 0.06f), emptyList(), floorY, false))
        assertEquals(PlaneKind.WALL, classifyVertical(panel(floorY + 0.1f, floorY + 1.4f), emptyList(), floorY, false))
        // Without a known floor there is nothing to measure against.
        assertEquals(PlaneKind.WALL, classifyVertical(panel(floorY + 0.1f, deskY + 0.06f), emptyList(), null, false))
    }

    private fun square(x: Float, z: Float, half: Float, y: Float): UpwardFootprint {
        val outline = floatArrayOf(x - half, z - half, x + half, z - half, x + half, z + half, x - half, z + half)
        return UpwardFootprint(y, x, z, polygonArea(outline), outline)
    }

    @Test
    fun smallPieceInsideALargerPlaneAtTheSameHeightIsRedundant() {
        val desk = square(0f, 0f, 0.6f, deskY)
        val piece = square(0.3f, 0.1f, 0.15f, deskY + 0.01f)
        val raised = PlaneKind.RAISED_SURFACE
        assertEquals(setOf(1), redundantPlanes(listOf(desk, piece), listOf(raised, raised)))
    }

    @Test
    fun piecesThatDifferInHeightKindOrPlaceAreKept() {
        val desk = square(0f, 0f, 0.6f, deskY)
        val raised = PlaneKind.RAISED_SURFACE
        // A shelf 10 cm higher, a piece beside the desk, and a floor piece under it.
        val shelf = square(0.3f, 0.1f, 0.15f, deskY + 0.1f)
        val beside = square(1.2f, 0f, 0.15f, deskY)
        val floor = square(0f, 0f, 0.2f, deskY)
        assertEquals(
            emptySet<Int>(),
            redundantPlanes(listOf(desk, shelf, beside, floor), listOf(raised, raised, raised, PlaneKind.FLOOR)),
        )
    }

    @Test
    fun polygonAreaOfARectangle() {
        assertEquals(2f, polygonArea(floatArrayOf(0f, 0f, 2f, 0f, 2f, 1f, 0f, 1f)), 1e-6f)
    }

    @Test
    fun distanceToPolygonIsZeroInsideAndToTheNearestEdgeOutside() {
        val square = floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)
        assertEquals(0f, distanceToPolygon(0.5f, 0.5f, square), 0f)
        assertEquals(0.5f, distanceToPolygon(1.5f, 0.5f, square), 1e-6f)
        assertEquals(kotlin.math.sqrt(2f), distanceToPolygon(2f, 2f, square), 1e-6f)
        assertEquals(0.3f, segmentDistanceToPolygon(floatArrayOf(-1f, -0.3f), floatArrayOf(2f, -0.3f), square), 1e-6f)
    }
}
