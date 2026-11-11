package com.arhomedecor.app.render

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * A neighbouring depth pixel whose depth differs from the centre's by more than this fraction of
 * the centre's depth lies across an edge, on another surface. The same rule as the normal
 * estimate in ARCore Depth Lab's oriented reticle (github.com/googlesamples/arcore-depth-lab);
 * a floor seen at a grazing angle changes depth by well under this between nearby pixels.
 */
internal const val NORMAL_OUTLIER_RATIO = 0.2f

/**
 * Estimates the orientation of the surface seen at a pixel of a depth image from the 3D positions
 * of its neighbours up to [span] pixels away on each side.
 *
 * Neighbours across an edge (see [NORMAL_OUTLIER_RATIO]) or without a measurement are left out,
 * and on each side the farthest remaining one is used (raw depth is sparse, so the pixel exactly
 * [span] away is often unmeasured). Where only one side along an axis has a neighbour on the same
 * surface, the one-sided difference towards it is used. A plain central difference straddles the
 * edge of a table and returns a direction halfway between the table and the floor beyond it; this
 * keeps a valid estimate right up to the edge.
 */
internal class DepthNormalEstimator {
    private val centre = FloatArray(3)
    private val before = FloatArray(3)
    private val after = FloatArray(3)
    private val alongU = FloatArray(3)
    private val alongV = FloatArray(3)

    /**
     * Writes the camera-space normal (not normalised, either sign) of the surface at pixel
     * ([u], [v]) to [out] and returns true, or returns false if it can't be estimated.
     *
     * [depthMetres] is the depth image, row-major [width] x [height], 0 where nothing was
     * measured. [intrinsics] are its (fx, fy, cx, cy) in pixels (see [depthPixelToWorld]).
     */
    fun estimate(
        depthMetres: FloatArray,
        width: Int,
        height: Int,
        u: Int,
        v: Int,
        span: Int,
        intrinsics: FloatArray,
        out: FloatArray,
    ): Boolean {
        val depth = depthMetres[v * width + u]
        if (depth <= 0f) return false
        depthPixelToCamera(u, v, depth, intrinsics, centre, 0)
        if (!tangent(depthMetres, width, height, u, v, span, 0, depth, intrinsics, alongU)) return false
        if (!tangent(depthMetres, width, height, u, v, 0, span, depth, intrinsics, alongV)) return false
        out[0] = alongU[1] * alongV[2] - alongU[2] * alongV[1]
        out[1] = alongU[2] * alongV[0] - alongU[0] * alongV[2]
        out[2] = alongU[0] * alongV[1] - alongU[1] * alongV[0]
        return out[0] * out[0] + out[1] * out[1] + out[2] * out[2] > 0f
    }

    /** The surface's direction through the centre along pixel offset (du, dv), into [out]. */
    private fun tangent(
        depthMetres: FloatArray,
        width: Int,
        height: Int,
        u: Int,
        v: Int,
        du: Int,
        dv: Int,
        centreDepth: Float,
        intrinsics: FloatArray,
        out: FloatArray,
    ): Boolean {
        val beforeOk = neighbour(depthMetres, width, height, u, v, -du, -dv, centreDepth, intrinsics, before)
        val afterOk = neighbour(depthMetres, width, height, u, v, du, dv, centreDepth, intrinsics, after)
        // The length of the result doesn't matter: only its direction enters the normal.
        val from = if (beforeOk) before else centre
        val to = if (afterOk) after else centre
        if (!beforeOk && !afterOk) return false
        for (axis in 0 until 3) out[axis] = to[axis] - from[axis]
        return true
    }

    /**
     * The farthest pixel on the same surface as the centre along (du, dv), at most that far
     * away, written to [out] as a camera-space point. Returns false if there is none.
     */
    private fun neighbour(
        depthMetres: FloatArray,
        width: Int,
        height: Int,
        u: Int,
        v: Int,
        du: Int,
        dv: Int,
        centreDepth: Float,
        intrinsics: FloatArray,
        out: FloatArray,
    ): Boolean {
        val limit = NORMAL_OUTLIER_RATIO * centreDepth
        val steps = maxOf(abs(du), abs(dv))
        val stepU = du / steps
        val stepV = dv / steps
        for (step in steps downTo 1) {
            val nu = u + stepU * step
            val nv = v + stepV * step
            val depth = depthAt(depthMetres, width, height, nu, nv)
            if (depth > 0f && abs(depth - centreDepth) <= limit) {
                depthPixelToCamera(nu, nv, depth, intrinsics, out, 0)
                return true
            }
        }
        return false
    }

    private fun depthAt(depthMetres: FloatArray, width: Int, height: Int, u: Int, v: Int): Float =
        if (u < 0 || v < 0 || u >= width || v >= height) 0f else depthMetres[v * width + u]
}

/**
 * How close to vertical a camera-space [normal] is in the world, from 0 (a wall) to 1 (a floor or
 * table top): the |cosine| of its angle to world up. [cameraToWorld] is as in [depthPixelToWorld].
 */
internal fun upwardness(normal: FloatArray, cameraToWorld: FloatArray): Float {
    val m = cameraToWorld
    val worldY = m[1] * normal[0] + m[5] * normal[1] + m[9] * normal[2]
    val length = sqrt(normal[0] * normal[0] + normal[1] * normal[1] + normal[2] * normal[2])
    return if (length > 0f) abs(worldY) / length else 0f
}
