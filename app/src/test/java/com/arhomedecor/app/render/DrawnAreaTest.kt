package com.arhomedecor.app.render

import org.junit.Assert.assertEquals
import org.junit.Test

class DrawnAreaTest {

    private fun square(half: Float) = floatArrayOf(-half, -half, half, -half, half, half, -half, half)

    // Areas are sampled on a 4 cm grid, so edges are only placed to within a row of cells.
    private val sampling = 0.1f

    // ARCore's outline 1 m x 1 m, drawn 0.3 m larger each way (1.6 m x 1.6 m).
    private val arcore = square(0.5f)
    private val drawn = square(0.8f)

    @Test
    fun areasSplitIntoInsideAndMargin() {
        val area = measureDrawnArea(arcore, drawn, true, 0.04f, { _, _ -> 0.5f }, { _, _ -> DepthVerdict.AGREES })
        assertEquals(1f, area.inside, sampling)
        assertEquals(1.56f, area.margin, sampling)
        // Unconfirmed: inside drawn on this frame's depth alone, margin not drawn.
        assertEquals(1f, area.shown, sampling)
        assertEquals(1f, area.shownOnDepthAlone, sampling)
    }

    @Test
    fun confirmedMarginOverLowerDepthIsFlagged() {
        // Measured depth confirmed the whole area, but this frame's depth sees something lower
        // on the +x side of the margin (x > 0.5): grid drawn where it probably shouldn't be.
        val area = measureDrawnArea(
            arcore, drawn, true, 0.04f,
            coverageAt = { _, _ -> 1f },
            depthAt = { x, _ -> if (x > 0.5f) DepthVerdict.LOWER else DepthVerdict.AGREES },
        )
        assertEquals(2.56f, area.shown, sampling)
        assertEquals(0.3f * 1.6f, area.shownOverLower, sampling)
        assertEquals(1f, area.insideConfirmed, sampling)
        assertEquals(1.56f, area.marginConfirmed, sampling)
    }

    @Test
    fun raisedSurfacesHideUnconfirmedInsideWhereDepthSeesLower() {
        val lowerOnRight = { x: Float, _: Float -> if (x > 0f) DepthVerdict.LOWER else DepthVerdict.AGREES }
        val raised = measureDrawnArea(arcore, drawn, true, 0.04f, { _, _ -> 0.5f }, lowerOnRight)
        assertEquals(0.5f, raised.shown, sampling)
        assertEquals(0f, raised.shownOverLower, sampling)
        // Floors don't check behind themselves: reflections in a glossy floor look lower.
        val floor = measureDrawnArea(arcore, drawn, false, 0.04f, { _, _ -> 0.5f }, lowerOnRight)
        assertEquals(1f, floor.shown, sampling)
        assertEquals(0.5f, floor.shownOverLower, sampling)
    }

    @Test
    fun refutedCellsAreNotDrawnAndOffScreenOnesNotCounted() {
        val area = measureDrawnArea(
            arcore, drawn, true, 0.04f,
            coverageAt = { x, _ -> if (x < 0f) 0f else 1f },
            depthAt = { _, z -> if (z > 0f) DepthVerdict.OUT_OF_VIEW else DepthVerdict.AGREES },
        )
        assertEquals(0.5f, area.insideRefuted, sampling)
        // On screen (z < 0), only x >= 0 is drawn: 0.8 m x 0.8 m.
        assertEquals(0.64f, area.shown, sampling)
    }

    // Camera at the origin, 1.2 m above a floor, looking along -z with a 90-degree field of view.
    private val view = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
    private val projection = floatArrayOf(
        1f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, -1.0033f, -1f,
        0f, 0f, -0.1003f, 0f,
    )
    private val ndcToUv = floatArrayOf(0.5f, 0f, 0.5f, 0f, -0.5f, 0.5f)

    private fun verdictWithDepth(realDepth: Float, z: Float = -2f) =
        depthVerdict(0f, -1.2f, z, view, 0f, projection, ndcToUv, 0.03f) { _, _ -> realDepth }

    @Test
    fun depthVerdictComparesTheRealSurfaceWithThePlane() {
        assertEquals(DepthVerdict.AGREES, verdictWithDepth(2f))
        assertEquals(DepthVerdict.AGREES, verdictWithDepth(2.05f))
        assertEquals(DepthVerdict.LOWER, verdictWithDepth(3f))
        assertEquals(DepthVerdict.IN_FRONT, verdictWithDepth(1.5f))
        assertEquals(DepthVerdict.NO_DEPTH, verdictWithDepth(0f))
        assertEquals(DepthVerdict.OUT_OF_VIEW, verdictWithDepth(2f, z = 1f))
    }
}
