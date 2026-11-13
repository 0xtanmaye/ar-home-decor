package com.arhomedecor.app.render

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sqrt

/** Size of a coverage cell. Small enough for a clean edge, large enough to fill quickly. */
internal const val COVERAGE_CELL_METRES = 0.04f

/**
 * A cell is refuted (drawn nowhere) once at least this many frames saw through it...
 */
internal const val COVERAGE_MIN_REFUTING_FRAMES = 3

/**
 * ...and at least this many times as many frames saw through it as saw the surface there. A
 * glossy top reflects the room, which reads as seeing through it, but its own texture is
 * measured too; a real gap (the floor past a table's edge, a lower cabinet ARCore merged into a
 * desk) is only ever seen through.
 */
internal const val COVERAGE_REFUTING_RATIO = 4

/** Frames that saw the surface in a cell for it to count as fully confirmed. */
internal const val COVERAGE_CONFIRMING_FRAMES = 4

/** Frame counts stop growing here, so old evidence can still be outweighed. */
internal const val COVERAGE_MAX_FRAMES = 30

/**
 * A measured point within this distance of a surface's height (plus [COVERAGE_TOLERANCE_PER_METRE]
 * per metre from the camera) lies on it.
 */
internal const val COVERAGE_TOLERANCE_METRES = 0.025f
internal const val COVERAGE_TOLERANCE_PER_METRE = 0.01f

/**
 * What a cell's frame counts say, from 0 to 1: 0 = refuted (repeatedly seen through and hardly
 * ever seen), 0.5 = no clear evidence either way, rising to 1 as more frames saw the surface
 * there than saw through it.
 */
/**
 * From this [coverageValue] on (measured in two more frames than seen through), a cell beyond
 * ARCore's outline is drawn clearly (plane.mat fades confirmed cells in between 0.6 and 0.85),
 * so a tap there counts as a tap on the surface.
 */
internal const val COVERAGE_DRAWN_VALUE = 0.75f

internal fun coverageValue(seen: Int, seenThrough: Int): Float = when {
    seenThrough >= COVERAGE_MIN_REFUTING_FRAMES && seenThrough >= COVERAGE_REFUTING_RATIO * seen -> 0f
    seen > seenThrough -> 0.5f + 0.5f * minOf(1f, seen.toFloat() / COVERAGE_CONFIRMING_FRAMES)
    else -> 0.5f
}

/**
 * Where a horizontal surface really extends, built up over time from depth that ARCore measured
 * (raw depth), as a grid of cells in world (x, z).
 *
 * ARCore's own outline of a surface is a hull of the points it tracked: it often stops short of
 * the real edge, and when ARCore merges two nearby surfaces it spans the gap between them. The
 * smoothed depth image covers everything, but on plain or glossy surfaces it is filled in rather
 * than measured, so it can agree with a surface where there is none. Each cell here counts the
 * frames in which measurements saw the surface there and (optionally) the frames in which they
 * saw through it, to something below; see [coverageValue].
 */
class SurfaceCoverage {
    // Frame counts per cell: seen in the low 16 bits, seen through in the high 16 bits.
    private val counts = HashMap<Long, Int>()

    // Per-frame tallies, reused between calls.
    private val frameVotes = HashMap<Long, Int>()

    /** Changes each time the evidence changes, so callers can tell when to redraw. */
    var version = 0
        private set

    /** For diagnostics: points at the surface's height in the last [observe], and how many of them were steep. */
    var lastPointsAtHeight = 0
        private set
    var lastSteepAtHeight = 0
        private set

    /** Cells at or above [COVERAGE_DRAWN_VALUE]: where measured depth has confirmed the surface. */
    private var confirmedCells = 0

    /** Area that measured depth has confirmed, in square metres. */
    val confirmedArea: Float get() = confirmedCells * COVERAGE_CELL_METRES * COVERAGE_CELL_METRES

    /**
     * Adds one frame of measurements: [count] world points in [points] (x, y, z triples) seen
     * from [camera] (x, y, z), against a surface at height [surfaceY].
     *
     * A point at the surface's height votes for the cell it lies in, unless [steep] marks it as
     * lying on a steep surface: a wall or the side of a cabinet passes through a table's height
     * too, and would otherwise confirm the table along it. If
     * [countsAgainst], a point clearly below the surface votes against the cell where its line of
     * sight crossed the surface's height: the camera saw through the surface there. Each cell
     * then counts one frame for the side with more votes, so a burst of noisy pixels counts no
     * more than a single one.
     */
    fun observe(
        points: FloatArray,
        count: Int,
        camera: FloatArray,
        surfaceY: Float,
        countsAgainst: Boolean,
        steep: BooleanArray? = null,
    ) {
        val cameraAbove = camera[1] - surfaceY
        if (cameraAbove <= 0f) return // seen from below: not a surface to put things on
        frameVotes.clear()
        lastPointsAtHeight = 0
        lastSteepAtHeight = 0
        for (i in 0 until count) {
            val x = points[i * 3]
            val y = points[i * 3 + 1]
            val z = points[i * 3 + 2]
            val dx = x - camera[0]
            val dy = y - camera[1]
            val dz = z - camera[2]
            val tolerance = COVERAGE_TOLERANCE_METRES + COVERAGE_TOLERANCE_PER_METRE * sqrt(dx * dx + dy * dy + dz * dz)
            val height = y - surfaceY
            if (abs(height) <= tolerance) {
                lastPointsAtHeight++
                if (steep == null || !steep[i]) vote(cellKey(x, z), 1) else lastSteepAtHeight++
            } else if (countsAgainst && height < -tolerance) {
                // Where the line of sight crossed the surface's height on its way down to the point.
                val t = cameraAbove / (camera[1] - y)
                vote(cellKey(camera[0] + dx * t, camera[2] + dz * t), -1)
            }
        }
        var changed = false
        for ((key, votes) in frameVotes) {
            if (votes == 0) continue
            val packed = counts[key] ?: 0
            var seen = packed and 0xFFFF
            var seenThrough = packed ushr 16
            if (votes > 0) seen = minOf(seen + 1, COVERAGE_MAX_FRAMES) else seenThrough = minOf(seenThrough + 1, COVERAGE_MAX_FRAMES)
            setCounts(key, packed, seen or (seenThrough shl 16))
            changed = true
        }
        if (changed) version++
    }

