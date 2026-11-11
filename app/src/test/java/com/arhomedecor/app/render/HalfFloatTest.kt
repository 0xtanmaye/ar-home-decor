package com.arhomedecor.app.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.pow

class HalfFloatTest {

    private fun bits(value: Float) = floatToHalf(value).toInt() and 0xFFFF

    /** Reference decoder, independent of the encoder under test. */
    private fun halfToFloat(half: Int): Float {
        val sign = if (half and 0x8000 != 0) -1f else 1f
        val exponent = (half ushr 10) and 0x1F
        val mantissa = half and 0x3FF
        return when (exponent) {
            0 -> sign * mantissa * 2f.pow(-24)
            0x1F -> if (mantissa == 0) sign * Float.POSITIVE_INFINITY else Float.NaN
            else -> sign * (1f + mantissa / 1024f) * 2f.pow(exponent - 15)
        }
    }

    @Test
    fun exactValuesHaveKnownBitPatterns() {
        assertEquals(0x0000, bits(0f))
        assertEquals(0x3C00, bits(1f))
        assertEquals(0x3800, bits(0.5f))
        assertEquals(0x4000, bits(2f))
        assertEquals(0x3E00, bits(1.5f))
        assertEquals(0xC000, bits(-2f))
        assertEquals(0x7BFF, bits(65504f)) // largest half
    }

    @Test
    fun roundsToNearest() {
        assertEquals(0x1419, bits(0.001f))
        assertEquals(0x3CF0, bits(1.234f))
    }

    @Test
    fun overflowAndTinyValuesAreHandled() {
        assertEquals(0x7C00, bits(1e6f)) // infinity
        assertEquals(0x0001, bits(2f.pow(-24))) // smallest subnormal
        assertEquals(0x0000, bits(1e-10f)) // underflows to zero
        assertEquals(0x7C00, bits(Float.POSITIVE_INFINITY))
        assertTrue(halfToFloat(bits(Float.NaN)).isNaN())
    }

    @Test
    fun depthRangeKeepsMillimetrePrecision() {
        // Depth images cover roughly 0 to 8 m; half precision there is within 4 mm.
        var depth = 0.1f
        while (depth <= 8f) {
            val error = abs(halfToFloat(bits(depth)) - depth)
            assertTrue("error $error m at $depth m", error <= 0.004f)
            depth += 0.0137f
        }
    }
}
