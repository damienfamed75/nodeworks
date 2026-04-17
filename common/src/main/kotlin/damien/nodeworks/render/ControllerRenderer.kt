package damien.nodeworks.render

import com.mojang.blaze3d.vertex.PoseStack
import damien.nodeworks.block.entity.NetworkControllerBlockEntity
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.resources.Identifier

/**
 * Renders an emissive overlay on the Network Controller (AE2-style glowing lines).
 */
class ControllerRenderer(context: BlockEntityRendererProvider.Context) :
    BlockEntityRenderer<NetworkControllerBlockEntity> {

    companion object {
        private val EMISSIVE_TEXTURE = Identifier.fromNamespaceAndPath("nodeworks", "textures/block/network_controller_emissive.png")
        private val EMISSIVE_TOP_TEXTURE = Identifier.fromNamespaceAndPath("nodeworks", "textures/block/network_controller_top_emissive.png")
    }

    override fun render(
        entity: NetworkControllerBlockEntity,
        partialTick: Float,
        poseStack: PoseStack,
        bufferSource: MultiBufferSource,
        packedLight: Int,
        packedOverlay: Int
    ) {
        // Emissive overlay is now handled by the block model via neoforge_data
        // BER kept for future dynamic rendering if needed
    }

    @Suppress("unused")
    private fun renderLegacy(entity: NetworkControllerBlockEntity, poseStack: PoseStack, bufferSource: MultiBufferSource, packedLight: Int, packedOverlay: Int) {
        val color = entity.networkColor
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF

        val light = 15728880
        val overlay = OverlayTexture.NO_OVERLAY
        val eyesType = RenderType.eyes(EMISSIVE_TEXTURE)

        // Slight offset to prevent Z-fighting with the block model
        val o = -0.001f
        val s = 1.0f + 0.002f

        run {
            val vc = bufferSource.getBuffer(eyesType)
            val pose = poseStack.last()

            // South face (+Z) — reversed winding to face outward
            vc.addVertex(pose, s, o, s).setUv(1f, 1f).setColor(r, g, b, 255).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 0f, 1f)
            vc.addVertex(pose, s, s, s).setUv(1f, 0f).setColor(r, g, b, 255).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 0f, 1f)
            vc.addVertex(pose, o, s, s).setUv(0f, 0f).setColor(r, g, b, 255).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 0f, 1f)
            vc.addVertex(pose, o, o, s).setUv(0f, 1f).setColor(r, g, b, 255).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 0f, 1f)

            // North face (-Z)
            vc.addVertex(pose, o, o, o).setUv(1f, 1f).setColor(r, g, b, 255).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 0f, 1f)
            vc.addVertex(pose, o, s, o).setUv(1f, 0f).setColor(r, g, b, 255).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 0f, 1f)
            vc.addVertex(pose, s, s, o).setUv(0f, 0f).setColor(r, g, b, 255).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 0f, 1f)
            vc.addVertex(pose, s, o, o).setUv(0f, 1f).setColor(r, g, b, 255).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 0f, 1f)

            // East face (+X)
            vc.addVertex(pose, s, o, o).setUv(1f, 1f).setColor(r, g, b, 255).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 0f, 1f)
            vc.addVertex(pose, s, s, o).setUv(1f, 0f).setColor(r, g, b, 255).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 0f, 1f)
            vc.addVertex(pose, s, s, s).setUv(0f, 0f).setColor(r, g, b, 255).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 0f, 1f)
            vc.addVertex(pose, s, o, s).setUv(0f, 1f).setColor(r, g, b, 255).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 0f, 1f)

            // West face (-X)
            vc.addVertex(pose, o, o, s).setUv(1f, 1f).setColor(r, g, b, 255).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 0f, 1f)
            vc.addVertex(pose, o, s, s).setUv(1f, 0f).setColor(r, g, b, 255).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 0f, 1f)
            vc.addVertex(pose, o, s, o).setUv(0f, 0f).setColor(r, g, b, 255).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 0f, 1f)
            vc.addVertex(pose, o, o, o).setUv(0f, 1f).setColor(r, g, b, 255).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 0f, 1f)

            // No emissive on bottom face
        }

        // Top face uses separate emissive texture
        val eyesTopType = RenderType.eyes(EMISSIVE_TOP_TEXTURE)
        run {
            val vc = bufferSource.getBuffer(eyesTopType)
            val pose = poseStack.last()
            vc.addVertex(pose, o, s, s).setUv(0f, 1f).setColor(r, g, b, 255).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 0f, 1f)
            vc.addVertex(pose, s, s, s).setUv(1f, 1f).setColor(r, g, b, 255).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 0f, 1f)
            vc.addVertex(pose, s, s, o).setUv(1f, 0f).setColor(r, g, b, 255).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 0f, 1f)
            vc.addVertex(pose, o, s, o).setUv(0f, 0f).setColor(r, g, b, 255).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 0f, 1f)
        }
    }
}
