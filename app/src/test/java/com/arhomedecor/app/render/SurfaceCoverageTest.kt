package com.arhomedecor.app.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SurfaceCoverageTest {

    // Camera 0.5 m above a desk top at y = 0.
    private val camera = floatArrayOf(0f, 0.5f, 0f)

    private fun SurfaceCoverage.see(vararg xyz: Float, countsAgainst: Boolean = true) =
        observe(xyz, xyz.size / 3, camera, surfaceY = 0f, countsAgainst = countsAgainst)

    // On the desk at x = 0.5, and the floor 0.5 m below seen past the desk's height at x = 0.5.
    private val onDesk = floatArrayOf(0.5f, 0.005f, 0.01f)
    private val pastDesk = floatArrayOf(1f, -0.5f, 0.02f)

    @Test
    fun repeatedMeasurementsOnTheSurfaceConfirmACell() {
        val coverage = SurfaceCoverage()
        assertEquals("never measured", 0.5f, coverage.valueAt(0.5f, 0.01f), 0f)
        repeat(2) { coverage.see(*onDesk) }
        assertEquals(0.75f, coverage.valueAt(0.5f, 0.01f), 1e-6f)
        repeat(5) { coverage.see(*onDesk) }
        assertEquals(1f, coverage.valueAt(0.5f, 0.01f), 1e-6f)
    }

    @Test
    fun aCellOnlyEverSeenThroughIsRefuted() {
        val coverage = SurfaceCoverage()
        repeat(COVERAGE_MIN_REFUTING_FRAMES - 1) { coverage.see(*pastDesk) }
        assertEquals("not yet", 0.5f, coverage.valueAt(0.5f, 0.02f), 0f)
        coverage.see(*pastDesk)
        assertEquals(0f, coverage.valueAt(0.5f, 0.02f), 0f)
        assertEquals("the point itself says nothing about its own cell", 0.5f, coverage.valueAt(1f, 0.02f), 0f)
    }

    @Test
    fun glossyTopSeenAsOftenAsReflectingIsNotRefuted() {
        // Reflections read as seeing through, but the top's own texture is measured too.
        val coverage = SurfaceCoverage()
        repeat(6) { coverage.see(*pastDesk) }
        repeat(2) { coverage.see(*onDesk) }
        assertEquals(0.5f, coverage.valueAt(0.5f, 0.01f), 0f)
    }

    @Test
    fun floorsIgnoreReadingsBelowThem() {
        val coverage = SurfaceCoverage()
        repeat(5) { coverage.see(*pastDesk, countsAgainst = false) }
        assertEquals(0.5f, coverage.valueAt(0.5f, 0.02f), 0f)
    }

    @Test
    fun pointsAboveTheSurfaceSayNothing() {
        // Something standing on the desk neither confirms nor refutes it.
        val coverage = SurfaceCoverage()
        coverage.see(0.3f, 0.2f, 0f)
        assertEquals(0, coverage.version)
    }

    @Test
    fun eachCellCountsOneFrameForTheMajority() {
        val coverage = SurfaceCoverage()
        // Three readings on the desk at x = 0.5 and one seeing past it at the same spot.
        coverage.see(0.5f, 0f, 0f, 0.51f, 0f, 0f, 0.505f, 0f, 0f, 1f, -0.5f, 0f)
        assertEquals(coverageValue(seen = 1, seenThrough = 0), coverage.valueAt(0.5f, 0f), 0f)
    }

    @Test
    fun rasterizeWritesValuesAsBytes() {
        val coverage = SurfaceCoverage()
        repeat(5) { coverage.see(0.01f, 0f, 0.01f) }
        repeat(5) { coverage.see(1f, -0.5f, 0.01f) } // refutes x = 0.5 (cell 12)
        val out = ByteArray(13)
        coverage.rasterize(minX = 0.01f, minZ = 0.01f, columns = 13, rows = 1, out = out)
        assertEquals("confirmed", 255, out[0].toInt() and 0xFF)
        assertEquals("never measured", 128, out[1].toInt() and 0xFF)
        assertEquals("seen through", 0, out[12].toInt() and 0xFF)
    }

    @Test
    fun absorbingAddsTheOtherPlanesCounts() {
        val a = SurfaceCoverage()
        val b = SurfaceCoverage()
        a.see(*onDesk)
        repeat(2) { b.see(*onDesk) }
        val versionBefore = a.version
        a.absorb(b)
        assertEquals(coverageValue(seen = 3, seenThrough = 0), a.valueAt(0.5f, 0.01f), 0f)
        assertTrue(a.version > versionBefore)
    }

    @Test
    fun cellsAreIndexedFromNegativeCoordinatesToo() {
        assertEquals(-1, SurfaceCoverage.cellIndex(-0.01f))
        assertEquals(0, SurfaceCoverage.cellIndex(0.01f))
    }

    @Test
    fun depthPixelBecomesAWorldPoint() {
        val intrinsics = floatArrayOf(100f, 100f, 50.5f, 50.5f)
        // Camera 1.2 m above the origin, looking straight down: camera -z is world -y and
        // camera +y (image up) is world -z.
        val cameraToWorld = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 0f, -1f, 0f,
            0f, 1f, 0f, 0f,
            0f, 1.2f, 0f, 1f,
        )
        val out = FloatArray(3)
        depthPixelToWorld(50, 50, 1.2f, intrinsics, cameraToWorld, out, 0)
        assertEquals(0f, out[0], 1e-6f)
        assertEquals(0f, out[1], 1e-6f)
        assertEquals(0f, out[2], 1e-6f)
        // 10 pixels right and 10 up in the image: 0.12 m along world x and world -z.
        depthPixelToWorld(60, 40, 1.2f, intrinsics, cameraToWorld, out, 0)
        assertEquals(0.12f, out[0], 1e-6f)
        assertEquals(0f, out[1], 1e-6f)
        assertEquals(-0.12f, out[2], 1e-6f)
    }
}
