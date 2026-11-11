package com.arhomedecor.app.device

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.util.Log
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

data class DeviceCapabilities(
    val model: String,
    val androidVersion: String,
    val apiLevel: Int,
    val glEsVersion: String,
    val arCoreAvailability: ArCoreApk.Availability,
    val mode: AppMode,
    /** Null when it could not be checked (ARCore missing or session creation failed). */
    val depthSupported: Boolean?,
)

object DeviceCapabilityChecker {
    private const val TAG = "DeviceCapabilities"
    private const val POLL_INTERVAL_MS = 200L
    private const val MAX_POLLS = 50

    suspend fun check(context: Context): DeviceCapabilities {
        val availability = pollArCoreAvailability(context)
        val mode = appModeFor(availability)
        val depth = if (mode == AppMode.LIVE_AR) checkDepthSupport(context) else null

        return DeviceCapabilities(
            model = "${Build.MANUFACTURER} ${Build.MODEL}",
            androidVersion = Build.VERSION.RELEASE,
            apiLevel = Build.VERSION.SDK_INT,
            glEsVersion = glEsVersion(context),
            arCoreAvailability = availability,
            mode = mode,
            depthSupported = depth,
        ).also { Log.i(TAG, it.toString()) }
    }

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

    private suspend fun checkDepthSupport(context: Context): Boolean? = withContext(Dispatchers.Default) {
        try {
            val session = Session(context)
            try {
                session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
            } finally {
                session.close()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not create ARCore session to check depth support", e)
            null
        }
    }

    private fun glEsVersion(context: Context): String {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return am.deviceConfigurationInfo.glEsVersion
    }
}
