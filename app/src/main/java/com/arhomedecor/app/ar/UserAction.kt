package com.arhomedecor.app.ar

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Something the user did that changes the AR scene. Actions are applied in the frame loop, and
 * recorded into session recordings so that playback repeats them at the same moments.
 */
sealed interface UserAction {
    /** A tap on the camera view; [x] and [y] are fractions of the view's width and height. */
    data class Tap(val x: Float, val y: Float) : UserAction

    data class SetMode(val mode: PlacementMode) : UserAction

    data object ClearWalls : UserAction

    /** The user saw something wrong at this moment; the session's recording is kept. */
    data object MarkProblem : UserAction
}

// Byte layout: format version, action type, then the action's fields (little-endian).
private const val FORMAT_VERSION: Byte = 1
private const val TYPE_TAP: Byte = 1
private const val TYPE_SET_MODE: Byte = 2
private const val TYPE_CLEAR_WALLS: Byte = 3
private const val TYPE_MARK_PROBLEM: Byte = 4

fun UserAction.encode(): ByteBuffer {
    val buffer = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
    buffer.put(FORMAT_VERSION)
    when (this) {
        is UserAction.Tap -> buffer.put(TYPE_TAP).putFloat(x).putFloat(y)
        is UserAction.SetMode -> buffer.put(TYPE_SET_MODE).put(mode.ordinal.toByte())
        UserAction.ClearWalls -> buffer.put(TYPE_CLEAR_WALLS)
        UserAction.MarkProblem -> buffer.put(TYPE_MARK_PROBLEM)
    }
    buffer.flip()
    return buffer
}

/** The action in [data], or null if it is not one this version of the app understands. */
fun decodeUserAction(data: ByteBuffer): UserAction? {
    val buffer = data.duplicate().order(ByteOrder.LITTLE_ENDIAN)
    if (buffer.remaining() < 2 || buffer.get() != FORMAT_VERSION) return null
    return when (buffer.get()) {
        TYPE_TAP -> if (buffer.remaining() >= 8) UserAction.Tap(buffer.getFloat(), buffer.getFloat()) else null
        TYPE_SET_MODE -> if (buffer.hasRemaining()) {
            PlacementMode.entries.getOrNull(buffer.get().toInt())?.let(UserAction::SetMode)
        } else {
            null
        }
        TYPE_CLEAR_WALLS -> UserAction.ClearWalls
        TYPE_MARK_PROBLEM -> UserAction.MarkProblem
        else -> null
    }
}
