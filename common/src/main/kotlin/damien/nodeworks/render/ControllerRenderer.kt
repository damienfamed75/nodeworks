package damien.nodeworks.render

import com.mojang.blaze3d.vertex.PoseStack
import damien.nodeworks.block.entity.NetworkControllerBlockEntity
import damien.nodeworks.network.NetworkSettingsRegistry
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState
import net.minecraft.client.renderer.feature.ModelFeatureRenderer
import net.minecraft.client.renderer.rendertype.RenderType
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.resources.Identifier
import net.minecraft.world.phys.Vec3
import org.joml.Quaternionf
import kotlin.math.sin

/**
 * Renders a floating, rotating, bobbing crystal at the Controller's centre — same
 * aesthetic as the End Crystal, tinted with the network colour. Three concentric
 * cubes rotate together around a tilted axis (pattern copied from vanilla
 * EndCrystalModel / EndCrystalRenderer):
 *
 *   outer  — network colour at low alpha → coloured glass shell
 *   inner  — network colour at higher alpha → brighter middle layer
 *   core   — pure white, opaque → white-hot centre the player reads as the "bulb"
 *
 * All three share the tilted-axis rotation + Y bob so they move as one crystal.
 * The JSON model's inner_cube element was removed — the dynamic crystal takes
 * that space over.
 */
