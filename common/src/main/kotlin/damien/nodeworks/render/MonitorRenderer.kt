package damien.nodeworks.render

import com.mojang.blaze3d.vertex.PoseStack
import damien.nodeworks.block.entity.NodeBlockEntity
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState
import net.minecraft.client.renderer.feature.ModelFeatureRenderer
import net.minecraft.client.renderer.item.ItemStackRenderState
import net.minecraft.client.renderer.state.CameraRenderState
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.world.item.ItemDisplayContext
import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.Vec3
import org.joml.Quaternionf

/**
 * Renders item icons and counts on node monitor faces.
 */
class MonitorRenderer(context: BlockEntityRendererProvider.Context) : BlockEntityRenderer<NodeBlockEntity, MonitorRenderer.MonitorRenderState> {

    data class MonitorFace(
        val direction: Direction,
        val itemId: String?,
        val count: Long,
        val itemRenderState: ItemStackRenderState? = null
    )

    class MonitorRenderState : BlockEntityRenderState() {
        var faces: List<MonitorFace> = emptyList()
    }

    override fun createRenderState(): MonitorRenderState = MonitorRenderState()

    override fun extractRenderState(
        entity: NodeBlockEntity,
        state: MonitorRenderState,
        partialTick: Float,
        cameraPos: Vec3,
        crumbling: ModelFeatureRenderer.CrumblingOverlay?
    ) {
        super.extractRenderState(entity, state, partialTick, cameraPos, crumbling)
        val mc = Minecraft.getInstance()
        state.faces = entity.getMonitorFaces().map { face ->
            val monitor = entity.getMonitor(face)
            val itemId = monitor?.trackedItemId

            val itemRenderState = if (itemId != null) {
                val identifier = Identifier.tryParse(itemId)
                val item = if (identifier != null) BuiltInRegistries.ITEM.getValue(identifier) else null
                if (item != null) {
                    val rs = ItemStackRenderState()
                    mc.itemModelResolver.appendItemLayers(rs, ItemStack(item, 1), ItemDisplayContext.GUI, mc.level, null, 0)
                    rs
                } else null
            } else null

            MonitorFace(face, itemId, monitor?.displayCount ?: 0L, itemRenderState)
        }
    }

