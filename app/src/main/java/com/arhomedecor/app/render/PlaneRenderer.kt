package com.arhomedecor.app.render

import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.IndexBuffer
import com.google.android.filament.Material
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager
import com.google.android.filament.Scene
import com.google.android.filament.VertexBuffer
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import kotlin.math.hypot

enum class PlaneKind { FLOOR, RAISED_SURFACE, WALL, CEILING }

/** Counts of planes currently shown, by kind. */
data class PlaneCounts(val floors: Int = 0, val raisedSurfaces: Int = 0, val walls: Int = 0, val ceilings: Int = 0)

/** Upward-facing planes within this distance of the lowest one count as floor. */
internal const val FLOOR_TOLERANCE_METRES = 0.10f

/**
 * ARCore labels every upward-facing surface the same way (floor, table, bed), so the floor is
 * taken to be the lowest one, and anything noticeably higher is a raised surface.
 */
internal fun classifyPlane(type: Plane.Type, planeY: Float, floorY: Float?): PlaneKind = when (type) {
    Plane.Type.VERTICAL -> PlaneKind.WALL
    Plane.Type.HORIZONTAL_DOWNWARD_FACING -> PlaneKind.CEILING
    Plane.Type.HORIZONTAL_UPWARD_FACING ->
        if (floorY == null || planeY - floorY <= FLOOR_TOLERANCE_METRES) PlaneKind.FLOOR else PlaneKind.RAISED_SURFACE
}

/**
 * Draws every tracked ARCore plane as a semi-transparent polygon with a metric grid.
 * Floors, raised surfaces, walls and ceilings get different colours.
 */
class PlaneRenderer(
    private val engine: Engine,
    private val scene: Scene,
    private val material: Material,
) {
    private class PlaneMesh(val entity: Int, val materialInstance: MaterialInstance) {
        lateinit var vertexBuffer: VertexBuffer
        lateinit var indexBuffer: IndexBuffer
        var capacity = 0
        var hasRenderable = false
        var inScene = false
        var kind: PlaneKind? = null
    }

    private val meshes = HashMap<Plane, PlaneMesh>()
    private val transform = FloatArray(16)

    var counts = PlaneCounts()
        private set

    /** World-space height of the floor (lowest upward-facing plane), or null if none is tracked. */
    var floorY: Float? = null
        private set

    /** Heights of raised surfaces above the floor, in metres, for diagnostics. */
    var raisedSurfaceHeights: List<Float> = emptyList()
        private set

    fun update(session: Session, frame: Frame) {
        val updated = frame.getUpdatedTrackables(Plane::class.java).toHashSet()
        val tracked = session.getAllTrackables(Plane::class.java)
            .filter { it.trackingState == TrackingState.TRACKING && it.subsumedBy == null }
        val floor = tracked
            .filter { it.type == Plane.Type.HORIZONTAL_UPWARD_FACING }
            .minOfOrNull { it.centerPose.ty() }
        floorY = floor

        val visible = HashSet<Plane>()
        val raised = ArrayList<Float>()
        var floors = 0
        var walls = 0
        var ceilings = 0

        for (plane in tracked) {
            val mesh = meshes.getOrPut(plane) { createMesh() }
            val needsGeometry = !mesh.hasRenderable || plane in updated
            if (needsGeometry && !updateGeometry(plane, mesh)) continue

            val pose = plane.centerPose
            val kind = classifyPlane(plane.type, pose.ty(), floor)
            if (kind != mesh.kind) {
                val (r, g, b, a) = colorFor(kind)
                mesh.materialInstance.setParameter("color", r, g, b, a)
                mesh.kind = kind
            }
            pose.toMatrix(transform, 0)
            engine.transformManager.setTransform(engine.transformManager.getInstance(mesh.entity), transform)
            if (!mesh.inScene) {
                scene.addEntity(mesh.entity)
                mesh.inScene = true
            }
            visible += plane
            when (kind) {
                PlaneKind.FLOOR -> floors++
                PlaneKind.RAISED_SURFACE -> raised += pose.ty() - (floor ?: pose.ty())
                PlaneKind.WALL -> walls++
                PlaneKind.CEILING -> ceilings++
            }
        }
        counts = PlaneCounts(floors, raised.size, walls, ceilings)
        raisedSurfaceHeights = raised.sorted()

        val iterator = meshes.entries.iterator()
        while (iterator.hasNext()) {
            val (plane, mesh) = iterator.next()
            if (plane in visible) continue
            if (plane.trackingState == TrackingState.STOPPED || plane.subsumedBy != null) {
                destroyMesh(mesh)
                iterator.remove()
            } else if (mesh.inScene) {
                // Temporarily lost: hide but keep, it may come back.
                scene.remove(mesh.entity)
                mesh.inScene = false
            }
        }
    }

    fun destroy() {
        meshes.values.forEach(::destroyMesh)
        meshes.clear()
    }

    private fun createMesh(): PlaneMesh {
        val entity = EntityManager.get().create()
        val materialInstance = material.createInstance()
        materialInstance.setParameter("gridSpacing", GRID_SPACING_METRES)

        val mesh = PlaneMesh(entity, materialInstance)
        allocateBuffers(mesh, INITIAL_CAPACITY)
        engine.transformManager.create(entity)
        // The renderable itself is built on the first geometry update, once there is real data.
        return mesh
    }

    /** Returns false if the polygon is degenerate (fewer than 3 vertices). */
    private fun updateGeometry(plane: Plane, mesh: PlaneMesh): Boolean {
        val polygon = plane.polygon
        val vertexCount = polygon.remaining() / 2
        if (vertexCount < 3) return false

        if (vertexCount > mesh.capacity) {
            engine.destroyVertexBuffer(mesh.vertexBuffer)
            engine.destroyIndexBuffer(mesh.indexBuffer)
            allocateBuffers(mesh, maxOf(vertexCount, mesh.capacity * 2))
        }

        // The polygon is (x, z) pairs in the plane's local frame, where the plane lies in y = 0.
        // ARCore's outline is only a rough hull of tracked points, so it is drawn with a soft
        // edge: an outer ring (transparent) on the outline and an inner ring (opaque) pulled
        // FEATHER_METRES towards the centre.
        val outline = FloatArray(vertexCount * 2) { polygon.get(it) }
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
            val scale = if (distance > 0f) maxOf(MIN_INNER_SCALE, 1f - FEATHER_METRES / distance) else 0f
            val ix = cx + (x - cx) * scale
            val iz = cz + (z - cz) * scale
            val inner = vertexCount + i

            positions[i * 3] = x
            positions[i * 3 + 2] = z
            positions[inner * 3] = ix
            positions[inner * 3 + 2] = iz
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
                .material(0, mesh.materialInstance)
                .culling(false)
                .castShadows(false)
                .receiveShadows(false)
                .build(engine, mesh.entity)
            mesh.hasRenderable = true
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
        engine.destroyMaterialInstance(mesh.materialInstance)
        engine.destroyVertexBuffer(mesh.vertexBuffer)
        engine.destroyIndexBuffer(mesh.indexBuffer)
    }

    private companion object {
        const val GRID_SPACING_METRES = 0.25f
        const val FEATHER_METRES = 0.15f

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
