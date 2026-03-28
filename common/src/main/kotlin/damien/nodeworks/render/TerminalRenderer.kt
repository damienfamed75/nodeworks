package damien.nodeworks.render

import com.mojang.blaze3d.vertex.PoseStack
import damien.nodeworks.block.entity.TerminalBlockEntity
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.core.Direction
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import org.joml.Quaternionf

/**
 * Renders an emissive overlay on the front face of the Terminal block.
 */
class TerminalRenderer(context: BlockEntityRendererProvider.Context) :
    BlockEntityRenderer<TerminalBlockEntity> {

    companion object {
        private val EMISSIVE_TEXTURE = ResourceLocation.fromNamespaceAndPath("nodeworks", "textures/block/terminal_front_emissive.png")
    }

    override fun render(
        entity: TerminalBlockEntity,
        partialTick: Float,
        poseStack: PoseStack,
        bufferSource: MultiBufferSource,
        packedLight: Int,
        packedOverlay: Int
    ) {
        // Emissive overlay is now handled by the block model via neoforge_data
    }

    @Suppress("unused")
    private fun renderLegacy(entity: TerminalBlockEntity, poseStack: PoseStack, bufferSource: MultiBufferSource, packedLight: Int, packedOverlay: Int) {
        val facing = entity.blockState.getValue(BlockStateProperties.HORIZONTAL_FACING)
        val color = NodeConnectionRenderer.findNetworkColor(entity.level, entity.blockPos)
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF

        val light = 15728880
        val overlay = OverlayTexture.NO_OVERLAY
        val eyesType = RenderType.eyes(EMISSIVE_TEXTURE)

        poseStack.pushPose()

        // Move to block center, rotate to face direction, then push to front face
        poseStack.translate(0.5, 0.5, 0.5)
        when (facing) {
            Direction.NORTH -> {} // blockstate y=0
            Direction.SOUTH -> poseStack.mulPose(Quaternionf().rotateY(Math.PI.toFloat())) // y=180
            Direction.EAST -> poseStack.mulPose(Quaternionf().rotateY((Math.PI * 1.5).toFloat())) // y=270
            Direction.WEST -> poseStack.mulPose(Quaternionf().rotateY((Math.PI / 2).toFloat())) // y=90
            else -> {}
        }

        // Front face quad at z = -0.501 (slightly outside block surface)
        val o = 0.501f
        val h = 0.5f // half-size

        val vc = bufferSource.getBuffer(eyesType)
        val pose = poseStack.last()
        vc.addVertex(pose, -h, -h, -o).setUv(1f, 1f).setColor(r, g, b, 255).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 0f, 1f)
        vc.addVertex(pose, -h, h, -o).setUv(1f, 0f).setColor(r, g, b, 255).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 0f, 1f)
        vc.addVertex(pose, h, h, -o).setUv(0f, 0f).setColor(r, g, b, 255).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 0f, 1f)
        vc.addVertex(pose, h, -h, -o).setUv(0f, 1f).setColor(r, g, b, 255).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 0f, 1f)

        poseStack.popPose()
    }
}
