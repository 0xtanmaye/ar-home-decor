package com.arhomedecor.app.render

import android.util.Log
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
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot

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

    // Diagnostics: short ids for planes in the log, and planes hidden on marked walls last update.
    private val planeIds = HashMap<Plane, Int>()
    private var nextPlaneId = 1
    private var hiddenOnWalls: Set<Plane> = emptySet()

    /** For each upward plane, the highest the camera has been above it (see [classifyUpward]). */
    private val maxCameraAbove = HashMap<Plane, Float>()

    fun kindOf(plane: Plane): PlaneKind? = kinds[plane]

    /**
     * One material instance per kind of ARCore plane (colour differs, depth inputs are shared),
     * plus one for walls marked by hand: their outline is not ARCore's, so depth must confirm
     * the wall everywhere (see fullCheckInside in plane.mat).
     */
    private val kindMaterials: Map<PlaneKind, MaterialInstance> =
        PlaneKind.entries.associateWith { createMaterialInstance(material, it, fullCheckInside = false) }
    private val markedWallMaterial = createMaterialInstance(material, PlaneKind.WALL, fullCheckInside = true)
    private val allMaterials = kindMaterials.values + markedWallMaterial

    private fun createMaterialInstance(material: Material, kind: PlaneKind, fullCheckInside: Boolean) =
        material.createInstance().apply {
            val (r, g, b, a) = colorFor(kind)
            setParameter("color", r, g, b, a)
            setParameter("gridSpacing", GRID_SPACING_METRES)
            setParameter("depthEnabled", 0f)
            setParameter("fullCheckInside", if (fullCheckInside) 1f else 0f)
            setParameter("baseTolerance", if (kind == PlaneKind.WALL) WALL_TOLERANCE_METRES else TOLERANCE_METRES)
            setParameter("minNormalAgreement", if (kind == PlaneKind.WALL) WALL_MIN_NORMAL_AGREEMENT else MIN_NORMAL_AGREEMENT)
            setParameter("debugView", 0f)
        }

    /** Development aid: colour planes by how far the real surface is from them (see plane.mat). */
    fun setDebugView(enabled: Boolean) {
        for (instance in allMaterials) instance.setParameter("debugView", if (enabled) 1f else 0f)
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
     * [ndcToUv] maps screen NDC to depth-image UV (see [ndcToUvAffine]); [projection] is the
     * camera's column-major projection matrix. Pass [enabled] = false when there is no depth
     * image; planes are then drawn unchecked.
     */
    fun setDepthInputs(texture: Texture, ndcToUv: FloatArray, projection: FloatArray, enabled: Boolean) {
        val uvToNdc = invertAffine2d(ndcToUv)
        for (instance in allMaterials) {
            instance.setParameter("depthTexture", texture, depthSampler)
            instance.setParameter("uvFromNdcU", ndcToUv[0], ndcToUv[1], ndcToUv[2])
            instance.setParameter("uvFromNdcV", ndcToUv[3], ndcToUv[4], ndcToUv[5])
            instance.setParameter("ndcFromUvX", uvToNdc[0], uvToNdc[1], uvToNdc[2])
            instance.setParameter("ndcFromUvY", uvToNdc[3], uvToNdc[4], uvToNdc[5])
            instance.setParameter("projectionScaleOffset", projection[0], projection[5], projection[8], projection[9])
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
        val hiddenNow = HashSet<Plane>()

        val upward = tracked.filter { it.type == Plane.Type.HORIZONTAL_UPWARD_FACING }
        val upwardInfo = upward.map { it.horizontalInfo() }
        val floorReferences = localFloorReferences(upwardInfo)
        val newKinds = HashMap<Plane, PlaneKind>(tracked.size)
        val localFloorOf = HashMap<Plane, Float>(upward.size)
        val floorReferenceOf = HashMap<Plane, Plane>(upward.size)
        upward.forEachIndexed { i, plane ->
            val cameraAbove = cameraPose.ty() - upwardInfo[i].y
            maxCameraAbove[plane] = maxOf(maxCameraAbove[plane] ?: cameraAbove, cameraAbove)
        }
        upward.forEachIndexed { i, plane ->
            val wasFloor = (meshes[plane]?.kind ?: kinds[plane])?.let { it == PlaneKind.FLOOR }
            val reference = upward[floorReferences[i]]
            val localFloorY = upwardInfo[floorReferences[i]].y
            newKinds[plane] = classifyUpward(upwardInfo[i].y, localFloorY, wasFloor, maxCameraAbove.getValue(reference))
            localFloorOf[plane] = localFloorY
            floorReferenceOf[plane] = reference
        }
        maxCameraAbove.keys.retainAll(allPlanes.toHashSet())
        val raisedFootprints = upward
            .filter { newKinds[it] == PlaneKind.RAISED_SURFACE }
            .map { RaisedFootprint(it.centerPose.ty(), it.worldOutlineXz()) }
        val floorInfo = upward.indices.filter { newKinds[upward[it]] == PlaneKind.FLOOR }.map { upwardInfo[it] }
        for (plane in tracked) {
            when (plane.type) {
                Plane.Type.VERTICAL -> {
                    val pose = plane.centerPose
                    newKinds[plane] = classifyVertical(
                        plane.verticalExtent(),
                        raisedFootprints,
                        nearestLevel(floorInfo, pose.tx(), pose.tz()),
                        wasFurnitureSide = kinds[plane] == PlaneKind.FURNITURE_SIDE,
                    )
                }
                Plane.Type.HORIZONTAL_DOWNWARD_FACING -> newKinds[plane] = PlaneKind.CEILING
                Plane.Type.HORIZONTAL_UPWARD_FACING -> Unit
            }
        }
        val previousKinds = kinds
        kinds = newKinds
        floorY = nearestFloorY(upward, upwardInfo, cameraPose)
        ceilingY = tracked
            .filter { it.type == Plane.Type.HORIZONTAL_DOWNWARD_FACING }
            .minOfOrNull { it.centerPose.ty() }

        // Pieces of a surface that ARCore hasn't merged into the larger plane covering them yet:
        // drawing both would show two misaligned grids until it does.
        val upwardFootprints = upward.mapIndexed { i, plane ->
            val outline = plane.worldOutlineXz()
            UpwardFootprint(upwardInfo[i].y, upwardInfo[i].x, upwardInfo[i].z, polygonArea(outline), outline)
        }
        val redundant = redundantPlanes(upwardFootprints, upward.map { newKinds.getValue(it) }).map { upward[it] }.toHashSet()

        val renderables = engine.renderableManager
        val visible = HashSet<Any>()
        val raised = ArrayList<Float>()
        var floors = 0
        var walls = 0
        var ceilings = 0
        var furnitureSides = 0

        for (plane in tracked) {
            val pose = plane.centerPose
            val kind = newKinds.getValue(plane)
            if (kind == PlaneKind.FURNITURE_SIDE) {
                furnitureSides++
                continue // not drawn: hidden by the loop below
            }
            if (plane in redundant) continue
            if (kind == PlaneKind.WALL && markedWallPlanes.isNotEmpty()) {
                val normal = pose.rotateVector(UP) // a plane's local Y axis is its normal
                val radius = plane.outlineRadius()
                if (markedWallPlanes.any { liesOnWall(pose.translation, normal, radius, it) }) {
                    onMarkedWalls++
                    hiddenNow += plane
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
                val outline = FloatArray(polygon.remaining()) { polygon.get(it) }
                val dilation = if (depthAvailable) depthExpansionFor(kind) else 0f
                val feather = if (depthAvailable) DEPTH_FEATHER_METRES else FEATHER_METRES
                if (!updateGeometry(outline, dilation, PLANE_AXIS_X, PLANE_AXIS_Z, feather, mesh, kind, kindMaterials.getValue(kind))) continue
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
                PlaneKind.FURNITURE_SIDE -> Unit
            }
        }

        var marked = 0
        for (wall in manualWalls) {
            if (wall.anchor.trackingState != TrackingState.TRACKING) continue
            val mesh = meshes.getOrPut(wall) { createMesh() }
            val feather = if (depthAvailable) DEPTH_FEATHER_METRES else FEATHER_METRES
            if (!mesh.hasRenderable || mesh.builtForDepth != depthAvailable) {
                if (!updateGeometry(wall.outline, 0f, wall.alongWall, UP, feather, mesh, PlaneKind.WALL, markedWallMaterial)) continue
                mesh.builtForDepth = depthAvailable
            }
            show(mesh, wall.anchor.pose)
            visible += wall
            marked++
        }
        counts = PlaneCounts(floors, raised.size, walls, ceilings, marked, furnitureSides)
        if (Log.isLoggable(EVENT_TAG, Log.INFO)) {
            logPlaneChanges(previousKinds, allPlanes, localFloorOf, floorReferenceOf, hiddenNow, markedWallPlanes, cameraPose)
        }
        hiddenOnWalls = hiddenNow
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

    /**
     * Logs planes appearing, changing kind, being hidden on a marked wall, and disappearing, with
     * the geometry the classification was based on.
     */
    private fun logPlaneChanges(
        previousKinds: Map<Plane, PlaneKind>,
        allPlanes: List<Plane>,
        localFloorOf: Map<Plane, Float>,
        floorReferenceOf: Map<Plane, Plane>,
        hiddenNow: Set<Plane>,
        markedWalls: List<WallPlaneWorld>,
        cameraPose: Pose,
    ) {
        for ((plane, kind) in kinds) {
            val before = previousKinds[plane]
            val hiddenChanged = (plane in hiddenNow) != (plane in hiddenOnWalls)
            if (before == kind && !hiddenChanged) continue
            val change = when {
                before == null -> if (plane in planeIds) "back $kind" else "new $kind"
                before != kind -> "$before -> $kind"
                plane in hiddenNow -> "$kind hidden: lies on a marked wall"
                else -> "$kind shown: no longer on a marked wall"
            }
            Log.i(EVENT_TAG, "P${idOf(plane)} $change ${describe(plane, localFloorOf, floorReferenceOf, markedWalls, cameraPose)}")
        }
        for (plane in previousKinds.keys) {
            if (plane in kinds) continue
            val why = plane.subsumedBy?.let { "merged into P${idOf(it)}" } ?: plane.trackingState.name.lowercase(Locale.US)
            Log.i(EVENT_TAG, "P${idOf(plane)} ${previousKinds[plane]} gone: $why")
        }
        // Forget ids of planes ARCore has discarded for good.
        planeIds.keys.removeAll { it.trackingState == TrackingState.STOPPED && it !in allPlanes }
    }

    private fun idOf(plane: Plane): Int = planeIds.getOrPut(plane) { nextPlaneId++ }

    private fun describe(
        plane: Plane,
        localFloorOf: Map<Plane, Float>,
        floorReferenceOf: Map<Plane, Plane>,
        markedWalls: List<WallPlaneWorld>,
        cameraPose: Pose,
    ): String {
        val pose = plane.centerPose
        val distance = hypot(pose.tx() - cameraPose.tx(), pose.tz() - cameraPose.tz())
        val base = String.format(Locale.US, "r=%.2f dist=%.2f", plane.outlineRadius(), distance)
        if (plane.type == Plane.Type.HORIZONTAL_UPWARD_FACING) {
            val localFloor = localFloorOf[plane] ?: return base
            val reference = floorReferenceOf[plane]?.let { if (it == plane) "itself" else "P${idOf(it)}" }
            return String.format(Locale.US, "height=%+.3f above floor (%s) %s", pose.ty() - localFloor, reference, base)
        }
        if (plane.type != Plane.Type.VERTICAL) return base
        val normal = pose.rotateVector(UP)
        val extent = plane.verticalExtent()
        val width = hypot(extent.endB[0] - extent.endA[0], extent.endB[1] - extent.endA[1])
        val floor = floorY
        val heights = if (floor != null) {
            String.format(Locale.US, "bottom=%+.2f top=%+.2f above floor", extent.bottomY - floor, extent.topY - floor)
        } else {
            "no floor"
        }
        val nearestWall = markedWalls.minByOrNull { wall ->
            val offset = floatArrayOf(pose.tx() - wall.point[0], pose.ty() - wall.point[1], pose.tz() - wall.point[2])
            abs(dot(offset, wall.normal))
        }
        val wallRelation = nearestWall?.let { wall ->
            val offset = floatArrayOf(pose.tx() - wall.point[0], pose.ty() - wall.point[1], pose.tz() - wall.point[2])
            String.format(
                Locale.US,
                " markedWall: off=%+.2f along=%+.2f facing=%.2f",
                dot(offset, wall.normal),
                dot(offset, wall.along),
                abs(dot(normal, wall.normal)),
            )
        } ?: ""
        return String.format(Locale.US, "%s width=%.2f %s%s", heights, width, base, wallRelation)
    }

    /** The plane's outline in world space as (x, z) pairs (for horizontal planes). */
    private fun Plane.worldOutlineXz(): FloatArray {
        val pose = centerPose
        val polygon = polygon
        val count = polygon.remaining() / 2
        val local = FloatArray(3)
        return FloatArray(count * 2).also { result ->
            for (i in 0 until count) {
                local[0] = polygon.get(i * 2)
                local[2] = polygon.get(i * 2 + 1)
                val world = pose.transformPoint(local)
                result[i * 2] = world[0]
                result[i * 2 + 1] = world[2]
            }
        }
    }

    /** Height range and horizontal ends of a vertical plane's outline, in world space. */
    private fun Plane.verticalExtent(): VerticalPlaneExtent {
        val pose = centerPose
        val normal = pose.rotateVector(UP) // a plane's local Y axis is its normal
        val length = hypot(normal[0], normal[2]).takeIf { it > 0f } ?: 1f
        val alongX = -normal[2] / length
        val alongZ = normal[0] / length
        val polygon = polygon
        var bottom = Float.MAX_VALUE
        var top = -Float.MAX_VALUE
        var alongMin = Float.MAX_VALUE
        var alongMax = -Float.MAX_VALUE
        val local = FloatArray(3)
        for (i in 0 until polygon.remaining() / 2) {
            local[0] = polygon.get(i * 2)
            local[2] = polygon.get(i * 2 + 1)
            val world = pose.transformPoint(local)
            bottom = minOf(bottom, world[1])
            top = maxOf(top, world[1])
            val a = (world[0] - pose.tx()) * alongX + (world[2] - pose.tz()) * alongZ
            alongMin = minOf(alongMin, a)
            alongMax = maxOf(alongMax, a)
        }
        return VerticalPlaneExtent(
            bottomY = bottom,
            topY = top,
            endA = floatArrayOf(pose.tx() + alongX * alongMin, pose.tz() + alongZ * alongMin),
            endB = floatArrayOf(pose.tx() + alongX * alongMax, pose.tz() + alongZ * alongMax),
        )
    }

    /** Height of the plane in [planes] whose outline comes closest to (x, z), or null if none. */
    private fun nearestLevel(planes: List<HorizontalPlaneInfo>, x: Float, z: Float): Float? =
        planes.minByOrNull { hypot(x - it.x, z - it.z) - it.radius }?.y

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
        allMaterials.forEach(engine::destroyMaterialInstance)
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
     * Builds the mesh for a convex [outline] of (u, v) pairs, drawn [dilation] metres larger.
     * Vertices are placed at u * [axisU] + v * [axisV] in the mesh's local frame; (u, v) also
     * drive the metric grid. Returns false if the polygon is degenerate (fewer than 3 vertices).
     *
     * Three rings of vertices, outermost first: the enlarged outline (transparent, for a soft
     * edge), the same pulled [feather] metres inwards (opaque), and the original outline.
     * uv1.x is the edge fade; uv1.y is 1 in the enlarged margin, where the surface is only
     * assumed to continue, and 0 inside the original outline.
     */
    private fun updateGeometry(
        outline: FloatArray,
        dilation: Float,
        axisU: FloatArray,
        axisV: FloatArray,
        feather: Float,
        mesh: PlaneMesh,
        kind: PlaneKind,
        instance: MaterialInstance,
    ): Boolean {
        val vertexCount = outline.size / 2
        if (vertexCount < 3) return false

        if (vertexCount > mesh.capacity) {
            engine.destroyVertexBuffer(mesh.vertexBuffer)
            engine.destroyIndexBuffer(mesh.indexBuffer)
            allocateBuffers(mesh, maxOf(vertexCount, mesh.capacity * 2))
        }

        val enlarged = dilateConvexOutline(outline, dilation)
        val opaque = insetConvexOutline(enlarged, feather)
        val rings = arrayOf(enlarged, opaque, if (dilation > feather) outline else opaque)
        val margin = if (dilation > 0f) 1f else 0f

        val totalVertices = vertexCount * RINGS
        val positions = FloatArray(totalVertices * 3)
        val uvs = FloatArray(totalVertices * 2)
        val fadeAndMargin = FloatArray(totalVertices * 2)
        rings.forEachIndexed { ring, points ->
            for (i in 0 until vertexCount) {
                val vertex = ring * vertexCount + i
                val u = points[i * 2]
                val v = points[i * 2 + 1]
                for (axis in 0 until 3) positions[vertex * 3 + axis] = u * axisU[axis] + v * axisV[axis]
                uvs[vertex * 2] = u
                uvs[vertex * 2 + 1] = v
                fadeAndMargin[vertex * 2] = if (ring == 0) 0f else 1f
                fadeAndMargin[vertex * 2 + 1] = if (ring == RINGS - 1) 0f else margin
            }
        }
        val indices = ringedPolygonIndices(vertexCount, RINGS)
        mesh.vertexBuffer.setBufferAt(engine, 0, floatBufferOf(positions))
        mesh.vertexBuffer.setBufferAt(engine, 1, floatBufferOf(uvs))
        mesh.vertexBuffer.setBufferAt(engine, 2, floatBufferOf(fadeAndMargin))
        mesh.indexBuffer.setBuffer(engine, shortBufferOf(indices))

        if (!mesh.hasRenderable) {
            RenderableManager.Builder(1)
                .geometry(0, RenderableManager.PrimitiveType.TRIANGLES, mesh.vertexBuffer, mesh.indexBuffer, 0, indices.size)
                .material(0, instance)
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

    /** [capacity] is the number of outline vertices; each one becomes a vertex in every ring. */
    private fun allocateBuffers(mesh: PlaneMesh, capacity: Int) {
        mesh.vertexBuffer = VertexBuffer.Builder()
            .vertexCount(capacity * RINGS)
            .bufferCount(3)
            .attribute(VertexBuffer.VertexAttribute.POSITION, 0, VertexBuffer.AttributeType.FLOAT3, 0, 12)
            .attribute(VertexBuffer.VertexAttribute.UV0, 1, VertexBuffer.AttributeType.FLOAT2, 0, 8)
            // uv1: edge fade (0 on the outline, 1 inside) and margin flag (see updateGeometry).
            .attribute(VertexBuffer.VertexAttribute.UV1, 2, VertexBuffer.AttributeType.FLOAT2, 0, 8)
            .build(engine)
        mesh.indexBuffer = IndexBuffer.Builder()
            .indexCount(ringedIndexCount(capacity, RINGS))
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
        const val EVENT_TAG = "ArPlanes"
        val PLANE_AXIS_X = floatArrayOf(1f, 0f, 0f)
        val PLANE_AXIS_Z = floatArrayOf(0f, 0f, 1f)

        const val GRID_SPACING_METRES = 0.25f

        /** Edge fade when ARCore's outline is all there is. */
        const val FEATHER_METRES = 0.15f

        /** Edge fade when depth trims the surface: depth already places the edge. */
        const val DEPTH_FEATHER_METRES = 0.05f

        /** How far (metres, plus depth noise) the real surface may be from a plane and still count as it. */
        const val TOLERANCE_METRES = 0.03f
        const val WALL_TOLERANCE_METRES = 0.10f

        /**
         * Beyond ARCore's outline, the real surface must face within about 45 degrees of the
         * plane (cos 45° ≈ 0.71) to count as it; walls allow about 55 degrees (cos 55° ≈ 0.57)
         * since depth on plain walls is poor. Depth on glossy or cluttered surfaces ripples, so
         * tighter limits make the grid flicker; perpendicular surfaces (a wall at table height,
         * a table side against a wall) are far outside either.
         */
        const val MIN_NORMAL_AGREEMENT = 0.71f
        const val WALL_MIN_NORMAL_AGREEMENT = 0.57f

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
            PlaneKind.CEILING, PlaneKind.FURNITURE_SIDE -> 0f
        }

        /** Vertex rings per plane mesh (see [updateGeometry]). */
        const val RINGS = 3
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
            PlaneKind.FURNITURE_SIDE -> RAISED_SURFACE_COLOR // never drawn
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

internal fun ringedIndexCount(vertexCount: Int, rings: Int) = (vertexCount - 2) * 3 + (rings - 1) * vertexCount * 6

/**
 * Indices for a convex polygon (ARCore plane polygons are convex) drawn as nested rings of
 * [vertexCount] vertices each: ring r is vertices r * n until (r + 1) * n, ring 0 outermost.
 * The innermost ring is filled as a triangle fan and each band between rings as one quad per edge.
 */
internal fun ringedPolygonIndices(vertexCount: Int, rings: Int): ShortArray {
    require(vertexCount >= 3) { "A polygon needs at least 3 vertices, got $vertexCount" }
    require(rings >= 1) { "Need at least one ring, got $rings" }
    val n = vertexCount
    val indices = ShortArray(ringedIndexCount(n, rings))
    var k = 0
    fun triangle(a: Int, b: Int, c: Int) {
        indices[k++] = a.toShort()
        indices[k++] = b.toShort()
        indices[k++] = c.toShort()
    }
    val innermost = (rings - 1) * n
    for (i in 1 until n - 1) triangle(innermost, innermost + i, innermost + i + 1)
    for (ring in 0 until rings - 1) {
        val outer = ring * n
        val inner = outer + n
        for (i in 0 until n) {
            val next = (i + 1) % n
            triangle(outer + i, outer + next, inner + i)
            triangle(outer + next, inner + next, inner + i)
        }
    }
    return indices
}

/**
 * Pulls every vertex of a convex outline ((u, v) pairs) [distance] towards its centroid, but
 * never closer than [MIN_INNER_SCALE] of its original distance, so small planes stay visible.
 */
internal fun insetConvexOutline(outline: FloatArray, distance: Float): FloatArray {
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
        val scale = if (length > 0f) maxOf(MIN_INNER_SCALE, 1f - distance / length) else 0f
        result[i * 2] = cu + du * scale
        result[i * 2 + 1] = cv + dv * scale
    }
    return result
}

/** The inset ring never shrinks below this fraction of the outline's size. */
internal const val MIN_INNER_SCALE = 0.3f
