package com.arhomedecor.app.device

import com.google.ar.core.ArCoreApk.Availability
import org.junit.Assert.assertEquals
import org.junit.Test

class AppModeTest {

    @Test
    fun installedArCoreGivesLiveAr() {
        assertEquals(AppMode.LIVE_AR, appModeFor(Availability.SUPPORTED_INSTALLED))
    }

    @Test
    fun missingOrOutdatedArCoreAsksForInstall() {
        assertEquals(AppMode.NEEDS_ARCORE_INSTALL, appModeFor(Availability.SUPPORTED_NOT_INSTALLED))
        assertEquals(AppMode.NEEDS_ARCORE_INSTALL, appModeFor(Availability.SUPPORTED_APK_TOO_OLD))
    }

    @Test
    fun unsupportedOrUnknownDevicesFallBackToPhotoMode() {
        assertEquals(AppMode.PHOTO_MODE, appModeFor(Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE))
        assertEquals(AppMode.PHOTO_MODE, appModeFor(Availability.UNKNOWN_ERROR))
        assertEquals(AppMode.PHOTO_MODE, appModeFor(Availability.UNKNOWN_TIMED_OUT))
    }

    @Test
    fun transientCheckingStateIsReportedAsChecking() {
        assertEquals(AppMode.CHECKING, appModeFor(Availability.UNKNOWN_CHECKING))
    }
}
