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
import com.google.ar.core.Anchor
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Camera
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.HitResult
import com.google.ar.core.Plane
import com.google.ar.core.Session
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
    private var cubeAnchor: Anchor? = null

    private val fpsCounter = FpsCounter()
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
                    ArOverlay(current, CUBE_EDGE_CENTIMETRES)
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
                    depthMode = Config.DepthMode.DISABLED
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
        // No camera image yet (first frames after resume).
        if (frame.timestamp == 0L) return

        val camera = frame.camera
        handleTaps(frame, camera)
        val cubePose = cubeAnchor?.takeIf { it.trackingState == TrackingState.TRACKING }?.pose
        renderer.render(activeSession, frame, frameTimeNanos, cubePose)

        fpsCounter.onFrame(frameTimeNanos)
        publishStatus(frame, camera, frameTimeNanos)
    }

    private fun handleTaps(frame: Frame, camera: Camera) {
        while (true) {
            val tap = pendingTaps.removeFirstOrNull() ?: return
            try {
                if (camera.trackingState != TrackingState.TRACKING) continue
                val hit = frame.hitTest(tap).firstOrNull(::isFloorHit) ?: continue
                cubeAnchor?.detach()
                cubeAnchor = hit.createAnchor()
                Log.i(TAG, String.format(Locale.US, "Placed reference cube %.2f m from camera", hit.distance))
            } finally {
                tap.recycle()
            }
        }
    }

    private fun isFloorHit(hit: HitResult): Boolean {
        val plane = hit.trackable as? Plane ?: return false
        return plane.type == Plane.Type.HORIZONTAL_UPWARD_FACING && plane.isPoseInPolygon(hit.hitPose)
    }

    private fun publishStatus(frame: Frame, camera: Camera, frameTimeNanos: Long) {
        if (frameTimeNanos - lastStatusNanos < STATUS_INTERVAL_NANOS) return
        lastStatusNanos = frameTimeNanos

        val counts = renderer.planeCounts
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
                walls = counts.walls,
                cubeDistanceMetres = cubeDistance,
            )
        }

        if (frameTimeNanos - lastLogNanos >= LOG_INTERVAL_NANOS) {
            lastLogNanos = frameTimeNanos
            val s = status.value
            Log.i(
                STATS_TAG,
                "fps=${s.fps} tracking=${s.tracking} reason=${s.failureReason} floors=${s.floors} " +
                    "walls=${s.walls} ceilings=${counts.ceilings} cube=${s.cubeDistanceMetres ?: "none"} " +
                    "frameTs=${frame.timestamp}",
            )
        }
    }

    private fun showError(message: String) {
        status.update { it.copy(error = message) }
    }

    private companion object {
        const val TAG = "ArActivity"
        const val STATS_TAG = "ArStats"
        const val STATUS_INTERVAL_NANOS = 250_000_000L
        const val LOG_INTERVAL_NANOS = 1_000_000_000L
        val CUBE_EDGE_CENTIMETRES = (ArRenderer.REFERENCE_CUBE_EDGE_METRES * 100).roundToInt()
    }
}
