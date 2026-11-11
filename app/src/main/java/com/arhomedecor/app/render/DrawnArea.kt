package com.arhomedecor.app.render

import java.util.Locale

/** What this frame's depth image says about a point on a horizontal surface. */
internal enum class DepthVerdict {
    /** Not on screen. */
    OUT_OF_VIEW,

    /** On screen, but the depth image has no value there. */
    NO_DEPTH,

    /** The real surface is at the surface's distance. */
    AGREES,

    /** The real surface is clearly lower: seen past the surface's edge (or a reflection). */
    LOWER,

    /** Something stands in front of the surface. */
    IN_FRONT,
}

/**
 * Where a floor's or raised surface's grid is drawn, and on what evidence, in square metres: for
 * finding out, from a log, why a grid appeared somewhere it shouldn't (see [measureDrawnArea]).
 */
internal class DrawnArea {
    /** Inside ARCore's own outline, and how much of that measured depth confirmed or refuted. */
    var inside = 0f
    var insideConfirmed = 0f
    var insideRefuted = 0f

    /** In the margin drawn beyond ARCore's outline, likewise. */
    var margin = 0f
    var marginConfirmed = 0f
    var marginRefuted = 0f

    /** Of the part on screen: how much is drawn. */
    var shown = 0f

    /** Drawn although this frame's depth sees the real surface clearly lower there. */
    var shownOverLower = 0f

    /**
     * Drawn inside ARCore's outline without confirmation, only because this frame's depth doesn't
     * see anything lower there (raised surfaces only; floors are drawn inside their outline
     * unless refuted).
     */
    var shownOnDepthAlone = 0f

    fun describe(): String = String.format(
        Locale.US,
        "inside=%.2f (confirmed %.2f, refuted %.2f) margin=%.2f (confirmed %.2f, refuted %.2f) " +
            "inView: shown=%.2f shownOverLower=%.2f shownOnDepthAlone=%.2f",
        inside, insideConfirmed, insideRefuted, margin, marginConfirmed, marginRefuted,
        shown, shownOverLower, shownOnDepthAlone,
    )
}

/**
 * At or below this [coverageValue] a cell counts as refuted (plane.mat hides it from about 0.3
 * down, fully at 0.1).
 */
internal const val COVERAGE_REFUTED_VALUE = 0.2f

/**
 * Measures a surface's drawn area on a grid of [step]-metre cells, following plane.mat's rules
 * for surfaces with a coverage map: inside ARCore's outline the grid is drawn unless measured
 * depth refuted it there (and, for raised surfaces, [checksBehindInside], unless unconfirmed and
 * this frame's depth sees something lower); in the margin beyond it, only where measured depth
 * confirmed it. Things standing in front, which also hide the grid, are left out.
 *
 * [arcoreOutline] and [drawnOutline] are world (x, z) pairs. [coverageAt] gives the coverage
 * value at a point and [depthAt] this frame's verdict there.
 */
