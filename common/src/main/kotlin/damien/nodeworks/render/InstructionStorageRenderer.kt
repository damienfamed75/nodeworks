package damien.nodeworks.render

import com.mojang.blaze3d.vertex.PoseStack
import damien.nodeworks.block.entity.InstructionStorageBlockEntity
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState
import net.minecraft.client.renderer.state.level.CameraRenderState

/**
 * TODO MC 26.1.2 BER REWRITE — stubbed.
 *
 * Pre-migration: renders up to 8 Instruction Set cards on the storage block's
 * front face with per-card tinted overlays pulled from the BE's inventory. Full
 * port needed to the extract-state pipeline. See git history for body.
 */
class InstructionStorageRenderer(context: BlockEntityRendererProvider.Context) :
    BlockEntityRenderer<InstructionStorageBlockEntity, BlockEntityRenderState> {

    override fun createRenderState(): BlockEntityRenderState = BlockEntityRenderState()

    override fun submit(
        state: BlockEntityRenderState,
        poseStack: PoseStack,
        submitNodeCollector: SubmitNodeCollector,
        camera: CameraRenderState
    ) {
        // Intentionally empty — see TODO above.
    }
}
