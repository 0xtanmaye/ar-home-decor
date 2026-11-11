package com.arhomedecor.app.render

import android.content.Context
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.gltfio.AssetLoader
import com.google.android.filament.gltfio.FilamentAsset
import com.google.android.filament.gltfio.ResourceLoader
import com.google.android.filament.gltfio.UbershaderProvider

/** Loads binary glTF (.glb) models from app assets. Models are authored in metres. */
class ModelLoader(private val engine: Engine) {
    private val materialProvider = UbershaderProvider(engine)
    private val assetLoader = AssetLoader(engine, materialProvider, EntityManager.get())
    private val resourceLoader = ResourceLoader(engine)

    fun loadGlb(context: Context, assetPath: String): FilamentAsset {
        val asset = checkNotNull(assetLoader.createAsset(context.readAssetToDirectBuffer(assetPath))) {
            "Could not parse $assetPath"
        }
        resourceLoader.loadResources(asset)
        asset.releaseSourceData()
        return asset
    }

    fun destroyAsset(asset: FilamentAsset) {
        assetLoader.destroyAsset(asset)
    }

    fun destroy() {
        resourceLoader.destroy()
        assetLoader.destroy()
        materialProvider.destroyMaterials()
        materialProvider.destroy()
    }
}
