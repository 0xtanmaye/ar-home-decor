package com.arhomedecor.app.device

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

enum class DepthSupport {
    SUPPORTED,
    NOT_SUPPORTED,

    /** ARCore can't open a session to check without camera access. */
    NEEDS_CAMERA_PERMISSION,

    /** No live AR on this device, so depth doesn't apply. */
    NOT_APPLICABLE,
    CHECK_FAILED,
}

data class DeviceCapabilities(
    val model: String,
    val androidVersion: String,
    val apiLevel: Int,
    val glEsVersion: String,
    val arCoreAvailability: ArCoreApk.Availability,
    val mode: AppMode,
    val depth: DepthSupport,
)

object DeviceCapabilityChecker {
    private const val TAG = "DeviceCapabilities"
    private const val POLL_INTERVAL_MS = 200L
    private const val MAX_POLLS = 50

    suspend fun check(context: Context): DeviceCapabilities {
        val availability = pollArCoreAvailability(context)
        val mode = appModeFor(availability)
        val depth = when {
            mode != AppMode.LIVE_AR -> DepthSupport.NOT_APPLICABLE
            !hasCameraPermission(context) -> DepthSupport.NEEDS_CAMERA_PERMISSION
            else -> checkDepthSupport(context)
        }

        return DeviceCapabilities(
            model = "${Build.MANUFACTURER} ${Build.MODEL}",
            androidVersion = Build.VERSION.RELEASE,
            apiLevel = Build.VERSION.SDK_INT,
            glEsVersion = glEsVersion(context),
            arCoreAvailability = availability,
            mode = mode,
            depth = depth,
        ).also { Log.i(TAG, it.toString()) }
    }

    fun hasCameraPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    /** checkAvailability() can report UNKNOWN_CHECKING at first, so poll until it settles. */
    private suspend fun pollArCoreAvailability(context: Context): ArCoreApk.Availability {
        var availability = ArCoreApk.getInstance().checkAvailability(context)
        var polls = 0
        while (availability.isTransient && polls < MAX_POLLS) {
            delay(POLL_INTERVAL_MS)
            availability = ArCoreApk.getInstance().checkAvailability(context)
            polls++
        }
        return availability
    }

    private suspend fun checkDepthSupport(context: Context): DepthSupport = withContext(Dispatchers.Default) {
        try {
            val session = Session(context)
            try {
                if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                    DepthSupport.SUPPORTED
                } else {
                    DepthSupport.NOT_SUPPORTED
                }
            } finally {
                session.close()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not create ARCore session to check depth support", e)
            DepthSupport.CHECK_FAILED
        }
    }

    private fun glEsVersion(context: Context): String {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return am.deviceConfigurationInfo.glEsVersion
    }
}
