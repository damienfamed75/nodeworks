package damien.nodeworks.render

import com.mojang.blaze3d.vertex.PoseStack
import damien.nodeworks.block.entity.VariableBlockEntity
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
 * Emissive overlay for the Variable block — glowing side faces (N/S/E/W only, top/bottom
 * unlit), tinted with the current network colour.
 */
class VariableRenderer(context: BlockEntityRendererProvider.Context) :
    BlockEntityRenderer<VariableBlockEntity, VariableRenderer.VariableState> {

    class VariableState : BlockEntityRenderState() {
        var color: Int = NodeConnectionRenderer.DEFAULT_NETWORK_COLOR
    }

    companion object {
        private val TEXTURE = Identifier.fromNamespaceAndPath("nodeworks", "textures/block/variable_emissive.png")
        private val RENDER_TYPE: RenderType = EmissiveCubeRenderer.renderType(TEXTURE)
    }

    override fun createRenderState(): VariableState = VariableState()

    override fun extractRenderState(
        blockEntity: VariableBlockEntity,
        state: VariableState,
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
        state: VariableState,
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
