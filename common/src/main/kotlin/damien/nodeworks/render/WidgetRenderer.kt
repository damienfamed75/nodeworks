package damien.nodeworks.render

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.QuadInstance
import com.mojang.math.Axis
import damien.nodeworks.block.WidgetBlock
import damien.nodeworks.block.entity.WidgetBlockEntity
import damien.nodeworks.block.entity.WidgetType
import damien.nodeworks.client.WidgetPartModels
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.client.renderer.feature.ModelFeatureRenderer
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.util.ARGB
import net.minecraft.world.phys.Vec3

/**
 * BER for the Widget block. Overlays the dynamic "moving part" of each widget
 * type on top of the static chassis baked into the `widget_<type>` blockstate
 * model:
 *
 *  * BUTTON, the cap (loaded from `widget_button_cap`) pushes inward briefly
 *    on press and stays in while a hold is latched.
 *  * SWITCH, the handle (`widget_switch_handle`) slides between the two ends.
 *  * RADIO, the pip (`widget_radio_pip`) is drawn once per option, with the
 *    selected one at full alpha and the rest dimmed.
 *  * SLIDER, the handle (`widget_slider_handle`) rides along the track.
 *
 * Each part model is authored centred on the face (model space `(8, 8)` is
 * the face centre, `z=0` is the face plane). The renderer applies the
 * blockstate-equivalent face rotation, then a per-state offset on top, so
 * the rest pose in Blockbench matches the value-zero appearance in-world.
 */
