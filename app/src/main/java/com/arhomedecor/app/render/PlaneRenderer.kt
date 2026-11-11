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

/** Counts of planes currently shown, by kind. */
data class PlaneCounts(val floors: Int = 0, val walls: Int = 0, val ceilings: Int = 0)

/**
 * Draws every tracked ARCore plane as a semi-transparent polygon with a metric grid.
 * Floors, walls and ceilings get different colours.
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
    }

    private val meshes = HashMap<Plane, PlaneMesh>()
    private val transform = FloatArray(16)

    var counts = PlaneCounts()
        private set

    fun update(session: Session, frame: Frame) {
        val updated = frame.getUpdatedTrackables(Plane::class.java).toHashSet()
        val visible = HashSet<Plane>()
        var floors = 0
        var walls = 0
        var ceilings = 0

        for (plane in session.getAllTrackables(Plane::class.java)) {
            if (plane.trackingState != TrackingState.TRACKING || plane.subsumedBy != null) continue
            val mesh = meshes.getOrPut(plane) { createMesh(plane) }
            val needsGeometry = !mesh.hasRenderable || plane in updated
            if (needsGeometry && !updateGeometry(plane, mesh)) continue

            plane.centerPose.toMatrix(transform, 0)
            engine.transformManager.setTransform(engine.transformManager.getInstance(mesh.entity), transform)
            if (!mesh.inScene) {
                scene.addEntity(mesh.entity)
                mesh.inScene = true
            }
            visible += plane
            when (plane.type) {
                Plane.Type.HORIZONTAL_UPWARD_FACING -> floors++
                Plane.Type.VERTICAL -> walls++
                Plane.Type.HORIZONTAL_DOWNWARD_FACING -> ceilings++
            }
        }
        counts = PlaneCounts(floors, walls, ceilings)

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

    private fun createMesh(plane: Plane): PlaneMesh {
        val entity = EntityManager.get().create()
        val materialInstance = material.createInstance()
        val (r, g, b, a) = colorFor(plane.type)
        materialInstance.setParameter("color", r, g, b, a)
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
        val positions = FloatArray(vertexCount * 3)
        val uvs = FloatArray(vertexCount * 2)
        for (i in 0 until vertexCount) {
            val x = polygon.get(i * 2)
            val z = polygon.get(i * 2 + 1)
            positions[i * 3] = x
            positions[i * 3 + 2] = z
            uvs[i * 2] = x
            uvs[i * 2 + 1] = z
        }
        val indices = fanIndices(vertexCount)
        mesh.vertexBuffer.setBufferAt(engine, 0, floatBufferOf(positions))
        mesh.vertexBuffer.setBufferAt(engine, 1, floatBufferOf(uvs))
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

    private fun allocateBuffers(mesh: PlaneMesh, capacity: Int) {
        mesh.vertexBuffer = VertexBuffer.Builder()
            .vertexCount(capacity)
            .bufferCount(2)
            .attribute(VertexBuffer.VertexAttribute.POSITION, 0, VertexBuffer.AttributeType.FLOAT3, 0, 12)
            .attribute(VertexBuffer.VertexAttribute.UV0, 1, VertexBuffer.AttributeType.FLOAT2, 0, 8)
            .build(engine)
        mesh.indexBuffer = IndexBuffer.Builder()
            .indexCount((capacity - 2) * 3)
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
        const val INITIAL_CAPACITY = 16

        // Linear RGB + alpha.
        val FLOOR_COLOR = floatArrayOf(0.0f, 0.75f, 0.85f, 0.22f)
        val WALL_COLOR = floatArrayOf(0.95f, 0.30f, 0.65f, 0.22f)
        val CEILING_COLOR = floatArrayOf(0.7f, 0.7f, 0.7f, 0.12f)

        fun colorFor(type: Plane.Type): FloatArray = when (type) {
            Plane.Type.HORIZONTAL_UPWARD_FACING -> FLOOR_COLOR
            Plane.Type.VERTICAL -> WALL_COLOR
            Plane.Type.HORIZONTAL_DOWNWARD_FACING -> CEILING_COLOR
        }
    }
}

/** Triangle-fan indices for a convex polygon (ARCore plane polygons are convex). */
internal fun fanIndices(vertexCount: Int): ShortArray {
    require(vertexCount >= 3) { "A polygon needs at least 3 vertices, got $vertexCount" }
    val indices = ShortArray((vertexCount - 2) * 3)
    for (i in 1 until vertexCount - 1) {
        val t = (i - 1) * 3
        indices[t] = 0
        indices[t + 1] = i.toShort()
        indices[t + 2] = (i + 1).toShort()
    }
    return indices
}
