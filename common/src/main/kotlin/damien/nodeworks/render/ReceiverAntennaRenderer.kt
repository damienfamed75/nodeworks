package damien.nodeworks.render

import com.mojang.blaze3d.vertex.PoseStack
import damien.nodeworks.block.entity.ReceiverAntennaBlockEntity
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.client.renderer.feature.ModelFeatureRenderer
import net.minecraft.client.renderer.rendertype.RenderType
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.resources.Identifier
import net.minecraft.world.phys.Vec3

/**
 * Emissive overlay for the Receiver Antenna — glowing side faces (N/S/E/W, top/bottom
 * unlit), tinted with the current network colour.
 */
open class ReceiverAntennaRenderer(context: BlockEntityRendererProvider.Context) :
    ConnectableBER<ReceiverAntennaBlockEntity, ReceiverAntennaRenderer.AntennaState>(context) {

    class AntennaState : ConnectableRenderState() {
        var color: Int = NodeConnectionRenderer.DEFAULT_NETWORK_COLOR
    }

    companion object {
        private val TEXTURE = Identifier.fromNamespaceAndPath("nodeworks", "textures/block/receiver_antenna_side_emissive.png")
        private val RENDER_TYPE: RenderType = EmissiveCubeRenderer.renderType(TEXTURE)
    }

    override fun createRenderState(): AntennaState = AntennaState()

    override fun extractConnectable(
        blockEntity: ReceiverAntennaBlockEntity,
        state: AntennaState,
        partialTicks: Float,
        cameraPosition: Vec3,
        breakProgress: ModelFeatureRenderer.CrumblingOverlay?,
    ) {
        state.color = resolveNetworkColor(blockEntity)
    }

    override fun submitConnectable(
        state: AntennaState,
        poseStack: PoseStack,
        submitNodeCollector: SubmitNodeCollector,
        camera: CameraRenderState,
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