    override fun submit(
        state: MonitorRenderState,
        poseStack: PoseStack,
        collector: SubmitNodeCollector,
        camera: CameraRenderState
    ) {
        val mc = Minecraft.getInstance()

        for (face in state.faces) {

            poseStack.pushPose()

            // Move to block center
            poseStack.translate(0.5, 0.5, 0.5)
            // Rotate so +Z points out of the face
            rotateToFace(poseStack, face.direction)
            // Push to the face surface (centered vertically)
            poseStack.translate(0.0, 0.0, 0.5)
            // Flip Z to face outward (toward the player)
            poseStack.scale(1f, 1f, -1f)

            // Render monitor as a thin 3D box matching the hitbox (12x12x2 pixels)
            run {
                val hw = 0.375f  // half-width (12/16 / 2)
                val hh = 0.375f  // half-height
                val depth = 0.125f // 2/16
                val overlay = net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY
                val light = 15728880

                val faceTex = net.minecraft.client.renderer.rendertype.RenderTypes.entityCutoutNoCull(
                    Identifier.fromNamespaceAndPath("nodeworks", "textures/block/monitor_face.png")
                )
                val sideTex = net.minecraft.client.renderer.rendertype.RenderTypes.entityCutoutNoCull(
                    Identifier.fromNamespaceAndPath("nodeworks", "textures/block/monitor_side.png")
                )
                val backTex = net.minecraft.client.renderer.rendertype.RenderTypes.entityCutoutNoCull(
                    Identifier.fromNamespaceAndPath("nodeworks", "textures/block/monitor_back.png")
                )

                // Front face (screen)
                collector.submitCustomGeometry(poseStack, faceTex) { pose, vc ->
                    vc.addVertex(pose,  hw, -hh, 0f).setUv(1f, 0f).setColor(255, 255, 255, 255).setLight(light).setOverlay(overlay).setNormal(pose, 0f, 0f, 1f)
                    vc.addVertex(pose,  hw,  hh, 0f).setUv(1f, 1f).setColor(255, 255, 255, 255).setLight(light).setOverlay(overlay).setNormal(pose, 0f, 0f, 1f)
                    vc.addVertex(pose, -hw,  hh, 0f).setUv(0f, 1f).setColor(255, 255, 255, 255).setLight(light).setOverlay(overlay).setNormal(pose, 0f, 0f, 1f)
                    vc.addVertex(pose, -hw, -hh, 0f).setUv(0f, 0f).setColor(255, 255, 255, 255).setLight(light).setOverlay(overlay).setNormal(pose, 0f, 0f, 1f)
                }
                // Back face
                collector.submitCustomGeometry(poseStack, backTex) { pose, vc ->
                    vc.addVertex(pose, -hw, -hh, depth).setUv(0f, 0f).setColor(255, 255, 255, 255).setLight(light).setOverlay(overlay).setNormal(pose, 0f, 0f, -1f)
                    vc.addVertex(pose, -hw,  hh, depth).setUv(0f, 1f).setColor(255, 255, 255, 255).setLight(light).setOverlay(overlay).setNormal(pose, 0f, 0f, -1f)
                    vc.addVertex(pose,  hw,  hh, depth).setUv(1f, 1f).setColor(255, 255, 255, 255).setLight(light).setOverlay(overlay).setNormal(pose, 0f, 0f, -1f)
                    vc.addVertex(pose,  hw, -hh, depth).setUv(1f, 0f).setColor(255, 255, 255, 255).setLight(light).setOverlay(overlay).setNormal(pose, 0f, 0f, -1f)
                }
                // Top, bottom, left, right faces (sides)
                collector.submitCustomGeometry(poseStack, sideTex) { pose, vc ->
                    // Top
                    vc.addVertex(pose,  hw, hh, 0f).setUv(1f, 0f).setColor(255, 255, 255, 255).setLight(light).setOverlay(overlay).setNormal(pose, 0f, 1f, 0f)
                    vc.addVertex(pose,  hw, hh, depth).setUv(1f, 0.125f).setColor(255, 255, 255, 255).setLight(light).setOverlay(overlay).setNormal(pose, 0f, 1f, 0f)
                    vc.addVertex(pose, -hw, hh, depth).setUv(0f, 0.125f).setColor(255, 255, 255, 255).setLight(light).setOverlay(overlay).setNormal(pose, 0f, 1f, 0f)
                    vc.addVertex(pose, -hw, hh, 0f).setUv(0f, 0f).setColor(255, 255, 255, 255).setLight(light).setOverlay(overlay).setNormal(pose, 0f, 1f, 0f)
                    // Bottom
                    vc.addVertex(pose, -hw, -hh, 0f).setUv(0f, 0f).setColor(255, 255, 255, 255).setLight(light).setOverlay(overlay).setNormal(pose, 0f, -1f, 0f)
                    vc.addVertex(pose, -hw, -hh, depth).setUv(0f, 0.125f).setColor(255, 255, 255, 255).setLight(light).setOverlay(overlay).setNormal(pose, 0f, -1f, 0f)
                    vc.addVertex(pose,  hw, -hh, depth).setUv(1f, 0.125f).setColor(255, 255, 255, 255).setLight(light).setOverlay(overlay).setNormal(pose, 0f, -1f, 0f)
                    vc.addVertex(pose,  hw, -hh, 0f).setUv(1f, 0f).setColor(255, 255, 255, 255).setLight(light).setOverlay(overlay).setNormal(pose, 0f, -1f, 0f)
                    // Right
                    vc.addVertex(pose, hw, -hh, 0f).setUv(0f, 0f).setColor(255, 255, 255, 255).setLight(light).setOverlay(overlay).setNormal(pose, 1f, 0f, 0f)
                    vc.addVertex(pose, hw, -hh, depth).setUv(0.125f, 0f).setColor(255, 255, 255, 255).setLight(light).setOverlay(overlay).setNormal(pose, 1f, 0f, 0f)
                    vc.addVertex(pose, hw,  hh, depth).setUv(0.125f, 1f).setColor(255, 255, 255, 255).setLight(light).setOverlay(overlay).setNormal(pose, 1f, 0f, 0f)
                    vc.addVertex(pose, hw,  hh, 0f).setUv(0f, 1f).setColor(255, 255, 255, 255).setLight(light).setOverlay(overlay).setNormal(pose, 1f, 0f, 0f)
                    // Left
                    vc.addVertex(pose, -hw,  hh, 0f).setUv(0f, 1f).setColor(255, 255, 255, 255).setLight(light).setOverlay(overlay).setNormal(pose, -1f, 0f, 0f)
                    vc.addVertex(pose, -hw,  hh, depth).setUv(0.125f, 1f).setColor(255, 255, 255, 255).setLight(light).setOverlay(overlay).setNormal(pose, -1f, 0f, 0f)
                    vc.addVertex(pose, -hw, -hh, depth).setUv(0.125f, 0f).setColor(255, 255, 255, 255).setLight(light).setOverlay(overlay).setNormal(pose, -1f, 0f, 0f)
                    vc.addVertex(pose, -hw, -hh, 0f).setUv(0f, 0f).setColor(255, 255, 255, 255).setLight(light).setOverlay(overlay).setNormal(pose, -1f, 0f, 0f)
                }
            }

            // Render item icon if set — push in front of the panel to avoid z-fighting
            val itemRS = face.itemRenderState
            if (itemRS != null && !itemRS.isEmpty) {
                poseStack.pushPose()
                poseStack.translate(0.0, 0.02, -0.02)
                poseStack.scale(0.3f, 0.3f, 0.001f)
                itemRS.submit(poseStack, collector, 0xF000F0, net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY, 0)
                poseStack.popPose()
            }

            // Render count text as a colored quad with the count baked into a texture
            // Since submitText doesn't work in BlockEntityRenderer, render text
            // using the world render event hook instead (NodeConnectionRenderer)
            // For now, store the data — the render hook will draw it

            poseStack.popPose()
        }
    }

    private fun rotateToFace(poseStack: PoseStack, face: Direction) {
        when (face) {
            Direction.SOUTH -> {}
            Direction.NORTH -> poseStack.mulPose(Quaternionf().rotateY(Math.PI.toFloat()))
            Direction.EAST  -> poseStack.mulPose(Quaternionf().rotateY((Math.PI / 2).toFloat()))
            Direction.WEST  -> poseStack.mulPose(Quaternionf().rotateY((-Math.PI / 2).toFloat()))
            Direction.DOWN  -> poseStack.mulPose(Quaternionf().rotateX((-Math.PI / 2).toFloat()))
            Direction.UP    -> poseStack.mulPose(Quaternionf().rotateX((Math.PI / 2).toFloat()))
        }
    }

    private fun formatCount(count: Long): String {
        return when {
            count >= 1_000_000 -> String.format("%.1fM", count / 1_000_000.0)
            count >= 1_000 -> String.format("%.1fK", count / 1_000.0)
            else -> count.toString()
        }
    }
}
