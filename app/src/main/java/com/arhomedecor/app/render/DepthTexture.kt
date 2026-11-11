package com.arhomedecor.app.render

import com.google.android.filament.Engine
import com.google.android.filament.Texture
import com.google.ar.core.Frame
import com.google.ar.core.exceptions.NotYetAvailableException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.ShortBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executor

/**
 * Uploads ARCore's depth image (metres, 0 = no measurement) to a Filament texture each time a
 * new one arrives, and keeps a CPU copy for measurements.
 *
 * The texture is half-float (R16F) because, unlike 32-bit float, it can be sampled with linear
 * filtering on every OpenGL ES 3 device, which keeps depth-trimmed edges smooth. Half precision
 * is about 1 mm at 1 m and 4 mm at 4 m, well below the depth image's own noise.
 *
 * The depth image covers the same view as the camera texture, so camera texture UVs address it.
 */
// Texture.PixelBufferDescriptor is deprecated in favour of the top-level PixelBufferDescriptor,
// but in Filament 1.77 Texture.setImage() only accepts the nested type.
@Suppress("DEPRECATION")
class DepthTexture(private val engine: Engine) {
    var texture: Texture = buildTexture(1, 1).also {
        it.setImage(engine, 0, Texture.PixelBufferDescriptor(halfBuffer(1), Texture.Format.R, Texture.Type.HALF))
    }
        private set

    var width = 0
        private set
    var height = 0
        private set

    /** True once at least one depth image has been received. */
    var available = false
        private set

    /** CPU copy of the latest depth image in metres, row-major, [width] x [height]. */
    var metres = FloatArray(0)
        private set

    private var lastTimestamp = -1L

    // Upload buffers are returned here by Filament once it has copied them, so a steady
    // stream of depth images doesn't keep allocating native memory.
    private val freeBuffers = ConcurrentLinkedQueue<ShortBuffer>()
    private val runOnFilamentThread = Executor { it.run() }

    /** Uploads the frame's depth image if it is new. Returns true if one was uploaded. */
    fun update(frame: Frame): Boolean {
        val image = try {
            frame.acquireDepthImage16Bits()
        } catch (e: NotYetAvailableException) {
            return false
        } catch (e: IllegalStateException) {
            return false // depth is disabled for this session
        }
        image.use {
            if (it.timestamp == lastTimestamp) return false
            lastTimestamp = it.timestamp

            if (it.width != width || it.height != height) resize(it.width, it.height)
            val plane = it.planes[0]
            val data = plane.buffer.order(ByteOrder.LITTLE_ENDIAN)
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            for (y in 0 until height) {
                val row = y * rowStride
                for (x in 0 until width) {
                    val millimetres = data.getShort(row + x * pixelStride).toInt() and 0xFFFF
                    metres[y * width + x] = millimetres / 1000f
                }
            }
        }

        val upload = freeBuffers.poll()?.takeIf { it.capacity() == metres.size } ?: halfBuffer(metres.size)
        upload.clear()
        for (value in metres) upload.put(floatToHalf(value))
        upload.flip()
        texture.setImage(
            engine,
            0,
            Texture.PixelBufferDescriptor(upload, Texture.Format.R, Texture.Type.HALF, runOnFilamentThread) {
                freeBuffers.offer(upload)
            },
        )
        available = true
        return true
    }

    /** Depth in metres at camera-texture UV ([u], [v] in 0..1), or 0 if unknown. */
    fun depthAt(u: Float, v: Float): Float {
        if (!available || u < 0f || u >= 1f || v < 0f || v >= 1f) return 0f
        return metres[(v * height).toInt() * width + (u * width).toInt()]
    }

    fun destroy() {
        engine.destroyTexture(texture)
    }

    private fun resize(newWidth: Int, newHeight: Int) {
        engine.destroyTexture(texture)
        texture = buildTexture(newWidth, newHeight)
        width = newWidth
        height = newHeight
        metres = FloatArray(newWidth * newHeight)
        freeBuffers.clear()
    }

    private fun buildTexture(textureWidth: Int, textureHeight: Int): Texture = Texture.Builder()
        .width(textureWidth)
        .height(textureHeight)
        .levels(1)
        .sampler(Texture.Sampler.SAMPLER_2D)
        .format(Texture.InternalFormat.R16F)
        .build(engine)

    private fun halfBuffer(size: Int): ShortBuffer =
        ByteBuffer.allocateDirect(size * Short.SIZE_BYTES).order(ByteOrder.nativeOrder()).asShortBuffer()
}

/**
 * IEEE 754 half-precision bits for [value], rounding to nearest. Written out because
 * android.util.Half needs API 26 and the app supports API 24. Depth values are small positive
 * numbers, but the full range is handled: overflow becomes infinity, tiny values become
 * subnormals or zero.
 */
internal fun floatToHalf(value: Float): Short {
    val bits = java.lang.Float.floatToRawIntBits(value)
    val sign = (bits ushr 16) and 0x8000
    val exponent = ((bits ushr 23) and 0xFF) - 127 + 15
    var mantissa = bits and 0x7FFFFF

    if (exponent >= 0x1F) {
        // Overflow, infinity or NaN.
        val isNaN = ((bits ushr 23) and 0xFF) == 0xFF && mantissa != 0
        return (sign or 0x7C00 or (if (isNaN) 0x200 else 0)).toShort()
    }
    if (exponent <= 0) {
        if (exponent < -10) return sign.toShort() // too small: signed zero
        // Subnormal: shift the mantissa (with its implicit leading 1) into place, rounding.
        mantissa = mantissa or 0x800000
        val shift = 14 - exponent
        val rounded = (mantissa + (1 shl (shift - 1))) ushr shift
        return (sign or rounded).toShort()
    }
    // Normal: round the 23-bit mantissa to 10 bits; a carry correctly bumps the exponent.
    val half = (exponent shl 10) or (mantissa ushr 13)
    val rounded = half + ((mantissa ushr 12) and 1)
    return (sign or rounded).toShort()
}
