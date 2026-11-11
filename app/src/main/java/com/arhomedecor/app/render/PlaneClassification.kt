package com.arhomedecor.app.render

import kotlin.math.abs
import kotlin.math.hypot

/**
 * What a detected plane is taken to be. ARCore only reports orientation (upward, downward,
 * vertical); the rest is inferred from geometry by the rules in this file.
 */
enum class PlaneKind {
    FLOOR,

    /** Tables, beds and other upward-facing surfaces above the floor. */
    RAISED_SURFACE,
    WALL,
    CEILING,

    /**
     * A vertical plane on the side or front of a table or cabinet, or one too low to tell from
     * one (see [classifyVertical]): not drawn.
     */
    FURNITURE_SIDE,
}

/** Counts of planes currently shown, by kind. [walls] counts ARCore-detected walls only. */
data class PlaneCounts(
    val floors: Int = 0,
    val raisedSurfaces: Int = 0,
    val walls: Int = 0,
    val ceilings: Int = 0,
    val markedWalls: Int = 0,
    /** Vertical planes recognised as furniture sides and therefore hidden. */
    val furnitureSides: Int = 0,
)

/** Upward-facing planes within this height of their local floor count as floor. */
internal const val FLOOR_TOLERANCE_METRES = 0.10f

/** Hysteresis: a floor must rise this far to become a raised surface... */
internal const val FLOOR_EXIT_METRES = 0.14f

/** ...and a raised surface must drop this low to become floor again. */
internal const val FLOOR_ENTER_METRES = 0.07f

/** Planes whose footprints come this close count as neighbours when finding the local floor. */
internal const val NEIGHBOUR_GAP_METRES = 1.0f

/**
 * A plane the camera has never been at least this high above is not a floor the user is
 * standing on: people scan with the phone at waist to eye height, so it is a table or similar.
 */
internal const val MIN_CAMERA_HEIGHT_ABOVE_FLOOR_METRES = 0.7f

/** Footprint of an upward-facing plane: centre height and position, and outline radius. */
internal class HorizontalPlaneInfo(val y: Float, val x: Float, val z: Float, val radius: Float)

/**
 * For each plane, the index of the plane that is its local floor.
 *
 * ARCore labels every upward-facing surface the same way (floor, table, bed), so each plane's
 * floor is the lowest upward plane among its neighbours (including itself). Looking only
 * nearby matters: another room's floor can sit lower, through a real step or slight drift,
 * and must not turn this room's floor into a "raised surface".
 */
internal fun localFloorReferences(planes: List<HorizontalPlaneInfo>, neighbourGap: Float = NEIGHBOUR_GAP_METRES): IntArray =
    IntArray(planes.size) { i ->
        val plane = planes[i]
        var lowest = i
        planes.forEachIndexed { j, other ->
            val gap = hypot(plane.x - other.x, plane.z - other.z) - plane.radius - other.radius
            if (gap <= neighbourGap && other.y < planes[lowest].y) lowest = j
        }
        lowest
    }

/**
 * Floor or raised surface, from the plane's height above its local floor. [wasFloor] (the
 * previous classification, null for a new plane) adds hysteresis so a plane near the
 * threshold doesn't flicker between the two.
 *
 * The lowest plane nearby is not necessarily a floor: it may be a desk seen before the floor
 * around it, and then the desk's other pieces would count as floor too. [floorMaxCameraHeight],
 * the highest the camera has been above that local floor plane, settles it (see
 * [MIN_CAMERA_HEIGHT_ABOVE_FLOOR_METRES]).
 */
internal fun classifyUpward(
    planeY: Float,
    localFloorY: Float,
    wasFloor: Boolean?,
    floorMaxCameraHeight: Float = Float.MAX_VALUE,
): PlaneKind {
    if (floorMaxCameraHeight < MIN_CAMERA_HEIGHT_ABOVE_FLOOR_METRES) return PlaneKind.RAISED_SURFACE
    val threshold = when (wasFloor) {
        true -> FLOOR_EXIT_METRES
        false -> FLOOR_ENTER_METRES
        null -> FLOOR_TOLERANCE_METRES
    }
    return if (planeY - localFloorY <= threshold) PlaneKind.FLOOR else PlaneKind.RAISED_SURFACE
}

/** A furniture side's top edge lies within this height of the raised surface it belongs to... */
internal const val FURNITURE_TOP_MATCH_METRES = 0.15f

/** ...hangs at least this far below it... */
internal const val FURNITURE_MIN_DROP_METRES = 0.15f

/** ...and is horizontally this close to the surface's outline. */
internal const val FURNITURE_MAX_GAP_METRES = 0.4f

/** Looser limits for a plane already recognised as a furniture side, so it doesn't flicker. */
internal const val FURNITURE_TOP_MATCH_KEEP_METRES = 0.25f
internal const val FURNITURE_MAX_GAP_KEEP_METRES = 0.6f

