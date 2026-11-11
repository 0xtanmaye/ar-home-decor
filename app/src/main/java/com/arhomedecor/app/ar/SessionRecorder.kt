package com.arhomedecor.app.ar

import android.net.Uri
import android.util.Log
import com.google.ar.core.Frame
import com.google.ar.core.PlaybackStatus
import com.google.ar.core.RecordingConfig
import com.google.ar.core.RecordingStatus
import com.google.ar.core.Session
import com.google.ar.core.Track
import com.google.ar.core.exceptions.PlaybackFailedException
import com.google.ar.core.exceptions.RecordingFailedException
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Development tool built on ARCore's Recording & Playback API. A recording holds the camera
 * images and motion sensor data of a session plus the user's [UserAction]s; playing it back runs
 * ARCore on the recorded data instead of the live camera, so a problem seen once can be replayed
 * and compared before and after a change. ARCore recomputes tracking, planes and depth during
 * playback, so results are close to the original but not guaranteed identical.
 *
 * Debug builds record every live AR session from its start ([startRecording] on resume), so
 * that a problem can be replayed whenever it appears, without anyone having to start a recording
 * beforehand. When the user marks a problem ([markProblem]), the session's file is kept (renamed
 * to end in "-marked.mp4"); of the unmarked ones, only the latest [KEEP_UNMARKED] are kept.
 *
 * Recordings are video of the user's surroundings, so they stay in the app's private storage;
 * tools/pull-recordings.sh copies them from a debug build.
 */
class SessionRecorder(private val directory: File) {

    /** File name of the recording in progress, or null. */
    var recordingName: String? = null
        private set

    /** The user has marked a problem in the recording in progress, so it will be kept. */
    var problemMarked = false
        private set

    /** File name of the recording being played back, or null for a live session. */
    var playbackName: String? = null
        private set

    val isPlayingBack: Boolean get() = playbackName != null

    /** Actions ARCore could not take yet, oldest first (see [record]). */
    private val unrecorded = ArrayDeque<UserAction>()
    private var failedFrames = 0

    /**
     * Starts recording a session (from its next frame) into a new file, first deleting older
     * unmarked recordings so that, with this one, [KEEP_UNMARKED] remain. Returns false (and
     * logs why) if it could not start.
     */
    fun startRecording(session: Session): Boolean {
        if (isPlayingBack || recordingName != null) return false
        directory.mkdirs()
        for (old in unmarkedRecordingsToDelete(directory.list()?.toList().orEmpty(), keep = KEEP_UNMARKED - 1)) {
            if (File(directory, old).delete()) Log.i(TAG, "deleted older recording: $old")
        }
        val name = "$RECORDING_PREFIX${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.mp4"
        val config = RecordingConfig(session)
            .setMp4DatasetUri(Uri.fromFile(File(directory, name)))
            // Pausing ends the recording: playback can't represent the gap anyway.
            .setAutoStopOnPause(true)
            .addTrack(Track(session).setId(ACTIONS_TRACK_ID).setMimeType(ACTIONS_MIME_TYPE))
        return try {
            session.startRecording(config)
            recordingName = name
            problemMarked = false
            Log.i(TAG, "recording started: $name")
            true
        } catch (e: RecordingFailedException) {
            Log.e(TAG, "could not start recording", e)
            false
        } catch (e: IllegalStateException) {
            Log.e(TAG, "could not start recording", e)
            false
        }
    }

    fun stopRecording(session: Session) {
        val name = recordingName ?: return
        try {
            session.stopRecording()
        } catch (e: RecordingFailedException) {
            Log.e(TAG, "could not stop recording cleanly", e)
        }
        Log.i(TAG, "recording stopped: $name")
        finish(name)
    }

    /**
     * Notes that the user saw a problem: the recording in progress will be kept. The moment
     * itself goes into the recording as a [UserAction.MarkProblem]. Returns false if nothing is
     * being recorded.
     */
    fun markProblem(): Boolean {
        val name = recordingName ?: return false
        problemMarked = true
        Log.i(TAG, "problem marked in $name")
        return true
    }

    /** Once a recording has ended: renames it if a problem was marked in it. */
    private fun finish(name: String) {
        recordingName = null
        dropUnrecorded()
        if (problemMarked) {
            val marked = markedRecordingName(name)
            if (File(directory, name).renameTo(File(directory, marked))) {
                Log.i(TAG, "kept as $marked")
            } else {
                Log.w(TAG, "could not rename $name to $marked")
            }
        }
        problemMarked = false
    }

