package com.arhomedecor.app.render

import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.IndexBuffer
import com.google.android.filament.Material
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager
import com.google.android.filament.Scene
import com.google.android.filament.Texture
import com.google.android.filament.TextureSampler
import com.google.android.filament.VertexBuffer
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import kotlin.math.hypot

enum class PlaneKind { FLOOR, RAISED_SURFACE, WALL, CEILING }

/** Counts of planes currently shown, by kind. [walls] counts ARCore-detected walls only. */
data class PlaneCounts(
    val floors: Int = 0,
    val raisedSurfaces: Int = 0,
    val walls: Int = 0,
    val ceilings: Int = 0,
    val markedWalls: Int = 0,
)

/** Upward-facing planes within this height of their local floor count as floor. */
internal const val FLOOR_TOLERANCE_METRES = 0.10f

/** Hysteresis: a floor must rise this far to become a raised surface... */
internal const val FLOOR_EXIT_METRES = 0.14f

/** ...and a raised surface must drop this low to become floor again. */
internal const val FLOOR_ENTER_METRES = 0.07f

/** Planes whose footprints come this close count as neighbours when finding the local floor. */
internal const val NEIGHBOUR_GAP_METRES = 1.0f

/** Footprint of an upward-facing plane: centre height and position, and outline radius. */
internal class HorizontalPlaneInfo(val y: Float, val x: Float, val z: Float, val radius: Float)

/**
 * ARCore labels every upward-facing surface the same way (floor, table, bed), so each plane's
 * floor is the lowest upward plane among its neighbours (including itself). Looking only
 * nearby matters: another room's floor can sit lower, through a real step or slight drift,
 * and must not turn this room's floor into a "raised surface".
 */
internal fun localFloorLevels(planes: List<HorizontalPlaneInfo>, neighbourGap: Float = NEIGHBOUR_GAP_METRES): FloatArray =
    FloatArray(planes.size) { i ->
        val plane = planes[i]
        var lowest = plane.y
        for (other in planes) {
            val gap = hypot(plane.x - other.x, plane.z - other.z) - plane.radius - other.radius
            if (gap <= neighbourGap && other.y < lowest) lowest = other.y
        }
        lowest
    }

/**
 * Floor or raised surface, from the plane's height above its local floor. [wasFloor] (the
 * previous classification, null for a new plane) adds hysteresis so a plane near the
 * threshold doesn't flicker between the two.
 */
internal fun classifyUpward(planeY: Float, localFloorY: Float, wasFloor: Boolean?): PlaneKind {
    val threshold = when (wasFloor) {
        true -> FLOOR_EXIT_METRES
        false -> FLOOR_ENTER_METRES
        null -> FLOOR_TOLERANCE_METRES
    }
    return if (planeY - localFloorY <= threshold) PlaneKind.FLOOR else PlaneKind.RAISED_SURFACE
}

/**
 * Draws every tracked ARCore plane, plus walls the user marked, as a semi-transparent polygon
 * with a metric grid.
 *
 * When a depth image is available, each grid pixel is only drawn where the measured real
 * surface is at the same distance as the plane. That hides grid that is covered by real
 * objects or that overhangs past the real edge of a surface.
 */
