package com.arhomedecor.app.ar

import android.Manifest
import android.annotation.SuppressLint
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Choreographer
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.SurfaceView
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import com.arhomedecor.app.device.ArCoreSessionLock
import com.arhomedecor.app.device.DeviceCapabilityChecker
import com.arhomedecor.app.render.ArRenderer
import com.arhomedecor.app.render.ManualWall
import com.arhomedecor.app.render.PlaneKind
import com.arhomedecor.app.render.wallFromFloorPoints
import com.arhomedecor.app.render.wallOutline
import com.google.ar.core.Anchor
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Camera
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.HitResult
import com.google.ar.core.Plane
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.UnavailableApkTooOldException
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import com.google.ar.core.exceptions.UnavailableSdkTooOldException
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Live AR view: camera feed, detected floors/walls, and a reference cube placed by tapping
 * the floor. Everything per-frame runs on the main thread in a Choreographer callback.
 */
class ArActivity : ComponentActivity(), Choreographer.FrameCallback {

    private lateinit var renderer: ArRenderer
    private var session: Session? = null
    private var installRequested = false
    private var running = false

    private var viewportWidth = 0
    private var viewportHeight = 0

    private val pendingTaps = ArrayDeque<MotionEvent>()
    private var mode = PlacementMode.CUBE
    private var cubeAnchor: Anchor? = null
    private val walls = ArrayList<ManualWall>()
    /** First tap along a wall's base, waiting for the second. */
    private var wallStartAnchor: Anchor? = null

    private var lastCameraTimestamp = 0L
    private var frameCpuNanosTotal = 0L
    private var frameCpuNanosMax = 0L
    private var framesTimed = 0

    private var lastTrackingState: TrackingState? = null
    private var lastFailureReason: TrackingFailureReason? = null
    private var visiblePlanes = 0
    private var lastFrameNanos = 0L
    private var notice: String? = null
    private var noticeNanos = 0L

    private val fpsCounter = FpsCounter()
    private val floorProbe = FloorDepthProbe()
    private var floorProbeResult: FloorDepthProbe.Result? = null
    private val wallProbe = WallDepthProbe()
    private var wallProbeResult: FloorDepthProbe.Result? = null
    private var lastStatusNanos = 0L
    private var lastLogNanos = 0L
    private val status = MutableStateFlow(ArStatus())

