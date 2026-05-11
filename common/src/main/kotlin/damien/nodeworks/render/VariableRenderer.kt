package damien.nodeworks.render

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.QuadInstance
import com.mojang.math.Axis
import damien.nodeworks.block.entity.VariableBlockEntity
import damien.nodeworks.block.entity.VariableType
import damien.nodeworks.client.VariableEmissiveModel
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.client.renderer.feature.ModelFeatureRenderer
import net.minecraft.client.renderer.item.ItemModelResolver
import net.minecraft.client.renderer.item.ItemStackRenderState
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.core.Direction
import net.minecraft.util.ARGB
import net.minecraft.world.item.ItemDisplayContext
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.phys.Vec3

/**
 * BER for the Variable block. Lays a network-colour-tinted emissive overlay
 * on the body using the same parent-model pattern the User / Placer /
 * Breaker use, AND floats a type-specific item (redstone for NUMBER, string
 * for STRING, lever for BOOL) inside the slime-cube so the player can read
 * the variable's mode at a glance without opening the GUI.
 */
open class VariableRenderer(context: BlockEntityRendererProvider.Context) :
    ConnectableBER<VariableBlockEntity, VariableRenderer.VariableState>(context) {

    private val itemModelResolver: ItemModelResolver = context.itemModelResolver()

    class VariableState : ConnectableRenderState() {
        var color: Int = NodeConnectionRenderer.DEFAULT_NETWORK_COLOR
        var variableType: VariableType = VariableType.NUMBER
        val itemRS: ItemStackRenderState = ItemStackRenderState()
    }

    companion object {
        /** Outset factor applied around the block centre to the emissive
         *  overlay's geometry to win z-fight tests against the underlying
         *  chunk-rendered body. 1.001 = 1 px offset on a full-block face,
         *  invisible to the eye but enough for the depth test. */
        private const val EMISSIVE_OUTSET = 1.001f

        /** Block-local Y position for the floating item. ~12/16 lands the
         *  item inside the slime_cube element, which after its 90° X-rotation
         *  sits roughly at Y=8..15.75 in world-local coords. */
        private const val FLOATING_ITEM_Y = 0.72f

        /** Vertical bob amplitude in block units. Half a pixel. */
        private const val FLOATING_BOB_AMP = 1f / 32f

        /** Bob cycle frequency in radians per second. ~1 full cycle every 3 s. */
        private const val FLOATING_BOB_FREQ = 2f

        /** Y-axis spin speed in degrees per second. Slow enough to read as
         *  decorative, fast enough that the icon is clearly animated. */
        private const val FLOATING_SPIN_DEG_PER_SEC = 45f

        /** Render scale for the floating item. 0.5 = 8 px tall, fits well
         *  inside the ~11×11×7.75 slime_cube. */
        private const val FLOATING_ITEM_SCALE = 0.5f

        /** All six directions + null, the parameter to [BlockStateModelPart.getQuads]
         *  for "this face direction" and "no specific face" respectively. */
        private val DIRECTIONS_AND_NULL: Array<Direction?> =
            arrayOf(
                Direction.DOWN,
                Direction.UP,
                Direction.NORTH,
                Direction.SOUTH,
                Direction.WEST,
                Direction.EAST,
                null,
            )

        /** Pre-built stacks per variable type so the resolver isn't handed a
         *  fresh ItemStack every frame. Lazy because `BlockEntityRenderers`
         *  initialises BERs during the resource-pack reload BEFORE item
         *  registry components are bound; eager `ItemStack(Items.REDSTONE)`
         *  at clinit time crashed with `Components not bound yet`. Resolving
         *  the stacks on first render sidesteps the timing problem since
         *  any rendered Variable post-dates registry bootstrap. */
        private val ITEM_NUMBER: ItemStack by lazy { ItemStack(Items.REDSTONE) }
        private val ITEM_STRING: ItemStack by lazy { ItemStack(Items.STRING) }
        private val ITEM_BOOL: ItemStack by lazy { ItemStack(Items.LEVER) }

        private fun stackFor(type: VariableType): ItemStack = when (type) {
            VariableType.NUMBER -> ITEM_NUMBER
            VariableType.STRING -> ITEM_STRING
            VariableType.BOOL -> ITEM_BOOL
        }
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
        state.variableType = blockEntity.variableType
        itemModelResolver.updateForTopItem(
            state.itemRS, stackFor(state.variableType), ItemDisplayContext.GROUND,
            blockEntity.level, null, 0,
        )
    }

    override fun submitConnectable(
        state: VariableState,
        poseStack: PoseStack,
        submitNodeCollector: SubmitNodeCollector,
        camera: CameraRenderState,
    ) {
        // Emissive overlay first so it sits behind the floating item.
        val emissive = VariableEmissiveModel.get()
        if (emissive != null && state.color != NodeConnectionRenderer.DEFAULT_NETWORK_COLOR) {
            poseStack.pushPose()
            poseStack.translate(0.5, 0.5, 0.5)
            poseStack.scale(EMISSIVE_OUTSET, EMISSIVE_OUTSET, EMISSIVE_OUTSET)
            poseStack.translate(-0.5, -0.5, -0.5)
            submitEmissiveOverlay(poseStack, submitNodeCollector, emissive, state.color)
            poseStack.popPose()
        }

        // Type-indicator item, floating inside the slime cube. Spins around Y
        // and bobs up/down on a wall-clock-derived time so every Variable in
        // the chunk stays in phase frame-to-frame (no per-BE state needed).
        // The spin time wraps at EXACTLY one rotation period so the angle
        // crosses 360° → 0° smoothly; the previous arbitrary 60 s window
        // ended mid-rotation and snapped the item back to 0° on each wrap.
        val nowMs = System.currentTimeMillis()
        val spinPeriodMs = (360f / FLOATING_SPIN_DEG_PER_SEC * 1000f).toLong()
        val spinSec = (nowMs % spinPeriodMs) / 1000f
        val spinDegrees = spinSec * FLOATING_SPIN_DEG_PER_SEC
        // Bob uses a separate, longer wrap; sin is continuous so a tiny
        // discontinuity at the rollover is invisible at this amplitude.
        val bobSec = (nowMs % 60_000L) / 1000f
        val bobOffset = kotlin.math.sin(bobSec * FLOATING_BOB_FREQ) * FLOATING_BOB_AMP
        poseStack.pushPose()
        poseStack.translate(0.5, (FLOATING_ITEM_Y + bobOffset).toDouble(), 0.5)
        poseStack.mulPose(Axis.YP.rotationDegrees(spinDegrees))
        poseStack.scale(FLOATING_ITEM_SCALE, FLOATING_ITEM_SCALE, FLOATING_ITEM_SCALE)
        state.itemRS.submit(poseStack, submitNodeCollector, 0xF000F0, OverlayTexture.NO_OVERLAY, 0)
        poseStack.popPose()
    }

    /** Network-coloured additive overlay over the Variable's body. The baked
     *  quads carry variable.json's per-face UVs (since variable_emissive.json
     *  inherits geometry via JSON parent), so the overlay's texture sampling
     *  lands on the same atlas regions as the body model. The vertex tint
     *  multiplies the texture sample, so transparent pixels in
     *  variable_emissive.png contribute zero (additive blend) and only
     *  authored emissive areas glow. */
    private fun submitEmissiveOverlay(
        poseStack: PoseStack,
        collector: SubmitNodeCollector,
        part: BlockStateModelPart,
        tintColor: Int,
    ) {
        val argb = ARGB.color(255, (tintColor shr 16) and 0xFF, (tintColor shr 8) and 0xFF, tintColor and 0xFF)
        val quadInstance = QuadInstance().apply {
            for (vertex in 0..3) setColor(vertex, argb)
        }
        collector.submitCustomGeometry(poseStack, EmissiveCubeRenderer.BLOCK_ATLAS_RENDER_TYPE) { pose, vc ->
            for (dir in DIRECTIONS_AND_NULL) {
                for (quad in part.getQuads(dir)) {
                    vc.putBakedQuad(pose, quad, quadInstance)
                }
            }
        }
    }
}
