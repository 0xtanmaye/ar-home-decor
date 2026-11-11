package com.arhomedecor.app.ar

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionRecorderTest {

    @Test
    fun onlyTheNewestUnmarkedRecordingsAreKept() {
        val names = listOf(
            "session-20261112-090000.mp4",
            "session-20261112-091500.mp4",
            "session-20261112-080000-marked.mp4",
            "session-20261112-093000.mp4",
            "session-20261112-084500.mp4",
        )
        assertEquals(
            listOf("session-20261112-084500.mp4", "session-20261112-090000.mp4"),
            unmarkedRecordingsToDelete(names, keep = 2),
        )
    }

    @Test
    fun markedAndOlderManualRecordingsAreNeverDeleted() {
        val names = listOf("rec-20261111-201051.mp4", "session-20261112-080000-marked.mp4", "notes.txt")
        assertEquals(emptyList<String>(), unmarkedRecordingsToDelete(names, keep = 0))
    }

    @Test
    fun markedRecordingKeepsItsStartTimeInItsName() {
        assertEquals("session-20261112-090000-marked.mp4", markedRecordingName("session-20261112-090000.mp4"))
    }
}
