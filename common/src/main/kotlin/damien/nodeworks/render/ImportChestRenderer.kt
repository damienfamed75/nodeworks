package damien.nodeworks.render

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import com.mojang.math.Axis
import damien.nodeworks.block.ImportChestBlock
import damien.nodeworks.block.entity.ImportChestBlockEntity
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
 * BER for the Import Chest. Mirrors vanilla `ChestRenderer` / Sophisticated
 * Storage: body + lid + lock all drawn here so they share one render pipeline
 * (no chunk-mesh vs BER lighting mismatch, no z-fight at the y=9..10 overlap).
 *
 * Geometry from vanilla `ChestModel.createSingleBodyLayer`:
 *  * Body: (1,0,1)→(15,10,15), texOffs(0, 19).
 *  * Lid: (1,9,1)→(15,14,15), texOffs(0, 0). Hinge at (x=8, y=9, z=1).
 *  * Lock: (7,7,15)→(9,11,16), texOffs(0, 0). Protrudes 1px past +Z.
 *
 * Vertex color is white, the ENTITY_SOLID shader's two-light diffuse is the
 * sole brightness source (same as vanilla). Applying chunk-style face
 * multipliers on top would double-dim the side faces.
 */
open class ImportChestRenderer(context: BlockEntityRendererProvider.Context) :
    ConnectableBER<ImportChestBlockEntity, ImportChestRenderer.RenderState>(context) {

    class RenderState : ConnectableRenderState() {
        var openness: Float = 0f
        var facing: Direction = Direction.SOUTH
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
            Identifier.fromNamespaceAndPath("nodeworks", "textures/block/import_chest.png")

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
        blockEntity: ImportChestBlockEntity,
        state: RenderState,
        partialTicks: Float,
        cameraPosition: Vec3,
        breakProgress: ModelFeatureRenderer.CrumblingOverlay?,
    ) {
        state.openness = blockEntity.getOpenNess(partialTicks)
        state.facing = blockEntity.blockState.getValue(ImportChestBlock.FACING)
    }

    override fun submitConnectable(
        state: RenderState,
        poseStack: PoseStack,
        submitNodeCollector: SubmitNodeCollector,
        camera: CameraRenderState,
    ) {
        val openness = state.openness
        val light = state.lightCoords
        // Vanilla ChestRenderer: lid.xRot = -openness * π/2, body and lid both
        // rotated by -toYRot() around the block centre to align FACING.
        val angleDeg = -easeOutCubic(openness) * MAX_OPEN_DEG
        val yRotDeg = -state.facing.toYRot()

        poseStack.pushPose()
        poseStack.translate(0.5f, 0.5f, 0.5f)
        poseStack.mulPose(Axis.YP.rotationDegrees(yRotDeg))
        poseStack.translate(-0.5f, -0.5f, -0.5f)

        if (openness <= 0f) {
            // Closed-chest fast path: body + lid + lock in one submit. >99% of
            // chests are closed at any given frame, so this halves buffer churn.
            submitNodeCollector.submitCustomGeometry(poseStack, LID_RENDER_TYPE) { p, vc ->
                emitBox(p, vc, light, BODY_X0, BODY_Y0, BODY_Z0, BODY_X1, BODY_Y1, BODY_Z1, BODY_UV, skipMinusZ = false)
                emitLid(p, vc, light)
            }
        } else {
            // Lid + lock need their own pose frame (hinge X rotation), body emits flat.
            submitNodeCollector.submitCustomGeometry(poseStack, LID_RENDER_TYPE) { p, vc ->
                emitBox(p, vc, light, BODY_X0, BODY_Y0, BODY_Z0, BODY_X1, BODY_Y1, BODY_Z1, BODY_UV, skipMinusZ = false)
            }
            poseStack.pushPose()
            poseStack.translate(HINGE_X, HINGE_Y, HINGE_Z)
            poseStack.mulPose(Axis.XP.rotationDegrees(angleDeg))
            poseStack.translate(-HINGE_X, -HINGE_Y, -HINGE_Z)
            submitNodeCollector.submitCustomGeometry(poseStack, LID_RENDER_TYPE) { p, vc ->
                emitLid(p, vc, light)
            }
            poseStack.popPose()
        }

        poseStack.popPose()
    }

    private fun emitLid(p: PoseStack.Pose, vc: VertexConsumer, light: Int) {
        emitBox(p, vc, light, LID_X0, LID_Y0, LID_Z0, LID_X1, LID_Y1, LID_Z1, LID_UV, skipMinusZ = false)
        // Lock's -Z face is coplanar with the lid's +Z front, skip to avoid z-fight.
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
    ) {
        val ov = OverlayTexture.NO_OVERLAY
        // White vertex color, ENTITY_SOLID's shader handles diffuse, multiplying
        // per-face here would double-dim the side faces relative to vanilla.
        val r = 255; val g = 255; val b = 255; val a = 255

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
