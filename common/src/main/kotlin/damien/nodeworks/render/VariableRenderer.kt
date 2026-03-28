package damien.nodeworks.render

import com.mojang.blaze3d.vertex.PoseStack
import damien.nodeworks.block.entity.VariableBlockEntity
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.resources.ResourceLocation

/**
 * Renders an emissive overlay on the Variable block.
 */
class VariableRenderer(context: BlockEntityRendererProvider.Context) :
    BlockEntityRenderer<VariableBlockEntity> {

    companion object {
        private val EMISSIVE_TEXTURE = ResourceLocation.fromNamespaceAndPath("nodeworks", "textures/block/variable_emissive.png")
    }

    override fun render(
        entity: VariableBlockEntity,
        partialTick: Float,
        poseStack: PoseStack,
        bufferSource: MultiBufferSource,
        packedLight: Int,
        packedOverlay: Int
    ) {
        val color = NodeConnectionRenderer.findNetworkColor(entity.level, entity.blockPos)
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF

        val light = 15728880
        val overlay = OverlayTexture.NO_OVERLAY
        val o = -0.001f
        val s = 1.0f + 0.002f

        // Side faces emissive
        val eyesType = RenderType.eyes(EMISSIVE_TEXTURE)
        val vc = bufferSource.getBuffer(eyesType)
        val pose = poseStack.last()

        // South face (+Z)
        vc.addVertex(pose, s, o, s).setUv(1f, 1f).setColor(r, g, b, 255).setUv2(light and 0xFFFF, (light shr 16) and 0xFFFF).setOverlay(overlay).setNormal(pose, 0f, 0f, 1f)
        vc.addVertex(pose, s, s, s).setUv(1f, 0f).setColor(r, g, b, 255).setUv2(light and 0xFFFF, (light shr 16) and 0xFFFF).setOverlay(overlay).setNormal(pose, 0f, 0f, 1f)
        vc.addVertex(pose, o, s, s).setUv(0f, 0f).setColor(r, g, b, 255).setUv2(light and 0xFFFF, (light shr 16) and 0xFFFF).setOverlay(overlay).setNormal(pose, 0f, 0f, 1f)
        vc.addVertex(pose, o, o, s).setUv(0f, 1f).setColor(r, g, b, 255).setUv2(light and 0xFFFF, (light shr 16) and 0xFFFF).setOverlay(overlay).setNormal(pose, 0f, 0f, 1f)

        // North face (-Z)
        vc.addVertex(pose, o, o, o).setUv(1f, 1f).setColor(r, g, b, 255).setUv2(light and 0xFFFF, (light shr 16) and 0xFFFF).setOverlay(overlay).setNormal(pose, 0f, 0f, -1f)
        vc.addVertex(pose, o, s, o).setUv(1f, 0f).setColor(r, g, b, 255).setUv2(light and 0xFFFF, (light shr 16) and 0xFFFF).setOverlay(overlay).setNormal(pose, 0f, 0f, -1f)
        vc.addVertex(pose, s, s, o).setUv(0f, 0f).setColor(r, g, b, 255).setUv2(light and 0xFFFF, (light shr 16) and 0xFFFF).setOverlay(overlay).setNormal(pose, 0f, 0f, -1f)
        vc.addVertex(pose, s, o, o).setUv(0f, 1f).setColor(r, g, b, 255).setUv2(light and 0xFFFF, (light shr 16) and 0xFFFF).setOverlay(overlay).setNormal(pose, 0f, 0f, -1f)

        // East face (+X)
        vc.addVertex(pose, s, o, o).setUv(1f, 1f).setColor(r, g, b, 255).setUv2(light and 0xFFFF, (light shr 16) and 0xFFFF).setOverlay(overlay).setNormal(pose, 1f, 0f, 0f)
        vc.addVertex(pose, s, s, o).setUv(1f, 0f).setColor(r, g, b, 255).setUv2(light and 0xFFFF, (light shr 16) and 0xFFFF).setOverlay(overlay).setNormal(pose, 1f, 0f, 0f)
        vc.addVertex(pose, s, s, s).setUv(0f, 0f).setColor(r, g, b, 255).setUv2(light and 0xFFFF, (light shr 16) and 0xFFFF).setOverlay(overlay).setNormal(pose, 1f, 0f, 0f)
        vc.addVertex(pose, s, o, s).setUv(0f, 1f).setColor(r, g, b, 255).setUv2(light and 0xFFFF, (light shr 16) and 0xFFFF).setOverlay(overlay).setNormal(pose, 1f, 0f, 0f)

        // West face (-X)
        vc.addVertex(pose, o, o, s).setUv(1f, 1f).setColor(r, g, b, 255).setUv2(light and 0xFFFF, (light shr 16) and 0xFFFF).setOverlay(overlay).setNormal(pose, -1f, 0f, 0f)
        vc.addVertex(pose, o, s, s).setUv(1f, 0f).setColor(r, g, b, 255).setUv2(light and 0xFFFF, (light shr 16) and 0xFFFF).setOverlay(overlay).setNormal(pose, -1f, 0f, 0f)
        vc.addVertex(pose, o, s, o).setUv(0f, 0f).setColor(r, g, b, 255).setUv2(light and 0xFFFF, (light shr 16) and 0xFFFF).setOverlay(overlay).setNormal(pose, -1f, 0f, 0f)
        vc.addVertex(pose, o, o, o).setUv(0f, 1f).setColor(r, g, b, 255).setUv2(light and 0xFFFF, (light shr 16) and 0xFFFF).setOverlay(overlay).setNormal(pose, -1f, 0f, 0f)
    }
}