/**
 * ARCore vertical planes whose top is lower than this above the floor are not shown as walls:
 * at that height they are usually the side of a bed, desk or cabinet, often found before the
 * top surface that would identify it. Walls continue higher; ARCore extends a wall plane upwards
 * as it sees more of it, and walls are marked by hand where it can't.
 */
internal const val MIN_WALL_TOP_ABOVE_FLOOR_METRES = 1.0f

/**
 * Wall or furniture side, for an ARCore vertical plane. [floorY] is the height of the floor near
 * it, or null if unknown.
 */
internal fun classifyVertical(
    side: VerticalPlaneExtent,
    raised: List<RaisedFootprint>,
    floorY: Float?,
    wasFurnitureSide: Boolean,
): PlaneKind {
    val tooLow = floorY != null && side.topY - floorY < MIN_WALL_TOP_ABOVE_FLOOR_METRES
    return if (tooLow || isFurnitureSide(side, raised, wasFurnitureSide)) PlaneKind.FURNITURE_SIDE else PlaneKind.WALL
}

/**
 * A vertical plane in world space: its lowest and highest points, and the two ends of its
 * horizontal extent as (x, z).
 */
internal class VerticalPlaneExtent(
    val bottomY: Float,
    val topY: Float,
    val endA: FloatArray,
    val endB: FloatArray,
)

/** A raised surface's height and its outline in world space, as (x, z) pairs. */
internal class RaisedFootprint(val y: Float, val outline: FloatArray)

/**
 * Whether a vertical plane is the side or front of a table, desk or cabinet rather than a wall:
 * its top edge meets a raised surface at that surface's height, and it extends down from there.
 * ARCore finds such panels as readily as walls (they are often more textured than plain walls),
 * but they are not surfaces anyone would paint.
 *
 * A wall section that happens to end at a table's height right beside the table would also
 * match; walls normally continue above furniture, so this is rare.
 */
internal fun isFurnitureSide(side: VerticalPlaneExtent, raised: List<RaisedFootprint>, wasFurnitureSide: Boolean): Boolean {
    val topMatch = if (wasFurnitureSide) FURNITURE_TOP_MATCH_KEEP_METRES else FURNITURE_TOP_MATCH_METRES
    val maxGap = if (wasFurnitureSide) FURNITURE_MAX_GAP_KEEP_METRES else FURNITURE_MAX_GAP_METRES
    return raised.any { surface ->
        abs(side.topY - surface.y) <= topMatch &&
            surface.y - side.bottomY >= FURNITURE_MIN_DROP_METRES &&
            segmentDistanceToPolygon(side.endA, side.endB, surface.outline) <= maxGap
    }
}

/**
 * Shortest horizontal distance between the segment [a]–[b] and a polygon ((x, z) pairs), or 0
 * if they touch. Sampling the segment is enough here: furniture sides are at most a few metres
 * wide and the answer is compared with a tolerance of tens of centimetres.
 */
internal fun segmentDistanceToPolygon(a: FloatArray, b: FloatArray, polygon: FloatArray): Float {
    val samples = 8
    var best = Float.MAX_VALUE
    for (i in 0..samples) {
        val t = i.toFloat() / samples
        val distance = distanceToPolygon(a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, polygon)
        if (distance < best) best = distance
    }
    return best
}

/** Distance from (x, z) to a simple polygon ((x, z) pairs): 0 inside, else to the nearest edge. */
internal fun distanceToPolygon(x: Float, z: Float, polygon: FloatArray): Float {
    val count = polygon.size / 2
    if (count < 3) return Float.MAX_VALUE
    var inside = false
    var nearest = Float.MAX_VALUE
    var j = count - 1
    for (i in 0 until count) {
        val xi = polygon[i * 2]
        val zi = polygon[i * 2 + 1]
        val xj = polygon[j * 2]
        val zj = polygon[j * 2 + 1]
        // Even-odd rule: count edges crossed by a ray from the point towards +x.
        if ((zi > z) != (zj > z) && x < (xj - xi) * (z - zi) / (zj - zi) + xi) inside = !inside
        nearest = minOf(nearest, distanceToSegment(x, z, xi, zi, xj, zj))
        j = i
    }
    return if (inside) 0f else nearest
}

private fun distanceToSegment(x: Float, z: Float, x1: Float, z1: Float, x2: Float, z2: Float): Float {
    val dx = x2 - x1
    val dz = z2 - z1
    val lengthSquared = dx * dx + dz * dz
    val t = if (lengthSquared > 0f) (((x - x1) * dx + (z - z1) * dz) / lengthSquared).coerceIn(0f, 1f) else 0f
    return hypot(x - (x1 + t * dx), z - (z1 + t * dz))
}
