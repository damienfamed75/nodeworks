package damien.nodeworks.render

import com.mojang.blaze3d.vertex.PoseStack
import damien.nodeworks.block.entity.VariableBlockEntity
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.client.renderer.feature.ModelFeatureRenderer
import net.minecraft.client.renderer.rendertype.RenderType
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.resources.Identifier
import net.minecraft.world.phys.Vec3

/**
 * Emissive overlay for the Variable block — glowing side faces (N/S/E/W only, top/bottom
 * unlit), tinted with the current network colour.
 */
open class VariableRenderer(context: BlockEntityRendererProvider.Context) :
    ConnectableBER<VariableBlockEntity, VariableRenderer.VariableState>(context) {

    class VariableState : ConnectableRenderState() {
        var color: Int = NodeConnectionRenderer.DEFAULT_NETWORK_COLOR
    }

    companion object {
        private val TEXTURE = Identifier.fromNamespaceAndPath("nodeworks", "textures/block/variable_emissive.png")
        private val RENDER_TYPE: RenderType = EmissiveCubeRenderer.renderType(TEXTURE)
    }

    override fun createRenderState(): VariableState = VariableState()

    override fun extractConnectable(
        blockEntity: VariableBlockEntity,
        state: VariableState,
        partialTicks: Float,
        cameraPosition: Vec3,
        breakProgress: ModelFeatureRenderer.CrumblingOverlay?,
    ) {
        state.color = resolveNetworkColor(blockEntity)
    }

    override fun submitConnectable(
        state: VariableState,
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
