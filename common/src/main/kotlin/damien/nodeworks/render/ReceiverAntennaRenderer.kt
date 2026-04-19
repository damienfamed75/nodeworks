package damien.nodeworks.render

import com.mojang.blaze3d.vertex.PoseStack
import damien.nodeworks.block.entity.ReceiverAntennaBlockEntity
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
 * Emissive overlay for the Receiver Antenna — glowing side faces (N/S/E/W, top/bottom
 * unlit), tinted with the current network colour.
 */
class ReceiverAntennaRenderer(context: BlockEntityRendererProvider.Context) :
    BlockEntityRenderer<ReceiverAntennaBlockEntity, ReceiverAntennaRenderer.AntennaState> {

    class AntennaState : BlockEntityRenderState() {
        var color: Int = NodeConnectionRenderer.DEFAULT_NETWORK_COLOR
    }

    companion object {
        private val TEXTURE = Identifier.fromNamespaceAndPath("nodeworks", "textures/block/receiver_antenna_side_emissive.png")
        private val RENDER_TYPE: RenderType = EmissiveCubeRenderer.renderType(TEXTURE)
    }

    override fun createRenderState(): AntennaState = AntennaState()

    override fun extractRenderState(
        blockEntity: ReceiverAntennaBlockEntity,
        state: AntennaState,
        partialTicks: Float,
        cameraPosition: Vec3,
        breakProgress: ModelFeatureRenderer.CrumblingOverlay?
    ) {
        BlockEntityRenderState.extractBase(blockEntity, state, breakProgress)
        state.color = if (!NodeConnectionRenderer.isReachable(blockEntity.blockPos)) {
            NodeConnectionRenderer.DEFAULT_NETWORK_COLOR
        } else {
            NodeConnectionRenderer.findNetworkColor(blockEntity.level, blockEntity.blockPos)
        }
    }

    override fun submit(
        state: AntennaState,
        poseStack: PoseStack,
        submitNodeCollector: SubmitNodeCollector,
        camera: CameraRenderState
    ) {
        val r = (state.color shr 16) and 0xFF
        val g = (state.color shr 8) and 0xFF
        val b = state.color and 0xFF
        EmissiveCubeRenderer.submit(
            submitNodeCollector, poseStack, RENDER_TYPE,
            EmissiveCubeRenderer.HORIZONTAL_SIDES, r, g, b, 255
        )
    }
}
