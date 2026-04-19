package damien.nodeworks.render

import com.mojang.blaze3d.vertex.PoseStack
import damien.nodeworks.block.InventoryTerminalBlock
import damien.nodeworks.block.entity.InventoryTerminalBlockEntity
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
 * Emissive overlay for the Inventory Terminal — front + back faces tinted with the
 * current network colour. Same pipeline as the Terminal / Monitor / Variable etc.
 */
class InventoryTerminalRenderer(context: BlockEntityRendererProvider.Context) :
    BlockEntityRenderer<InventoryTerminalBlockEntity, InventoryTerminalRenderer.State> {

    class State : BlockEntityRenderState() {
        var facing: Direction = Direction.NORTH
        var color: Int = NodeConnectionRenderer.DEFAULT_NETWORK_COLOR
    }

    companion object {
        private val FRONT_TEXTURE = Identifier.fromNamespaceAndPath("nodeworks", "textures/block/inventory_terminal_front_emissive.png")
        private val BACK_TEXTURE = Identifier.fromNamespaceAndPath("nodeworks", "textures/block/inventory_terminal_back_emissive.png")
        private val FRONT_RENDER_TYPE: RenderType = EmissiveCubeRenderer.renderType(FRONT_TEXTURE)
        private val BACK_RENDER_TYPE: RenderType = EmissiveCubeRenderer.renderType(BACK_TEXTURE)
    }

    override fun createRenderState(): State = State()

    override fun extractRenderState(
        blockEntity: InventoryTerminalBlockEntity,
        state: State,
        partialTicks: Float,
        cameraPosition: Vec3,
        breakProgress: ModelFeatureRenderer.CrumblingOverlay?
    ) {
        BlockEntityRenderState.extractBase(blockEntity, state, breakProgress)
        state.facing = blockEntity.blockState.getValue(InventoryTerminalBlock.FACING)
        // Match every other network-tinted emissive block: unreachable → grey default.
        state.color = if (!NodeConnectionRenderer.isReachable(blockEntity.blockPos)) {
            NodeConnectionRenderer.DEFAULT_NETWORK_COLOR
        } else {
            NodeConnectionRenderer.findNetworkColor(blockEntity.level, blockEntity.blockPos)
        }
    }

    override fun submit(
        state: State,
        poseStack: PoseStack,
        submitNodeCollector: SubmitNodeCollector,
        camera: CameraRenderState
    ) {
        val r = (state.color shr 16) and 0xFF
        val g = (state.color shr 8) and 0xFF
        val b = state.color and 0xFF
        EmissiveCubeRenderer.submit(
            submitNodeCollector, poseStack, FRONT_RENDER_TYPE,
            EmissiveCubeRenderer.faceOf(state.facing), r, g, b, 255
        )
        EmissiveCubeRenderer.submit(
            submitNodeCollector, poseStack, BACK_RENDER_TYPE,
            EmissiveCubeRenderer.faceOf(state.facing.opposite), r, g, b, 255
        )
    }
}
