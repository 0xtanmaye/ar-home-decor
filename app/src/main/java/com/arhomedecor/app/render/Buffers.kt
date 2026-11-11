package com.arhomedecor.app.render

import android.content.Context
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer

/** Filament reads data from direct, native-order NIO buffers. */
internal fun Context.readAssetToDirectBuffer(path: String): ByteBuffer {
    val bytes = assets.open(path).use { it.readBytes() }
    return ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).put(bytes).apply { flip() }
}

internal fun floatBufferOf(values: FloatArray): FloatBuffer =
    ByteBuffer.allocateDirect(values.size * Float.SIZE_BYTES)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .put(values)
        .apply { flip() }

internal fun shortBufferOf(values: ShortArray): ShortBuffer =
    ByteBuffer.allocateDirect(values.size * Short.SIZE_BYTES)
        .order(ByteOrder.nativeOrder())
        .asShortBuffer()
        .put(values)
        .apply { flip() }