class PlaneRenderer(
    private val engine: Engine,
    private val scene: Scene,
    material: Material,
) {
    private class PlaneMesh(val entity: Int) {
        lateinit var vertexBuffer: VertexBuffer
        lateinit var indexBuffer: IndexBuffer
        var capacity = 0
        var hasRenderable = false
        var inScene = false
        var kind: PlaneKind? = null
        /** Whether the current geometry was sized for depth trimming (see [update]). */
        var builtForDepth = false
        var lastBuildNanos = 0L
    }

    /** Classification of each tracked ARCore plane from the last update. */
    private var kinds: Map<Plane, PlaneKind> = emptyMap()

    fun kindOf(plane: Plane): PlaneKind? = kinds[plane]

    /** One material instance per kind: colour differs, depth inputs are shared. */
    private val kindMaterials: Map<PlaneKind, MaterialInstance> = PlaneKind.entries.associateWith { kind ->
        material.createInstance().apply {
            val (r, g, b, a) = colorFor(kind)
            setParameter("color", r, g, b, a)
            setParameter("gridSpacing", GRID_SPACING_METRES)
            setParameter("depthEnabled", 0f)
            // Nothing real lies beyond a floor or ceiling, so "farther" readings there are noise.
            val checkOverhang = kind == PlaneKind.RAISED_SURFACE || kind == PlaneKind.WALL
            setParameter("hideWhenFarther", if (checkOverhang) 1f else 0f)
            setParameter("baseTolerance", if (kind == PlaneKind.WALL) WALL_TOLERANCE_METRES else TOLERANCE_METRES)
        }
    }
    private val depthSampler = TextureSampler(
        TextureSampler.MinFilter.LINEAR,
        TextureSampler.MagFilter.LINEAR,
        TextureSampler.WrapMode.CLAMP_TO_EDGE,
    )

    /** Keyed by ARCore [Plane] or [ManualWall]. */
    private val meshes = HashMap<Any, PlaneMesh>()
    private val transform = FloatArray(16)

    var counts = PlaneCounts()
        private set

    /** World-space height of the floor nearest the camera, or null if none is tracked. */
    var floorY: Float? = null
        private set

    /** World-space height of the lowest ceiling plane, or null if none is tracked. */
    var ceilingY: Float? = null
        private set

    /** Heights of raised surfaces above their local floor, in metres, for diagnostics. */
    var raisedSurfaceHeights: List<Float> = emptyList()
        private set

    /** Planes ARCore permanently discarded during the last update (all at once = map reset). */
    var discardedLastUpdate = 0
        private set

    /**
     * Planes ARCore has put on hold. This happens when it loses its place and starts a fresh
     * map: the old planes come back if the camera sees an already-mapped area again.
     */
    var pausedPlanes = 0
        private set

    /** ARCore vertical planes hidden because they lie on a marked wall. */
    var planesOnMarkedWalls = 0
        private set

    /**
     * [ndcToUv] maps screen NDC to depth-image UV (see [ndcToUvAffine]).
     * Pass [enabled] = false when there is no depth image; planes are then drawn unchecked.
     */
    fun setDepthInputs(texture: Texture, ndcToUv: FloatArray, enabled: Boolean) {
        for (instance in kindMaterials.values) {
            instance.setParameter("depthTexture", texture, depthSampler)
            instance.setParameter("uvFromNdcU", ndcToUv[0], ndcToUv[1], ndcToUv[2])
            instance.setParameter("uvFromNdcV", ndcToUv[3], ndcToUv[4], ndcToUv[5])
            instance.setParameter("depthEnabled", if (enabled) 1f else 0f)
        }
    }

    /**
     * With [depthAvailable], ARCore planes are drawn larger than their detected outline and
     * left to the depth check to cut back to the real surface's edges: ARCore's outline is only
     * a hull of the points it happened to track, while depth sees the whole surface.
     *
     * ARCore refines planes almost every frame; outlines are rebuilt at most every
     * [REBUILD_INTERVAL_NANOS] per plane, since rebuilding allocates new GPU buffers.
     */
    fun update(
        session: Session,
        frame: Frame,
        manualWalls: Collection<ManualWall>,
        depthAvailable: Boolean,
        cameraPose: Pose,
        nowNanos: Long,
    ) {
        val updated = frame.getUpdatedTrackables(Plane::class.java).toHashSet()
        val allPlanes = session.getAllTrackables(Plane::class.java).filter { it.subsumedBy == null }
        val tracked = allPlanes.filter { it.trackingState == TrackingState.TRACKING }
        pausedPlanes = allPlanes.count { it.trackingState == TrackingState.PAUSED }

        val markedWallPlanes = manualWalls
            .filter { it.anchor.trackingState == TrackingState.TRACKING }
            .map { wall ->
                val pose = wall.anchor.pose
                WallPlaneWorld(pose.translation, pose.rotateVector(wall.normal), pose.rotateVector(wall.alongWall), wall.outline[0], wall.outline[2])
            }
        var onMarkedWalls = 0

        val upward = tracked.filter { it.type == Plane.Type.HORIZONTAL_UPWARD_FACING }
        val upwardInfo = upward.map { it.horizontalInfo() }
        val localFloors = localFloorLevels(upwardInfo)
        val newKinds = HashMap<Plane, PlaneKind>(tracked.size)
        val localFloorOf = HashMap<Plane, Float>(upward.size)
        upward.forEachIndexed { i, plane ->
            val wasFloor = (meshes[plane]?.kind ?: kinds[plane])?.let { it == PlaneKind.FLOOR }
            newKinds[plane] = classifyUpward(upwardInfo[i].y, localFloors[i], wasFloor)
            localFloorOf[plane] = localFloors[i]
        }
        for (plane in tracked) {
            when (plane.type) {
                Plane.Type.VERTICAL -> newKinds[plane] = PlaneKind.WALL
                Plane.Type.HORIZONTAL_DOWNWARD_FACING -> newKinds[plane] = PlaneKind.CEILING
                Plane.Type.HORIZONTAL_UPWARD_FACING -> Unit
            }
        }
        kinds = newKinds
        floorY = nearestFloorY(upward, upwardInfo, cameraPose)
        ceilingY = tracked
            .filter { it.type == Plane.Type.HORIZONTAL_DOWNWARD_FACING }
            .minOfOrNull { it.centerPose.ty() }

        val renderables = engine.renderableManager
        val visible = HashSet<Any>()
        val raised = ArrayList<Float>()
        var floors = 0
        var walls = 0
        var ceilings = 0

        for (plane in tracked) {
            val pose = plane.centerPose
            val kind = newKinds.getValue(plane)
            if (kind == PlaneKind.WALL && markedWallPlanes.isNotEmpty()) {
                val normal = pose.rotateVector(UP) // a plane's local Y axis is its normal
                val radius = plane.outlineRadius()
                if (markedWallPlanes.any { liesOnWall(pose.translation, normal, radius, it) }) {
                    onMarkedWalls++
                    continue // not visible: hidden by the loop below
                }
            }
            val mesh = meshes.getOrPut(plane) { createMesh() }
            val rebuildDue = plane in updated && nowNanos - mesh.lastBuildNanos >= REBUILD_INTERVAL_NANOS
            // With depth, the outline is enlarged by an amount that depends on the kind.
            val kindChangesSize = depthAvailable && mesh.kind != null && mesh.kind != kind
            val needsGeometry = !mesh.hasRenderable || mesh.builtForDepth != depthAvailable || rebuildDue || kindChangesSize
            if (needsGeometry) {
                val polygon = plane.polygon
                // ARCore polygons are (x, z) pairs in the plane's local frame (the plane is y = 0).
                var outline = FloatArray(polygon.remaining()) { polygon.get(it) }
                val feather = if (depthAvailable) {
                    outline = dilateConvexOutline(outline, depthExpansionFor(kind))
                    DEPTH_FEATHER_METRES
                } else {
                    FEATHER_METRES
                }
                if (!updateGeometry(outline, PLANE_AXIS_X, PLANE_AXIS_Z, feather, mesh, kind)) continue
                mesh.builtForDepth = depthAvailable
                mesh.lastBuildNanos = nowNanos
            }

            if (kind != mesh.kind) {
                renderables.setMaterialInstanceAt(renderables.getInstance(mesh.entity), 0, kindMaterials.getValue(kind))
                mesh.kind = kind
            }
            show(mesh, pose)
            visible += plane
            when (kind) {
                PlaneKind.FLOOR -> floors++
                PlaneKind.RAISED_SURFACE -> raised += pose.ty() - (localFloorOf[plane] ?: pose.ty())
                PlaneKind.WALL -> walls++
                PlaneKind.CEILING -> ceilings++
            }
        }

        var marked = 0
        for (wall in manualWalls) {
            if (wall.anchor.trackingState != TrackingState.TRACKING) continue
            val mesh = meshes.getOrPut(wall) { createMesh() }
            val feather = if (depthAvailable) DEPTH_FEATHER_METRES else FEATHER_METRES
            if (!mesh.hasRenderable || mesh.builtForDepth != depthAvailable) {
                if (!updateGeometry(wall.outline, wall.alongWall, UP, feather, mesh, PlaneKind.WALL)) continue
                mesh.builtForDepth = depthAvailable
            }
            show(mesh, wall.anchor.pose)
            visible += wall
            marked++
        }
        counts = PlaneCounts(floors, raised.size, walls, ceilings, marked)
        raisedSurfaceHeights = raised.sorted()
        planesOnMarkedWalls = onMarkedWalls

        val markedSet = manualWalls.toHashSet()
        var discarded = 0
        val iterator = meshes.entries.iterator()
        while (iterator.hasNext()) {
            val (key, mesh) = iterator.next()
            if (key in visible) continue
            val gone = when (key) {
                // Merged planes (subsumed) live on in the plane that absorbed them; only
                // STOPPED ones are really gone.
                is Plane -> key.trackingState == TrackingState.STOPPED || key.subsumedBy != null
                is ManualWall -> key !in markedSet || key.anchor.trackingState == TrackingState.STOPPED
                else -> true
            }
            if (gone) {
                if (key is Plane && key.trackingState == TrackingState.STOPPED) discarded++
                destroyMesh(mesh)
                iterator.remove()
            } else if (mesh.inScene) {
                // Temporarily lost: hide but keep, it may come back.
                scene.remove(mesh.entity)
                mesh.inScene = false
            }
        }
        discardedLastUpdate = discarded
    }

    /** Height of the floor plane closest to the camera horizontally (its outline, not centre). */
    private fun nearestFloorY(upward: List<Plane>, info: List<HorizontalPlaneInfo>, cameraPose: Pose): Float? {
        var bestY: Float? = null
        var bestGap = Float.MAX_VALUE
        upward.forEachIndexed { i, plane ->
            if (kinds[plane] != PlaneKind.FLOOR) return@forEachIndexed
            val gap = maxOf(0f, hypot(cameraPose.tx() - info[i].x, cameraPose.tz() - info[i].z) - info[i].radius)
            if (gap < bestGap) {
                bestGap = gap
                bestY = info[i].y
            }
        }
        return bestY
    }

    private fun Plane.horizontalInfo(): HorizontalPlaneInfo {
        val pose = centerPose
        return HorizontalPlaneInfo(pose.ty(), pose.tx(), pose.tz(), outlineRadius())
    }

    /** Outline vertices are relative to the plane's centre, so the longest is the radius. */
    private fun Plane.outlineRadius(): Float {
        val polygon = polygon
        var radius = 0f
        for (i in 0 until polygon.remaining() / 2) {
            radius = maxOf(radius, hypot(polygon.get(i * 2), polygon.get(i * 2 + 1)))
        }
        return radius
    }

    private fun show(mesh: PlaneMesh, pose: Pose) {
        pose.toMatrix(transform, 0)
        engine.transformManager.setTransform(engine.transformManager.getInstance(mesh.entity), transform)
        if (!mesh.inScene) {
            scene.addEntity(mesh.entity)
            mesh.inScene = true
        }
    }

    fun destroy() {
        meshes.values.forEach(::destroyMesh)
        meshes.clear()
        kindMaterials.values.forEach(engine::destroyMaterialInstance)
    }

    private fun createMesh(): PlaneMesh {
        val entity = EntityManager.get().create()
        val mesh = PlaneMesh(entity)
        allocateBuffers(mesh, INITIAL_CAPACITY)
        engine.transformManager.create(entity)
        // The renderable itself is built on the first geometry update, once there is real data.
        return mesh
    }

    /**
     * Builds the mesh for a convex [outline] of (u, v) pairs. Vertices are placed at
     * u * [axisU] + v * [axisV] in the mesh's local frame; (u, v) also drive the metric grid.
     * Returns false if the polygon is degenerate (fewer than 3 vertices).
     */
    private fun updateGeometry(
        outline: FloatArray,
        axisU: FloatArray,
        axisV: FloatArray,
        feather: Float,
        mesh: PlaneMesh,
        kind: PlaneKind,
    ): Boolean {
        val vertexCount = outline.size / 2
        if (vertexCount < 3) return false

        if (vertexCount > mesh.capacity) {
            engine.destroyVertexBuffer(mesh.vertexBuffer)
            engine.destroyIndexBuffer(mesh.indexBuffer)
            allocateBuffers(mesh, maxOf(vertexCount, mesh.capacity * 2))
        }

        // Drawn with a soft edge: an outer ring (transparent) on the outline and an inner ring
        // (opaque) pulled [feather] metres towards the centre.
        var cx = 0f
        var cz = 0f
        for (i in 0 until vertexCount) {
            cx += outline[i * 2]
            cz += outline[i * 2 + 1]
        }
        cx /= vertexCount
        cz /= vertexCount

        val positions = FloatArray(vertexCount * 2 * 3)
        val uvs = FloatArray(vertexCount * 2 * 2)
        val edgeAlpha = FloatArray(vertexCount * 2 * 2)
        for (i in 0 until vertexCount) {
            val x = outline[i * 2]
            val z = outline[i * 2 + 1]
            val distance = hypot(x - cx, z - cz)
            val scale = if (distance > 0f) maxOf(MIN_INNER_SCALE, 1f - feather / distance) else 0f
            val ix = cx + (x - cx) * scale
            val iz = cz + (z - cz) * scale
            val inner = vertexCount + i

            for (axis in 0 until 3) {
                positions[i * 3 + axis] = x * axisU[axis] + z * axisV[axis]
                positions[inner * 3 + axis] = ix * axisU[axis] + iz * axisV[axis]
            }
            uvs[i * 2] = x
            uvs[i * 2 + 1] = z
            uvs[inner * 2] = ix
            uvs[inner * 2 + 1] = iz
            edgeAlpha[inner * 2] = 1f
        }
        val indices = featheredPolygonIndices(vertexCount)
        mesh.vertexBuffer.setBufferAt(engine, 0, floatBufferOf(positions))
        mesh.vertexBuffer.setBufferAt(engine, 1, floatBufferOf(uvs))
        mesh.vertexBuffer.setBufferAt(engine, 2, floatBufferOf(edgeAlpha))
        mesh.indexBuffer.setBuffer(engine, shortBufferOf(indices))

        if (!mesh.hasRenderable) {
            RenderableManager.Builder(1)
                .geometry(0, RenderableManager.PrimitiveType.TRIANGLES, mesh.vertexBuffer, mesh.indexBuffer, 0, indices.size)
                .material(0, kindMaterials.getValue(kind))
                .culling(false)
                .castShadows(false)
                .receiveShadows(false)
                .build(engine, mesh.entity)
            mesh.hasRenderable = true
            mesh.kind = kind
        } else {
            val renderables = engine.renderableManager
            renderables.setGeometryAt(
                renderables.getInstance(mesh.entity), 0, RenderableManager.PrimitiveType.TRIANGLES,
                mesh.vertexBuffer, mesh.indexBuffer, 0, indices.size,
            )
        }
        return true
    }

    /** [capacity] is the number of outline vertices; each one becomes an outer and an inner vertex. */
    private fun allocateBuffers(mesh: PlaneMesh, capacity: Int) {
        mesh.vertexBuffer = VertexBuffer.Builder()
            .vertexCount(capacity * 2)
            .bufferCount(3)
            .attribute(VertexBuffer.VertexAttribute.POSITION, 0, VertexBuffer.AttributeType.FLOAT3, 0, 12)
            .attribute(VertexBuffer.VertexAttribute.UV0, 1, VertexBuffer.AttributeType.FLOAT2, 0, 8)
            // uv1.x carries the edge fade (0 on the outline, 1 inside).
            .attribute(VertexBuffer.VertexAttribute.UV1, 2, VertexBuffer.AttributeType.FLOAT2, 0, 8)
            .build(engine)
        mesh.indexBuffer = IndexBuffer.Builder()
            .indexCount(featheredIndexCount(capacity))
            .bufferType(IndexBuffer.Builder.IndexType.USHORT)
            .build(engine)
        mesh.capacity = capacity
    }

    private fun destroyMesh(mesh: PlaneMesh) {
        if (mesh.inScene) scene.remove(mesh.entity)
        engine.destroyEntity(mesh.entity)
        EntityManager.get().destroy(mesh.entity)
        engine.destroyVertexBuffer(mesh.vertexBuffer)
        engine.destroyIndexBuffer(mesh.indexBuffer)
    }

    private companion object {
        val PLANE_AXIS_X = floatArrayOf(1f, 0f, 0f)
        val PLANE_AXIS_Z = floatArrayOf(0f, 0f, 1f)

        const val GRID_SPACING_METRES = 0.25f

        /** Edge fade when ARCore's outline is all there is. */
        const val FEATHER_METRES = 0.15f

        /** Edge fade when depth trims the surface: depth already places the edge. */
        const val DEPTH_FEATHER_METRES = 0.05f

        const val TOLERANCE_METRES = 0.05f
        const val WALL_TOLERANCE_METRES = 0.10f

        /** Outline rebuilds per plane at most 10 times a second. */
        const val REBUILD_INTERVAL_NANOS = 100_000_000L

        /**
         * How far past ARCore's outline a surface is drawn when depth can trim it. Floors get
         * the most: they usually extend well beyond what was tracked, and walls and furniture
         * standing on them hide the excess.
         */
        fun depthExpansionFor(kind: PlaneKind): Float = when (kind) {
            PlaneKind.FLOOR -> 0.6f
            PlaneKind.RAISED_SURFACE, PlaneKind.WALL -> 0.3f
            PlaneKind.CEILING -> 0f
        }

        /** Keeps small planes visible: the inner ring never shrinks below this fraction. */
        const val MIN_INNER_SCALE = 0.3f
        const val INITIAL_CAPACITY = 16

        // Linear RGB + alpha.
        val FLOOR_COLOR = floatArrayOf(0.0f, 0.75f, 0.85f, 0.22f)
        val RAISED_SURFACE_COLOR = floatArrayOf(1.0f, 0.65f, 0.0f, 0.22f)
        val WALL_COLOR = floatArrayOf(0.95f, 0.30f, 0.65f, 0.22f)
        val CEILING_COLOR = floatArrayOf(0.7f, 0.7f, 0.7f, 0.12f)

        fun colorFor(kind: PlaneKind): FloatArray = when (kind) {
            PlaneKind.FLOOR -> FLOOR_COLOR
            PlaneKind.RAISED_SURFACE -> RAISED_SURFACE_COLOR
            PlaneKind.WALL -> WALL_COLOR
            PlaneKind.CEILING -> CEILING_COLOR
        }
    }
}

