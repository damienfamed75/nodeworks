package damien.nodeworks.render

import com.mojang.blaze3d.vertex.PoseStack
import damien.nodeworks.block.CraftingCoreBlock
import damien.nodeworks.block.entity.CraftingCoreBlockEntity
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.client.renderer.feature.ModelFeatureRenderer
import net.minecraft.client.renderer.rendertype.RenderType
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.resources.Identifier
import net.minecraft.world.phys.Vec3

/**
 * Emissive overlay for the Crafting Core — all 6 faces, un-tinted white. Only renders
 * when `FORMED=true`. See [EmissiveCubeRenderer] for the shared pipeline.
 */
open class CraftingCoreRenderer(context: BlockEntityRendererProvider.Context) :
    ConnectableBER<CraftingCoreBlockEntity, CraftingCoreRenderer.CoreState>(context) {

    class CoreState : ConnectableRenderState() {
        var formed: Boolean = false
    }

    companion object {
        private val TEXTURE = Identifier.fromNamespaceAndPath("nodeworks", "textures/block/crafting_core_emissive.png")
        private val RENDER_TYPE: RenderType = EmissiveCubeRenderer.renderType(TEXTURE)
    }

    override fun createRenderState(): CoreState = CoreState()

    override fun extractConnectable(
        blockEntity: CraftingCoreBlockEntity,
        state: CoreState,
        partialTicks: Float,
        cameraPosition: Vec3,
        breakProgress: ModelFeatureRenderer.CrumblingOverlay?,
    ) {
        state.formed = blockEntity.blockState.getValue(CraftingCoreBlock.FORMED)
    }

    override fun submitConnectable(
        state: CoreState,
        poseStack: PoseStack,
        submitNodeCollector: SubmitNodeCollector,
        camera: CameraRenderState,
    ) {
        if (!state.formed) return
        EmissiveCubeRenderer.submit(
            submitNodeCollector, poseStack, RENDER_TYPE,
            EmissiveCubeRenderer.ALL_FACES, 255, 255, 255, 255
        )
    }
}
