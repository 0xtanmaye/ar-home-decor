package com.arhomedecor.app.render

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface

/**
 * A small OpenGL ES 3 context, made current on the thread that creates it (the main thread).
 *
 * ARCore writes camera frames into textures that belong to this context, and Filament is
 * created with this context as its shared context so it can sample those same textures.
 */
class SharedEglContext {
    private val display: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    val context: EGLContext
    private val surface: EGLSurface

    init {
        check(display != EGL14.EGL_NO_DISPLAY) { "No EGL display" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed: ${eglError()}" }

        val configAttributes = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val configCount = IntArray(1)
        check(
            EGL14.eglChooseConfig(display, configAttributes, 0, configs, 0, 1, configCount, 0) &&
                configCount[0] > 0,
        ) { "No suitable EGL config: ${eglError()}" }
        val config = checkNotNull(configs[0])

        val contextAttributes = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE)
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, contextAttributes, 0)
        check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed: ${eglError()}" }

        // A 1x1 pbuffer: this context never draws anything itself.
        val surfaceAttributes = intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE)
        surface = EGL14.eglCreatePbufferSurface(display, config, surfaceAttributes, 0)
        check(surface != EGL14.EGL_NO_SURFACE) { "eglCreatePbufferSurface failed: ${eglError()}" }

        check(EGL14.eglMakeCurrent(display, surface, surface, context)) { "eglMakeCurrent failed: ${eglError()}" }
    }

    fun release() {
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(display, surface)
        EGL14.eglDestroyContext(display, context)
        EGL14.eglReleaseThread()
        // The default display is shared across the process (Filament uses it too), so it is not terminated.
    }

    private fun eglError() = "0x" + Integer.toHexString(EGL14.eglGetError())
}
