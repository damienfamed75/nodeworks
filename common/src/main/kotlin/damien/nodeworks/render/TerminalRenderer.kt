package damien.nodeworks.render

import com.mojang.blaze3d.vertex.PoseStack
import damien.nodeworks.block.entity.TerminalBlockEntity
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState
import net.minecraft.client.renderer.state.level.CameraRenderState

/**
 * No-op BER. Pre-migration this renderer diffed connection count / reachability
 * per frame and forced chunk rebuilds on flip so the emissive network-color
 * tint stayed current. That work is now centralized in
 * [NodeConnectionRenderer.refreshLosCache] (diffing the reachable set and
 * invalidating only the affected sections), so every network-tinted block —
 * Terminal included — gets correct behavior without any BER involvement.
 */
class TerminalRenderer(context: BlockEntityRendererProvider.Context) :
    BlockEntityRenderer<TerminalBlockEntity, BlockEntityRenderState> {

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
