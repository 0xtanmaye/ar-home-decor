package com.arhomedecor.app.render

import android.opengl.GLES11Ext
import android.opengl.GLES30
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.IndexBuffer
import com.google.android.filament.Material
import com.google.android.filament.RenderableManager
import com.google.android.filament.Texture
import com.google.android.filament.TextureSampler
import com.google.android.filament.VertexBuffer
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import com.google.ar.core.Session

/**
 * Draws the ARCore camera image behind all virtual content.
 *
 * The textures are created in the shared EGL context (which must be current on the calling
 * thread). ARCore writes camera frames into them; Filament imports the same texture names
 * and samples them. ARCore rotates through several textures so a frame Filament is still
 * drawing isn't overwritten by the next one.
 */
class CameraBackground(private val engine: Engine, material: Material) {
    val entity: Int = EntityManager.get().create()

    private val textureIds = IntArray(TEXTURE_COUNT)
    private val textures = HashMap<Int, Texture>(TEXTURE_COUNT)
    private val sampler = TextureSampler(
        TextureSampler.MinFilter.LINEAR,
        TextureSampler.MagFilter.LINEAR,
        TextureSampler.WrapMode.CLAMP_TO_EDGE,
    )
    private val materialInstance = material.createInstance()
    private val vertexBuffer: VertexBuffer
    private val indexBuffer: IndexBuffer

    private val uvs = FloatArray(NDC_CORNERS.size)
    private var uvsValid = false

    /** Affine map from screen NDC to camera texture UV (also valid for the depth image). */
    val ndcToUv = FloatArray(6)

    init {
        GLES30.glGenTextures(TEXTURE_COUNT, textureIds, 0)
        for (id in textureIds) {
            GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, id)
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            textures[id] = Texture.Builder()
                .sampler(Texture.Sampler.SAMPLER_EXTERNAL)
                .format(Texture.InternalFormat.RGB8)
                .importTexture(id.toLong())
                .build(engine)
        }
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)

        vertexBuffer = VertexBuffer.Builder()
            .vertexCount(4)
            .bufferCount(2)
            .attribute(VertexBuffer.VertexAttribute.POSITION, 0, VertexBuffer.AttributeType.FLOAT4, 0, 16)
            .attribute(VertexBuffer.VertexAttribute.UV0, 1, VertexBuffer.AttributeType.FLOAT2, 0, 8)
            .build(engine)
        val positions = FloatArray(16)
        for (i in 0 until 4) {
            positions[i * 4] = NDC_CORNERS[i * 2]
            positions[i * 4 + 1] = NDC_CORNERS[i * 2 + 1]
            positions[i * 4 + 2] = 0f
            positions[i * 4 + 3] = 1f
        }
        vertexBuffer.setBufferAt(engine, 0, floatBufferOf(positions))
        vertexBuffer.setBufferAt(engine, 1, floatBufferOf(uvs))

        indexBuffer = IndexBuffer.Builder()
            .indexCount(6)
            .bufferType(IndexBuffer.Builder.IndexType.USHORT)
            .build(engine)
        indexBuffer.setBuffer(engine, shortBufferOf(shortArrayOf(0, 1, 2, 2, 1, 3)))

        RenderableManager.Builder(1)
            .geometry(0, RenderableManager.PrimitiveType.TRIANGLES, vertexBuffer, indexBuffer)
            .material(0, materialInstance)
            .culling(false)
            .castShadows(false)
            .receiveShadows(false)
            // Drawn first; everything else renders on top.
            .priority(0)
            .build(engine, entity)
    }

    fun attachTo(session: Session) {
        session.setCameraTextureNames(textureIds)
    }

    /** Call after Session.update(). Picks this frame's texture and refreshes UVs after rotation/resize. */
    fun update(frame: Frame) {
        // hasDisplayGeometryChanged() is only true for one frame, which may be one the caller
        // skipped (e.g. before the first camera image), so also compute UVs on first use.
        if (frame.hasDisplayGeometryChanged() || !uvsValid) {
            frame.transformCoordinates2d(
                Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,
                NDC_CORNERS,
                Coordinates2d.TEXTURE_NORMALIZED,
                uvs,
            )
            vertexBuffer.setBufferAt(engine, 1, floatBufferOf(uvs))
            ndcToUvAffine(uvs).copyInto(ndcToUv)
            uvsValid = true
        }
        textures[frame.cameraTextureName]?.let {
            materialInstance.setParameter("cameraTexture", it, sampler)
        }
    }

    /** Destroys the Filament objects. GL textures are deleted separately, after the engine is gone. */
    fun destroy() {
        engine.destroyEntity(entity)
        EntityManager.get().destroy(entity)
        engine.destroyMaterialInstance(materialInstance)
        engine.destroyVertexBuffer(vertexBuffer)
        engine.destroyIndexBuffer(indexBuffer)
        textures.values.forEach(engine::destroyTexture)
        textures.clear()
    }

    /** Requires the shared EGL context to be current. */
    fun deleteGlTextures() {
        GLES30.glDeleteTextures(TEXTURE_COUNT, textureIds, 0)
    }

    private companion object {
        const val TEXTURE_COUNT = 4

        /** Full-screen quad corners: bottom-left, bottom-right, top-left, top-right. */
        val NDC_CORNERS = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
    }
}

/**
 * Builds the affine map from screen NDC to texture UV out of the UVs of the full-screen quad's
 * corners (bottom-left, bottom-right, top-left, top-right, as (u, v) pairs). The camera image is
 * only ever rotated, flipped, scaled and cropped, so three corners define the map exactly.
 *
 * Returns (a, b, c, d, e, f) with u = a*x + b*y + c and v = d*x + e*y + f.
 */
internal fun ndcToUvAffine(cornerUvs: FloatArray): FloatArray {
    val (u00, v00) = cornerUvs[0] to cornerUvs[1] // NDC (-1, -1)
    val (u10, v10) = cornerUvs[2] to cornerUvs[3] // NDC (1, -1)
    val (u01, v01) = cornerUvs[4] to cornerUvs[5] // NDC (-1, 1)
    val a = (u10 - u00) / 2f
    val b = (u01 - u00) / 2f
    val d = (v10 - v00) / 2f
    val e = (v01 - v00) / 2f
    return floatArrayOf(a, b, u00 + a + b, d, e, v00 + d + e)
}

/**
 * Inverse of an affine map in the form returned by [ndcToUvAffine]: given u = a*x + b*y + c and
 * v = d*x + e*y + f, returns the coefficients of x and y in terms of (u, v), in the same order.
 * The map from screen to camera image is never degenerate, so the inverse always exists.
 */
internal fun invertAffine2d(m: FloatArray): FloatArray {
    val (a, b, c) = Triple(m[0], m[1], m[2])
    val (d, e, f) = Triple(m[3], m[4], m[5])
    val det = a * e - b * d
    return floatArrayOf(e / det, -b / det, (b * f - e * c) / det, -d / det, a / det, (d * c - a * f) / det)
}
