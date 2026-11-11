package com.arhomedecor.app.ar

import com.arhomedecor.app.render.DepthTexture
import com.arhomedecor.app.render.FLOOR_TOLERANCE_METRES
import com.google.ar.core.Camera
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import kotlin.math.abs

/**
 * Measures how far the detected floor plane is from the real floor seen in the depth image.
 *
 * A grid of screen points is hit-tested against the floor plane; for each hit, the depth image
 * says where the real surface is along the same camera ray. The median vertical gap is reported:
 * positive means the plane sits above the real floor.
 */
class FloorDepthProbe {
    data class Result(val offsetMetres: Float, val samples: Int)

    private var viewWidth = 0
    private var viewHeight = 0
    private var viewPoints = FloatArray(0)
    private var texturePoints = FloatArray(0)

    fun measure(frame: Frame, camera: Camera, depth: DepthTexture, floorY: Float, width: Int, height: Int): Result? {
        if (!depth.available || width == 0 || height == 0) return null
        if (width != viewWidth || height != viewHeight) buildGrid(width, height)
        frame.transformCoordinates2d(Coordinates2d.VIEW, viewPoints, Coordinates2d.TEXTURE_NORMALIZED, texturePoints)

        val cameraPose = camera.pose
        val worldToCamera = cameraPose.inverse()
        val offsets = ArrayList<Float>(viewPoints.size / 2)
        for (k in 0 until viewPoints.size / 2) {
            val hit = frame.hitTest(viewPoints[k * 2], viewPoints[k * 2 + 1]).firstOrNull {
                val plane = it.trackable as? Plane
                plane != null &&
                    plane.type == Plane.Type.HORIZONTAL_UPWARD_FACING &&
                    abs(it.hitPose.ty() - floorY) <= FLOOR_TOLERANCE_METRES &&
                    plane.isPoseInPolygon(it.hitPose)
            } ?: continue
            val hitPoint = hit.hitPose.translation
            // ARCore cameras look down -Z; depth images store distance along that axis.
            val planeDepth = -worldToCamera.transformPoint(hitPoint)[2]
            val realDepth = depth.depthAt(texturePoints[k * 2], texturePoints[k * 2 + 1])
            if (planeDepth <= 0f || realDepth <= 0f) continue
            offsets += planeHeightAboveRealSurface(cameraPose.ty(), hitPoint[1], planeDepth, realDepth)
        }
        if (offsets.size < MIN_SAMPLES) return null
        return Result(median(offsets), offsets.size)
    }

    private fun buildGrid(width: Int, height: Int) {
        viewWidth = width
        viewHeight = height
        viewPoints = FloatArray(COLUMNS * ROWS * 2)
        texturePoints = FloatArray(viewPoints.size)
        var k = 0
        for (row in 0 until ROWS) {
            for (column in 0 until COLUMNS) {
                viewPoints[k++] = (column + 0.5f) / COLUMNS * width
                viewPoints[k++] = (row + 0.5f) / ROWS * height
            }
        }
    }

    private companion object {
        const val COLUMNS = 8
        const val ROWS = 14
        const val MIN_SAMPLES = 8
    }
}

/**
 * Vertical gap between a plane hit and the real surface on the same camera ray.
 *
 * Points on a ray scale with depth, so the real point is the hit point scaled by
 * realDepth / planeDepth about the camera. Positive means the plane is above the real surface.
 */
internal fun planeHeightAboveRealSurface(cameraY: Float, hitY: Float, planeDepth: Float, realDepth: Float): Float =
    (cameraY - hitY) * (realDepth / planeDepth - 1f)

internal fun median(values: List<Float>): Float {
    require(values.isNotEmpty()) { "median of no values" }
    val sorted = values.sorted()
    val middle = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2f
}
