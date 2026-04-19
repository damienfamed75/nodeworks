package damien.nodeworks.render

import com.mojang.blaze3d.vertex.PoseStack
import damien.nodeworks.block.TerminalBlock
import damien.nodeworks.block.entity.TerminalBlockEntity
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState
import net.minecraft.client.renderer.feature.ModelFeatureRenderer
import net.minecraft.client.renderer.rendertype.RenderType
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.core.Direction
import net.minecraft.resources.Identifier
import net.minecraft.world.phys.Vec3

/**
 * Emissive overlay for the Terminal — glowing front-face screen, tinted with the
 * current network colour. The front face rotates with the block's `facing` property.
 */
class TerminalRenderer(context: BlockEntityRendererProvider.Context) :
    BlockEntityRenderer<TerminalBlockEntity, TerminalRenderer.TerminalState> {

    class TerminalState : BlockEntityRenderState() {
        var facing: Direction = Direction.NORTH
        var color: Int = NodeConnectionRenderer.DEFAULT_NETWORK_COLOR
    }

    companion object {
        private val TEXTURE = Identifier.fromNamespaceAndPath("nodeworks", "textures/block/terminal_front_emissive.png")
        private val RENDER_TYPE: RenderType = EmissiveCubeRenderer.renderType(TEXTURE)
    }

    override fun createRenderState(): TerminalState = TerminalState()

    override fun extractRenderState(
        blockEntity: TerminalBlockEntity,
        state: TerminalState,
        partialTicks: Float,
        cameraPosition: Vec3,
        breakProgress: ModelFeatureRenderer.CrumblingOverlay?
    ) {
        BlockEntityRenderState.extractBase(blockEntity, state, breakProgress)
        state.facing = blockEntity.blockState.getValue(TerminalBlock.FACING)
        // Match NetworkColorTintSource's reachability gate — an unreachable Terminal
        // (e.g. LOS to its node was blocked) falls back to the grey default so the
        // screen goes dim, matching the rest of the network-colour UI.
        state.color = if (!NodeConnectionRenderer.isReachable(blockEntity.blockPos)) {
            NodeConnectionRenderer.DEFAULT_NETWORK_COLOR
        } else {
            NodeConnectionRenderer.findNetworkColor(blockEntity.level, blockEntity.blockPos)
        }
    }

    override fun submit(
        state: TerminalState,
        poseStack: PoseStack,
        submitNodeCollector: SubmitNodeCollector,
        camera: CameraRenderState
    ) {
        val r = (state.color shr 16) and 0xFF
        val g = (state.color shr 8) and 0xFF
        val b = state.color and 0xFF
        EmissiveCubeRenderer.submit(
            submitNodeCollector, poseStack, RENDER_TYPE,
            EmissiveCubeRenderer.faceOf(state.facing), r, g, b, 255
        )
    }
}
