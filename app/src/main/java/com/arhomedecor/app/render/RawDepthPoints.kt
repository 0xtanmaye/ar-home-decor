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
 *
 * Each point also records whether the surface around it is known to be steep ([steep]),
 * estimated from its measured neighbours ([DepthNormalEstimator]).
 */
class RawDepthPoints {
    /** (x, y, z) per point; only the first [count] are valid. */
    var points = FloatArray(0)
        private set
    var count = 0
        private set

    /**
     * Per point: whether its neighbours show it lies on a surface more than about 45 degrees from
     * horizontal (a wall or the side of furniture). False where they don't, including where too
     * little around it was measured to tell.
     */
    var steep = BooleanArray(0)
        private set

    /** Where the camera was when the points were measured. */
    val cameraPosition = FloatArray(3)

    private val cameraToWorld = FloatArray(16)
    private val intrinsics = FloatArray(4)

    /** The latest image in metres, 0 where nothing was measured with enough confidence. */
    private var measured = FloatArray(0)
    private val normals = DepthNormalEstimator()
    private val normal = FloatArray(3)

    private var lastImageTimestamp = -1L

    // For the log: how many new images arrived, what share of their pixels was kept, and for
    // what share of those the orientation could be estimated and was steep.
    private var newImages = 0
    private var pixelsSampled = 0L
    private var pixelsKept = 0L
    private var pixelsOriented = 0L
    private var pixelsSteep = 0L
    private var processingNanos = 0L

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
            val startNanos = System.nanoTime()
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

                // Every pixel first, so that each point's neighbours can be looked up.
                if (measured.size != width * height) measured = FloatArray(width * height)
                // Strides are read once: each read is a call into the image, too slow per pixel.
                val depthPlane = depth.planes[0]
                val depthData = depthPlane.buffer.order(ByteOrder.LITTLE_ENDIAN)
                val depthRowStride = depthPlane.rowStride
                val depthPixelStride = depthPlane.pixelStride
                val confidencePlane = confidence.planes[0]
                val confidenceData = confidencePlane.buffer
                val confidenceRowStride = confidencePlane.rowStride
                val confidencePixelStride = confidencePlane.pixelStride
                for (v in 0 until height) {
                    for (u in 0 until width) {
                        val score = confidenceData.get(v * confidenceRowStride + u * confidencePixelStride).toInt() and 0xFF
                        val millimetres = depthData.getShort(v * depthRowStride + u * depthPixelStride).toInt() and 0xFFFF
                        val kept = score >= MIN_CONFIDENCE && millimetres in 1..MAX_DEPTH_MILLIMETRES
                        measured[v * width + u] = if (kept) millimetres / 1000f else 0f
                    }
                }

                val maxPoints = ((width + PIXEL_STEP - 1) / PIXEL_STEP) * ((height + PIXEL_STEP - 1) / PIXEL_STEP)
                if (points.size < maxPoints * 3) {
                    points = FloatArray(maxPoints * 3)
                    steep = BooleanArray(maxPoints)
                }
                var kept = 0
                var sampled = 0
                var oriented = 0
                var steepCount = 0
                for (v in 0 until height step PIXEL_STEP) {
                    for (u in 0 until width step PIXEL_STEP) {
                        sampled++
                        val metres = measured[v * width + u]
                        if (metres == 0f) continue
                        depthPixelToWorld(u, v, metres, intrinsics, cameraToWorld, points, kept * 3)
                        val hasNormal = normals.estimate(measured, width, height, u, v, NORMAL_SPAN_PIXELS, intrinsics, normal)
                        val isSteep = hasNormal && upwardness(normal, cameraToWorld) < MIN_UPWARDNESS
                        steep[kept] = isSteep
                        if (hasNormal) oriented++
                        if (isSteep) steepCount++
                        kept++
                    }
                }
                count = kept
                newImages++
                pixelsSampled += sampled
                pixelsKept += kept
                pixelsOriented += oriented
                pixelsSteep += steepCount
                processingNanos += System.nanoTime() - startNanos
            }
        }
        return true
    }

    /**
     * "rawDepthImages=N rawDepthKept=P% rawDepthOriented=Q% rawDepthSteep=R% rawDepthMs=T" since
     * the last call: new estimates, share of pixels kept, shares of kept points whose orientation
     * could be estimated and that were steep, and the average time spent on each image.
     */
    fun takeSummary(): String {
        fun percent(part: Long, whole: Long) = if (whole > 0) 100.0 * part / whole else 0.0
        val summary = String.format(
            Locale.US,
            "rawDepthImages=%d rawDepthKept=%.0f%% rawDepthOriented=%.0f%% rawDepthSteep=%.0f%% rawDepthMs=%.1f",
            newImages,
            percent(pixelsKept, pixelsSampled),
            percent(pixelsOriented, pixelsKept),
            percent(pixelsSteep, pixelsKept),
            if (newImages > 0) processingNanos / 1e6 / newImages else 0.0,
        )
        newImages = 0
        pixelsSampled = 0L
        pixelsKept = 0L
        pixelsOriented = 0L
        pixelsSteep = 0L
        processingNanos = 0L
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

        /**
         * Orientation is measured between neighbours up to this many pixels away on each side
         * (about 2.5 cm at 1 m): wide enough to average out depth noise, narrow enough for small
         * tops.
         */
        const val NORMAL_SPAN_PIXELS = 3

        /**
         * A point is steep if the surface's normal is more than about 45 degrees from vertical
         * (cos 45° ≈ 0.71). Walls and the sides of furniture are near 0; depth noise on a glossy
         * top tilts its estimate by well under that.
         */
        const val MIN_UPWARDNESS = 0.7f
    }
}