    private val choreographer: Choreographer = Choreographer.getInstance()

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) showError("Camera access is needed for live AR.")
    }

    @SuppressLint("ClickableViewAccessibility") // Taps place objects in 3D; there is no 2D click target.
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val surfaceView = SurfaceView(this)
        renderer = ArRenderer(this, surfaceView) { width, height ->
            viewportWidth = width
            viewportHeight = height
            applyDisplayGeometry()
        }

        val gestures = GestureDetector(
            this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent) = true
                override fun onSingleTapUp(e: MotionEvent): Boolean {
                    pendingTaps.addLast(MotionEvent.obtain(e))
                    return true
                }
            },
        )
        surfaceView.setOnTouchListener { _, event -> gestures.onTouchEvent(event) }

        val overlay = ComposeView(this).apply {
            setContent {
                MaterialTheme {
                    val current by status.collectAsState()
                    ArOverlay(current, CUBE_EDGE_CENTIMETRES, ::setMode, ::clearWalls)
                }
            }
        }
        setContentView(
            FrameLayout(this).apply {
                addView(surfaceView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
                addView(overlay, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            },
        )

        if (!DeviceCapabilityChecker.hasCameraPermission(this)) {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onResume() {
        super.onResume()
        if (!DeviceCapabilityChecker.hasCameraPermission(this)) return
        val activeSession = session ?: createSession() ?: return
        try {
            activeSession.resume()
        } catch (e: CameraNotAvailableException) {
            Log.e(TAG, "Camera not available", e)
            showError("The camera is not available. Close other camera apps and try again.")
            return
        }
        running = true
        choreographer.postFrameCallback(this)
    }

    override fun onPause() {
        super.onPause()
        running = false
        choreographer.removeFrameCallback(this)
        session?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        pendingTaps.forEach(MotionEvent::recycle)
        pendingTaps.clear()
        cubeAnchor?.detach()
        cubeAnchor = null
        clearWalls()
        session?.close()
        session = null
        renderer.destroy()
    }

    private fun createSession(): Session? {
        try {
            when (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
                ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                    // Play Store opens; onResume runs again when the user comes back.
                    installRequested = true
                    return null
                }
                ArCoreApk.InstallStatus.INSTALLED -> Unit
            }
            val newSession = synchronized(ArCoreSessionLock) { Session(this) }
            newSession.configure(
                Config(newSession).apply {
                    planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                    // Don't block on the camera: render at display rate with the newest image.
                    updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                    focusMode = Config.FocusMode.AUTO
                    lightEstimationMode = Config.LightEstimationMode.DISABLED
                    // Depth lets planes be checked against the real surface; optional, so
                    // devices without it still get plain plane rendering.
                    depthMode = if (newSession.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                        Config.DepthMode.AUTOMATIC
                    } else {
                        Config.DepthMode.DISABLED
                    }
                },
            )
            renderer.attachSession(newSession)
            session = newSession
            applyDisplayGeometry()
            return newSession
        } catch (e: Exception) {
            Log.e(TAG, "Could not create AR session", e)
            showError(
                when (e) {
                    is UnavailableUserDeclinedInstallationException ->
                        "Google Play Services for AR is needed for live AR."
                    is UnavailableDeviceNotCompatibleException -> "This device doesn't support live AR."
                    is UnavailableApkTooOldException -> "Please update Google Play Services for AR."
                    is UnavailableSdkTooOldException -> "Please update this app."
                    else -> "AR could not start: ${e.message}"
                },
            )
            return null
        }
    }

    private fun applyDisplayGeometry() {
        if (viewportWidth == 0 || viewportHeight == 0) return
        session?.setDisplayGeometry(displayRotation(), viewportWidth, viewportHeight)
    }

    @Suppress("DEPRECATION") // Activity.display needs API 30; minSdk is 24.
    private fun displayRotation(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) display!!.rotation else windowManager.defaultDisplay.rotation

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        choreographer.postFrameCallback(this)
        val activeSession = session ?: return

        val frame = try {
            activeSession.update()
        } catch (e: CameraNotAvailableException) {
            Log.e(TAG, "Camera lost", e)
            showError("The camera was disconnected. Close and reopen this screen.")
            return
        }
        // No camera image yet (first frames after resume), or no new one since the last draw:
        // the camera runs slower than the display, and redrawing the same image and pose would
        // only cost battery and heat.
        if (frame.timestamp == 0L || frame.timestamp == lastCameraTimestamp) return
        lastCameraTimestamp = frame.timestamp
        val cpuStart = System.nanoTime()

        lastFrameNanos = frameTimeNanos
        val camera = frame.camera
        handleTaps(activeSession, frame, camera)
        val content = ArRenderer.Content(
            referenceCubePose = cubeAnchor?.takeIf { it.trackingState == TrackingState.TRACKING }?.pose,
            wallMarkerPose = wallStartAnchor?.takeIf { it.trackingState == TrackingState.TRACKING }?.pose,
            walls = walls,
        )
        renderer.render(activeSession, frame, frameTimeNanos, content)
        logTrackingEvents(camera, frameTimeNanos)

        fpsCounter.onFrame(frameTimeNanos)
        publishStatus(frame, camera, frameTimeNanos)

        val cpuNanos = System.nanoTime() - cpuStart
        frameCpuNanosTotal += cpuNanos
        frameCpuNanosMax = maxOf(frameCpuNanosMax, cpuNanos)
        framesTimed++
    }

    private fun handleTaps(session: Session, frame: Frame, camera: Camera) {
        while (true) {
            val tap = pendingTaps.removeFirstOrNull() ?: return
            try {
                if (camera.trackingState != TrackingState.TRACKING) continue
                when (mode) {
                    PlacementMode.CUBE -> placeCube(frame, tap)
                    PlacementMode.WALL -> markWallPoint(session, frame, camera, tap)
                }
            } finally {
                tap.recycle()
            }
        }
    }

    private fun placeCube(frame: Frame, tap: MotionEvent) {
        val hit = frame.hitTest(tap).firstOrNull(::isHorizontalSurfaceHit) ?: return
        cubeAnchor?.detach()
        cubeAnchor = hit.createAnchor()
        Log.i(TAG, String.format(Locale.US, "Placed reference cube %.2f m from camera", hit.distance))
    }

    /**
     * Wall marking: the first tap on the floor at the wall's base is kept as a marker; the second
     * defines the wall's direction. Floor hits outside ARCore's floor outline are accepted: the
     * floor is flat, and its outline often stops short of a plain wall.
     */
    private fun markWallPoint(session: Session, frame: Frame, camera: Camera, tap: MotionEvent) {
        val hit = frame.hitTest(tap).firstOrNull(::isFloorLevelHit) ?: return
        val floorY = hit.hitPose.ty()
        val point = hit.hitPose.translation
        val start = wallStartAnchor
        if (start == null) {
            wallStartAnchor = session.createAnchor(Pose.makeTranslation(point))
            Log.i(TAG, String.format(Locale.US, "Wall start marked %.2f m from camera", hit.distance))
            return
        }

        val geometry = wallFromFloorPoints(start.pose.translation, point, camera.pose.translation)
        if (geometry == null) {
            showNotice(WALL_TAPS_TOO_CLOSE_NOTICE)
            return
        }
        // Up to the ceiling when one is detected at a plausible height, otherwise a typical room height.
        val height = renderer.ceilingY?.let { it - floorY }?.takeIf { it in 2.0f..4.0f } ?: DEFAULT_WALL_HEIGHT_METRES
        // With depth, the extra width is trimmed back to the real wall's edges; without it, keep close to the taps.
        val margin = if (renderer.depth.available) WALL_MARGIN_WITH_DEPTH_METRES else WALL_MARGIN_METRES
        val anchor = session.createAnchor(Pose.makeTranslation(geometry.midpoint))
        walls += ManualWall(anchor, geometry.alongWall, geometry.normal, wallOutline(geometry.halfLength, margin, height))
        start.detach()
        wallStartAnchor = null
        Log.i(
            TAG,
            String.format(Locale.US, "Marked wall: taps %.2f m apart, height %.2f m", geometry.halfLength * 2, height),
        )
    }

    private fun setMode(newMode: PlacementMode) {
        mode = newMode
        // A half-marked wall makes no sense once the user switches away.
        wallStartAnchor?.detach()
        wallStartAnchor = null
        status.update { it.copy(mode = newMode, wallStartPlaced = false) }
    }

    private fun clearWalls() {
        walls.forEach { it.anchor.detach() }
        walls.clear()
        wallStartAnchor?.detach()
        wallStartAnchor = null
        wallProbeResult = null
        status.update { it.copy(markedWalls = 0, wallStartPlaced = false, wallOffsetMetres = null) }
    }

    private fun showNotice(message: String) {
        notice = message
        noticeNanos = lastFrameNanos
    }

    /**
     * Logs tracking changes as they happen (the stats line is only once a second, so short
     * losses would be missed) and detects ARCore discarding its whole map.
     */
    private fun logTrackingEvents(camera: Camera, frameTimeNanos: Long) {
        val state = camera.trackingState
        val reason = camera.trackingFailureReason
        if (state != lastTrackingState || reason != lastFailureReason) {
            Log.i(EVENT_TAG, "tracking $lastTrackingState/$lastFailureReason -> $state/$reason")
            lastTrackingState = state
            lastFailureReason = reason
        }

        val counts = renderer.planeCounts
        val planesNow = counts.floors + counts.raisedSurfaces + counts.walls + counts.ceilings
        val discarded = renderer.planesDiscardedLastFrame
        if (discarded > 0) {
            Log.i(EVENT_TAG, "ARCore discarded $discarded planes ($visiblePlanes visible before, $planesNow after)")
            // Several planes gone at once and nothing left: ARCore restarted its map.
            if (discarded >= 2 && planesNow == 0) {
                Log.w(EVENT_TAG, "map reset")
                showNotice(MAP_RESET_NOTICE)
            }
        }
        visiblePlanes = planesNow
    }

    /** Floor or table/bed, within its detected outline (the reference cube needs real support). */
    private fun isHorizontalSurfaceHit(hit: HitResult): Boolean {
        val plane = hit.trackable as? Plane ?: return false
        return plane.type == Plane.Type.HORIZONTAL_UPWARD_FACING && plane.isPoseInPolygon(hit.hitPose)
    }

    /** Anywhere on a floor plane, even beyond its detected outline. */
    private fun isFloorLevelHit(hit: HitResult): Boolean {
        val plane = hit.trackable as? Plane ?: return false
        return renderer.planeKind(plane) == PlaneKind.FLOOR
    }

    private fun publishStatus(frame: Frame, camera: Camera, frameTimeNanos: Long) {
        if (frameTimeNanos - lastStatusNanos < STATUS_INTERVAL_NANOS) return
        lastStatusNanos = frameTimeNanos

        val counts = renderer.planeCounts
        val isTracking = camera.trackingState == TrackingState.TRACKING
        val floorY = renderer.floorY?.takeIf { isTracking }
        val cameraHeight = floorY?.let { camera.pose.ty() - it }
        val logDue = frameTimeNanos - lastLogNanos >= LOG_INTERVAL_NANOS
        if (logDue) {
            // ~100 hit tests: once a second is plenty.
            floorProbeResult = floorY?.let {
                floorProbe.measure(frame, camera, renderer.depth, it, viewportWidth, viewportHeight)
            }
            val trackedWalls = walls.filter { it.anchor.trackingState == TrackingState.TRACKING }
            wallProbeResult = if (isTracking) {
                wallProbe.measure(frame, camera, renderer.depth, trackedWalls, viewportWidth, viewportHeight)
            } else {
                null
            }
        }
        val cubeDistance = cubeAnchor?.takeIf { camera.trackingState == TrackingState.TRACKING }?.let { anchor ->
            val a = anchor.pose
            val c = camera.pose
            val dx = a.tx() - c.tx()
            val dy = a.ty() - c.ty()
            val dz = a.tz() - c.tz()
            sqrt(dx * dx + dy * dy + dz * dz)
        }
        status.update {
            it.copy(
                fps = fpsCounter.fps,
                tracking = camera.trackingState,
                failureReason = camera.trackingFailureReason,
                floors = counts.floors,
                raisedSurfaces = counts.raisedSurfaces,
                walls = counts.walls,
                cameraHeightMetres = cameraHeight,
                depthActive = renderer.depth.available,
                floorOffsetMetres = floorProbeResult?.offsetMetres,
                floorFitInliers = floorProbeResult?.inlierFraction,
                cubeDistanceMetres = cubeDistance,
                mode = mode,
                wallStartPlaced = wallStartAnchor != null,
                markedWalls = counts.markedWalls,
                wallOffsetMetres = wallProbeResult?.offsetMetres,
                wallFitInliers = wallProbeResult?.inlierFraction,
                notice = notice?.takeIf { frameTimeNanos - noticeNanos < NOTICE_DURATION_NANOS },
            )
        }

        if (logDue) {
            lastLogNanos = frameTimeNanos
            val s = status.value
            val raised = renderer.raisedSurfaceHeights.joinToString(",") { String.format(Locale.US, "%.2f", it) }
            val probe = floorProbeResult
            Log.i(
                STATS_TAG,
                "fps=${s.fps} tracking=${s.tracking} reason=${s.failureReason} floors=${s.floors} " +
                    "raised=[$raised] walls=${s.walls} ceilings=${counts.ceilings} " +
                    "cameraHeight=${s.cameraHeightMetres?.let { String.format(Locale.US, "%.3f", it) } ?: "none"} " +
                    "depth=${renderer.depth.width}x${renderer.depth.height} " +
                    "floorFit=${probe?.let { String.format(Locale.US, "%+.3f", it.offsetMetres) } ?: "none"} " +
                    "floorFitSamples=${probe?.samples ?: 0} " +
                    "floorFitInliers=${probe?.let { String.format(Locale.US, "%.2f", it.inlierFraction) } ?: "none"} " +
                    "markedWalls=${counts.markedWalls} " +
                    "wallFit=${wallProbeResult?.let { String.format(Locale.US, "%+.3f", it.offsetMetres) } ?: "none"} " +
                    "wallFitSamples=${wallProbeResult?.samples ?: 0} " +
                    "wallFitInliers=${wallProbeResult?.let { String.format(Locale.US, "%.2f", it.inlierFraction) } ?: "none"} " +
                    "cube=${s.cubeDistanceMetres?.let { String.format(Locale.US, "%.3f", it) } ?: "none"} " +
                    "cpuMsAvg=${String.format(Locale.US, "%.1f", frameCpuNanosTotal / 1e6 / maxOf(framesTimed, 1))} " +
                    "cpuMsMax=${String.format(Locale.US, "%.1f", frameCpuNanosMax / 1e6)} " +
                    "frameTs=${frame.timestamp}",
            )
            frameCpuNanosTotal = 0L
            frameCpuNanosMax = 0L
            framesTimed = 0
        }
    }

    private fun showError(message: String) {
        status.update { it.copy(error = message) }
    }

    private companion object {
        const val TAG = "ArActivity"
        const val STATS_TAG = "ArStats"
        const val EVENT_TAG = "ArEvents"
        const val STATUS_INTERVAL_NANOS = 250_000_000L
        const val NOTICE_DURATION_NANOS = 5_000_000_000L
        const val LOG_INTERVAL_NANOS = 1_000_000_000L
        const val DEFAULT_WALL_HEIGHT_METRES = 2.8f
        const val WALL_MARGIN_METRES = 0.3f
        const val WALL_MARGIN_WITH_DEPTH_METRES = 3.0f
        val CUBE_EDGE_CENTIMETRES = (ArRenderer.REFERENCE_CUBE_EDGE_METRES * 100).roundToInt()
    }
}
