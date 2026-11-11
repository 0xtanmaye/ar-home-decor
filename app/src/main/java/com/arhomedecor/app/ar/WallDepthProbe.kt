package com.arhomedecor.app.ar

import com.arhomedecor.app.render.DepthTexture
import com.arhomedecor.app.render.ManualWall
import com.arhomedecor.app.render.UP
import com.arhomedecor.app.render.cameraRayDirection
import com.arhomedecor.app.render.dot
import com.arhomedecor.app.render.intersectRayPlane
import com.google.ar.core.Camera
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame

/**
 * Measures how far marked walls are from the real walls seen in the depth image.
 *
 * Marked walls aren't ARCore trackables, so hit-testing can't find them: instead a grid of
 * camera rays is intersected with each wall's plane directly. The median gap along the wall's
 * normal is reported: positive means the marked wall stands in front of the real one.
 */
class WallDepthProbe {
    private var viewWidth = 0
    private var viewHeight = 0
    private var viewPoints = FloatArray(0)
    private var texturePoints = FloatArray(0)
    private val projection = FloatArray(16)

    fun measure(
        frame: Frame,
        camera: Camera,
        depth: DepthTexture,
        walls: List<ManualWall>,
        width: Int,
        height: Int,
    ): FloorDepthProbe.Result? {
        if (!depth.available || walls.isEmpty() || width == 0 || height == 0) return null
        if (width != viewWidth || height != viewHeight) buildGrid(width, height)
        frame.transformCoordinates2d(Coordinates2d.VIEW, viewPoints, Coordinates2d.TEXTURE_NORMALIZED, texturePoints)

        // The projection is display-oriented, so it pairs with the display-oriented pose.
        camera.getProjectionMatrix(projection, 0, 0.05f, 30f)
        val cameraPose = camera.displayOrientedPose
        val origin = cameraPose.translation
        val planes = walls.map { wall ->
            val pose = wall.anchor.pose
            WallPlane(pose.translation, pose.rotateVector(wall.normal), pose.rotateVector(wall.alongWall), wall.outline)
        }

        val offsets = ArrayList<Float>()
        for (k in 0 until viewPoints.size / 2) {
            val ndcX = viewPoints[k * 2] / width * 2f - 1f
            val ndcY = 1f - viewPoints[k * 2 + 1] / height * 2f
            val direction = cameraPose.rotateVector(cameraRayDirection(ndcX, ndcY, projection))

            // Nearest marked wall this ray hits inside its rectangle.
            var best: Pair<WallPlane, Float>? = null
            for (plane in planes) {
                val t = intersectRayPlane(origin, direction, plane.point, plane.normal) ?: continue
                if (best != null && t >= best.second) continue
                val hit = FloatArray(3) { origin[it] + direction[it] * t }
                if (plane.contains(hit)) best = plane to t
            }
            val (plane, planeDepth) = best ?: continue
            val realDepth = depth.depthAt(texturePoints[k * 2], texturePoints[k * 2 + 1])
            if (realDepth <= 0f) continue
            val cameraDistance = dot(floatArrayOf(origin[0] - plane.point[0], origin[1] - plane.point[1], origin[2] - plane.point[2]), plane.normal)
            offsets += planeOffsetFromRealSurface(cameraDistance, planeDepth, realDepth)
        }
        if (offsets.size < MIN_SAMPLES) return null
        return FloorDepthProbe.Result(median(offsets), offsets.size)
    }

    private class WallPlane(val point: FloatArray, val normal: FloatArray, val along: FloatArray, val outline: FloatArray) {
        fun contains(p: FloatArray): Boolean {
            val offset = floatArrayOf(p[0] - point[0], p[1] - point[1], p[2] - point[2])
            val u = dot(offset, along)
            val v = dot(offset, UP)
            // The outline is a rectangle: corners 0 and 2 are opposite.
            return u in outline[0]..outline[4] && v in outline[1]..outline[5]
        }
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
