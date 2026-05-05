package damien.nodeworks.render

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import com.mojang.math.Axis
import damien.nodeworks.block.ExportChestBlock
import damien.nodeworks.block.entity.ExportChestBlockEntity
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.client.renderer.feature.ModelFeatureRenderer
import net.minecraft.client.renderer.rendertype.RenderSetup
import net.minecraft.client.renderer.rendertype.RenderType
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.core.Direction
import net.minecraft.resources.Identifier
import net.minecraft.world.phys.Vec3

/**
 * BER for the Export Chest. Geometry, hinge, FACING rotation, and emissive
 * overlay logic mirror [ImportChestRenderer] exactly, only the textures
 * differ. When both chests stabilise we'll factor the shared body+lid+lock
 * pipeline into a `ChestLikeRenderer` base.
 */
open class ExportChestRenderer(context: BlockEntityRendererProvider.Context) :
    ConnectableBER<ExportChestBlockEntity, ExportChestRenderer.RenderState>(context) {

    class RenderState : ConnectableRenderState() {
        var openness: Float = 0f
        var facing: Direction = Direction.SOUTH
        var networkColor: Int = NodeConnectionRenderer.DEFAULT_NETWORK_COLOR
        var pushFace: Direction? = null
    }

    private data class FaceUv(val u0: Float, val v0: Float, val u1: Float, val v1: Float)

    private class BoxUv(texU: Int, texV: Int, sx: Int, sy: Int, sz: Int, atlasSize: Int = 64) {
        private val s = atlasSize.toFloat()
        val up    = FaceUv((texU + sz)            / s, (texV + 0)  / s, (texU + sz + sx)        / s, (texV + sz)      / s)
        val down  = FaceUv((texU + sz + sx)       / s, (texV + 0)  / s, (texU + sz + 2 * sx)    / s, (texV + sz)      / s)
        val west  = FaceUv((texU + 0)             / s, (texV + sz) / s, (texU + sz)             / s, (texV + sz + sy) / s)
        val north = FaceUv((texU + sz)            / s, (texV + sz) / s, (texU + sz + sx)        / s, (texV + sz + sy) / s)
        val east  = FaceUv((texU + sz + sx)       / s, (texV + sz) / s, (texU + 2 * sz + sx)    / s, (texV + sz + sy) / s)
        val south = FaceUv((texU + 2 * sz + sx)   / s, (texV + sz) / s, (texU + 2 * sz + 2 * sx)/ s, (texV + sz + sy) / s)
    }

    companion object {
        private val LID_TEXTURE: Identifier =
            Identifier.fromNamespaceAndPath("nodeworks", "textures/block/export_chest.png")

        private val EMISSIVE_TEXTURE: Identifier =
            Identifier.fromNamespaceAndPath("nodeworks", "textures/block/export_chest_emissive.png")

        /** 16×16 face texture stamped on the side that auto-push targets.
         *  Whatever PNG sits at this path gets rendered as the indicator. */
        private val PUSH_INDICATOR_TEXTURE: Identifier =
            Identifier.fromNamespaceAndPath("nodeworks", "textures/block/export_chest_push.png")

        private val LID_RENDER_TYPE: RenderType = run {
            val safe = LID_TEXTURE.path.replace('/', '_').replace('.', '_')
            RenderType.create(
                "nodeworks_chest_lid_${LID_TEXTURE.namespace}_$safe",
                RenderSetup.builder(RenderPipelines.ENTITY_SOLID)
                    .withTexture("Sampler0", LID_TEXTURE)
                    .useLightmap()
                    .useOverlay()
                    .createRenderSetup()
            )
        }

        private val EMISSIVE_RENDER_TYPE: RenderType =
            EmissiveCubeRenderer.renderType(EMISSIVE_TEXTURE)

        private val PUSH_RENDER_TYPE: RenderType = run {
            val safe = PUSH_INDICATOR_TEXTURE.path.replace('/', '_').replace('.', '_')
            // ENTITY_TRANSLUCENT_CULL respects the texture's alpha channel
            // (ENTITY_SOLID would treat sub-1.0 alpha as opaque, eating the
            // transparent pixels around the indicator art).
            RenderType.create(
                "nodeworks_chest_push_${PUSH_INDICATOR_TEXTURE.namespace}_$safe",
                RenderSetup.builder(RenderPipelines.ENTITY_TRANSLUCENT_CULL)
                    .withTexture("Sampler0", PUSH_INDICATOR_TEXTURE)
                    .useLightmap()
                    .useOverlay()
                    .createRenderSetup()
            )
        }

        private const val EMISSIVE_OUTSET = 0.0006f

        /** Fixed emissive tint. The chest's role (export → orange) reads at a
         *  glance, decoupled from whatever network channel happens to be
         *  attached. RGB only; the emissive PNG carries its own alpha. */
        private const val EMISSIVE_TINT = 0xFF9933
        /** Slight outset so the indicator quad doesn't z-fight with the body
         *  / lid texture underneath. Same magnitude as the emissive overlay. */
        private const val PUSH_OUTSET = 0.001f
        /** Indicator footprint in pixel units (1/16 = one pixel). The quad is
         *  centred on whichever face the auto-push targets. */
        private const val PUSH_PX = 8f / 16f

        private const val BODY_X0 = 1f / 16f
        private const val BODY_X1 = 15f / 16f
        private const val BODY_Y0 = 0f / 16f
        private const val BODY_Y1 = 10f / 16f
        private const val BODY_Z0 = 1f / 16f
        private const val BODY_Z1 = 15f / 16f

        private const val LID_X0 = 1f / 16f
        private const val LID_X1 = 15f / 16f
        private const val LID_Y0 = 9f / 16f
        private const val LID_Y1 = 14f / 16f
        private const val LID_Z0 = 1f / 16f
        private const val LID_Z1 = 15f / 16f

        private const val LOCK_X0 = 7f / 16f
        private const val LOCK_X1 = 9f / 16f
        private const val LOCK_Y0 = 7f / 16f
        private const val LOCK_Y1 = 11f / 16f
        private const val LOCK_Z0 = 15f / 16f
        private const val LOCK_Z1 = 16f / 16f

        private const val HINGE_X = 8f / 16f
        private const val HINGE_Y = 9f / 16f
        private const val HINGE_Z = 1f / 16f

        private const val MAX_OPEN_DEG = 90f

        private fun easeOutCubic(x: Float): Float {
            val inv = 1f - x
            return 1f - inv * inv * inv
        }
    }

    private val BODY_UV = BoxUv(texU = 0, texV = 19, sx = 14, sy = 10, sz = 14)
    private val LID_UV = BoxUv(texU = 0, texV = 0, sx = 14, sy = 5, sz = 14)
    private val LOCK_UV = BoxUv(texU = 0, texV = 0, sx = 2, sy = 4, sz = 1)

    override fun createRenderState(): RenderState = RenderState()

    override fun extractConnectable(
        blockEntity: ExportChestBlockEntity,
        state: RenderState,
        partialTicks: Float,
        cameraPosition: Vec3,
        breakProgress: ModelFeatureRenderer.CrumblingOverlay?,
    ) {
        state.openness = blockEntity.getOpenNess(partialTicks)
        state.facing = blockEntity.blockState.getValue(ExportChestBlock.FACING)
        state.networkColor = resolveNetworkColor(blockEntity)
        state.pushFace = blockEntity.pushFace
    }

    override fun submitConnectable(
        state: RenderState,
        poseStack: PoseStack,
        submitNodeCollector: SubmitNodeCollector,
        camera: CameraRenderState,
    ) {
        val openness = state.openness
        val light = state.lightCoords
        val networkColor = state.networkColor
        val connected = networkColor != NodeConnectionRenderer.DEFAULT_NETWORK_COLOR
        val angleDeg = -easeOutCubic(openness) * MAX_OPEN_DEG
        val yRotDeg = -state.facing.toYRot()

        poseStack.pushPose()
        poseStack.translate(0.5f, 0.5f, 0.5f)
        poseStack.mulPose(Axis.YP.rotationDegrees(yRotDeg))
        poseStack.translate(-0.5f, -0.5f, -0.5f)

        if (openness <= 0f) {
            submitNodeCollector.submitCustomGeometry(poseStack, LID_RENDER_TYPE) { p, vc ->
                emitBox(p, vc, light, BODY_X0, BODY_Y0, BODY_Z0, BODY_X1, BODY_Y1, BODY_Z1, BODY_UV, skipMinusZ = false)
                emitLid(p, vc, light)
            }
            if (connected) {
                emitEmissive(poseStack, submitNodeCollector,
                    BODY_X0, BODY_Y0, BODY_Z0, BODY_X1, BODY_Y1, BODY_Z1, BODY_UV, skipMinusZ = false)
                emitEmissive(poseStack, submitNodeCollector,
                    LID_X0, LID_Y0, LID_Z0, LID_X1, LID_Y1, LID_Z1, LID_UV, skipMinusZ = false)
                emitEmissive(poseStack, submitNodeCollector,
                    LOCK_X0, LOCK_Y0, LOCK_Z0, LOCK_X1, LOCK_Y1, LOCK_Z1, LOCK_UV, skipMinusZ = true)
            }
        } else {
            submitNodeCollector.submitCustomGeometry(poseStack, LID_RENDER_TYPE) { p, vc ->
                emitBox(p, vc, light, BODY_X0, BODY_Y0, BODY_Z0, BODY_X1, BODY_Y1, BODY_Z1, BODY_UV, skipMinusZ = false)
            }
            if (connected) {
                emitEmissive(poseStack, submitNodeCollector,
                    BODY_X0, BODY_Y0, BODY_Z0, BODY_X1, BODY_Y1, BODY_Z1, BODY_UV, skipMinusZ = false)
            }

            poseStack.pushPose()
            poseStack.translate(HINGE_X, HINGE_Y, HINGE_Z)
            poseStack.mulPose(Axis.XP.rotationDegrees(angleDeg))
            poseStack.translate(-HINGE_X, -HINGE_Y, -HINGE_Z)
            submitNodeCollector.submitCustomGeometry(poseStack, LID_RENDER_TYPE) { p, vc ->
                emitLid(p, vc, light)
            }
            if (connected) {
                emitEmissive(poseStack, submitNodeCollector,
                    LID_X0, LID_Y0, LID_Z0, LID_X1, LID_Y1, LID_Z1, LID_UV, skipMinusZ = false)
                emitEmissive(poseStack, submitNodeCollector,
                    LOCK_X0, LOCK_Y0, LOCK_Z0, LOCK_X1, LOCK_Y1, LOCK_Z1, LOCK_UV, skipMinusZ = true)
            }
            poseStack.popPose()
        }

        // Auto-push indicator: small textured quad on the local face that maps
        // to the BE's pushFace. Drawn after the body so it overlays the
        // existing texture, slightly outset to dodge z-fighting. Uses the
        // post-rotation pose so converting world pushFace -> local face is a
        // pure direction-mapping (no extra transforms).
        //
        // The UP indicator rides on the lid surface, so we re-use the lid's
        // hinge rotation. angleDeg is 0 when closed → identity transform.
        val pushFace = state.pushFace
        if (pushFace != null) {
            val localFace = localPushFace(pushFace, state.facing)
            if (localFace == Direction.UP) {
                poseStack.pushPose()
                poseStack.translate(HINGE_X, HINGE_Y, HINGE_Z)
                poseStack.mulPose(Axis.XP.rotationDegrees(angleDeg))
                poseStack.translate(-HINGE_X, -HINGE_Y, -HINGE_Z)
                submitNodeCollector.submitCustomGeometry(poseStack, PUSH_RENDER_TYPE) { p, vc ->
                    emitPushIndicator(p, vc, light, localFace)
                }
                poseStack.popPose()
            } else {
                submitNodeCollector.submitCustomGeometry(poseStack, PUSH_RENDER_TYPE) { p, vc ->
                    emitPushIndicator(p, vc, light, localFace)
                }
            }
        }

        poseStack.popPose()
    }

    /** World pushFace → local face on the unrotated model. The model's local
     *  +Z (SOUTH) is the lock side, which the YP rotation in [submitConnectable]
     *  aligns with the chest's [Direction] FACING. */
    private fun localPushFace(worldFace: Direction, facing: Direction): Direction = when {
        worldFace == Direction.UP -> Direction.UP
        worldFace == Direction.DOWN -> Direction.DOWN
        worldFace == facing -> Direction.SOUTH
        worldFace == facing.opposite -> Direction.NORTH
        facing.axis.isHorizontal && worldFace == facing.counterClockWise -> Direction.EAST
        facing.axis.isHorizontal && worldFace == facing.clockWise -> Direction.WEST
        else -> Direction.SOUTH
    }

    /** Centred indicator quad on [localFace] of the chest's outer envelope.
     *  Footprint is [PUSH_PX] (~8 pixels) on each side, outset by [PUSH_OUTSET]
     *  past the body / lid surface. UV spans the full texture (0..1). */
    private fun emitPushIndicator(
        p: PoseStack.Pose,
        vc: VertexConsumer,
        light: Int,
        localFace: Direction,
    ) {
        val ov = OverlayTexture.NO_OVERLAY
        val r = 255; val g = 255; val b = 255; val a = 255
        val half = PUSH_PX / 2f
        val out = PUSH_OUTSET
        val center = 0.5f
        val bodyCenterY = (BODY_Y0 + BODY_Y1) / 2f

        when (localFace) {
            Direction.UP -> {
                val y = LID_Y1 + out
                val nx = center - half; val xx = center + half
                val nz = center - half; val xz = center + half
                vc.addVertex(p, nx, y, nz).setUv(0f, 0f).setColor(r, g, b, a).setOverlay(ov).setLight(light).setNormal(p, 0f, 1f, 0f)
                vc.addVertex(p, nx, y, xz).setUv(0f, 1f).setColor(r, g, b, a).setOverlay(ov).setLight(light).setNormal(p, 0f, 1f, 0f)
                vc.addVertex(p, xx, y, xz).setUv(1f, 1f).setColor(r, g, b, a).setOverlay(ov).setLight(light).setNormal(p, 0f, 1f, 0f)
                vc.addVertex(p, xx, y, nz).setUv(1f, 0f).setColor(r, g, b, a).setOverlay(ov).setLight(light).setNormal(p, 0f, 1f, 0f)
            }
            Direction.DOWN -> {
                val y = BODY_Y0 - out
                val nx = center - half; val xx = center + half
                val nz = center - half; val xz = center + half
                vc.addVertex(p, nx, y, nz).setUv(0f, 0f).setColor(r, g, b, a).setOverlay(ov).setLight(light).setNormal(p, 0f, -1f, 0f)
                vc.addVertex(p, xx, y, nz).setUv(1f, 0f).setColor(r, g, b, a).setOverlay(ov).setLight(light).setNormal(p, 0f, -1f, 0f)
                vc.addVertex(p, xx, y, xz).setUv(1f, 1f).setColor(r, g, b, a).setOverlay(ov).setLight(light).setNormal(p, 0f, -1f, 0f)
                vc.addVertex(p, nx, y, xz).setUv(0f, 1f).setColor(r, g, b, a).setOverlay(ov).setLight(light).setNormal(p, 0f, -1f, 0f)
            }
            Direction.SOUTH -> {
                val z = BODY_Z1 + out
                val nx = center - half; val xx = center + half
                val ny = bodyCenterY - half; val xy = bodyCenterY + half
                vc.addVertex(p, xx, ny, z).setUv(1f, 1f).setColor(r, g, b, a).setOverlay(ov).setLight(light).setNormal(p, 0f, 0f, 1f)
                vc.addVertex(p, xx, xy, z).setUv(1f, 0f).setColor(r, g, b, a).setOverlay(ov).setLight(light).setNormal(p, 0f, 0f, 1f)
                vc.addVertex(p, nx, xy, z).setUv(0f, 0f).setColor(r, g, b, a).setOverlay(ov).setLight(light).setNormal(p, 0f, 0f, 1f)
                vc.addVertex(p, nx, ny, z).setUv(0f, 1f).setColor(r, g, b, a).setOverlay(ov).setLight(light).setNormal(p, 0f, 0f, 1f)
            }
            Direction.NORTH -> {
                val z = BODY_Z0 - out
                val nx = center - half; val xx = center + half
                val ny = bodyCenterY - half; val xy = bodyCenterY + half
                vc.addVertex(p, nx, ny, z).setUv(1f, 1f).setColor(r, g, b, a).setOverlay(ov).setLight(light).setNormal(p, 0f, 0f, -1f)
                vc.addVertex(p, nx, xy, z).setUv(1f, 0f).setColor(r, g, b, a).setOverlay(ov).setLight(light).setNormal(p, 0f, 0f, -1f)
                vc.addVertex(p, xx, xy, z).setUv(0f, 0f).setColor(r, g, b, a).setOverlay(ov).setLight(light).setNormal(p, 0f, 0f, -1f)
                vc.addVertex(p, xx, ny, z).setUv(0f, 1f).setColor(r, g, b, a).setOverlay(ov).setLight(light).setNormal(p, 0f, 0f, -1f)
            }
            Direction.EAST -> {
                val x = BODY_X1 + out
                val ny = bodyCenterY - half; val xy = bodyCenterY + half
                val nz = center - half; val xz = center + half
                vc.addVertex(p, x, ny, nz).setUv(1f, 1f).setColor(r, g, b, a).setOverlay(ov).setLight(light).setNormal(p, 1f, 0f, 0f)
                vc.addVertex(p, x, xy, nz).setUv(1f, 0f).setColor(r, g, b, a).setOverlay(ov).setLight(light).setNormal(p, 1f, 0f, 0f)
                vc.addVertex(p, x, xy, xz).setUv(0f, 0f).setColor(r, g, b, a).setOverlay(ov).setLight(light).setNormal(p, 1f, 0f, 0f)
                vc.addVertex(p, x, ny, xz).setUv(0f, 1f).setColor(r, g, b, a).setOverlay(ov).setLight(light).setNormal(p, 1f, 0f, 0f)
            }
            Direction.WEST -> {
                val x = BODY_X0 - out
                val ny = bodyCenterY - half; val xy = bodyCenterY + half
                val nz = center - half; val xz = center + half
                vc.addVertex(p, x, ny, xz).setUv(1f, 1f).setColor(r, g, b, a).setOverlay(ov).setLight(light).setNormal(p, -1f, 0f, 0f)
                vc.addVertex(p, x, xy, xz).setUv(1f, 0f).setColor(r, g, b, a).setOverlay(ov).setLight(light).setNormal(p, -1f, 0f, 0f)
                vc.addVertex(p, x, xy, nz).setUv(0f, 0f).setColor(r, g, b, a).setOverlay(ov).setLight(light).setNormal(p, -1f, 0f, 0f)
                vc.addVertex(p, x, ny, nz).setUv(0f, 1f).setColor(r, g, b, a).setOverlay(ov).setLight(light).setNormal(p, -1f, 0f, 0f)
            }
            else -> Unit
        }
    }

    private fun emitEmissive(
        poseStack: PoseStack,
        submitNodeCollector: SubmitNodeCollector,
        mnx: Float, mny: Float, mnz: Float,
        mxx: Float, mxy: Float, mxz: Float,
        uv: BoxUv,
        skipMinusZ: Boolean,
    ) {
        val r = (EMISSIVE_TINT shr 16) and 0xFF
        val g = (EMISSIVE_TINT shr 8) and 0xFF
        val b = EMISSIVE_TINT and 0xFF
        val out = EMISSIVE_OUTSET
        submitNodeCollector.submitCustomGeometry(poseStack, EMISSIVE_RENDER_TYPE) { p, vc ->
            emitBox(
                p, vc, RenderUtils.FULL_BRIGHT,
                mnx - out, mny - out, mnz - out,
                mxx + out, mxy + out, mxz + out,
                uv, skipMinusZ,
                tintR = r, tintG = g, tintB = b,
            )
        }
    }

    private fun emitLid(p: PoseStack.Pose, vc: VertexConsumer, light: Int) {
        emitBox(p, vc, light, LID_X0, LID_Y0, LID_Z0, LID_X1, LID_Y1, LID_Z1, LID_UV, skipMinusZ = false)
        emitBox(p, vc, light, LOCK_X0, LOCK_Y0, LOCK_Z0, LOCK_X1, LOCK_Y1, LOCK_Z1, LOCK_UV, skipMinusZ = true)
    }

    private fun emitBox(
        p: PoseStack.Pose,
        vc: VertexConsumer,
        light: Int,
        mnx: Float, mny: Float, mnz: Float,
        mxx: Float, mxy: Float, mxz: Float,
        uv: BoxUv,
        skipMinusZ: Boolean,
        tintR: Int = 255, tintG: Int = 255, tintB: Int = 255, tintA: Int = 255,
    ) {
        val ov = OverlayTexture.NO_OVERLAY
        val r = tintR; val g = tintG; val b = tintB; val a = tintA

        run {
            val f = uv.south
            vc.addVertex(p, mxx, mny, mxz).setUv(f.u1, f.v1).setColor(r, g, b, a).setOverlay(ov).setLight(light).setNormal(p, 0f, 0f, 1f)
            vc.addVertex(p, mxx, mxy, mxz).setUv(f.u1, f.v0).setColor(r, g, b, a).setOverlay(ov).setLight(light).setNormal(p, 0f, 0f, 1f)
            vc.addVertex(p, mnx, mxy, mxz).setUv(f.u0, f.v0).setColor(r, g, b, a).setOverlay(ov).setLight(light).setNormal(p, 0f, 0f, 1f)
            vc.addVertex(p, mnx, mny, mxz).setUv(f.u0, f.v1).setColor(r, g, b, a).setOverlay(ov).setLight(light).setNormal(p, 0f, 0f, 1f)
        }

        if (!skipMinusZ) {
            val f = uv.north
            vc.addVertex(p, mnx, mny, mnz).setUv(f.u1, f.v1).setColor(r, g, b, a).setOverlay(ov).setLight(light).setNormal(p, 0f, 0f, -1f)
            vc.addVertex(p, mnx, mxy, mnz).setUv(f.u1, f.v0).setColor(r, g, b, a).setOverlay(ov).setLight(light).setNormal(p, 0f, 0f, -1f)
            vc.addVertex(p, mxx, mxy, mnz).setUv(f.u0, f.v0).setColor(r, g, b, a).setOverlay(ov).setLight(light).setNormal(p, 0f, 0f, -1f)
            vc.addVertex(p, mxx, mny, mnz).setUv(f.u0, f.v1).setColor(r, g, b, a).setOverlay(ov).setLight(light).setNormal(p, 0f, 0f, -1f)
        }

        run {
            val f = uv.east
            vc.addVertex(p, mxx, mny, mnz).setUv(f.u1, f.v1).setColor(r, g, b, a).setOverlay(ov).setLight(light).setNormal(p, 1f, 0f, 0f)
            vc.addVertex(p, mxx, mxy, mnz).setUv(f.u1, f.v0).setColor(r, g, b, a).setOverlay(ov).setLight(light).setNormal(p, 1f, 0f, 0f)
            vc.addVertex(p, mxx, mxy, mxz).setUv(f.u0, f.v0).setColor(r, g, b, a).setOverlay(ov).setLight(light).setNormal(p, 1f, 0f, 0f)
            vc.addVertex(p, mxx, mny, mxz).setUv(f.u0, f.v1).setColor(r, g, b, a).setOverlay(ov).setLight(light).setNormal(p, 1f, 0f, 0f)
        }

        run {
            val f = uv.west
            vc.addVertex(p, mnx, mny, mxz).setUv(f.u1, f.v1).setColor(r, g, b, a).setOverlay(ov).setLight(light).setNormal(p, -1f, 0f, 0f)
            vc.addVertex(p, mnx, mxy, mxz).setUv(f.u1, f.v0).setColor(r, g, b, a).setOverlay(ov).setLight(light).setNormal(p, -1f, 0f, 0f)
            vc.addVertex(p, mnx, mxy, mnz).setUv(f.u0, f.v0).setColor(r, g, b, a).setOverlay(ov).setLight(light).setNormal(p, -1f, 0f, 0f)
            vc.addVertex(p, mnx, mny, mnz).setUv(f.u0, f.v1).setColor(r, g, b, a).setOverlay(ov).setLight(light).setNormal(p, -1f, 0f, 0f)
        }

        run {
            val f = uv.up
            vc.addVertex(p, mnx, mxy, mxz).setUv(f.u0, f.v1).setColor(r, g, b, a).setOverlay(ov).setLight(light).setNormal(p, 0f, 1f, 0f)
            vc.addVertex(p, mxx, mxy, mxz).setUv(f.u1, f.v1).setColor(r, g, b, a).setOverlay(ov).setLight(light).setNormal(p, 0f, 1f, 0f)
            vc.addVertex(p, mxx, mxy, mnz).setUv(f.u1, f.v0).setColor(r, g, b, a).setOverlay(ov).setLight(light).setNormal(p, 0f, 1f, 0f)
            vc.addVertex(p, mnx, mxy, mnz).setUv(f.u0, f.v0).setColor(r, g, b, a).setOverlay(ov).setLight(light).setNormal(p, 0f, 1f, 0f)
        }

        run {
            val f = uv.down
            vc.addVertex(p, mnx, mny, mnz).setUv(f.u0, f.v0).setColor(r, g, b, a).setOverlay(ov).setLight(light).setNormal(p, 0f, -1f, 0f)
            vc.addVertex(p, mxx, mny, mnz).setUv(f.u1, f.v0).setColor(r, g, b, a).setOverlay(ov).setLight(light).setNormal(p, 0f, -1f, 0f)
            vc.addVertex(p, mxx, mny, mxz).setUv(f.u1, f.v1).setColor(r, g, b, a).setOverlay(ov).setLight(light).setNormal(p, 0f, -1f, 0f)
            vc.addVertex(p, mnx, mny, mxz).setUv(f.u0, f.v1).setColor(r, g, b, a).setOverlay(ov).setLight(light).setNormal(p, 0f, -1f, 0f)
        }
    }
}
