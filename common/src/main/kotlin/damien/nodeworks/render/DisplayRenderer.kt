package damien.nodeworks.render

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import damien.nodeworks.block.DisplayBlock
import damien.nodeworks.block.entity.DisplayBlockEntity
import damien.nodeworks.block.entity.DisplayElement
import damien.nodeworks.block.entity.DisplayElementType
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.client.renderer.entity.state.EntityRenderState
import net.minecraft.client.renderer.feature.ModelFeatureRenderer
import net.minecraft.client.renderer.item.ItemStackRenderState
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.item.ItemDisplayContext
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import org.joml.Matrix3f
import org.joml.Quaternionf
import org.joml.Vector3f

/**
 * Block Entity renderer for the Display block, a pure draw surface. Projects
 * the committed [DisplayElement] list as flat quads, floating cuboids,
 * wireframes, gradient fills, lines, and item / entity models in the
 * holographic volume. There are no interactive elements.
 *
 * Geometry is mapped through [DisplayVolume.basis] (normalized `-1..1` coords,
 * cluster-size aware) so it stays in lockstep with the label hook
 * ([NodeConnectionRenderer.renderDisplayText]).
 *
 * For a multiblock cluster only the anchor draws, and it renders the whole
 * combined surface.
 */
class DisplayRenderer(context: BlockEntityRendererProvider.Context) :
    ConnectableBER<DisplayBlockEntity, DisplayRenderer.DisplayState>(context) {

    private val itemModelResolver = context.itemModelResolver()
    private val entityRenderer = context.entityRenderer()

    companion object {
        /** Nudge every element this far (normalized depth) off the block face. */
        private const val FACE_OFFSET = 0.01f

        /** One "pixel" (the flat unit for wireframe / line thickness) in blocks. */
        private const val PX = 1f / 128f

        private const val ITEM_BASE_SCALE = 0.35f
        private const val ENTITY_BASE_SCALE = 0.5f
        private const val FULL_BRIGHT = 0xF000F0

        private val dummyEntities = HashMap<String, Entity?>()

        private fun dummyEntity(id: String, level: Level): Entity? {
            val cached = dummyEntities[id]
            if (cached != null && cached.level() === level) return cached
            if (cached == null && dummyEntities.containsKey(id)) return null
            val ident = Identifier.tryParse(id)
            val type = if (ident != null) BuiltInRegistries.ENTITY_TYPE.getValue(ident) else null
            val ent = try {
                type?.create(level, EntitySpawnReason.SPAWNER)
            } catch (_: Throwable) {
                null
            }
            dummyEntities[id] = ent
            return ent
        }
    }

    class DisplayState : ConnectableRenderState() {
        var facing: Direction = Direction.NORTH
        var cols: Int = 1
        var rows: Int = 1
        var elements: List<DisplayElement> = emptyList()

        val itemStates: MutableMap<Int, ItemStackRenderState> = HashMap()
        val entityStates: MutableMap<Int, EntityRenderState> = HashMap()
        val entityFit: MutableMap<Int, Float> = HashMap()
    }

    override fun createRenderState(): DisplayState = DisplayState()

    override fun extractConnectable(
        blockEntity: DisplayBlockEntity,
        state: DisplayState,
        partialTicks: Float,
        cameraPosition: Vec3,
        breakProgress: ModelFeatureRenderer.CrumblingOverlay?,
    ) {
        state.facing = blockEntity.blockState.getValue(DisplayBlock.FACING)
        val cluster = blockEntity.cluster()
        state.cols = cluster.cols
        state.rows = cluster.rows
        state.elements = if (cluster.anchor == blockEntity.blockPos) blockEntity.elements else emptyList()

        state.itemStates.clear()
        state.entityStates.clear()
        state.entityFit.clear()
        val level = blockEntity.level ?: return
        for (e in state.elements) {
            when (e.type) {
                DisplayElementType.ITEM -> {
                    val ident = Identifier.tryParse(e.label) ?: continue
                    val item = BuiltInRegistries.ITEM.getValue(ident) ?: continue
                    val rs = ItemStackRenderState()
                    itemModelResolver.updateForTopItem(
                        rs, ItemStack(item, 1), ItemDisplayContext.GUI, level, null, 0,
                    )
                    state.itemStates[e.id] = rs
                }
                DisplayElementType.ENTITY -> {
                    val dummy = dummyEntity(e.label, level) ?: continue
                    val rs = try {
                        entityRenderer.extractEntity(dummy, partialTicks)
                    } catch (_: Throwable) {
                        continue
                    }
                    rs.lightCoords = FULL_BRIGHT
                    state.entityStates[e.id] = rs
                    state.entityFit[e.id] = 1f / maxOf(1f, dummy.bbWidth, dummy.bbHeight)
                }
                else -> Unit
            }
        }
    }

    /** The anchor renders the whole multiblock volume, which can extend past the
     *  anchor block's own section, so render off-screen (still 64-block bounded). */
    override fun shouldRenderOffScreen(): Boolean = true

    override fun submitConnectable(
        state: DisplayState,
        poseStack: PoseStack,
        submitNodeCollector: SubmitNodeCollector,
        camera: CameraRenderState,
    ) {
        if (state.elements.isEmpty()) return
        val basis = DisplayVolume.basis(state.facing, state.cols, state.rows)
        val facingVec = Vector3f(
            state.facing.stepX.toFloat(), state.facing.stepY.toFloat(), state.facing.stepZ.toFloat(),
        )

        for (e in state.elements) {
            when (e.type) {
                DisplayElementType.TEXT -> Unit // drawn by the world-space font hook
                DisplayElementType.LINE -> drawLine(submitNodeCollector, poseStack, basis, facingVec, e)
                DisplayElementType.ITEM -> drawItem(submitNodeCollector, poseStack, basis, state, e)
                DisplayElementType.ENTITY -> drawEntity(submitNodeCollector, poseStack, basis, state, camera, e)
                DisplayElementType.BOX -> drawBox(submitNodeCollector, poseStack, basis, state, e)
            }
        }
    }

    private fun drawBox(
        submitter: SubmitNodeCollector,
        poseStack: PoseStack,
        basis: DisplayVolume.Basis,
        state: DisplayState,
        e: DisplayElement,
    ) {
        val r = (e.color shr 16) and 0xFF
        val g = (e.color shr 8) and 0xFF
        val b = e.color and 0xFF
        val a = (e.color ushr 24) and 0xFF

        if (e.billboard) {
            submitBillboard(submitter, poseStack, basis, state, e, r, g, b, a)
            return
        }

        val ez = e.z.coerceIn(0f, 1f) + FACE_OFFSET
        val ed = e.d.coerceIn(0f, 1f)
        val q = boxQuad(e) ?: return

        val origin = basis.point(q[0], q[1], ez)
        val edgeX = Vector3f(basis.point(q[2], q[3], ez)).sub(origin)
        val edgeY = Vector3f(basis.point(q[4], q[5], ez)).sub(origin)

        if (ed <= 0f) {
            when {
                e.wireframe > 0 -> EmissiveCubeRenderer.submitRectOutline(
                    submitter, poseStack, origin, edgeX, edgeY, e.wireframe * PX, r, g, b, a,
                )
                e.gradient -> EmissiveCubeRenderer.submitRectGradient(
                    submitter, poseStack, origin, edgeX, edgeY, e.color, e.color2, alongX = false,
                )
                else -> EmissiveCubeRenderer.submitRect(submitter, poseStack, origin, edgeX, edgeY, r, g, b, a)
            }
        } else {
            val edgeZ = Vector3f(basis.point(q[0], q[1], ez + ed)).sub(origin)
            if (e.wireframe > 0) {
                EmissiveCubeRenderer.submitBoxOutline(
                    submitter, poseStack, origin, edgeX, edgeY, edgeZ, e.wireframe * PX, r, g, b, a,
                )
            } else {
                EmissiveCubeRenderer.submitBox(submitter, poseStack, origin, edgeX, edgeY, edgeZ, r, g, b, a)
            }
        }
    }

    /**
     * Normalized corners of a box element as `[blX, blY, brX, brY, tlX, tlY]`.
     * Axis-aligned boxes are clipped to the `-1..1` surface; rotated boxes
     * overhang. Returns null when a clipped box ends up fully off-surface.
     */
    private fun boxQuad(e: DisplayElement): FloatArray? {
        if (e.rotation == 0f) {
            val x0 = e.x.coerceAtLeast(-1f)
            val y0 = e.y.coerceAtLeast(-1f)
            val x1 = (e.x + e.w).coerceAtMost(1f)
            val y1 = (e.y + e.h).coerceAtMost(1f)
            if (x1 <= x0 || y1 <= y0) return null
            return floatArrayOf(x0, y0, x1, y0, x0, y1)
        }
        val cx = e.x + e.w / 2f
        val cy = e.y + e.h / 2f
        val rad = Math.toRadians(e.rotation.toDouble())
        val cos = Math.cos(rad).toFloat()
        val sin = Math.sin(rad).toFloat()
        fun rx(px: Float, py: Float) = cx + (px - cx) * cos - (py - cy) * sin
        fun ry(px: Float, py: Float) = cy + (px - cx) * sin + (py - cy) * cos
        return floatArrayOf(
            rx(e.x, e.y), ry(e.x, e.y),
            rx(e.x + e.w, e.y), ry(e.x + e.w, e.y),
            rx(e.x, e.y + e.h), ry(e.x, e.y + e.h),
        )
    }

    private fun drawLine(
        submitter: SubmitNodeCollector,
        poseStack: PoseStack,
        basis: DisplayVolume.Basis,
        facingVec: Vector3f,
        e: DisplayElement,
    ) {
        var x1 = e.x; var y1 = e.y; var x2 = e.x2; var y2 = e.y2
        if (e.rotation != 0f) {
            val cx = (x1 + x2) / 2f; val cy = (y1 + y2) / 2f
            val rad = Math.toRadians(e.rotation.toDouble())
            val cos = Math.cos(rad).toFloat(); val sin = Math.sin(rad).toFloat()
            val nx1 = cx + (x1 - cx) * cos - (y1 - cy) * sin
            val ny1 = cy + (x1 - cx) * sin + (y1 - cy) * cos
            val nx2 = cx + (x2 - cx) * cos - (y2 - cy) * sin
            val ny2 = cy + (x2 - cx) * sin + (y2 - cy) * cos
            x1 = nx1; y1 = ny1; x2 = nx2; y2 = ny2
        }
        val ez = e.z.coerceIn(0f, 1f) + FACE_OFFSET
        val p1 = basis.point(x1, y1, ez)
        val p2 = basis.point(x2, y2, ez)
        val dir = Vector3f(p2).sub(p1)
        if (dir.lengthSquared() < 1e-9f) return
        val perp = Vector3f(dir).cross(facingVec)
        if (perp.lengthSquared() < 1e-9f) return
        perp.normalize()
        val thickness = (if (e.wireframe > 0) e.wireframe else 1) * PX
        val origin = Vector3f(p1).fma(-thickness / 2f, perp)
        val edgeY = Vector3f(perp).mul(thickness)

        val r = (e.color shr 16) and 0xFF
        val g = (e.color shr 8) and 0xFF
        val b = e.color and 0xFF
        val a = (e.color ushr 24) and 0xFF
        if (e.gradient) {
            EmissiveCubeRenderer.submitRectGradient(
                submitter, poseStack, origin, dir, edgeY, e.color, e.color2, alongX = true,
            )
        } else {
            EmissiveCubeRenderer.submitRect(submitter, poseStack, origin, dir, edgeY, r, g, b, a)
        }
    }

    private fun drawItem(
        submitter: SubmitNodeCollector,
        poseStack: PoseStack,
        basis: DisplayVolume.Basis,
        state: DisplayState,
        e: DisplayElement,
    ) {
        val rs = state.itemStates[e.id] ?: return
        val ez = e.z.coerceIn(0f, 1f) + FACE_OFFSET
        val p = basis.point(e.x, e.y, ez)
        poseStack.pushPose()
        poseStack.translate(p.x.toDouble(), p.y.toDouble(), p.z.toDouble())
        poseStack.mulPose(orientationFor(basis, e.billboard))
        if (e.rotation != 0f) poseStack.mulPose(Axis.YP.rotationDegrees(e.rotation))
        val s = ITEM_BASE_SCALE * e.textSize.coerceIn(0.05f, 16f)
        poseStack.scale(s, s, s)
        rs.submit(poseStack, submitter, FULL_BRIGHT, OverlayTexture.NO_OVERLAY, 0)
        poseStack.popPose()
    }

    private fun drawEntity(
        submitter: SubmitNodeCollector,
        poseStack: PoseStack,
        basis: DisplayVolume.Basis,
        state: DisplayState,
        camera: CameraRenderState,
        e: DisplayElement,
    ) {
        val rs = state.entityStates[e.id] ?: return
        val fit = state.entityFit[e.id] ?: 1f
        val ez = e.z.coerceIn(0f, 1f) + FACE_OFFSET
        val p = basis.point(e.x, e.y, ez)
        poseStack.pushPose()
        poseStack.translate(p.x.toDouble(), p.y.toDouble(), p.z.toDouble())
        poseStack.mulPose(orientationFor(basis, e.billboard))
        if (e.rotation != 0f) poseStack.mulPose(Axis.YP.rotationDegrees(e.rotation))
        val s = ENTITY_BASE_SCALE * e.textSize.coerceIn(0.05f, 16f) * fit
        poseStack.scale(s, s, s)
        try {
            entityRenderer.submit(rs, camera, 0.0, 0.0, 0.0, poseStack, submitter)
        } catch (_: Throwable) {
            // A model that fails mid-submit shouldn't take down the whole frame.
        }
        poseStack.popPose()
    }

    /** Quaternion orienting a model's local +X/+Y/+Z onto the display surface's
     *  right / up / outward axes, or the camera's when [billboard]. */
    private fun orientationFor(basis: DisplayVolume.Basis, billboard: Boolean): Quaternionf {
        val right: Vector3f
        val up: Vector3f
        val out: Vector3f
        if (billboard) {
            val cam = Minecraft.getInstance().gameRenderer.mainCamera
            right = Vector3f(cam.leftVector()).mul(-1f)
            up = Vector3f(cam.upVector())
            out = Vector3f(right).cross(up)
        } else {
            val o = basis.point(-1f, -1f, 0f)
            right = Vector3f(basis.point(1f, -1f, 0f)).sub(o).normalize()
            up = Vector3f(basis.point(-1f, 1f, 0f)).sub(o).normalize()
            out = Vector3f(basis.point(-1f, -1f, 1f)).sub(o).normalize()
        }
        return Quaternionf().setFromNormalized(
            Matrix3f(right.x, right.y, right.z, up.x, up.y, up.z, out.x, out.y, out.z),
        )
    }

    /** Camera-facing flat quad centred on the element's volume anchor. */
    private fun submitBillboard(
        submitter: SubmitNodeCollector,
        poseStack: PoseStack,
        basis: DisplayVolume.Basis,
        state: DisplayState,
        e: DisplayElement,
        r: Int, g: Int, b: Int, a: Int,
    ) {
        val ez = e.z.coerceIn(0f, 1f) + FACE_OFFSET
        val center = basis.point(e.x + e.w / 2f, e.y + e.h / 2f, ez)
        val cam = Minecraft.getInstance().gameRenderer.mainCamera
        val right = Vector3f(cam.leftVector()).mul(-1f)
        val up = Vector3f(cam.upVector())
        val halfW = e.w * state.cols / 4f
        val halfH = e.h * state.rows / 4f
        val origin = Vector3f(center).fma(-halfW, right).fma(-halfH, up)
        val edgeX = Vector3f(right).mul(halfW * 2f)
        val edgeY = Vector3f(up).mul(halfH * 2f)
        EmissiveCubeRenderer.submitRect(submitter, poseStack, origin, edgeX, edgeY, r, g, b, a)
    }
}