/**
 * Pushes every vertex of a convex outline ((u, v) pairs) [distance] further from the outline's
 * centroid. The result is still convex; a radial push is not an exact offset curve, but for
 * ARCore's hulls (many short edges) the difference is negligible.
 */
internal fun dilateConvexOutline(outline: FloatArray, distance: Float): FloatArray {
    if (distance == 0f) return outline
    val count = outline.size / 2
    var cu = 0f
    var cv = 0f
    for (i in 0 until count) {
        cu += outline[i * 2]
        cv += outline[i * 2 + 1]
    }
    cu /= count
    cv /= count
    val result = FloatArray(outline.size)
    for (i in 0 until count) {
        val du = outline[i * 2] - cu
        val dv = outline[i * 2 + 1] - cv
        val length = hypot(du, dv)
        val scale = if (length > 0f) (length + distance) / length else 1f
        result[i * 2] = cu + du * scale
        result[i * 2 + 1] = cv + dv * scale
    }
    return result
}

internal fun featheredIndexCount(vertexCount: Int) = (vertexCount - 2) * 3 + vertexCount * 6

/**
 * Indices for a convex polygon (ARCore plane polygons are convex) drawn with a soft edge.
 * Vertices 0 until n are the outer ring and n until 2n the inner ring. The inner ring is filled
 * as a triangle fan and the band between the two rings as one quad per edge.
 */
internal fun featheredPolygonIndices(vertexCount: Int): ShortArray {
    require(vertexCount >= 3) { "A polygon needs at least 3 vertices, got $vertexCount" }
    val n = vertexCount
    val indices = ShortArray(featheredIndexCount(n))
    var k = 0
    fun triangle(a: Int, b: Int, c: Int) {
        indices[k++] = a.toShort()
        indices[k++] = b.toShort()
        indices[k++] = c.toShort()
    }
    for (i in 1 until n - 1) triangle(n, n + i, n + i + 1)
    for (i in 0 until n) {
        val next = (i + 1) % n
        triangle(i, next, n + i)
        triangle(next, n + next, n + i)
    }
    return indices
}
