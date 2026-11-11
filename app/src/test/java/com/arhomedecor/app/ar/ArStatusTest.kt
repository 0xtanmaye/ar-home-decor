package com.arhomedecor.app.ar

import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import org.junit.Assert.assertEquals
import org.junit.Test

class ArStatusTest {

    private val tracking = ArStatus(tracking = TrackingState.TRACKING)

    @Test
    fun errorOverridesEverything() {
        val status = tracking.copy(floors = 2, cubeDistanceMetres = 1f, error = "Camera unavailable")
        assertEquals("Camera unavailable", hintFor(status, 50))
    }

    @Test
    fun resetNoticeTakesPriorityOverNormalHints() {
        val status = tracking.copy(floors = 0, notice = MAP_RESET_NOTICE)
        assertEquals(MAP_RESET_NOTICE, hintFor(status, 50))
        assertEquals("Camera unavailable", hintFor(status.copy(error = "Camera unavailable"), 50))
    }

    @Test
    fun trackingFailureExplainsWhatToDo() {
        val status = ArStatus(tracking = TrackingState.PAUSED, failureReason = TrackingFailureReason.INSUFFICIENT_LIGHT)
        assertEquals("Too dark: turn on more lights.", hintFor(status, 50))
    }

    @Test
    fun asksToScanFloorBeforeAnyFloorIsFound() {
        assertEquals("Slowly move the phone while pointing at the floor.", hintFor(tracking, 50))
    }

    @Test
    fun asksToPlaceCubeOnceFloorIsFound() {
        assertEquals("Tap the floor to place a 50 cm reference cube.", hintFor(tracking.copy(floors = 1), 50))
    }

    @Test
    fun reportsCubeDistanceOncePlaced() {
        val status = tracking.copy(floors = 1, cubeDistanceMetres = 1.234f)
        assertEquals("Cube is 1.23 m away. Tap elsewhere to move it.", hintFor(status, 50))
    }

    @Test
    fun wallModeWalksThroughTheTwoTaps() {
        val wallMode = tracking.copy(mode = PlacementMode.WALL)
        assertEquals("Scan the floor first: walls are marked where they meet the floor.", hintFor(wallMode, 50))
        assertEquals("Tap the floor right where it meets the wall.", hintFor(wallMode.copy(floors = 1), 50))
        assertEquals(
            "Now tap another point along the bottom of the same wall.",
            hintFor(wallMode.copy(floors = 1, wallStartPlaced = true), 50),
        )
    }

    @Test
    fun trackingLabelDescribesFailureReason() {
        val status = ArStatus(tracking = TrackingState.PAUSED, failureReason = TrackingFailureReason.EXCESSIVE_MOTION)
        assertEquals("lost (excessive motion)", trackingLabel(status))
        assertEquals("starting", trackingLabel(ArStatus(tracking = TrackingState.PAUSED)))
    }

    @Test
    fun fpsCounterReportsFramesPerSecond() {
        val counter = FpsCounter()
        for (i in 0..60L) counter.onFrame(i * 1_000_000_000L / 60)
        assertEquals(60, counter.fps)
    }

    @Test
    fun fpsCounterTracksRateChanges() {
        val counter = FpsCounter()
        for (i in 0..60L) counter.onFrame(i * 1_000_000_000L / 60)
        val start = 1_000_000_000L
        for (i in 1..30L) counter.onFrame(start + i * 1_000_000_000L / 30)
        assertEquals(30, counter.fps)
    }

    @Test
    fun fpsCounterIsZeroBeforeFirstFullSecond() {
        val counter = FpsCounter()
        counter.onFrame(0)
        counter.onFrame(16_000_000)
        assertEquals(0, counter.fps)
    }
}
