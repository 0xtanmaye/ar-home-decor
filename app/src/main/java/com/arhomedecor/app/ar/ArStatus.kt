package com.arhomedecor.app.ar

import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import java.util.Locale

/** What a tap on the camera view does. */
enum class PlacementMode {
    /** Place the reference cube on the floor or a raised surface. */
    CUBE,

    /** Mark a wall by tapping two points where it meets the floor. */
    WALL,
}

/** Snapshot of the AR session shown in the on-screen debug panel. */
data class ArStatus(
    val mode: PlacementMode = PlacementMode.CUBE,
    /** In wall mode: the first point along the wall's base has been tapped. */
    val wallStartPlaced: Boolean = false,
    val markedWalls: Int = 0,
    /** Marked walls relative to the real walls measured by depth; positive = in front. */
    val wallOffsetMetres: Float? = null,
    /** Share of wall samples that saw the wall itself rather than something in front. */
    val wallFitInliers: Float? = null,
    val fps: Int = 0,
    val tracking: TrackingState = TrackingState.PAUSED,
    val failureReason: TrackingFailureReason = TrackingFailureReason.NONE,
    val floors: Int = 0,
    /** Tables, beds and other upward-facing surfaces above the floor. */
    val raisedSurfaces: Int = 0,
    val walls: Int = 0,
    /** Height of the phone's camera above the floor, or null until a floor is found. */
    val cameraHeightMetres: Float? = null,
    /** True when ARCore depth images are arriving (supported devices only). */
    val depthActive: Boolean = false,
    /** Floor plane height relative to the real floor measured by depth; positive = above. */
    val floorOffsetMetres: Float? = null,
    /** Share of floor samples that saw the floor itself rather than something on it. */
    val floorFitInliers: Float? = null,
    /** Distance from the camera to the reference cube, or null if it hasn't been placed. */
    val cubeDistanceMetres: Float? = null,
    /** A blocking problem (e.g. ARCore missing); overrides the normal hint. */
    val error: String? = null,
    /** A short-lived message about something that just happened, e.g. a tracking reset. */
    val notice: String? = null,
    /**
     * ARCore lost its place and has put earlier surfaces and marks on hold; they return when
     * the camera sees an already-scanned area.
     */
    val mapOnHold: Boolean = false,
    /** Debuggable build: sessions are recorded, and problems can be marked in them. */
    val devTools: Boolean = false,
    /** Seconds recorded so far, or null when not recording. */
    val recordingSeconds: Int? = null,
    /** A problem has been marked in the recording in progress, so it will be kept. */
    val problemMarked: Boolean = false,
    /** The recording stopped at its length limit; the session continues unrecorded. */
    val recordingLimitReached: Boolean = false,
    /** Name of the recording being played back instead of the camera, or null when live. */
    val playback: String? = null,
)

const val MAP_ON_HOLD_HINT =
    "Lost track of part of the room. Point the camera back at an area you've already scanned to restore it."

const val MAP_RESET_NOTICE = "Tracking was reset, so detected surfaces were cleared. Scan the floor again."
const val PLAYBACK_FINISHED_NOTICE = "Playback finished."
const val WALL_TAPS_TOO_CLOSE_NOTICE = "Those points are too close together. Tap further along the wall."
const val PROBLEM_MARKED_NOTICE = "Problem marked. This session's recording will be kept."
const val PROBLEM_MARKED_IN_PLAYBACK_NOTICE = "A problem was marked here."
const val NOT_RECORDING_NOTICE =
    "This session isn't being recorded any more. Leave this screen and open it again to record a new one."

/**
 * "live", "recording 1:05", "recording 1:05, problem marked", "not recorded after 5:00" or
 * "playback session-….mp4".
 */
fun sessionLabel(status: ArStatus): String {
    status.playback?.let { return "playback $it" }
    if (status.recordingLimitReached) {
        val limit = SessionRecorder.MAX_RECORDING_SECONDS
        return String.format(Locale.US, "not recorded after %d:%02d", limit / 60, limit % 60)
    }
    val seconds = status.recordingSeconds ?: return "live"
    val marked = if (status.problemMarked) ", problem marked" else ""
    return String.format(Locale.US, "recording %d:%02d%s", seconds / 60, seconds % 60, marked)
}

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
    status.notice?.let { return it }
    if (status.tracking == TrackingState.TRACKING && status.mapOnHold) return MAP_ON_HOLD_HINT
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
    if (status.floors == 0) {
        return if (status.mode == PlacementMode.WALL) {
            "Scan the floor first: walls are marked where they meet the floor."
        } else {
            "Slowly move the phone while pointing at the floor."
        }
    }
    if (status.mode == PlacementMode.WALL) {
        return if (status.wallStartPlaced) {
            "Now tap another point along the bottom of the same wall."
        } else {
            "Tap the floor right where it meets the wall."
        }
    }
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
