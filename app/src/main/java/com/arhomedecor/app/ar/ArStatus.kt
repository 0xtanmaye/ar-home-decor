package com.arhomedecor.app.ar

import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import java.util.Locale

/** Snapshot of the AR session shown in the on-screen debug panel. */
data class ArStatus(
    val fps: Int = 0,
    val tracking: TrackingState = TrackingState.PAUSED,
    val failureReason: TrackingFailureReason = TrackingFailureReason.NONE,
    val floors: Int = 0,
    /** Tables, beds and other upward-facing surfaces above the floor. */
    val raisedSurfaces: Int = 0,
    val walls: Int = 0,
    /** Height of the phone's camera above the floor, or null until a floor is found. */
    val cameraHeightMetres: Float? = null,
    /** Distance from the camera to the reference cube, or null if it hasn't been placed. */
    val cubeDistanceMetres: Float? = null,
    /** A blocking problem (e.g. ARCore missing); overrides the normal hint. */
    val error: String? = null,
)

fun trackingLabel(status: ArStatus): String = when (status.tracking) {
    TrackingState.TRACKING -> "tracking"
    TrackingState.PAUSED -> if (status.failureReason == TrackingFailureReason.NONE) {
        "starting"
    } else {
        "lost (${status.failureReason.name.lowercase(Locale.US).replace('_', ' ')})"
    }
    TrackingState.STOPPED -> "stopped"
}

/** The single instruction shown to the user for the current state. */
fun hintFor(status: ArStatus, cubeEdgeCentimetres: Int): String {
    status.error?.let { return it }
    if (status.tracking != TrackingState.TRACKING) {
        return when (status.failureReason) {
            TrackingFailureReason.INSUFFICIENT_LIGHT -> "Too dark: turn on more lights."
            TrackingFailureReason.EXCESSIVE_MOTION -> "Move the phone more slowly."
            TrackingFailureReason.INSUFFICIENT_FEATURES -> "Point at a surface with more detail or texture."
            TrackingFailureReason.CAMERA_UNAVAILABLE -> "Camera is in use by another app."
            TrackingFailureReason.BAD_STATE -> "AR tracking failed. Close and reopen this screen."
            TrackingFailureReason.NONE -> "Move the phone slowly to start tracking."
        }
    }
    if (status.floors == 0) return "Slowly move the phone while pointing at the floor."
    val distance = status.cubeDistanceMetres
        ?: return "Tap the floor to place a $cubeEdgeCentimetres cm reference cube."
    return String.format(Locale.US, "Cube is %.2f m away. Tap elsewhere to move it.", distance)
}

/** Frames per second, measured over windows of at least one second. */
class FpsCounter {
    private var windowStartNanos = -1L
    private var framesInWindow = 0

    var fps = 0
        private set

    fun onFrame(frameTimeNanos: Long) {
        if (windowStartNanos < 0) {
            // The first frame only marks the start; frames are counted as intervals after it.
            windowStartNanos = frameTimeNanos
            return
        }
        framesInWindow++
        val elapsed = frameTimeNanos - windowStartNanos
        if (elapsed >= NANOS_PER_SECOND) {
            fps = Math.round(framesInWindow * NANOS_PER_SECOND.toDouble() / elapsed).toInt()
            windowStartNanos = frameTimeNanos
            framesInWindow = 0
        }
    }

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000L
    }
}