class ControllerRenderer(context: BlockEntityRendererProvider.Context) :
    BlockEntityRenderer<NetworkControllerBlockEntity, ControllerRenderer.ControllerState> {

    class ControllerState : BlockEntityRenderState() {
        var networkColor: Int = NodeConnectionRenderer.DEFAULT_NETWORK_COLOR
    }

    companion object {
        /** Pure-white 1×1 texture. Using any textured render type pulls the texture's
         *  colour into the output; we want the vertex colour (network colour for shells,
         *  255/255/255 for the core) to be the only tint source, so a uniform white
         *  texture is the cleanest path. */
        private val CRYSTAL_TEXTURE = Identifier.fromNamespaceAndPath("nodeworks", "textures/block/crystal_core.png")

        /** Vanilla EndCrystal's tilt axis — rotating around (sin45, 0, sin45) by PI/3
         *  gives the diamond its characteristic off-square orientation. */
        private val SIN_45 = sin(Math.PI / 4).toFloat()
    }

    override fun createRenderState(): ControllerState = ControllerState()

    override fun extractRenderState(
        blockEntity: NetworkControllerBlockEntity,
        state: ControllerState,
        partialTicks: Float,
        cameraPosition: Vec3,
        breakProgress: ModelFeatureRenderer.CrumblingOverlay?
    ) {
        BlockEntityRenderState.extractBase(blockEntity, state, breakProgress)
        val settings = NetworkSettingsRegistry.get(blockEntity.networkId)
        state.networkColor = settings.color
    }

    override fun submit(
        state: ControllerState,
        poseStack: PoseStack,
        submitNodeCollector: SubmitNodeCollector,
        camera: CameraRenderState
    ) {
        // Time-driven animation. 50ms/tick ≈ the rate vanilla EndCrystalRenderer uses
        // (it takes ageInTicks * 3 degrees); match that so the rotation looks identical.
        val ticks = (System.currentTimeMillis() % 100000L) / 50f
        val rotRad = ticks * 3f * (Math.PI / 180f).toFloat()
        // Bob formula from EndCrystalRenderer.getY — range roughly -1.4..-0.6 in entity
        // space, which in block space becomes a subtle ±0.05 block vertical wobble.
        val bobRaw = sin(ticks * 0.2f) * 0.5f + 0.5f
        val bobY = (bobRaw * bobRaw + bobRaw) * 0.4f * 0.1f

        val r = (state.networkColor shr 16) and 0xFF
        val g = (state.networkColor shr 8) and 0xFF
        val b = state.networkColor and 0xFF

        val glowType = RenderTypes.entityTranslucentEmissive(CRYSTAL_TEXTURE)
        val coreType = RenderTypes.entityCutout(CRYSTAL_TEXTURE)

        poseStack.pushPose()
        // Centre on the block, with the bob applied before rotation so the whole
        // crystal floats as one.
        poseStack.translate(0.5, 0.5 + bobY, 0.5)

        // Outer shell — half-width 0.22 (covers roughly the old inner_cube footprint),
        // rotating on its own axis with a slower bias so the layers visibly shift.
        poseStack.pushPose()
        applyCrystalRotation(poseStack, rotRad)
        submitCube(submitNodeCollector, poseStack, glowType, 0.22f, r, g, b, 90)
        poseStack.popPose()

        // Inner shell — slightly smaller, counter-rotated so the two shells pass
        // through each other visibly (same trick EndCrystalModel uses to give the
        // crystal its animated shimmer).
        poseStack.pushPose()
        applyCrystalRotation(poseStack, -rotRad * 1.3f)
        submitCube(submitNodeCollector, poseStack, glowType, 0.22f * 0.875f, r, g, b, 160)
        poseStack.popPose()

        // White-hot core — opaque emissive, smallest. The user sees this as the
        // crystal's "brightest inside" centre regardless of how dark the network
        // colour is.
        poseStack.pushPose()
        applyCrystalRotation(poseStack, rotRad * 0.8f)
        submitCube(submitNodeCollector, poseStack, coreType, 0.22f * 0.56f, 255, 255, 255, 255)
        poseStack.popPose()

        poseStack.popPose()
    }

    override fun shouldRenderOffScreen(): Boolean = true

    /** Tilt by PI/3 around (sin45, 0, sin45), then rotate around Y by [rotRadians].
     *  Identical to EndCrystalModel's quaternion setup. */
    private fun applyCrystalRotation(poseStack: PoseStack, rotRadians: Float) {
        val q = Quaternionf()
            .setAngleAxis((Math.PI / 3).toFloat(), SIN_45, 0f, SIN_45)
            .rotateY(rotRadians)
        poseStack.mulPose(q)
    }

    /** Emit a cube centred at origin, half-width [hw], with the given tint, emissive &
     *  no-overlay. Six faces, standard quad winding. */
    private fun submitCube(
        submitNodeCollector: SubmitNodeCollector,
        poseStack: PoseStack,
        renderType: RenderType,
        hw: Float,
        r: Int, g: Int, b: Int, a: Int,
    ) {
        val overlay = OverlayTexture.NO_OVERLAY
        val light = 15728880
        val mn = -hw
        val mx = hw
        submitNodeCollector.submitCustomGeometry(poseStack, renderType) { pose, vc ->
            // +Z
            vc.addVertex(pose, mx, mn, mx).setUv(1f, 1f).setColor(r, g, b, a).setOverlay(overlay).setUv2(light, light).setNormal(pose, 0f, 0f, 1f)
            vc.addVertex(pose, mx, mx, mx).setUv(1f, 0f).setColor(r, g, b, a).setOverlay(overlay).setUv2(light, light).setNormal(pose, 0f, 0f, 1f)
            vc.addVertex(pose, mn, mx, mx).setUv(0f, 0f).setColor(r, g, b, a).setOverlay(overlay).setUv2(light, light).setNormal(pose, 0f, 0f, 1f)
            vc.addVertex(pose, mn, mn, mx).setUv(0f, 1f).setColor(r, g, b, a).setOverlay(overlay).setUv2(light, light).setNormal(pose, 0f, 0f, 1f)
            // -Z
            vc.addVertex(pose, mn, mn, mn).setUv(1f, 1f).setColor(r, g, b, a).setOverlay(overlay).setUv2(light, light).setNormal(pose, 0f, 0f, -1f)
            vc.addVertex(pose, mn, mx, mn).setUv(1f, 0f).setColor(r, g, b, a).setOverlay(overlay).setUv2(light, light).setNormal(pose, 0f, 0f, -1f)
            vc.addVertex(pose, mx, mx, mn).setUv(0f, 0f).setColor(r, g, b, a).setOverlay(overlay).setUv2(light, light).setNormal(pose, 0f, 0f, -1f)
            vc.addVertex(pose, mx, mn, mn).setUv(0f, 1f).setColor(r, g, b, a).setOverlay(overlay).setUv2(light, light).setNormal(pose, 0f, 0f, -1f)
            // +X
            vc.addVertex(pose, mx, mn, mn).setUv(1f, 1f).setColor(r, g, b, a).setOverlay(overlay).setUv2(light, light).setNormal(pose, 1f, 0f, 0f)
            vc.addVertex(pose, mx, mx, mn).setUv(1f, 0f).setColor(r, g, b, a).setOverlay(overlay).setUv2(light, light).setNormal(pose, 1f, 0f, 0f)
            vc.addVertex(pose, mx, mx, mx).setUv(0f, 0f).setColor(r, g, b, a).setOverlay(overlay).setUv2(light, light).setNormal(pose, 1f, 0f, 0f)
            vc.addVertex(pose, mx, mn, mx).setUv(0f, 1f).setColor(r, g, b, a).setOverlay(overlay).setUv2(light, light).setNormal(pose, 1f, 0f, 0f)
            // -X
            vc.addVertex(pose, mn, mn, mx).setUv(1f, 1f).setColor(r, g, b, a).setOverlay(overlay).setUv2(light, light).setNormal(pose, -1f, 0f, 0f)
            vc.addVertex(pose, mn, mx, mx).setUv(1f, 0f).setColor(r, g, b, a).setOverlay(overlay).setUv2(light, light).setNormal(pose, -1f, 0f, 0f)
            vc.addVertex(pose, mn, mx, mn).setUv(0f, 0f).setColor(r, g, b, a).setOverlay(overlay).setUv2(light, light).setNormal(pose, -1f, 0f, 0f)
            vc.addVertex(pose, mn, mn, mn).setUv(0f, 1f).setColor(r, g, b, a).setOverlay(overlay).setUv2(light, light).setNormal(pose, -1f, 0f, 0f)
            // +Y
            vc.addVertex(pose, mn, mx, mx).setUv(0f, 1f).setColor(r, g, b, a).setOverlay(overlay).setUv2(light, light).setNormal(pose, 0f, 1f, 0f)
            vc.addVertex(pose, mx, mx, mx).setUv(1f, 1f).setColor(r, g, b, a).setOverlay(overlay).setUv2(light, light).setNormal(pose, 0f, 1f, 0f)
            vc.addVertex(pose, mx, mx, mn).setUv(1f, 0f).setColor(r, g, b, a).setOverlay(overlay).setUv2(light, light).setNormal(pose, 0f, 1f, 0f)
            vc.addVertex(pose, mn, mx, mn).setUv(0f, 0f).setColor(r, g, b, a).setOverlay(overlay).setUv2(light, light).setNormal(pose, 0f, 1f, 0f)
            // -Y
            vc.addVertex(pose, mn, mn, mn).setUv(0f, 0f).setColor(r, g, b, a).setOverlay(overlay).setUv2(light, light).setNormal(pose, 0f, -1f, 0f)
            vc.addVertex(pose, mx, mn, mn).setUv(1f, 0f).setColor(r, g, b, a).setOverlay(overlay).setUv2(light, light).setNormal(pose, 0f, -1f, 0f)
            vc.addVertex(pose, mx, mn, mx).setUv(1f, 1f).setColor(r, g, b, a).setOverlay(overlay).setUv2(light, light).setNormal(pose, 0f, -1f, 0f)
            vc.addVertex(pose, mn, mn, mx).setUv(0f, 1f).setColor(r, g, b, a).setOverlay(overlay).setUv2(light, light).setNormal(pose, 0f, -1f, 0f)
        }
    }
}