    /** [coverageValue] of the cell containing (x, z). */
    fun valueAt(x: Float, z: Float): Float = valueOf(counts[cellKey(x, z)] ?: 0)

    /** Takes over another coverage's evidence, e.g. when ARCore merges its plane into this one. */
    fun absorb(other: SurfaceCoverage) {
        if (other.counts.isEmpty()) return
        for ((key, theirs) in other.counts) {
            val mine = counts[key] ?: 0
            val seen = minOf((mine and 0xFFFF) + (theirs and 0xFFFF), COVERAGE_MAX_FRAMES)
            val seenThrough = minOf((mine ushr 16) + (theirs ushr 16), COVERAGE_MAX_FRAMES)
            setCounts(key, mine, seen or (seenThrough shl 16))
        }
        version++
    }

    /** Replaces a cell's counts ([before] to [after]), keeping [confirmedCells] up to date. */
    private fun setCounts(key: Long, before: Int, after: Int) {
        counts[key] = after
        val wasConfirmed = valueOf(before) >= COVERAGE_DRAWN_VALUE
        val isConfirmed = valueOf(after) >= COVERAGE_DRAWN_VALUE
        if (isConfirmed != wasConfirmed) confirmedCells += if (isConfirmed) 1 else -1
    }

    /**
     * Writes [coverageValue] for each cell of a [columns] x [rows] block, starting at the cell
     * containing ([minX], [minZ]), as bytes (0..255): row by row, x increasing along a row and
     * z from row to row.
     */
    fun rasterize(minX: Float, minZ: Float, columns: Int, rows: Int, out: ByteArray) {
        val firstColumn = cellIndex(minX)
        val firstRow = cellIndex(minZ)
        for (row in 0 until rows) {
            for (column in 0 until columns) {
                val value = valueOf(counts[packKey(firstColumn + column, firstRow + row)] ?: 0)
                out[row * columns + column] = (value * 255f + 0.5f).toInt().toByte()
            }
        }
    }

    private fun vote(key: Long, vote: Int) {
        frameVotes[key] = (frameVotes[key] ?: 0) + vote
    }

    private fun cellKey(x: Float, z: Float): Long = packKey(cellIndex(x), cellIndex(z))

    companion object {
        private fun valueOf(packed: Int): Float = coverageValue(packed and 0xFFFF, packed ushr 16)

        /** Index of the cell containing [coordinate] along one axis. */
        internal fun cellIndex(coordinate: Float): Int = floor(coordinate / COVERAGE_CELL_METRES).toInt()

        private fun packKey(column: Int, row: Int): Long = (column.toLong() shl 32) or (row.toLong() and 0xFFFFFFFFL)
    }
}

/**
 * World position of a depth-image pixel at ([u], [v]) (pixel indices) measuring [depth] metres
 * along the camera axis, written to [out] at [offset].
 *
 * [intrinsics] are the depth image's (fx, fy, cx, cy) in pixels. [cameraToWorld] is the camera's
 * column-major pose matrix in ARCore's convention: +x towards increasing image column, +y towards
 * decreasing image row, looking along -z.
 */
internal fun depthPixelToWorld(
    u: Int,
    v: Int,
    depth: Float,
    intrinsics: FloatArray,
    cameraToWorld: FloatArray,
    out: FloatArray,
    offset: Int,
) {
    depthPixelToCamera(u, v, depth, intrinsics, out, offset)
    val x = out[offset]
    val y = out[offset + 1]
    val z = out[offset + 2]
    val m = cameraToWorld
    out[offset] = m[0] * x + m[4] * y + m[8] * z + m[12]
    out[offset + 1] = m[1] * x + m[5] * y + m[9] * z + m[13]
    out[offset + 2] = m[2] * x + m[6] * y + m[10] * z + m[14]
}

/** Like [depthPixelToWorld], but in the camera's own frame (+x right, +y up, looking along -z). */
internal fun depthPixelToCamera(u: Int, v: Int, depth: Float, intrinsics: FloatArray, out: FloatArray, offset: Int) {
    out[offset] = (u + 0.5f - intrinsics[2]) * depth / intrinsics[0]
    out[offset + 1] = -(v + 0.5f - intrinsics[3]) * depth / intrinsics[1]
    out[offset + 2] = -depth
}
