package damien.nodeworks.render

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import damien.nodeworks.block.UserBlock
import damien.nodeworks.block.entity.UserBlockEntity
import damien.nodeworks.platform.PlatformServices
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.core.BlockPos
import kotlin.math.sqrt

/**
 * Wireframe outline of the AABB the User can target with `use()`.
 *
 * Drawn as a single box spanning every block in the User's straight-ahead
 * reach ([UserBlockEntity.REACH] blocks along facing), tinted the User's
 * channel colour so multiple Users on the same channel network read as a
 * group while different channels stay visually distinct. Drawn as bright
 * LINES via [RenderTypes.LINES] so the preview reads clearly through walls.
 *
 * The User device gates rendering on its own [UserBlockEntity.previewArea]
 * flag, set from the Settings GUI's Preview toggle.
 */
object UserPreviewRenderer {

    private const val LINE_WIDTH = 4.0f

    /** 1 px outset from the AABB so the wireframe doesn't z-fight with
     *  solid neighbours (the line stays visible when reach borders a
     *  block face). */
    private const val OUTSET = 1f / 16f

    fun init() {
        PlatformServices.clientEvents.onWorldRender { poseStack, consumers, cameraPos ->
            if (poseStack == null || consumers == null) return@onWorldRender
            renderAll(poseStack, consumers, cameraPos)
        }
    }

    private fun renderAll(
        poseStack: PoseStack,
        consumers: MultiBufferSource,
        cameraPos: net.minecraft.world.phys.Vec3,
    ) {
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return

        val buffer = consumers.getBuffer(RenderTypes.LINES)
        poseStack.pushPose()
        poseStack.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z)

        TrackedUsers.forEach { pos, entity ->
            if (!entity.previewArea) return@forEach
            val state = level.getBlockState(pos)
            if (state.block !is UserBlock) return@forEach
            val facing = state.getValue(UserBlock.FACING)
            // BE also has a diagonal-below fallback for horizontal facings
            // (see [UserBlockEntity.resolveTarget]); excluded here since the
            // straight reach is the primary signal.
            val near = pos.relative(facing, 1)
            val far = pos.relative(facing, UserBlockEntity.REACH)
            val color = entity.channel.textureDiffuseColor or 0xFF000000.toInt()
            drawAabb(poseStack, buffer, near, far, color)
        }

        poseStack.popPose()
    }

    private fun drawAabb(
        poseStack: PoseStack,
        buffer: VertexConsumer,
        a: BlockPos,
        b: BlockPos,
        color: Int,
    ) {
        val x0 = minOf(a.x, b.x).toFloat() - OUTSET
        val y0 = minOf(a.y, b.y).toFloat() - OUTSET
        val z0 = minOf(a.z, b.z).toFloat() - OUTSET
        val x1 = maxOf(a.x, b.x) + 1f + OUTSET
        val y1 = maxOf(a.y, b.y) + 1f + OUTSET
        val z1 = maxOf(a.z, b.z) + 1f + OUTSET

        val pose = poseStack.last()
        // 12 cube edges.
        line(buffer, pose, color, x0, y0, z0, x1, y0, z0)
        line(buffer, pose, color, x1, y0, z0, x1, y0, z1)
        line(buffer, pose, color, x1, y0, z1, x0, y0, z1)
        line(buffer, pose, color, x0, y0, z1, x0, y0, z0)
        line(buffer, pose, color, x0, y1, z0, x1, y1, z0)
        line(buffer, pose, color, x1, y1, z0, x1, y1, z1)
        line(buffer, pose, color, x1, y1, z1, x0, y1, z1)
        line(buffer, pose, color, x0, y1, z1, x0, y1, z0)
        line(buffer, pose, color, x0, y0, z0, x0, y1, z0)
        line(buffer, pose, color, x1, y0, z0, x1, y1, z0)
        line(buffer, pose, color, x1, y0, z1, x1, y1, z1)
        line(buffer, pose, color, x0, y0, z1, x0, y1, z1)
    }

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

    /** Live UserBlockEntities the renderer iterates each frame, populated by
     *  the BE's setLevel / setRemoved hooks. Single-thread access (BE
     *  add/remove and the world-render hook all run on the client main
     *  thread on Minecraft client) so no locking. */
    object TrackedUsers {
        private val byPos: MutableMap<BlockPos, UserBlockEntity> = HashMap()

        fun add(entity: UserBlockEntity) {
            byPos[entity.blockPos] = entity
        }

        fun remove(pos: BlockPos) {
            byPos.remove(pos)
        }

        fun forEach(action: (BlockPos, UserBlockEntity) -> Unit) {
            for ((pos, entity) in byPos) action(pos, entity)
        }
    }
}
