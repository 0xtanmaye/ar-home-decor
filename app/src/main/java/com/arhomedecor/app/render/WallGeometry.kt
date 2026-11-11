package com.arhomedecor.app.render

import com.google.ar.core.Anchor
import kotlin.math.hypot

/** World up. ARCore's world Y axis is aligned with gravity. */
internal val UP = floatArrayOf(0f, 1f, 0f)

/** Taps closer together than this can't define a wall direction reliably. */
internal const val MIN_WALL_TAP_DISTANCE_METRES = 0.2f

/**
 * A wall the user marked by tapping two points where it meets the floor.
 *
 * The anchor sits on the floor halfway between the taps and was created with no rotation, so
 * its local axes started out equal to the world axes; ARCore later adjusts its pose as the map
 * is refined. [alongWall] and [normal] are horizontal unit vectors in that local frame.
 * [outline] is the wall rectangle as (u, v) pairs: u metres along the wall, v metres up.
 */
class ManualWall(
    val anchor: Anchor,
    val alongWall: FloatArray,
    val normal: FloatArray,
    val outline: FloatArray,
)

internal class WallGeometry(
    val midpoint: FloatArray,
    val alongWall: FloatArray,
    /** Faces the side the user was standing on. */
    val normal: FloatArray,
    val halfLength: Float,
)

/**
 * The vertical wall through two floor points [p1] and [p2], facing [viewer].
 * Returns null if the points are too close together to give a direction.
 */
internal fun wallFromFloorPoints(p1: FloatArray, p2: FloatArray, viewer: FloatArray): WallGeometry? {
    val dx = p2[0] - p1[0]
    val dz = p2[2] - p1[2]
    val length = hypot(dx, dz)
    if (length < MIN_WALL_TAP_DISTANCE_METRES) return null
    val along = floatArrayOf(dx / length, 0f, dz / length)
    val midpoint = floatArrayOf((p1[0] + p2[0]) / 2f, (p1[1] + p2[1]) / 2f, (p1[2] + p2[2]) / 2f)
    var normal = floatArrayOf(-along[2], 0f, along[0])
    val towardsViewer = (viewer[0] - midpoint[0]) * normal[0] + (viewer[2] - midpoint[2]) * normal[2]
    if (towardsViewer < 0f) normal = floatArrayOf(-normal[0], 0f, -normal[2])
    return WallGeometry(midpoint, along, normal, length / 2f)
}

/** Rectangle outline (u, v pairs) for a wall [halfLength] each side of its midpoint, [height] tall. */
internal fun wallOutline(halfLength: Float, margin: Float, height: Float): FloatArray {
    val half = halfLength + margin
    return floatArrayOf(-half, 0f, half, 0f, half, height, -half, height)
}

/**
 * Direction of the camera ray through a screen point, in camera space, scaled so its z is -1
 * (so the distance along the ray in those units equals depth along the camera axis).
 *
 * [projection] is ARCore's column-major OpenGL projection matrix; [ndcX] and [ndcY] are in
 * -1..1 with y pointing up.
 */
internal fun cameraRayDirection(ndcX: Float, ndcY: Float, projection: FloatArray): FloatArray =
    floatArrayOf((ndcX + projection[8]) / projection[0], (ndcY + projection[9]) / projection[5], -1f)

/**
 * Distance t along [direction] from [origin] to the plane through [planePoint] with
 * [planeNormal], or null if the ray is parallel to the plane or the plane is behind it.
 */
internal fun intersectRayPlane(
    origin: FloatArray,
    direction: FloatArray,
    planePoint: FloatArray,
    planeNormal: FloatArray,
): Float? {
    val denominator = dot(direction, planeNormal)
    if (kotlin.math.abs(denominator) < 1e-6f) return null
    val t = ((planePoint[0] - origin[0]) * planeNormal[0] +
        (planePoint[1] - origin[1]) * planeNormal[1] +
        (planePoint[2] - origin[2]) * planeNormal[2]) / denominator
    return t.takeIf { it > 0f }
}

internal fun dot(a: FloatArray, b: FloatArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
