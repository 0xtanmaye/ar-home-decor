package com.arhomedecor.app.render

import com.google.ar.core.Frame
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.NotYetAvailableException
import java.nio.ByteOrder
import java.util.Locale

/**
 * World-space points from ARCore's raw depth: only the pixels it actually measured, with enough
 * confidence. The full depth image ([DepthTexture]) fills every pixel by smoothing, which suits
 * hiding things behind real objects, but on plain or glossy surfaces it is filled in rather than
 * measured; raw depth leaves those pixels empty (zero confidence).
 */
class RawDepthPoints {
    /** (x, y, z) per point; only the first [count] are valid. */
    var points = FloatArray(0)
        private set
    var count = 0
        private set

    /** Where the camera was when the points were measured. */
    val cameraPosition = FloatArray(3)

    private val cameraToWorld = FloatArray(16)
    private val intrinsics = FloatArray(4)

    private var lastImageTimestamp = -1L

    // For the log: how many new images arrived and what share of their pixels was kept.
    private var newImages = 0
    private var pixelsSampled = 0L
    private var pixelsKept = 0L

    /**
     * Reads the frame's raw depth image if it holds a new measurement. ARCore makes about ten
     * new estimates a second; in between it re-renders the latest one for the current camera
     * pose, which adds nothing. Every image is aligned with the current camera, and a new
     * estimate is recognised by its timestamp changing (it is that of the camera image the
     * estimate was computed from, so it can be a few frames old). Returns true if [points]
     * were replaced.
     */
    fun update(frame: Frame): Boolean {
        val camera = frame.camera
        if (camera.trackingState != TrackingState.TRACKING) return false
        val depthImage = try {
            frame.acquireRawDepthImage16Bits()
        } catch (e: NotYetAvailableException) {
            return false
        } catch (e: IllegalStateException) {
            return false // depth is disabled for this session
        }
        depthImage.use { depth ->
            if (depth.timestamp == lastImageTimestamp) return false
            lastImageTimestamp = depth.timestamp
            val confidenceImage = try {
                frame.acquireRawDepthConfidenceImage()
            } catch (e: NotYetAvailableException) {
                return false
            }
            confidenceImage.use { confidence ->
                val width = depth.width
                val height = depth.height
                // The depth image covers the camera texture's view at lower resolution.
                val texture = camera.textureIntrinsics
                val focal = texture.focalLength
                val principal = texture.principalPoint
                val size = texture.imageDimensions
                val scaleX = width / size[0].toFloat()
                val scaleY = height / size[1].toFloat()
                intrinsics[0] = focal[0] * scaleX
                intrinsics[1] = focal[1] * scaleY
                intrinsics[2] = principal[0] * scaleX
                intrinsics[3] = principal[1] * scaleY
                val pose = camera.pose
                pose.toMatrix(cameraToWorld, 0)
                cameraPosition[0] = pose.tx()
                cameraPosition[1] = pose.ty()
                cameraPosition[2] = pose.tz()

                val maxPoints = ((width + PIXEL_STEP - 1) / PIXEL_STEP) * ((height + PIXEL_STEP - 1) / PIXEL_STEP)
                if (points.size < maxPoints * 3) points = FloatArray(maxPoints * 3)
                val depthPlane = depth.planes[0]
                val depthData = depthPlane.buffer.order(ByteOrder.LITTLE_ENDIAN)
                val confidencePlane = confidence.planes[0]
                val confidenceData = confidencePlane.buffer
                var kept = 0
                var sampled = 0
                for (v in 0 until height step PIXEL_STEP) {
                    for (u in 0 until width step PIXEL_STEP) {
                        sampled++
                        val score = confidenceData.get(v * confidencePlane.rowStride + u * confidencePlane.pixelStride).toInt() and 0xFF
                        if (score < MIN_CONFIDENCE) continue
                        val millimetres = depthData.getShort(v * depthPlane.rowStride + u * depthPlane.pixelStride).toInt() and 0xFFFF
                        if (millimetres == 0 || millimetres > MAX_DEPTH_MILLIMETRES) continue
                        depthPixelToWorld(u, v, millimetres / 1000f, intrinsics, cameraToWorld, points, kept * 3)
                        kept++
                    }
                }
                count = kept
                newImages++
                pixelsSampled += sampled
                pixelsKept += kept
            }
        }
        return true
    }

    /** "rawDepthImages=N rawDepthKept=P%" since the last call: new estimates, share of pixels kept. */
    fun takeSummary(): String {
        val share = if (pixelsSampled > 0) 100.0 * pixelsKept / pixelsSampled else 0.0
        val summary = String.format(Locale.US, "rawDepthImages=%d rawDepthKept=%.0f%%", newImages, share)
        newImages = 0
        pixelsSampled = 0L
        pixelsKept = 0L
        return summary
    }

    private companion object {
        /** Every second pixel each way: about 2 cm apart at 1.5 m, finer than a coverage cell. */
        const val PIXEL_STEP = 2

        /**
         * Of 255. Plain surfaces score 0; ARCore's reference suggests dropping pixels below 128,
         * which keeps measurements it is fairly sure of.
         */
        const val MIN_CONFIDENCE = 128

        /** Raw depth beyond this is too coarse to place an edge. */
        const val MAX_DEPTH_MILLIMETRES = 4000
    }
}
