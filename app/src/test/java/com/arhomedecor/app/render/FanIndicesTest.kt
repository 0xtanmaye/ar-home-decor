package com.arhomedecor.app.render

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class FanIndicesTest {

    @Test
    fun triangleIsASingleFace() {
        assertArrayEquals(shortArrayOf(0, 1, 2), fanIndices(3))
    }

    @Test
    fun pentagonFansOutFromFirstVertex() {
        assertArrayEquals(shortArrayOf(0, 1, 2, 0, 2, 3, 0, 3, 4), fanIndices(5))
    }

    @Test
    fun indexCountIsThreePerTriangle() {
        for (n in 3..64) assertEquals((n - 2) * 3, fanIndices(n).size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsDegeneratePolygons() {
        fanIndices(2)
    }
}
