package damien.nodeworks.render

import com.mojang.blaze3d.vertex.PoseStack
import damien.nodeworks.block.CoProcessorBlock
import damien.nodeworks.block.entity.CoProcessorBlockEntity
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
 * Emissive overlay for the Co-Processor — all 6 faces, un-tinted white. Texture
 * picks between the formed base glow and the 3 overheating variants based on the
 * block's `overheat_level` state. Does nothing when `FORMED=false`.
 */
class CoProcessorRenderer(context: BlockEntityRendererProvider.Context) :
    BlockEntityRenderer<CoProcessorBlockEntity, CoProcessorRenderer.ProcState> {

    class ProcState : BlockEntityRenderState() {
        var formed: Boolean = false
        var overheatLevel: Int = 0
    }

    companion object {
        private val TEX_NORMAL = Identifier.fromNamespaceAndPath("nodeworks", "textures/block/co_processor_emissive.png")
        private val TEX_OVERHEAT_0 = Identifier.fromNamespaceAndPath("nodeworks", "textures/block/co_processor_overheating_emissive_0.png")
        private val TEX_OVERHEAT_1 = Identifier.fromNamespaceAndPath("nodeworks", "textures/block/co_processor_overheating_emissive_1.png")
        private val TEX_OVERHEAT_2 = Identifier.fromNamespaceAndPath("nodeworks", "textures/block/co_processor_overheating_emissive_2.png")

        private val RT_NORMAL: RenderType = EmissiveCubeRenderer.renderType(TEX_NORMAL)
        private val RT_OVERHEAT_0: RenderType = EmissiveCubeRenderer.renderType(TEX_OVERHEAT_0)
        private val RT_OVERHEAT_1: RenderType = EmissiveCubeRenderer.renderType(TEX_OVERHEAT_1)
        private val RT_OVERHEAT_2: RenderType = EmissiveCubeRenderer.renderType(TEX_OVERHEAT_2)
    }

    override fun createRenderState(): ProcState = ProcState()

    override fun extractRenderState(
        blockEntity: CoProcessorBlockEntity,
        state: ProcState,
        partialTicks: Float,
        cameraPosition: Vec3,
        breakProgress: ModelFeatureRenderer.CrumblingOverlay?
    ) {
        BlockEntityRenderState.extractBase(blockEntity, state, breakProgress)
        val bs = blockEntity.blockState
        state.formed = bs.getValue(CoProcessorBlock.FORMED)
        state.overheatLevel = bs.getValue(CoProcessorBlock.OVERHEAT_LEVEL)
    }

    override fun submit(
        state: ProcState,
        poseStack: PoseStack,
        submitNodeCollector: SubmitNodeCollector,
        camera: CameraRenderState
    ) {
        if (!state.formed) return
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
