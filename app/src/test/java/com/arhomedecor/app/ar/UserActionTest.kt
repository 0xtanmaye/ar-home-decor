package com.arhomedecor.app.ar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer

class UserActionTest {

    @Test
    fun everyActionSurvivesEncoding() {
        val actions = listOf(
            UserAction.Tap(0.25f, 0.875f),
            UserAction.SetMode(PlacementMode.WALL),
            UserAction.SetMode(PlacementMode.CUBE),
            UserAction.ClearWalls,
            UserAction.MarkProblem,
        )
        for (action in actions) assertEquals(action, decodeUserAction(action.encode()))
    }

    @Test
    fun decodingDoesNotConsumeTheBuffer() {
        val buffer = UserAction.Tap(0.5f, 0.5f).encode()
        decodeUserAction(buffer)
        assertEquals(UserAction.Tap(0.5f, 0.5f), decodeUserAction(buffer))
    }

    @Test
    fun unknownOrTruncatedDataIsIgnored() {
        assertNull(decodeUserAction(ByteBuffer.wrap(byteArrayOf())))
        assertNull(decodeUserAction(ByteBuffer.wrap(byteArrayOf(99, 1)))) // future format version
        assertNull(decodeUserAction(ByteBuffer.wrap(byteArrayOf(1, 42)))) // unknown action
        assertNull(decodeUserAction(ByteBuffer.wrap(byteArrayOf(1, 1, 0, 0)))) // tap cut short
        assertNull(decodeUserAction(ByteBuffer.wrap(byteArrayOf(1, 2, 9)))) // unknown mode
    }
}
