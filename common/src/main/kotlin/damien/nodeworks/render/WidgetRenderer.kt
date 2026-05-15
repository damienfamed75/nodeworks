package damien.nodeworks.render

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import damien.nodeworks.block.WidgetBlock
import damien.nodeworks.block.entity.WidgetBlockEntity
import damien.nodeworks.block.entity.WidgetType
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.client.renderer.feature.ModelFeatureRenderer
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.phys.Vec3
import org.joml.Vector3f

/**
 * BER for the Widget block. Draws a network-coloured emissive "moving part" on
 * the block's front face, shaped by the widget's configured type:
 *
 *  * BUTTON, a square cap that pushes inward briefly after each press.
 *  * SWITCH, a handle that slides left (off) / right (on).
 *  * RADIO, a row of pips with the selected index lit brightest.
 *  * SLIDER, a track bar with a handle positioned at the value's fraction.
 *
 * The in-world name label is drawn separately by [NodeConnectionRenderer]
 * (font rendering wants the level-render pose, not a per-BER pass).
 */
class WidgetRenderer(context: BlockEntityRendererProvider.Context) :
    ConnectableBER<WidgetBlockEntity, WidgetRenderer.RenderState>(context) {

    class RenderState : ConnectableRenderState() {
        var facing: Direction = Direction.NORTH
        var type: WidgetType = WidgetType.BUTTON
        var color: Int = NodeConnectionRenderer.DEFAULT_NETWORK_COLOR
        /** Normalized value: SWITCH 0/1, RADIO index, SLIDER fraction 0..1. */
        var valueFraction: Float = 0f
        var radioIndex: Int = 0
        var radioCount: Int = 1
        /** 1.0 right after a press, decaying to 0 over [PRESS_TICKS]. */
        var pressProgress: Float = 0f
    }

    companion object {
        /** How long the button-press push-in animation lasts, in ticks. */
        private const val PRESS_TICKS = 5f
        /** Front face plane in centred block-local space. [applyFaceRotation]
         *  orients local +Z along the block's facing, so the face is +Z. */
        private const val FACE_Z = 0.5f
        /** Vertical offset of the control on the face. Centred so the name
         *  label (above) and the value readout (below) sit symmetrically
         *  around it, a fixed layout a custom model can be built to. */
        private const val CTRL_Y = 0f

        /** Last seen interaction generation per widget, to detect a fresh press. */
        private val lastGen = HashMap<BlockPos, Int>()
        /** Game-time (+ partial) of the last detected press, per widget. */
        private val pressAt = HashMap<BlockPos, Float>()
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
        state.color = resolveNetworkColor(blockEntity)

        val pos = blockEntity.blockPos
        val now = (blockEntity.level?.gameTime ?: 0L).toFloat() + partialTicks
        val gen = blockEntity.interactionGeneration
        if (lastGen.put(pos, gen) != gen) pressAt[pos] = now
        val age = now - (pressAt[pos] ?: (now - PRESS_TICKS))
        state.pressProgress = (1f - age / PRESS_TICKS).coerceIn(0f, 1f)
        // A latched (hold-configured) button stays fully pressed in until the
        // server releases value back to 0; override the time decay here.
        if (blockEntity.widgetType == WidgetType.BUTTON && blockEntity.value >= 0.5f) {
            state.pressProgress = 1f
        }

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
        poseStack.pushPose()
        poseStack.translate(0.5, 0.5, 0.5)
        applyFaceRotation(poseStack, state.facing)

        val r = (state.color shr 16) and 0xFF
        val g = (state.color shr 8) and 0xFF
        val b = state.color and 0xFF

        // Z planes, all kept strictly in front of the model surface (FACE_Z)
        // so nothing co-planar z-fights the block face.
        val trackZ = FACE_Z + 0.03f
        val partZ = FACE_Z + 0.06f
        when (state.type) {
            WidgetType.BUTTON -> {
                // A square cap pushed inward by the press animation. Slightly
                // smaller than before so the name label fits above it.
                val pushIn = state.pressProgress * 0.05f
                box(submitNodeCollector, poseStack, 0f, CTRL_Y, partZ - pushIn, 0.13f, 0.13f, 0.05f, r, g, b, 255)
            }
            WidgetType.SWITCH -> {
                // Recessed track + a handle that slides between the two ends.
                box(submitNodeCollector, poseStack, 0f, CTRL_Y, trackZ, 0.28f, 0.1f, 0.018f, r, g, b, 90)
                val hx = -0.16f + state.valueFraction * 0.32f
                box(submitNodeCollector, poseStack, hx, CTRL_Y, partZ, 0.1f, 0.14f, 0.05f, r, g, b, 255)
            }
            WidgetType.RADIO -> {
                // A row of pips, the selected one lit bright, the rest dim.
                val count = state.radioCount.coerceAtMost(6)
                val spacing = 0.6f / count
                val start = -0.3f + spacing / 2f
                for (i in 0 until count) {
                    val lit = i == state.radioIndex
                    val a = if (lit) 255 else 70
                    val s = if (lit) 0.07f else 0.05f
                    box(submitNodeCollector, poseStack, start + i * spacing, CTRL_Y, partZ, s, s, 0.035f, r, g, b, a)
                }
            }
            WidgetType.SLIDER -> {
                // Horizontal track + a handle at the value fraction.
                box(submitNodeCollector, poseStack, 0f, CTRL_Y, trackZ, 0.36f, 0.04f, 0.018f, r, g, b, 90)
                val hx = -0.32f + state.valueFraction * 0.64f
                box(submitNodeCollector, poseStack, hx, CTRL_Y, partZ, 0.06f, 0.16f, 0.05f, r, g, b, 255)
            }
        }

        poseStack.popPose()
    }

    /** Submit one emissive cuboid centred at [cx],[cy],[cz] with the given
     *  half-extents, in the pose's centred block-local space. */
    private fun box(
        submitter: SubmitNodeCollector,
        pose: PoseStack,
        cx: Float, cy: Float, cz: Float,
        hx: Float, hy: Float, hz: Float,
        r: Int, g: Int, b: Int, a: Int,
    ) {
        EmissiveCubeRenderer.submitBox(
            submitter, pose,
            Vector3f(cx - hx, cy - hy, cz - hz),
            Vector3f(2f * hx, 0f, 0f),
            Vector3f(0f, 2f * hy, 0f),
            Vector3f(0f, 0f, 2f * hz),
            r, g, b, a,
        )
    }

    /** Orient the pose so local +Z points along [facing] (outward from the
     *  block's front face) and local +Y reads as "up" on that face. Shared
     *  convention with [NodeConnectionRenderer]'s widget-name path so the
     *  label and the control agree on which way is up for every facing. */
    private fun applyFaceRotation(poseStack: PoseStack, facing: Direction) {
        when (facing) {
            Direction.SOUTH -> Unit
            Direction.NORTH -> poseStack.mulPose(Axis.YP.rotationDegrees(180f))
            Direction.EAST -> poseStack.mulPose(Axis.YP.rotationDegrees(90f))
            Direction.WEST -> poseStack.mulPose(Axis.YP.rotationDegrees(-90f))
            Direction.UP -> poseStack.mulPose(Axis.XP.rotationDegrees(-90f))
            Direction.DOWN -> poseStack.mulPose(Axis.XP.rotationDegrees(90f))
        }
    }
}
