package damien.nodeworks.render

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import damien.nodeworks.block.NodeBlock
import damien.nodeworks.card.NodeCard
import damien.nodeworks.platform.PlatformServices
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import kotlin.math.sqrt

/**
 * Live preview of which face of a Node a held Card will land on.
 *
 * Wireframe box in the card's tint colour, drawn slightly larger than the node
 * body so the player can see at a glance which side is targeted, including the
 * shift+right-click "opposite face" case where the preview slides to the other
 * side of the node as the player crouches. No-op when the player isn't holding
 * a card or isn't aiming at a node.
 */
object CardPlacementPreviewRenderer {

    /** Per-card-type RGB. Mirrors [NodeRenderer]'s palette so the puff at
     *  placement time, the indicator on the node body, and this preview all
     *  agree on what colour means what. */
    private val CARD_COLORS: Map<String, Int> = mapOf(
        "io" to 0x83E086,
        "storage" to 0xAA83E0,
        "redstone" to 0xF53B68,
        "observer" to 0xFFEB3B,
    )
    private const val DEFAULT_COLOR = 0xFFFFFF

    /** Box span around the node body. The node body lives at [5..11] (6/16 wide,
     *  centred). The preview cube extends 2px past that on every axis (so
     *  [3..13], 10/16 wide), which makes the highlighted face read as a "bigger
     *  shell" hovering on the node and stays clearly visible from any angle. */
    private const val OUTSET = 2f / 16f
    private const val NODE_MIN = 5f / 16f
    private const val NODE_MAX = 11f / 16f
    private const val BOX_MIN = NODE_MIN - OUTSET   // 3/16
    private const val BOX_MAX = NODE_MAX + OUTSET   // 13/16
    private const val BOX_DEPTH = 2f / 16f          // 2px slab thickness on the highlighted face

    fun init() {
        PlatformServices.clientEvents.onWorldRender { poseStack, consumers, cameraPos ->
            if (poseStack == null || consumers == null) return@onWorldRender
            renderPreview(poseStack, consumers, cameraPos)
        }
    }

