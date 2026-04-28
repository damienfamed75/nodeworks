package damien.nodeworks.render

import com.mojang.blaze3d.vertex.PoseStack
import damien.nodeworks.block.CraftingStorageBlock
import damien.nodeworks.block.entity.CraftingStorageBlockEntity
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState
import net.minecraft.client.renderer.feature.ModelFeatureRenderer
import net.minecraft.client.renderer.rendertype.RenderType
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.resources.Identifier
import net.minecraft.world.phys.Vec3

/**
 * Emissive overlay for the Crafting Storage, all 6 faces, un-tinted white. Texture
 * picks between the formed base glow and the 3 overheating variants based on the
 * block's `overheat_level` state. Does nothing when `FORMED=false`.
 */
class CraftingStorageRenderer(context: BlockEntityRendererProvider.Context) :
    BlockEntityRenderer<CraftingStorageBlockEntity, CraftingStorageRenderer.StorageState> {

    class StorageState : BlockEntityRenderState() {
        var formed: Boolean = false
        /** 0 = normal (white emissive), 1..3 = overheating stages (warm → hot textures). */
        var overheatLevel: Int = 0
    }

    companion object {
        private val TEX_NORMAL = Identifier.fromNamespaceAndPath("nodeworks", "textures/block/crafting_storage_emissive.png")
        private val TEX_OVERHEAT_0 = Identifier.fromNamespaceAndPath("nodeworks", "textures/block/crafting_storage_overheating_emissive_0.png")
        private val TEX_OVERHEAT_1 = Identifier.fromNamespaceAndPath("nodeworks", "textures/block/crafting_storage_overheating_emissive_1.png")
        private val TEX_OVERHEAT_2 = Identifier.fromNamespaceAndPath("nodeworks", "textures/block/crafting_storage_overheating_emissive_2.png")

        private val RT_NORMAL: RenderType = EmissiveCubeRenderer.renderType(TEX_NORMAL)
        private val RT_OVERHEAT_0: RenderType = EmissiveCubeRenderer.renderType(TEX_OVERHEAT_0)
        private val RT_OVERHEAT_1: RenderType = EmissiveCubeRenderer.renderType(TEX_OVERHEAT_1)
        private val RT_OVERHEAT_2: RenderType = EmissiveCubeRenderer.renderType(TEX_OVERHEAT_2)
    }

    override fun createRenderState(): StorageState = StorageState()

    override fun extractRenderState(
        blockEntity: CraftingStorageBlockEntity,
        state: StorageState,
        partialTicks: Float,
        cameraPosition: Vec3,
        breakProgress: ModelFeatureRenderer.CrumblingOverlay?
    ) {
        BlockEntityRenderState.extractBase(blockEntity, state, breakProgress)
        val bs = blockEntity.blockState
        state.formed = bs.getValue(CraftingStorageBlock.FORMED)
        state.overheatLevel = bs.getValue(CraftingStorageBlock.OVERHEAT_LEVEL)
    }

    override fun submit(
        state: StorageState,
        poseStack: PoseStack,
        submitNodeCollector: SubmitNodeCollector,
        camera: CameraRenderState
    ) {
        if (!state.formed) return
        // blockstate matrix: overheat 0 → crafting_storage_on (white emissive),
        // overheat 1/2/3 → overheating_0/1/2 emissive variants respectively.
        val rt = when (state.overheatLevel) {
            0 -> RT_NORMAL
            1 -> RT_OVERHEAT_0
            2 -> RT_OVERHEAT_1
            else -> RT_OVERHEAT_2
        }
        EmissiveCubeRenderer.submit(
            submitNodeCollector, poseStack, rt,
            EmissiveCubeRenderer.ALL_FACES, 255, 255, 255, 255
        )
    }
}