class WidgetRenderer(context: BlockEntityRendererProvider.Context) :
    ConnectableBER<WidgetBlockEntity, WidgetRenderer.RenderState>(context) {

    class RenderState : ConnectableRenderState() {
        var facing: Direction = Direction.NORTH
        var type: WidgetType = WidgetType.BUTTON
        /** Normalized 0..1, drives the per-type horizontal offset. SWITCH 0/1,
         *  SLIDER the value fraction. Unused by BUTTON / RADIO. */
        var valueFraction: Float = 0f
        var radioIndex: Int = 0
        var radioCount: Int = 1
        /** Binary 0 or 1, no easing: 1 while the button is "in", 0 while "out". */
        var pressProgress: Float = 0f
    }

    companion object {
        /** Button push-in depth (block-relative). Held short of a full pixel so
         *  the cap's front face doesn't reach the recessed inner panel and
         *  z-fight with it. Half a pixel reads as a clear press. */
        private const val BUTTON_PUSH_DEPTH = 0.5f / 16f
        /** Horizontal travel range for the switch handle (block-relative). Kept
         *  short so the handle stays inside the modeled toggle frame's endcaps. */
        private const val SWITCH_HANDLE_TRAVEL = 0.25f
        /** Six +null face buckets to walk a BlockStateModelPart's quads. */
        private val DIRECTIONS_AND_NULL: Array<Direction?> =
            arrayOf(Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST, null)
    }

    override fun createRenderState(): RenderState = RenderState()

    override fun extractConnectable(
        blockEntity: WidgetBlockEntity,
        state: RenderState,
        partialTicks: Float,
        cameraPosition: Vec3,
        breakProgress: ModelFeatureRenderer.CrumblingOverlay?,
    ) {
        state.facing = blockEntity.blockState.getValue(WidgetBlock.FACING)
        state.type = blockEntity.widgetType

        // Binary press state mirrors the server-driven value: in for the
        // whole hold, out immediately after. Every button is hold-configured
        // (floored at MIN_HOLD_TICKS), so there's no momentary fallback.
        val pressed = blockEntity.widgetType == WidgetType.BUTTON && blockEntity.value >= 0.5f
        state.pressProgress = if (pressed) 1f else 0f

        when (blockEntity.widgetType) {
            WidgetType.BUTTON -> state.valueFraction = 0f
            WidgetType.SWITCH -> state.valueFraction = if (blockEntity.value >= 0.5f) 1f else 0f
            WidgetType.RADIO -> {
                state.radioCount = blockEntity.radioOptions.size.coerceAtLeast(1)
                state.radioIndex = blockEntity.value.toInt().coerceIn(0, state.radioCount - 1)
            }
            WidgetType.SLIDER -> {
                val span = blockEntity.sliderMax - blockEntity.sliderMin
                state.valueFraction =
                    if (span <= 0f) 0f
                    else ((blockEntity.value - blockEntity.sliderMin) / span).coerceIn(0f, 1f)
            }
        }
    }

    override fun submitConnectable(
        state: RenderState,
        poseStack: PoseStack,
        submitNodeCollector: SubmitNodeCollector,
        camera: CameraRenderState,
    ) {
        // No network-colour tint, the authored texture shows through as-is.
        // Only the alpha varies, used to dim non-selected radio pips.
        val r = 255
        val g = 255
        val b = 255

        // After the blockstate rotation, local +X points toward the player's
        // LEFT on every horizontal facing and toward the player's RIGHT for
        // UP / DOWN. Flip the offset on horizontal facings so a higher value
        // always slides the part toward the player's right.
        val xSign = if (state.facing.axis == Direction.Axis.Y) 1f else -1f

        when (state.type) {
            WidgetType.BUTTON -> {
                val cap = WidgetPartModels.buttonCap() ?: return
                // Press pushes the cap inward along the face's local +Z axis.
                submitPart(poseStack, submitNodeCollector, cap, state.facing,
                    xOffset = 0f, zOffset = state.pressProgress * BUTTON_PUSH_DEPTH,
                    r = r, g = g, b = b, a = 255)
            }
            WidgetType.SWITCH -> {
                val handle = WidgetPartModels.switchHandle() ?: return
                val xOffset = (state.valueFraction - 0.5f) * SWITCH_HANDLE_TRAVEL * xSign
                submitPart(poseStack, submitNodeCollector, handle, state.facing,
                    xOffset = xOffset, zOffset = 0f,
                    r = r, g = g, b = b, a = 255)
            }
            WidgetType.RADIO -> {
                val pip = WidgetPartModels.radioPip() ?: return
                val count = state.radioCount.coerceAtMost(6)
                // Even spacing on the face, matching the previous box layout
                // so authored models slot into the same positions.
                val spacing = 0.6f / count
                val start = -0.3f + spacing / 2f
                for (i in 0 until count) {
                    val lit = i == state.radioIndex
                    val a = if (lit) 255 else 70
                    submitPart(poseStack, submitNodeCollector, pip, state.facing,
                        xOffset = (start + i * spacing) * xSign, zOffset = 0f,
                        r = r, g = g, b = b, a = a)
                }
            }
            WidgetType.SLIDER -> {
                val handle = WidgetPartModels.sliderHandle() ?: return
                // Travel matches the modeled track width so the handle's edges
                // line up with the track's edges at min / max value.
                val xOffset = (state.valueFraction - 0.5f) * WidgetBlock.SLIDER_HANDLE_TRAVEL * xSign
                submitPart(poseStack, submitNodeCollector, handle, state.facing,
                    xOffset = xOffset, zOffset = 0f,
                    r = r, g = g, b = b, a = 255)
            }
        }
    }

    /** Submit [part]'s baked quads, oriented to match the chunk-rendered
     *  chassis (so model `z=0` lands on the widget's facing face) and offset
     *  along the face's local axes by ([xOffset], [zOffset]) in block-relative
     *  units. The colour multiplies the texture, so a "neutral" authored mesh
     *  picks up the network tint while the alpha controls intensity for the
     *  additive-emissive blend. */
    private fun submitPart(
        pose: PoseStack,
        submitter: SubmitNodeCollector,
        part: BlockStateModelPart,
        facing: Direction,
        xOffset: Float,
        zOffset: Float,
        r: Int, g: Int, b: Int, a: Int,
    ) {
        pose.pushPose()
        // Rotate around the block centre to match the blockstate JSON, so the
        // baked quads land on the same world geometry as the chunk-rendered
        // chassis.
        pose.translate(0.5, 0.5, 0.5)
        applyBlockstateRotation(pose, facing)
        pose.translate(-0.5, -0.5, -0.5)
        // Local +X = right on the face, local +Z = into the block (away from
        // the player looking at the widget). The author centres the part at
        // face centre, the renderer slides it from there.
        pose.translate(xOffset, 0f, zOffset)

        val argb = ARGB.color(a, r, g, b)
        val quadInstance = QuadInstance().apply {
            for (vertex in 0..3) setColor(vertex, argb)
        }
        submitter.submitCustomGeometry(pose, EmissiveCubeRenderer.OPAQUE_BLOCK_ATLAS_RENDER_TYPE) { p, vc ->
            for (dir in DIRECTIONS_AND_NULL) {
                for (quad in part.getQuads(dir)) {
                    vc.putBakedQuad(p, quad, quadInstance)
                }
            }
        }
        pose.popPose()
    }

    /** Same rotation the chunk renderer applies via `blockstates/widget.json`,
     *  so a baked overlay lands on the same world geometry as the chassis.
     *  MC's blockstate Y/X rotation goes opposite the standard right-hand
     *  convention, so blockstate `y=90` corresponds to `Axis.YP.rotationDegrees(-90)`
     *  here. */
    private fun applyBlockstateRotation(pose: PoseStack, facing: Direction) {
        when (facing) {
            Direction.NORTH -> Unit
            Direction.SOUTH -> pose.mulPose(Axis.YP.rotationDegrees(180f))
            Direction.EAST -> pose.mulPose(Axis.YP.rotationDegrees(-90f))
            Direction.WEST -> pose.mulPose(Axis.YP.rotationDegrees(90f))
            Direction.UP -> pose.mulPose(Axis.XP.rotationDegrees(90f))
            Direction.DOWN -> pose.mulPose(Axis.XP.rotationDegrees(-90f))
        }
    }
}
