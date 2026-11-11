package com.arhomedecor.app.render

import android.content.Context
import android.opengl.GLES30
import android.util.Log
import android.view.Surface
import android.view.SurfaceView
import com.google.android.filament.ColorGrading
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.IndirectLight
import com.google.android.filament.LightManager
import com.google.android.filament.Material
import com.google.android.filament.Renderer
import com.google.android.filament.SwapChain
import com.google.android.filament.SwapChainFlags
import com.google.android.filament.ToneMapper
import com.google.android.filament.View
import com.google.android.filament.Viewport
import com.google.android.filament.android.UiHelper
import com.google.android.filament.gltfio.FilamentAsset
import com.google.android.filament.gltfio.Gltfio
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingState

/**
 * Renders one AR frame: camera image, detected planes and placed models.
 *
 * Must be created, used and destroyed on the main thread: the shared EGL context that ARCore
 * updates camera textures in is current there, and Filament's API is single-threaded.
 */
class ArRenderer(
    context: Context,
    surfaceView: SurfaceView,
    private val onViewportChanged: (width: Int, height: Int) -> Unit,
) : UiHelper.RendererCallback {

    private val egl = SharedEglContext()
    private val engine: Engine = Engine.create(egl.context)
    private val renderer: Renderer = engine.createRenderer()
    private val scene = engine.createScene()
    private val view: View = engine.createView()
    private val cameraEntity = EntityManager.get().create()
    private val camera = engine.createCamera(cameraEntity)
    private val uiHelper = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK)
    private var swapChain: SwapChain? = null

    /** Hardware sRGB output where available: exact colours and no extra post-processing pass. */
    private val srgbSwapChain = SwapChain.isSRGBSwapChainSupported(engine)
    private var colorGrading: ColorGrading? = null

    private val backgroundMaterial = loadMaterial(context, "materials/camera_background.filamat")
    private val planeMaterial = loadMaterial(context, "materials/plane.filamat")
    private val cameraBackground = CameraBackground(engine, backgroundMaterial)
    private val planeRenderer = PlaneRenderer(engine, scene, planeMaterial)

    /** Latest ARCore depth image; unavailable on devices or sessions without depth. */
    val depth = DepthTexture(engine)

    private val modelLoader = ModelLoader(engine)
    private val referenceCube = PlacedModel(modelLoader.loadGlb(context, REFERENCE_CUBE_ASSET))
    private val wallMarker = PlacedModel(modelLoader.loadGlb(context, WALL_MARKER_ASSET))

    private val sunEntity = EntityManager.get().create()
    private val indirectLight: IndirectLight

    private val projection = FloatArray(16)
    private val projectionDouble = DoubleArray(16)
    private val cameraModelMatrix = FloatArray(16)
    private val modelMatrix = FloatArray(16)

    val planeCounts: PlaneCounts get() = planeRenderer.counts

    /** World-space height of the floor nearest the camera, or null until one is found. */
    val floorY: Float? get() = planeRenderer.floorY

    /** How a tracked ARCore plane was classified in the last frame (null if not drawn). */
    fun planeKind(plane: Plane): PlaneKind? = planeRenderer.kindOf(plane)

    /** World-space height of the ceiling, or null if none has been detected. */
    val ceilingY: Float? get() = planeRenderer.ceilingY
    val raisedSurfaceHeights: List<Float> get() = planeRenderer.raisedSurfaceHeights

    /** Planes ARCore permanently discarded during the last rendered frame. */
    val planesDiscardedLastFrame: Int get() = planeRenderer.discardedLastUpdate

    /** Planes ARCore has on hold after losing its place (see [PlaneRenderer.pausedPlanes]). */
    val pausedPlanes: Int get() = planeRenderer.pausedPlanes

    /** ARCore wall detections hidden because they lie on a marked wall. */
    val planesOnMarkedWalls: Int get() = planeRenderer.planesOnMarkedWalls

    init {
        view.setScene(scene)
        view.setCamera(camera)
        view.setAntiAliasing(View.AntiAliasing.NONE)
        if (srgbSwapChain) {
            view.setPostProcessingEnabled(false)
        } else {
            // Fallback: post-processing applies the sRGB output curve; a linear tone mapper
            // keeps the camera image unchanged.
            colorGrading = ColorGrading.Builder().toneMapper(ToneMapper.Linear()).build(engine)
            view.setColorGrading(colorGrading)
        }
        Log.i(TAG, "sRGB swap chain supported: $srgbSwapChain")
        // Light values below are in output units, not physical units.
        camera.setExposure(1f)
        renderer.setClearOptions(Renderer.ClearOptions().apply { clear = true })

        scene.addEntity(cameraBackground.entity)

        LightManager.Builder(LightManager.Type.DIRECTIONAL)
            .color(1f, 1f, 1f)
            .intensity(SUN_INTENSITY)
            .direction(0.3f, -1f, -0.5f)
            .castShadows(false)
            .build(engine, sunEntity)
        scene.addEntity(sunEntity)
        indirectLight = IndirectLight.Builder()
            .irradiance(1, floatArrayOf(AMBIENT_INTENSITY, AMBIENT_INTENSITY, AMBIENT_INTENSITY))
            .intensity(1f)
            .build(engine)
        scene.setIndirectLight(indirectLight)

        uiHelper.setRenderCallback(this)
        uiHelper.attachTo(surfaceView)
    }

    fun attachSession(session: Session) {
        cameraBackground.attachTo(session)
    }

    /** What the user has placed: null poses hide the object. */
    class Content(
        val referenceCubePose: Pose?,
        val wallMarkerPose: Pose?,
        val walls: List<ManualWall>,
    )

    /** Draws the frame returned by the latest Session.update(). */
    fun render(session: Session, frame: Frame, frameTimeNanos: Long, content: Content) {
        val chain = swapChain ?: return
        // Make ARCore's texture update (done in the shared context) visible to Filament's context.
        GLES30.glFlush()

        cameraBackground.update(frame)
        depth.update(frame)
        planeRenderer.setDepthInputs(depth.texture, cameraBackground.ndcToUv, depth.available)

        val arCamera = frame.camera
        arCamera.getProjectionMatrix(projection, 0, NEAR_METRES, FAR_METRES)
        for (i in 0 until 16) projectionDouble[i] = projection[i].toDouble()
        camera.setCustomProjection(projectionDouble, NEAR_METRES.toDouble(), FAR_METRES.toDouble())
        if (arCamera.trackingState == TrackingState.TRACKING) {
            arCamera.displayOrientedPose.toMatrix(cameraModelMatrix, 0)
            camera.setModelMatrix(cameraModelMatrix)
        }

        planeRenderer.update(session, frame, content.walls, depth.available, arCamera.pose, frameTimeNanos)
        referenceCube.place(content.referenceCubePose)
        wallMarker.place(content.wallMarkerPose)

        if (renderer.beginFrame(chain, frameTimeNanos)) {
            renderer.render(view)
            renderer.endFrame()
        }
    }

    /** A loaded model that is shown at a pose, or hidden. */
    private inner class PlacedModel(val asset: FilamentAsset) {
        private var inScene = false

        fun place(pose: Pose?) {
            if (pose == null) {
                if (inScene) {
                    scene.removeEntities(asset.entities)
                    inScene = false
                }
                return
            }
            pose.toMatrix(modelMatrix, 0)
            val transforms = engine.transformManager
            transforms.setTransform(transforms.getInstance(asset.root), modelMatrix)
            if (!inScene) {
                scene.addEntities(asset.entities)
                inScene = true
            }
        }

        fun destroy() {
            place(null)
            modelLoader.destroyAsset(asset)
        }
    }

    // UiHelper callbacks (main thread).

    override fun onNativeWindowChanged(surface: Surface) {
        swapChain?.let(engine::destroySwapChain)
        var flags = uiHelper.swapChainFlags
        if (srgbSwapChain) flags = flags or SwapChainFlags.CONFIG_SRGB_COLORSPACE
        swapChain = engine.createSwapChain(surface, flags)
    }

    override fun onDetachedFromSurface() {
        swapChain?.let {
            engine.destroySwapChain(it)
            // The surface is about to be destroyed: make sure Filament has finished with it.
            engine.flushAndWait()
        }
        swapChain = null
    }

    override fun onResized(width: Int, height: Int) {
        view.setViewport(Viewport(0, 0, width, height))
        onViewportChanged(width, height)
    }

    fun destroy() {
        uiHelper.detach()
        referenceCube.destroy()
        wallMarker.destroy()
        modelLoader.destroy()
        planeRenderer.destroy()
        depth.destroy()
        cameraBackground.destroy()
        engine.destroyMaterial(planeMaterial)
        engine.destroyMaterial(backgroundMaterial)
        engine.destroyIndirectLight(indirectLight)
        engine.destroyEntity(sunEntity)
        EntityManager.get().destroy(sunEntity)
        colorGrading?.let(engine::destroyColorGrading)
        engine.destroyRenderer(renderer)
        engine.destroyView(view)
        engine.destroyScene(scene)
        engine.destroyCameraComponent(cameraEntity)
        EntityManager.get().destroy(cameraEntity)
        swapChain?.let(engine::destroySwapChain)
        swapChain = null
        engine.destroy()
        // Filament is gone, so the camera textures can be deleted from the shared context.
        cameraBackground.deleteGlTextures()
        egl.release()
    }

    private fun loadMaterial(context: Context, path: String): Material {
        val buffer = context.readAssetToDirectBuffer(path)
        return Material.Builder().payload(buffer, buffer.remaining()).build(engine)
    }

    companion object {
        private const val TAG = "ArRenderer"
        private const val NEAR_METRES = 0.05f
        private const val FAR_METRES = 30f
        private const val SUN_INTENSITY = 2.2f
        private const val AMBIENT_INTENSITY = 0.35f
        const val REFERENCE_CUBE_ASSET = "models/reference_cube_50cm.glb"
        const val WALL_MARKER_ASSET = "models/marker_4cm.glb"
        const val REFERENCE_CUBE_EDGE_METRES = 0.5f

        init {
            Gltfio.init()
        }
    }
}
