package damien.nodeworks.render

import com.mojang.blaze3d.vertex.PoseStack
import damien.nodeworks.block.entity.NodeBlockEntity
import damien.nodeworks.network.NetworkSettingsRegistry
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState
import net.minecraft.client.renderer.feature.ModelFeatureRenderer
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.resources.Identifier
import net.minecraft.world.phys.Vec3

/**
 * Renders the glowing emissive overlay on the outside of each Node's central core,
 * tinted to the network colour.
 *
 * The 26.1 BER pipeline splits rendering into `extractRenderState` (reads from the
 * BlockEntity on the main thread, produces an immutable state object) and
 * `submit` (runs on the render thread, emits geometry from the state only — no BE
 * access). We snapshot the current networkColor and glowStyle into [NodeRenderState]
 * during extract, then submit one cube of emissive quads using that snapshot.
 *
 * Monitor-face item icons and card-link laser beams were also handled by the
 * pre-migration `MonitorRenderer.render`; those are separate BER features and are
 * still pending migration.
 */
class MonitorRenderer(context: BlockEntityRendererProvider.Context) :
    BlockEntityRenderer<NodeBlockEntity, MonitorRenderer.NodeRenderState> {

    class NodeRenderState : BlockEntityRenderState() {
        var networkColor: Int = NodeConnectionRenderer.DEFAULT_NETWORK_COLOR
        var glowStyle: Int = 0
        var hasGlow: Boolean = false
    }

    companion object {
        private val GLOW_TEXTURES = arrayOf(
            Identifier.fromNamespaceAndPath("nodeworks", "textures/block/node_glow_square.png"),
            Identifier.fromNamespaceAndPath("nodeworks", "textures/block/node_glow_circle.png"),
            Identifier.fromNamespaceAndPath("nodeworks", "textures/block/node_glow_dot.png"),
            Identifier.fromNamespaceAndPath("nodeworks", "textures/block/node_glow_creeper.png"),
            Identifier.fromNamespaceAndPath("nodeworks", "textures/block/node_glow_spiral.png")
        )
        // glowStyle 5 = NONE in the controller GUI — skip rendering
        private const val GLOW_STYLE_NONE = 5
    }

    override fun createRenderState(): NodeRenderState = NodeRenderState()

    override fun extractRenderState(
        blockEntity: NodeBlockEntity,
        state: NodeRenderState,
        partialTicks: Float,
        cameraPosition: Vec3,
        breakProgress: ModelFeatureRenderer.CrumblingOverlay?
    ) {
        BlockEntityRenderState.extractBase(blockEntity, state, breakProgress)
        val settings = NetworkSettingsRegistry.get(blockEntity.networkId)
        state.networkColor = settings.color
        state.glowStyle = settings.glowStyle
        state.hasGlow = settings.glowStyle != GLOW_STYLE_NONE
    }

    override fun submit(
        state: NodeRenderState,
        poseStack: PoseStack,
        submitNodeCollector: SubmitNodeCollector,
        camera: CameraRenderState
    ) {
        if (!state.hasGlow) return

        val texIndex = state.glowStyle.coerceIn(0, GLOW_TEXTURES.size - 1)
        val renderType = RenderTypes.entityTranslucentEmissive(GLOW_TEXTURES[texIndex])
        val r = (state.networkColor shr 16) and 0xFF
        val g = (state.networkColor shr 8) and 0xFF
        val b = state.networkColor and 0xFF

        // Overlay cube just outside the 4x4x4 center core (pixels 6–10).
        val min = 5.9f / 16f
        val max = 10.1f / 16f
        val overlay = OverlayTexture.NO_OVERLAY
        val light = 15728880

        submitNodeCollector.submitCustomGeometry(poseStack, renderType) { pose, vc ->
            // +Z
            vc.addVertex(pose, max, min, max).setUv(1f, 1f).setColor(r, g, b, 255).setOverlay(overlay).setUv2(light, light).setNormal(pose, 0f, 0f, 1f)
            vc.addVertex(pose, max, max, max).setUv(1f, 0f).setColor(r, g, b, 255).setOverlay(overlay).setUv2(light, light).setNormal(pose, 0f, 0f, 1f)
            vc.addVertex(pose, min, max, max).setUv(0f, 0f).setColor(r, g, b, 255).setOverlay(overlay).setUv2(light, light).setNormal(pose, 0f, 0f, 1f)
            vc.addVertex(pose, min, min, max).setUv(0f, 1f).setColor(r, g, b, 255).setOverlay(overlay).setUv2(light, light).setNormal(pose, 0f, 0f, 1f)
            // -Z
            vc.addVertex(pose, min, min, min).setUv(1f, 1f).setColor(r, g, b, 255).setOverlay(overlay).setUv2(light, light).setNormal(pose, 0f, 0f, -1f)
            vc.addVertex(pose, min, max, min).setUv(1f, 0f).setColor(r, g, b, 255).setOverlay(overlay).setUv2(light, light).setNormal(pose, 0f, 0f, -1f)
            vc.addVertex(pose, max, max, min).setUv(0f, 0f).setColor(r, g, b, 255).setOverlay(overlay).setUv2(light, light).setNormal(pose, 0f, 0f, -1f)
            vc.addVertex(pose, max, min, min).setUv(0f, 1f).setColor(r, g, b, 255).setOverlay(overlay).setUv2(light, light).setNormal(pose, 0f, 0f, -1f)
            // +X
            vc.addVertex(pose, max, min, min).setUv(1f, 1f).setColor(r, g, b, 255).setOverlay(overlay).setUv2(light, light).setNormal(pose, 1f, 0f, 0f)
            vc.addVertex(pose, max, max, min).setUv(1f, 0f).setColor(r, g, b, 255).setOverlay(overlay).setUv2(light, light).setNormal(pose, 1f, 0f, 0f)
            vc.addVertex(pose, max, max, max).setUv(0f, 0f).setColor(r, g, b, 255).setOverlay(overlay).setUv2(light, light).setNormal(pose, 1f, 0f, 0f)
            vc.addVertex(pose, max, min, max).setUv(0f, 1f).setColor(r, g, b, 255).setOverlay(overlay).setUv2(light, light).setNormal(pose, 1f, 0f, 0f)
            // -X
            vc.addVertex(pose, min, min, max).setUv(1f, 1f).setColor(r, g, b, 255).setOverlay(overlay).setUv2(light, light).setNormal(pose, -1f, 0f, 0f)
            vc.addVertex(pose, min, max, max).setUv(1f, 0f).setColor(r, g, b, 255).setOverlay(overlay).setUv2(light, light).setNormal(pose, -1f, 0f, 0f)
            vc.addVertex(pose, min, max, min).setUv(0f, 0f).setColor(r, g, b, 255).setOverlay(overlay).setUv2(light, light).setNormal(pose, -1f, 0f, 0f)
            vc.addVertex(pose, min, min, min).setUv(0f, 1f).setColor(r, g, b, 255).setOverlay(overlay).setUv2(light, light).setNormal(pose, -1f, 0f, 0f)
            // +Y
            vc.addVertex(pose, min, max, max).setUv(0f, 1f).setColor(r, g, b, 255).setOverlay(overlay).setUv2(light, light).setNormal(pose, 0f, 1f, 0f)
            vc.addVertex(pose, max, max, max).setUv(1f, 1f).setColor(r, g, b, 255).setOverlay(overlay).setUv2(light, light).setNormal(pose, 0f, 1f, 0f)
            vc.addVertex(pose, max, max, min).setUv(1f, 0f).setColor(r, g, b, 255).setOverlay(overlay).setUv2(light, light).setNormal(pose, 0f, 1f, 0f)
            vc.addVertex(pose, min, max, min).setUv(0f, 0f).setColor(r, g, b, 255).setOverlay(overlay).setUv2(light, light).setNormal(pose, 0f, 1f, 0f)
            // -Y
            vc.addVertex(pose, min, min, min).setUv(0f, 0f).setColor(r, g, b, 255).setOverlay(overlay).setUv2(light, light).setNormal(pose, 0f, -1f, 0f)
            vc.addVertex(pose, max, min, min).setUv(1f, 0f).setColor(r, g, b, 255).setOverlay(overlay).setUv2(light, light).setNormal(pose, 0f, -1f, 0f)
            vc.addVertex(pose, max, min, max).setUv(1f, 1f).setColor(r, g, b, 255).setOverlay(overlay).setUv2(light, light).setNormal(pose, 0f, -1f, 0f)
            vc.addVertex(pose, min, min, max).setUv(0f, 1f).setColor(r, g, b, 255).setOverlay(overlay).setUv2(light, light).setNormal(pose, 0f, -1f, 0f)
        }
    }

    override fun shouldRenderOffScreen(): Boolean = true
}