    private fun renderPreview(
        poseStack: PoseStack,
        consumers: MultiBufferSource,
        cameraPos: net.minecraft.world.phys.Vec3,
    ) {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return
        val level = mc.level ?: return

        // Card in the main hand is the trigger. Off-hand cards intentionally don't
        // preview; the right-click placement only fires from the main hand anyway.
        val held = player.mainHandItem
        val card = held.item as? NodeCard ?: return

        // Crosshair target. Anything other than a Node block bails out.
        val hit = mc.hitResult as? BlockHitResult ?: return
        if (hit.type != HitResult.Type.BLOCK) return
        val pos = hit.blockPos
        val state = level.getBlockState(pos)
        if (state.block !is NodeBlock) return

        // Shift flips to the opposite side, matching the placement rule in
        // [NodeBlock.tryQuickPlaceCard] so the preview never lies about where
        // the card will go.
        val face = if (player.isCrouching) hit.direction.opposite else hit.direction

        val color = CARD_COLORS[card.cardType] ?: DEFAULT_COLOR
        val buffer = consumers.getBuffer(RenderTypes.LINES)

        poseStack.pushPose()
        poseStack.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z)
        drawFaceSlab(poseStack, buffer, pos, face, color)
        poseStack.popPose()
    }

    /** Draw a thin slab outline on [face]. Slab spans [BOX_MIN..BOX_MAX] on the
     *  two axes parallel to [face], and [BOX_DEPTH] thick along the face normal,
     *  starting flush against the outer face of the node body and extruding
     *  outward. Twelve edges, no fill. */
    private fun drawFaceSlab(
        poseStack: PoseStack,
        buffer: VertexConsumer,
        pos: BlockPos,
        face: Direction,
        color: Int,
    ) {
        val ox = pos.x.toFloat()
        val oy = pos.y.toFloat()
        val oz = pos.z.toFloat()
        // Inner / outer plane distance from block centre along the face normal.
        // Inner = node face, outer = node face + BOX_DEPTH.
        val inner = if (face.stepX + face.stepY + face.stepZ > 0) NODE_MAX else NODE_MIN
        val outer = if (face.stepX + face.stepY + face.stepZ > 0) NODE_MAX + BOX_DEPTH else NODE_MIN - BOX_DEPTH

        val (x0, x1, y0, y1, z0, z1) = when (face) {
            Direction.UP -> SlabBounds(BOX_MIN, BOX_MAX, inner, outer, BOX_MIN, BOX_MAX)
            Direction.DOWN -> SlabBounds(BOX_MIN, BOX_MAX, outer, inner, BOX_MIN, BOX_MAX)
            Direction.NORTH -> SlabBounds(BOX_MIN, BOX_MAX, BOX_MIN, BOX_MAX, outer, inner)
            Direction.SOUTH -> SlabBounds(BOX_MIN, BOX_MAX, BOX_MIN, BOX_MAX, inner, outer)
            Direction.EAST -> SlabBounds(inner, outer, BOX_MIN, BOX_MAX, BOX_MIN, BOX_MAX)
            Direction.WEST -> SlabBounds(outer, inner, BOX_MIN, BOX_MAX, BOX_MIN, BOX_MAX)
        }
        val ax0 = ox + x0;
        val ax1 = ox + x1
        val ay0 = oy + y0;
        val ay1 = oy + y1
        val az0 = oz + z0;
        val az1 = oz + z1

        val pose = poseStack.last()
        // 12 edges of the slab box. Width comes from the per-vertex LineWidth
        // element in [line] below.
        line(buffer, pose, color, ax0, ay0, az0, ax1, ay0, az0)
        line(buffer, pose, color, ax1, ay0, az0, ax1, ay0, az1)
        line(buffer, pose, color, ax1, ay0, az1, ax0, ay0, az1)
        line(buffer, pose, color, ax0, ay0, az1, ax0, ay0, az0)
        line(buffer, pose, color, ax0, ay1, az0, ax1, ay1, az0)
        line(buffer, pose, color, ax1, ay1, az0, ax1, ay1, az1)
        line(buffer, pose, color, ax1, ay1, az1, ax0, ay1, az1)
        line(buffer, pose, color, ax0, ay1, az1, ax0, ay1, az0)
        line(buffer, pose, color, ax0, ay0, az0, ax0, ay1, az0)
        line(buffer, pose, color, ax1, ay0, az0, ax1, ay1, az0)
        line(buffer, pose, color, ax1, ay0, az1, ax1, ay1, az1)
        line(buffer, pose, color, ax0, ay0, az1, ax0, ay1, az1)
    }

    /** Six floats packed into a struct so the per-face setup above can use
     *  destructuring instead of duplicating six assignments per face. */
    private data class SlabBounds(
        val x0: Float, val x1: Float,
        val y0: Float, val y1: Float,
        val z0: Float, val z1: Float,
    )

    /** Single line segment in [color]. The LINES render type's vertex format
     *  in 26.1 requires a per-vertex LineWidth element or BufferBuilder
     *  crashes; the value (in pixels) becomes the rendered line width. AE2's
     *  placement-preview overlay uses 3.0 via a baked-pipeline `LineStateShard`
     *  for the same reason. We go a touch wider so the lighter card colours
     *  (observer yellow, io green) stay legible against bright skies. */
    private const val LINE_WIDTH = 4.0f

    private fun line(
        buffer: VertexConsumer,
        pose: PoseStack.Pose,
        color: Int,
        x0: Float, y0: Float, z0: Float,
        x1: Float, y1: Float, z1: Float,
    ) {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        val dx = x1 - x0
        val dy = y1 - y0
        val dz = z1 - z0
        val len = sqrt((dx * dx + dy * dy + dz * dz).toDouble()).toFloat()
        val nx = if (len > 0) dx / len else 0f
        val ny = if (len > 0) dy / len else 0f
        val nz = if (len > 0) dz / len else 1f
        buffer.addVertex(pose, x0, y0, z0)
            .setColor(r, g, b, 255)
            .setNormal(pose, nx, ny, nz)
            .setLineWidth(LINE_WIDTH)
        buffer.addVertex(pose, x1, y1, z1)
            .setColor(r, g, b, 255)
            .setNormal(pose, nx, ny, nz)
            .setLineWidth(LINE_WIDTH)
    }
}