    /**
     * Notices a recording that ARCore ended by itself (the session was paused, or writing
     * failed). Call once per frame.
     */
    fun checkRecording(session: Session) {
        val name = recordingName ?: return
        val status = session.recordingStatus
        if (status != RecordingStatus.OK) {
            Log.i(TAG, "recording ended ($status): $name")
            finish(name)
        }
    }

    /**
     * Sets [session], which must not have been resumed yet, to play [name] back instead of
     * using the camera. Returns false (and logs why) if the file can't be played.
     */
    fun startPlayback(session: Session, name: String): Boolean {
        val file = File(directory, name)
        if (!file.isFile) {
            Log.e(TAG, "no recording named $name in $directory")
            return false
        }
        return try {
            session.setPlaybackDatasetUri(Uri.fromFile(file))
            playbackName = name
            Log.i(TAG, "playback started: $name")
            true
        } catch (e: PlaybackFailedException) {
            Log.e(TAG, "could not play $name", e)
            false
        }
    }

    /** True once a playback has reached the end of its recording (or failed to read it). */
    fun playbackEnded(session: Session): Boolean =
        isPlayingBack && session.playbackStatus != PlaybackStatus.OK

    /**
     * Adds [actions] to the recording at [frame], if one is in progress. Call every frame, even
     * with no actions: ARCore refuses track data for the first frames of a recording and while
     * briefly overloaded, and asks for another try on the next frame, so refused actions wait
     * here (in order) and go into a slightly later frame.
     */
    fun record(frame: Frame, actions: List<UserAction>) {
        if (recordingName == null) return
        unrecorded.addAll(actions)
        while (unrecorded.isNotEmpty()) {
            try {
                frame.recordTrackData(ACTIONS_TRACK_ID, unrecorded.first().encode())
            } catch (e: IllegalStateException) {
                if (++failedFrames >= MAX_FAILED_FRAMES) {
                    Log.w(TAG, "ARCore refused track data for $failedFrames frames", e)
                    dropUnrecorded()
                }
                return
            }
            unrecorded.removeFirst()
            failedFrames = 0
        }
    }

    private fun dropUnrecorded() {
        if (unrecorded.isNotEmpty()) Log.w(TAG, "${unrecorded.size} actions not recorded: $unrecorded")
        unrecorded.clear()
        failedFrames = 0
    }

    /** Actions recorded at [frame]'s moment, when playing back. */
    fun recordedActions(frame: Frame): List<UserAction> {
        if (!isPlayingBack) return emptyList()
        return frame.getUpdatedTrackData(ACTIONS_TRACK_ID).mapNotNull { trackData ->
            try {
                decodeUserAction(trackData.data)
            } finally {
                trackData.close()
            }
        }
    }

    companion object {
        private const val TAG = "ArRecording"

        /** Identifies the user-actions track; fixed so every recording can be played back. */
        val ACTIONS_TRACK_ID: UUID = UUID.fromString("6f0f2d3c-8a52-4c1e-9b7e-3d2a5e9c4b10")
        private const val ACTIONS_MIME_TYPE = "application/x-arhomedecor-actions"

        /** About two seconds at 30 FPS: much longer than ARCore's "a few frames". */
        private const val MAX_FAILED_FRAMES = 60

        /** Unmarked recordings kept, including the one in progress. */
        const val KEEP_UNMARKED = 3

        /**
         * Recordings stop after this long (about 1 MB a second, so up to 300 MB), so that a
         * forgotten session doesn't fill the phone. Problems usually show within a minute or two.
         */
        const val MAX_RECORDING_SECONDS = 300

        const val RECORDING_PREFIX = "session-"
    }
}

/** The name a recording is kept under once a problem has been marked in it. */
internal fun markedRecordingName(name: String): String = name.removeSuffix(".mp4") + "-marked.mp4"

/**
 * Of the automatic recordings in [names] (file names in the recordings folder), the unmarked ones
 * to delete so that only the newest [keep] remain. Marked recordings, and any other files (such as
 * recordings made before recording was automatic), are never deleted.
 */
internal fun unmarkedRecordingsToDelete(names: List<String>, keep: Int): List<String> {
    val unmarked = names
        .filter { it.startsWith(SessionRecorder.RECORDING_PREFIX) && it.endsWith(".mp4") && !it.endsWith("-marked.mp4") }
        // Names hold the start time (yyyyMMdd-HHmmss), so they sort oldest first.
        .sorted()
    return unmarked.dropLast(keep.coerceAtLeast(0))
}
