package damien.nodeworks.render

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.QuadInstance
import com.mojang.math.Axis
import damien.nodeworks.block.UserBlock
import damien.nodeworks.block.entity.UserBlockEntity
import damien.nodeworks.client.UserArmModel
import damien.nodeworks.client.UserEmissiveModel
import damien.nodeworks.network.NetworkSettingsRegistry
import net.minecraft.client.renderer.Sheets
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
import net.minecraft.world.phys.Vec3
import org.joml.Quaternionf
import kotlin.math.min

/**
 * BER for the User device. Animates a Blockbench-authored arm model out the
 * front face like a piston extending and retracting:
 *
 *   * INSTANT mode runs a 1-second triangle wave. 10 ticks extend, world
 *     effect fires at apex (scheduled by the BE's `pendingFireTick`), 10
 *     ticks retract.
 *   * HOLD mode ramps up over the same 10 ticks, holds at full extension
 *     while the device's [UserBlockEntity.heldStack] is non-empty, retracts
 *     over 10 ticks once the hold ends.
 *
 * The arm is loaded as a standalone model via NeoForge's StandaloneModelKey
 * machinery (registered in `NeoForgeClientSetup`); editing the JSON file
 * picks up on the next resource pack reload, no code changes needed.
 */
open class UserRenderer(context: BlockEntityRendererProvider.Context) :
    ConnectableBER<UserBlockEntity, UserRenderer.RenderState>(context) {

    private val itemModelResolver: ItemModelResolver = context.itemModelResolver()

    class RenderState : ConnectableRenderState() {
        var facing: Direction = Direction.NORTH
        val itemRS: ItemStackRenderState = ItemStackRenderState()
        var hasItem: Boolean = false

        /** 0..1 fraction of full extension. Computed in extract from
         *  partialTicks so the animation reads smoothly between server ticks. */
        var extension: Float = 0f

        /** Cached at extract time so the emissive overlay tints to whatever
         *  channel the User is currently bound to. Defaults to the
         *  no-network grey so disconnected Users render dark instead of
         *  picking up a stale tint. */
        var networkColor: Int = NodeConnectionRenderer.DEFAULT_NETWORK_COLOR

        /** Network laser mode (FANCY vs FAST) and connectivity flag, mirroring
         *  the fields PipeRenderer / NodeRenderer cache for [PipeLaserBeam]. */
        var laserMode: Int = NetworkSettingsRegistry.LASER_MODE_FANCY
        var hasNetwork: Boolean = false
        var isMicro: Boolean = false
    }

    override fun createRenderState(): RenderState = RenderState()

    override fun extractConnectable(
        blockEntity: UserBlockEntity,
        state: RenderState,
        partialTicks: Float,
        cameraPosition: Vec3,
        breakProgress: ModelFeatureRenderer.CrumblingOverlay?,
    ) {
        state.facing = blockEntity.blockState.getValue(UserBlock.FACING)
        state.networkColor = resolveNetworkColor(blockEntity)
        val id = blockEntity.networkId
        val settings = NetworkSettingsRegistry.get(id)
        state.laserMode = settings.laserMode
        state.hasNetwork = id != null
        state.isMicro = MicroNetworkClientRegistry.isMicro(id)

        // Arm + held item temporarily disabled for an animation-less model
        // test. Re-enable by uncommenting both this block and the matching
        // arm-submit block in [submitConnectable].
        // state.extension = computeExtension(blockEntity, partialTicks)
        // val displayStack = blockEntity.heldStack
        // state.hasItem = !displayStack.isEmpty
        // if (state.hasItem) {
        //     itemModelResolver.updateForTopItem(
        //         state.itemRS, displayStack, ItemDisplayContext.GROUND,
        //         blockEntity.level, null, 0,
        //     )
        // }
    }

    override fun submitConnectable(
        state: RenderState,
        poseStack: PoseStack,
        submitNodeCollector: SubmitNodeCollector,
        camera: CameraRenderState,
    ) {
        // Tiny network-coloured laser stub inside the body. Runs from block
        // centre toward the FRONT face for [INNER_LASER_HALF_LENGTH] block
        // units, so the visible span sits at pixels 3..8 along the
        // front-back axis - inside the model's throat region where the
        // cauldron-style opening lets the player see it. The back half is a
        // solid 12×12 body element and would hide the beam if we drew it
        // toward the actual network-connection face.
        if (state.hasNetwork) {
            PipeLaserBeam.submitStub(
                poseStack,
                submitNodeCollector,
                state.pos,
                camera.pos,
                dir = state.facing,
                color = state.networkColor,
                laserMode = state.laserMode,
                halfLength = INNER_LASER_HALF_LENGTH,
                isMicro = state.isMicro,
            )
        }

        // Network-coloured emissive overlay on the body. user_emissive.json
        // inherits user.json's elements + per-face UVs (only the texture
        // ref differs), so iterating its baked quads through the additive-
        // glow pipeline produces a glow that aligns precisely with the
        // body's atlas regions. Two pose tweaks ensure correctness:
        //   * Rotation matches the chunk renderer's blockstate variant so
        //     the overlay tracks the body across all six facings. See
        //     [applyBodyRotation] for the per-facing math.
        //   * 1.001x scale outward from block centre so the overlay's
        //     faces sit a hair outside the body's faces, winning the
        //     LEQUAL depth test cleanly. EmissiveCubeRenderer's submit
        //     does the same with -0.000625 INSET on its hand-rolled cube.
        // Skipped when the device isn't on a network -- no sensible colour.
        val emissive = UserEmissiveModel.get()
        if (emissive != null && state.networkColor != NodeConnectionRenderer.DEFAULT_NETWORK_COLOR) {
            poseStack.pushPose()
            poseStack.translate(0.5, 0.5, 0.5)
            applyBodyRotation(poseStack, state.facing)
            poseStack.scale(EMISSIVE_OUTSET, EMISSIVE_OUTSET, EMISSIVE_OUTSET)
            poseStack.translate(-0.5, -0.5, -0.5)
            submitEmissiveOverlay(poseStack, submitNodeCollector, emissive, state.networkColor)
            poseStack.popPose()
        }

        // Arm + held item temporarily disabled for an animation-less model
        // test. The body model (chunk-rendered from blockstates/user.json)
        // and the network-coloured emissive overlay above stay live; only
        // the BER-driven extending arm and its held item are silenced.
        // Re-enable alongside the matching extract block.
        // val armModel = UserArmModel.get() ?: return
        //
        // poseStack.pushPose()
        // poseStack.translate(0.5, 0.5, 0.5)
        // rotateToFace(poseStack, state.facing)
        //
        // val travel = ARM_REST_OFFSET + ARM_MAX_TRAVEL * state.extension
        // poseStack.pushPose()
        // poseStack.translate(-0.5, -0.5, travel.toDouble())
        // submitArmModel(poseStack, submitNodeCollector, armModel)
        // poseStack.popPose()
        //
        // if (state.hasItem) {
        //     poseStack.pushPose()
        //     poseStack.translate(0.0, ITEM_GRIP_DROP.toDouble(), (travel + ITEM_GRIP_LOCAL).toDouble())
        //     poseStack.scale(1.1f, 1.1f, 1.1f)
        //     state.itemRS.submit(poseStack, submitNodeCollector, 0xF000F0, OverlayTexture.NO_OVERLAY, 0)
        //     poseStack.popPose()
        // }
        //
        // poseStack.popPose()
    }

    private fun submitArmModel(
        poseStack: PoseStack,
        collector: SubmitNodeCollector,
        part: BlockStateModelPart,
    ) {
        // submitBlockModel sat the quads in a render layer that wasn't
        // depth-occluded against the world (the arm visibly drew through
        // opaque blocks), so iterate the BakedQuads ourselves and route
        // them through the standard cutout-block sheet via custom geometry.
        // This pipeline (entity_cutout_cull on DepthStencilState.DEFAULT)
        // uses the regular LEQUAL depth test so the arm is occluded by any
        // block that's between it and the camera.
        collector.submitCustomGeometry(poseStack, Sheets.cutoutBlockSheet()) { pose, vc ->
            val quadInstance = QuadInstance()
            for (dir in DIRECTIONS_AND_NULL) {
                for (quad in part.getQuads(dir)) {
                    vc.putBakedQuad(pose, quad, quadInstance)
                }
            }
        }
    }

    /** Network-coloured additive overlay over the User's body. The baked
     *  quads carry user.json's per-face UVs (since user_emissive.json
     *  inherits geometry via JSON parent) so the overlay's texture
     *  sampling lands on the same atlas regions as the body model. The
     *  vertex tint multiplies the texture sample, so transparent pixels
     *  in user_emissive.png contribute zero (additive blend) and only
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

    private fun computeExtension(be: UserBlockEntity, partialTicks: Float): Float =
        quadCurve(computeRawExtension(be, partialTicks))

    /** Linear 0..1 extension fraction. The renderer applies [quadEaseInOut]
     *  on top so the arm decelerates near the endpoints (apex + idle) and
     *  accelerates through the middle, but the BE's state-machine math
     *  ([UserBlockEntity.cancelPending]'s synthetic offset, the serverTick
     *  retract-end check) runs on the linear value -- mixing easing into
     *  the BE side would couple animation curves to gameplay timing. */
    private fun computeRawExtension(be: UserBlockEntity, partialTicks: Float): Float {
        val level = be.level ?: return 0f
        val now = level.gameTime + partialTicks
        val extendTicks = UserBlockEntity.EXTEND_TICKS.toFloat()

        // EXTENDING: ramp 0 -> 1 over EXTEND_TICKS leading up to apex.
        if (be.pendingFireTick != Long.MIN_VALUE) {
            val sinceStart = (now - be.animStartTick).coerceAtLeast(0f)
            return min(1f, sinceStart / extendTicks)
        }
        // RETRACTING: must be checked before HOLDING because the post-apex
        // state has both heldStack non-empty AND animEndTick set; without
        // this ordering the ramp-down would never start. After the retract
        // window the BE will reset to IDLE on the next serverTick.
        //
        // animEndTick may be a few ticks in the future (APEX_HOLD_TICKS
        // pause inserted by [UserBlockEntity.fireScheduledUse]) -- during
        // that window the arm sits at full extension before the retract
        // ramp begins, giving the apex hit visual weight.
        if (be.animEndTick != Long.MIN_VALUE) {
            val sinceEnd = now - be.animEndTick
            if (sinceEnd < 0f) return 1f
            if (sinceEnd <= extendTicks) return 1f - (sinceEnd / extendTicks)
            return 0f
        }
        // HOLDING: stationary at full extension while the device is
        // actively driving onUseTick on its held stack.
        if (!be.heldStack.isEmpty) return 1f
        return 0f
    }

    /** Quadratic curve `t -> t^2`. Applied to the linear-extension value:
     *  during EXTEND (linear 0 -> 1) the arm accelerates into apex; during
     *  RETRACT (linear 1 -> 0) the arm starts fast and decelerates into
     *  rest. Picks up speed where ease-in-out felt sluggish (deceleration
     *  near apex) while keeping a soft landing at idle. */
    private fun quadCurve(t: Float): Float {
        val c = t.coerceIn(0f, 1f)
        return c * c
    }

    private fun rotateToFace(poseStack: PoseStack, face: Direction) {
        when (face) {
            Direction.SOUTH -> Unit
            Direction.NORTH -> poseStack.mulPose(Quaternionf().rotateY(Math.PI.toFloat()))
            Direction.EAST -> poseStack.mulPose(Quaternionf().rotateY((Math.PI / 2).toFloat()))
            Direction.WEST -> poseStack.mulPose(Quaternionf().rotateY((-Math.PI / 2).toFloat()))
            Direction.UP -> poseStack.mulPose(Quaternionf().rotateX((-Math.PI / 2).toFloat()))
            Direction.DOWN -> poseStack.mulPose(Quaternionf().rotateX((Math.PI / 2).toFloat()))
        }
    }

    /** Apply the same rotation the chunk renderer applies to the User
     *  body model via `blockstates/user.json`'s variants. user.json puts
     *  the cauldron mouth on the model's `-Z` (north) face, which gives
     *  these blockstate values:
     *
     *    facing=N  -> y=0   (no rotation)
     *    facing=E  -> y=90  (+Y CCW takes -Z to +X)
     *    facing=S  -> y=180
     *    facing=W  -> y=270
     *    facing=UP -> x=270 (+X CW takes -Z to +Y)
     *    facing=DN -> x=90  (+X CCW takes -Z to -Y)
     *
     *  MC blockstate y/x rotation direction is opposite the math convention
     *  pose-stack rotations follow (positive blockstate angle = clockwise
     *  from above for y, clockwise from +X for x), so each value is fed
     *  negated to `Axis.{YP,XP}.rotationDegrees`. Vertical facings only
     *  apply x; horizontal facings only apply y, matching the variant
     *  table -- composing both for one variant would land the model in a
     *  different orientation than the chunk renderer puts it in. */
    private fun applyBodyRotation(poseStack: PoseStack, facing: Direction) {
        when (facing) {
            Direction.NORTH -> Unit
            Direction.SOUTH -> poseStack.mulPose(Axis.YP.rotationDegrees(-180f))
            Direction.EAST -> poseStack.mulPose(Axis.YP.rotationDegrees(-90f))
            Direction.WEST -> poseStack.mulPose(Axis.YP.rotationDegrees(-270f))
            Direction.UP -> poseStack.mulPose(Axis.XP.rotationDegrees(-270f))
            Direction.DOWN -> poseStack.mulPose(Axis.XP.rotationDegrees(-90f))
        }
    }

    companion object {
        /** Outset factor applied around the block centre to the emissive
         *  overlay's geometry to win z-fight tests against the underlying
         *  chunk-rendered body. 1.001 = 1 px offset on a full-block face,
         *  invisible to the eye but enough for the depth test. */
        private const val EMISSIVE_OUTSET = 1.001f

        /** Length of the interior network-laser stub in block units. 5 px
         *  (= 5/16) runs the beam from block centre (pixel 8) to pixel 3
         *  along the back-facing axis, sitting just shy of the back face
         *  where the actual pipe connection lands. */
        private const val INNER_LASER_HALF_LENGTH = 5f / 16f

        /** Local-Z offset of the arm's origin when fully retracted. The arm
         *  model is authored from Z=-1 to Z=9 px so a small negative offset
         *  here pulls the rod's tail back into the User block's interior
         *  while leaving the holder peeking out its mouth. */
        private const val ARM_REST_OFFSET = -0.25f

        /** Maximum outward travel of the arm at full extension, on top of
         *  [ARM_REST_OFFSET]. Combined reach ≈ ARM_REST_OFFSET + value, so
         *  the arm pokes out ~half a block past the User's front face. */
        private const val ARM_MAX_TRAVEL = 0.5f

        /** Local-Z position where the held item sits relative to the arm's
         *  origin, in block units. Should land roughly at the holder's grip
         *  (model's `holder` element spans Z=7..9 px). */
        // private const val ITEM_GRIP_LOCAL = 0.5f
        private const val ITEM_GRIP_LOCAL = 0.53125f

        /** Local-Y offset of the held item from the arm's pose origin, in
         *  block units. Negative values nudge the item down (toward world
         *  -Y for horizontal facings) so it sits inside the holder rather
         *  than floating above it. Tweak in 1/16 steps. */
        private const val ITEM_GRIP_DROP = -0.0625f * 1.5f

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
                null
            )
    }
}
