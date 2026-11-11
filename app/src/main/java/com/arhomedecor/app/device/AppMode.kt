package com.arhomedecor.app.device

import com.google.ar.core.ArCoreApk

/** Which experience the app can offer on the current device. */
enum class AppMode {
    /** ARCore availability is still being determined. */
    CHECKING,

    /** Device supports ARCore and it is installed and up to date. */
    LIVE_AR,

    /** Device supports ARCore but Google Play Services for AR must be installed or updated. */
    NEEDS_ARCORE_INSTALL,

    /** No live AR on this device: offer the photo-based fallback. */
    PHOTO_MODE,
}

fun appModeFor(availability: ArCoreApk.Availability): AppMode = when (availability) {
    ArCoreApk.Availability.UNKNOWN_CHECKING -> AppMode.CHECKING
    ArCoreApk.Availability.SUPPORTED_INSTALLED -> AppMode.LIVE_AR
    ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD,
    ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED -> AppMode.NEEDS_ARCORE_INSTALL
    ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE,
    ArCoreApk.Availability.UNKNOWN_ERROR,
    ArCoreApk.Availability.UNKNOWN_TIMED_OUT -> AppMode.PHOTO_MODE
}
