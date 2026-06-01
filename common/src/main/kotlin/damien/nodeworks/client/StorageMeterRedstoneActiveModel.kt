package damien.nodeworks.client

import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart

/**
 * Holder for the redstone-active overlay on the Storage Meter's body. Drawn
 * alongside [StorageMeterEmissiveModel] when the meter is below threshold,
 * the two textures don't overlap in pixel space so they stack without
 * fighting. Geometry mirrors `nodeworks:block/storage_meter` via JSON
 * parent inheritance.
 */
object StorageMeterRedstoneActiveModel {

    @Volatile
    var fetcher: () -> BlockStateModelPart? = { null }

    fun get(): BlockStateModelPart? = fetcher.invoke()
}