internal fun measureDrawnArea(
    arcoreOutline: FloatArray,
    drawnOutline: FloatArray,
    checksBehindInside: Boolean,
    step: Float,
    coverageAt: (x: Float, z: Float) -> Float,
    depthAt: (x: Float, z: Float) -> DepthVerdict,
): DrawnArea {
    val area = DrawnArea()
    if (drawnOutline.size < 6) return area
    var minX = Float.MAX_VALUE
    var maxX = -Float.MAX_VALUE
    var minZ = Float.MAX_VALUE
    var maxZ = -Float.MAX_VALUE
    for (i in 0 until drawnOutline.size / 2) {
        minX = minOf(minX, drawnOutline[i * 2])
        maxX = maxOf(maxX, drawnOutline[i * 2])
        minZ = minOf(minZ, drawnOutline[i * 2 + 1])
        maxZ = maxOf(maxZ, drawnOutline[i * 2 + 1])
    }
    val cellArea = step * step
    var z = minZ + step / 2
    while (z < maxZ) {
        var x = minX + step / 2
        while (x < maxX) {
            if (distanceToPolygon(x, z, drawnOutline) == 0f) {
                val inside = distanceToPolygon(x, z, arcoreOutline) == 0f
                val coverage = coverageAt(x, z)
                val confirmed = coverage >= COVERAGE_DRAWN_VALUE
                val refuted = coverage <= COVERAGE_REFUTED_VALUE
                if (inside) {
                    area.inside += cellArea
                    if (confirmed) area.insideConfirmed += cellArea
                    if (refuted) area.insideRefuted += cellArea
                } else {
                    area.margin += cellArea
                    if (confirmed) area.marginConfirmed += cellArea
                    if (refuted) area.marginRefuted += cellArea
                }
                val verdict = depthAt(x, z)
                if (verdict != DepthVerdict.OUT_OF_VIEW) {
                    val onDepthAlone = inside && checksBehindInside && !confirmed && !refuted
                    val shown = if (inside) {
                        !refuted && (confirmed || !checksBehindInside || verdict != DepthVerdict.LOWER)
                    } else {
                        confirmed
                    }
                    if (shown) {
                        area.shown += cellArea
                        if (verdict == DepthVerdict.LOWER) area.shownOverLower += cellArea
                        if (onDepthAlone) area.shownOnDepthAlone += cellArea
                    }
                }
            }
            x += step
        }
        z += step
    }
    return area
}

/** Depth error per metre of distance, as DEPTH_NOISE_PER_METRE in plane.mat. */
internal const val DEPTH_NOISE_PER_METRE = 0.02f

/**
 * What this frame's depth says at world point ([x], [y], [z]) on a horizontal surface, judged as
 * plane.mat judges it. [view] (world to view) and [projection] are the column-major matrices the
 * frame was drawn with, [cameraY] the camera's height, [ndcToUv] the map from screen NDC to
 * depth-image UV (see ndcToUvAffine), and [depthAt] the depth in metres at a UV (0 if unknown).
 */
internal fun depthVerdict(
    x: Float,
    y: Float,
    z: Float,
    view: FloatArray,
    cameraY: Float,
    projection: FloatArray,
    ndcToUv: FloatArray,
    baseTolerance: Float,
    depthAt: (u: Float, v: Float) -> Float,
): DepthVerdict {
    val v = view
    val vx = v[0] * x + v[4] * y + v[8] * z + v[12]
    val vy = v[1] * x + v[5] * y + v[9] * z + v[13]
    val vz = v[2] * x + v[6] * y + v[10] * z + v[14]
    if (vz >= -NEAR_METRES) return DepthVerdict.OUT_OF_VIEW
    val p = projection
    val w = p[3] * vx + p[7] * vy + p[11] * vz + p[15]
    val ndcX = (p[0] * vx + p[4] * vy + p[8] * vz + p[12]) / w
    val ndcY = (p[1] * vx + p[5] * vy + p[9] * vz + p[13]) / w
    if (ndcX < -1f || ndcX > 1f || ndcY < -1f || ndcY > 1f) return DepthVerdict.OUT_OF_VIEW
    val m = ndcToUv
    val realDepth = depthAt(m[0] * ndcX + m[1] * ndcY + m[2], m[3] * ndcX + m[4] * ndcY + m[5])
    if (realDepth <= 0f) return DepthVerdict.NO_DEPTH
    val planeDepth = -vz
    val cameraToPlane = kotlin.math.abs(cameraY - y)
    // Positive: the real surface is in front of the plane (see checkDepth in plane.mat).
    val offset = (1f - realDepth / planeDepth) * cameraToPlane
    val alongNormal = cameraToPlane / kotlin.math.sqrt(vx * vx + vy * vy + vz * vz)
    val tolerance = baseTolerance + DEPTH_NOISE_PER_METRE * planeDepth * alongNormal
    // plane.mat fades the grid out between one and two tolerances; halfway counts.
    return when {
        -offset > 1.5f * tolerance -> DepthVerdict.LOWER
        offset > 1.5f * tolerance -> DepthVerdict.IN_FRONT
        else -> DepthVerdict.AGREES
    }
}

private const val NEAR_METRES = 0.05f
