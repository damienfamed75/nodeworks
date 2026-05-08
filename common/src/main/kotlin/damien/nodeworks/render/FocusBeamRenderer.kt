package damien.nodeworks.render

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import damien.nodeworks.network.Connectable
import damien.nodeworks.network.NetworkSettingsRegistry
import damien.nodeworks.network.NodeConnectionHelper
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.rendertype.RenderType
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.core.BlockPos
import net.minecraft.resources.Identifier
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Inter-block laser beams between linked Focus Nodes. Only Focus Nodes
 * populate `getConnections()`, so this implicitly scopes itself to them
 * with no explicit type check.
 *
 * Each pair is rendered once from its lex-lower endpoint. Reuses the same
 * laser texture and beacon-beam render type as [PipeLaserBeam] so the
 * inside-pipe and outgoing beams read as continuous.
 */
object FocusBeamRenderer {

    private val LASER_TEXTURE: Identifier =
        Identifier.fromNamespaceAndPath("nodeworks", "textures/block/laser_trail.png")
    /** Hazard-stripe texture for micro-networks; matches PipeLaserBeam's
     *  treatment so the in-pipe + inter-focus-node beams read as the same
     *  visual entity. */
    private val MICRO_TEXTURE: Identifier =
        Identifier.fromNamespaceAndPath("nodeworks", "textures/block/hazard_stripes.png")

    private val TRANSLUCENT_TYPE: RenderType = RenderTypes.beaconBeam(LASER_TEXTURE, true)
    private val OPAQUE_TYPE: RenderType = RenderTypes.beaconBeam(LASER_TEXTURE, false)
    private val MICRO_OPAQUE_TYPE: RenderType = RenderTypes.beaconBeam(MICRO_TEXTURE, false)

    /** Yellow glow tint on micro-networks (#FFDC3B). Matches
     *  [PipeLaserBeam.MICRO_GLOW_R/G/B] so the halo accent reads continuous. */
    private const val MICRO_GLOW_R = 0xFF
    private const val MICRO_GLOW_G = 0xDC
    private const val MICRO_GLOW_B = 0x3B

    /** Beam width in blocks. Same scale as [PipeLaserBeam.BEAM_WIDTH] so the
     *  inter-block beam matches the inside-pipe beam thickness. */
    private const val BEAM_WIDTH = 1f / 16f
    /** Matches [PipeLaserBeam.BEAM_SCROLL_SPEED] so in-pipe and inter-focus
     *  scroll velocities stay in lockstep. */
    private const val BEAM_SCROLL_SPEED = 0.4f

    /** Pre-extracted render data for one beam. Coordinates are block-relative
     *  so the BER's pose transform (already at the source block) renders
     *  without an extra translate. [blocked] flips the colour to red. */
    data class Beam(
        val toDx: Float,
        val toDy: Float,
        val toDz: Float,
        val r: Int,
        val g: Int,
        val b: Int,
        val blocked: Boolean,
        val mode: Int = NetworkSettingsRegistry.LASER_MODE_FANCY,
        /** True when either endpoint sits on a Processing Handler micro-net.
         *  Switches to the hazard texture + yellow glow. */
        val isMicro: Boolean = false,
    )

    /** Collect the inter-block beams to render this frame. Each pair emits
     *  from its lex-lower endpoint so the beam draws once across both ends.
     *  An unloaded lower endpoint drops the beam, but the target isn't
     *  visible from there either, so this matches what the player sees. */
    fun extract(connectable: Connectable): List<Beam> {
        val be = connectable as? net.minecraft.world.level.block.entity.BlockEntity ?: return emptyList()
        val level = be.level ?: return emptyList()
        val myPos = be.blockPos

        val networkId = connectable.networkId
        val mySettings = NetworkSettingsRegistry.get(networkId)
        if (networkId != null && !mySettings.laserEnabled) return emptyList()
        val laserMode = mySettings.laserMode
        val myColor = if (networkId != null) mySettings.color
            else NodeConnectionRenderer.DEFAULT_NETWORK_COLOR

        val beams = mutableListOf<Beam>()
        for (targetPos in connectable.getConnections()) {
            if (!isLessThan(myPos, targetPos)) continue
            val targetBe = level.getBlockEntity(targetPos) as? Connectable ?: continue

            val pairColor = when {
                connectable.networkId != null -> myColor
                targetBe.networkId != null -> NetworkSettingsRegistry.getColor(targetBe.networkId!!)
                else -> NodeConnectionRenderer.DEFAULT_NETWORK_COLOR
            }

            // Fresh client-side raycast each frame so the red "blocked" tint
            // reacts immediately to the player walling off the line. No server
            // packet sync needed since [checkLineOfSight] runs on either side.
            val blocked = !NodeConnectionHelper.checkLineOfSight(level, myPos, targetPos)
            val isMicro = MicroNetworkClientRegistry.isMicro(connectable.networkId) ||
                MicroNetworkClientRegistry.isMicro(targetBe.networkId)

            beams.add(
                Beam(
                    toDx = (targetPos.x - myPos.x).toFloat(),
                    toDy = (targetPos.y - myPos.y).toFloat(),
                    toDz = (targetPos.z - myPos.z).toFloat(),
                    r = (pairColor shr 16) and 0xFF,
                    g = (pairColor shr 8) and 0xFF,
                    b = pairColor and 0xFF,
                    blocked = blocked,
                    mode = laserMode,
                    isMicro = isMicro,
                )
            )
        }
        return beams
    }

    /** Emit geometry for each extracted [Beam]. Two render-type batches max:
     *  opaque (fancy prism core) and translucent (fancy outer glow + fast
     *  billboard), so pipeline switches stay minimal. */
    fun submit(
        beams: List<Beam>,
        poseStack: PoseStack,
        submitNodeCollector: SubmitNodeCollector,
        blockWorldPos: BlockPos,
        camWorld: Vec3,
    ) {
        if (beams.isEmpty()) return

        val time = (System.currentTimeMillis() % 100_000) / 1000f
        val pulse = (sin((time * 2.0)).toFloat() * 0.3f + 0.7f)
        val sizePulse = (sin((time * 2.0)).toFloat() * 0.25f + 1.0f)

        // Camera in block-local coords. The BER's pose stack already
        // translates to the block origin so vertex math stays in 0..1 space.
        val camLocalX = (camWorld.x - blockWorldPos.x).toFloat()
        val camLocalY = (camWorld.y - blockWorldPos.y).toFloat()
        val camLocalZ = (camWorld.z - blockWorldPos.z).toFloat()

        // Source position is the centre of *this* block in BER-local space.
        val fx = 0.5f; val fy = 0.5f; val fz = 0.5f

        val (fastBeams, fancyBeams) = beams.partition { it.mode == NetworkSettingsRegistry.LASER_MODE_FAST }
        val standardFancy = fancyBeams.filterNot { it.isMicro }
        val microFancy = fancyBeams.filter { it.isMicro }
        val standardFast = fastBeams.filterNot { it.isMicro }
        val microFast = fastBeams.filter { it.isMicro }

        // Fancy prism cores: separate batches per render type since the
        // texture differs (standard streak vs. hazard stripes).
        if (standardFancy.any { !it.blocked }) {
            submitNodeCollector.submitCustomGeometry(poseStack, OPAQUE_TYPE) { pose, vc ->
                for (beam in standardFancy) {
                    if (beam.blocked) continue
                    val tx = fx + beam.toDx; val ty = fy + beam.toDy; val tz = fz + beam.toDz
                    renderPrismBeam(vc, pose, fx, fy, fz, tx, ty, tz, time, 255, 255, 255, 255, BEAM_WIDTH * 0.5f)
                }
            }
        }
        if (microFancy.any { !it.blocked }) {
            submitNodeCollector.submitCustomGeometry(poseStack, MICRO_OPAQUE_TYPE) { pose, vc ->
                for (beam in microFancy) {
                    if (beam.blocked) continue
                    val tx = fx + beam.toDx; val ty = fy + beam.toDy; val tz = fz + beam.toDz
                    renderPrismBeam(
                        vc, pose, fx, fy, fz, tx, ty, tz, time, 255, 255, 255, 255,
                        BEAM_WIDTH * 0.5f, rotateUv = true,
                    )
                }
            }
        }
        // Fancy glow: single batch (always TRANSLUCENT_TYPE / standard
        // streak texture). Per-beam tint picks micro yellow vs. network color.
        if (fancyBeams.isNotEmpty()) {
            submitNodeCollector.submitCustomGeometry(poseStack, TRANSLUCENT_TYPE) { pose, vc ->
                for (beam in fancyBeams) {
                    val tx = fx + beam.toDx; val ty = fy + beam.toDy; val tz = fz + beam.toDz
                    if (beam.blocked) {
                        renderBillboardBeam(
                            vc, pose, camLocalX, camLocalY, camLocalZ,
                            fx, fy, fz, tx, ty, tz, time, 180, 50, 50, 80, BEAM_WIDTH * 2f,
                        )
                    } else {
                        val alpha = (120 * pulse).toInt().coerceIn(0, 255)
                        val gr = if (beam.isMicro) MICRO_GLOW_R else beam.r
                        val gg = if (beam.isMicro) MICRO_GLOW_G else beam.g
                        val gb = if (beam.isMicro) MICRO_GLOW_B else beam.b
                        renderBillboardBeam(
                            vc, pose, camLocalX, camLocalY, camLocalZ,
                            fx, fy, fz, tx, ty, tz, time, gr, gg, gb, alpha,
                            BEAM_WIDTH * 3.5f * sizePulse,
                        )
                    }
                }
            }
        }

        // Fast mode: standard uses TRANSLUCENT_TYPE (streak) tinted with
        // network color; micro uses MICRO_OPAQUE_TYPE so the hazard texture
        // shows on the billboard with rotated UV + white tint.
        if (standardFast.isNotEmpty()) {
            submitNodeCollector.submitCustomGeometry(poseStack, TRANSLUCENT_TYPE) { pose, vc ->
                for (beam in standardFast) {
                    val tx = fx + beam.toDx; val ty = fy + beam.toDy; val tz = fz + beam.toDz
                    val (cr, cg, cb) = if (beam.blocked) Triple(180, 50, 50) else Triple(beam.r, beam.g, beam.b)
                    renderBillboardBeam(
                        vc, pose, camLocalX, camLocalY, camLocalZ,
                        fx, fy, fz, tx, ty, tz, time, cr, cg, cb, 220, BEAM_WIDTH * 0.75f,
                    )
                }
            }
        }
        if (microFast.isNotEmpty()) {
            submitNodeCollector.submitCustomGeometry(poseStack, MICRO_OPAQUE_TYPE) { pose, vc ->
                for (beam in microFast) {
                    val tx = fx + beam.toDx; val ty = fy + beam.toDy; val tz = fz + beam.toDz
                    val (cr, cg, cb) = if (beam.blocked) Triple(180, 50, 50) else Triple(255, 255, 255)
                    renderBillboardBeam(
                        vc, pose, camLocalX, camLocalY, camLocalZ,
                        fx, fy, fz, tx, ty, tz, time, cr, cg, cb, 220,
                        BEAM_WIDTH * 0.75f, rotateUv = true,
                    )
                }
            }
        }
    }

    /** AABB encompassing this Connectable plus every block it has a link
     *  to. BERs feed this into `getRenderBoundingBox` so the frustum culler
     *  keeps the BER alive whenever any beam it draws is on screen. */
    fun computeBoundingBox(connectable: Connectable): AABB {
        val be = connectable as? net.minecraft.world.level.block.entity.BlockEntity
            ?: return AABB(BlockPos.ZERO)
        val me = be.blockPos
        var minX = me.x.toDouble(); var minY = me.y.toDouble(); var minZ = me.z.toDouble()
        var maxX = (me.x + 1).toDouble(); var maxY = (me.y + 1).toDouble(); var maxZ = (me.z + 1).toDouble()
        for (c in connectable.getConnections()) {
            minX = min(minX, c.x.toDouble()); minY = min(minY, c.y.toDouble()); minZ = min(minZ, c.z.toDouble())
            maxX = max(maxX, (c.x + 1).toDouble()); maxY = max(maxY, (c.y + 1).toDouble()); maxZ = max(maxZ, (c.z + 1).toDouble())
        }
        return AABB(minX, minY, minZ, maxX, maxY, maxZ)
    }

    private fun isLessThan(a: BlockPos, b: BlockPos): Boolean {
        if (a.x != b.x) return a.x < b.x
        if (a.y != b.y) return a.y < b.y
        return a.z < b.z
    }

    /** Solid rotating 4-sided prism along the beam axis. Same geometry the
     *  inside-pipe laser uses, the call site sets up start / end / width
     *  differently. Ported from the pre-pipe-refactor `ConnectionBeamRenderer`. */
    private fun renderPrismBeam(
        vc: VertexConsumer,
        pose: PoseStack.Pose,
        fromX: Float, fromY: Float, fromZ: Float,
        toX: Float, toY: Float, toZ: Float,
        time: Float,
        r: Int, g: Int, b: Int, a: Int,
        width: Float,
        rotateUv: Boolean = false,
    ) {
        val dx = toX - fromX; val dy = toY - fromY; val dz = toZ - fromZ
        val len = sqrt(dx * dx + dy * dy + dz * dz)
        if (len < 0.01f) return
        val dirX = dx / len; val dirY = dy / len; val dirZ = dz / len

        val refX: Float; val refY: Float; val refZ: Float
        if (abs(dirY) < 0.9f) { refX = 0f; refY = 1f; refZ = 0f }
        else { refX = 1f; refY = 0f; refZ = 0f }

        var a1x = dirY * refZ - dirZ * refY
        var a1y = dirZ * refX - dirX * refZ
        var a1z = dirX * refY - dirY * refX
        val a1len = sqrt(a1x * a1x + a1y * a1y + a1z * a1z)
        a1x /= a1len; a1y /= a1len; a1z /= a1len
        var a2x = dirY * a1z - dirZ * a1y
        var a2y = dirZ * a1x - dirX * a1z
        var a2z = dirX * a1y - dirY * a1x
        val a2len = sqrt(a2x * a2x + a2y * a2y + a2z * a2z)
        a2x /= a2len; a2y /= a2len; a2z /= a2len

        val angle = time * 1.0f
        val ca = kotlin.math.cos(angle); val sa = sin(angle)
        val r1x = a1x * ca + a2x * sa
        val r1y = a1y * ca + a2y * sa
        val r1z = a1z * ca + a2z * sa
        val r2x = -a1x * sa + a2x * ca
        val r2y = -a1y * sa + a2y * ca
        val r2z = -a1z * sa + a2z * ca
        a1x = r1x; a1y = r1y; a1z = r1z
        a2x = r2x; a2y = r2y; a2z = r2z

        val hw = width / 2f
        val uMax = 5f / 16f
        val uvScroll = time * BEAM_SCROLL_SPEED
        val v0 = uvScroll
        val v1 = uvScroll + len * 0.5f
        val o = OverlayTexture.NO_OVERLAY

        val f0x = fromX - a1x * hw - a2x * hw; val f0y = fromY - a1y * hw - a2y * hw; val f0z = fromZ - a1z * hw - a2z * hw
        val f1x = fromX + a1x * hw - a2x * hw; val f1y = fromY + a1y * hw - a2y * hw; val f1z = fromZ + a1z * hw - a2z * hw
        val f2x = fromX + a1x * hw + a2x * hw; val f2y = fromY + a1y * hw + a2y * hw; val f2z = fromZ + a1z * hw + a2z * hw
        val f3x = fromX - a1x * hw + a2x * hw; val f3y = fromY - a1y * hw + a2y * hw; val f3z = fromZ - a1z * hw + a2z * hw
        val t0x = toX - a1x * hw - a2x * hw; val t0y = toY - a1y * hw - a2y * hw; val t0z = toZ - a1z * hw - a2z * hw
        val t1x = toX + a1x * hw - a2x * hw; val t1y = toY + a1y * hw - a2y * hw; val t1z = toZ + a1z * hw - a2z * hw
        val t2x = toX + a1x * hw + a2x * hw; val t2y = toY + a1y * hw + a2y * hw; val t2z = toZ + a1z * hw + a2z * hw
        val t3x = toX - a1x * hw + a2x * hw; val t3y = toY - a1y * hw + a2y * hw; val t3z = toZ - a1z * hw + a2z * hw

        emitPrismSide(vc, pose, f0x, f0y, f0z, f1x, f1y, f1z, t1x, t1y, t1z, t0x, t0y, t0z, uMax, v0, v1, r, g, b, a, o, rotateUv)
        emitPrismSide(vc, pose, f1x, f1y, f1z, f2x, f2y, f2z, t2x, t2y, t2z, t1x, t1y, t1z, uMax, v0, v1, r, g, b, a, o, rotateUv)
        emitPrismSide(vc, pose, f2x, f2y, f2z, f3x, f3y, f3z, t3x, t3y, t3z, t2x, t2y, t2z, uMax, v0, v1, r, g, b, a, o, rotateUv)
        emitPrismSide(vc, pose, f3x, f3y, f3z, f0x, f0y, f0z, t0x, t0y, t0z, t3x, t3y, t3z, uMax, v0, v1, r, g, b, a, o, rotateUv)
    }

    private fun emitPrismSide(
        vc: VertexConsumer, pose: PoseStack.Pose,
        ax: Float, ay: Float, az: Float,
        bx: Float, by: Float, bz: Float,
        cx: Float, cy: Float, cz: Float,
        dx: Float, dy: Float, dz: Float,
        uMax: Float, v0: Float, v1: Float,
        r: Int, g: Int, b: Int, a: Int,
        o: Int,
        rotateUv: Boolean = false,
    ) {
        // [rotateUv] swaps U and V so the texture's "horizontal" axis runs
        // along the beam length - used by the hazard texture whose stripes
        // need to read along the beam, not across.
        val aU: Float; val aV: Float; val bU: Float; val bV: Float
        val cU: Float; val cV: Float; val dU: Float; val dV: Float
        if (rotateUv) {
            aU = v0;   aV = 0f
            bU = v0;   bV = uMax
            cU = v1;   cV = uMax
            dU = v1;   dV = 0f
        } else {
            aU = 0f;   aV = v0
            bU = uMax; bV = v0
            cU = uMax; cV = v1
            dU = 0f;   dV = v1
        }
        vc.addVertex(pose, ax, ay, az).setUv(aU, aV).setColor(r, g, b, a).setOverlay(o).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
        vc.addVertex(pose, bx, by, bz).setUv(bU, bV).setColor(r, g, b, a).setOverlay(o).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
        vc.addVertex(pose, cx, cy, cz).setUv(cU, cV).setColor(r, g, b, a).setOverlay(o).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
        vc.addVertex(pose, dx, dy, dz).setUv(dU, dV).setColor(r, g, b, a).setOverlay(o).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
        vc.addVertex(pose, bx, by, bz).setUv(bU, bV).setColor(r, g, b, a).setOverlay(o).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
        vc.addVertex(pose, ax, ay, az).setUv(aU, aV).setColor(r, g, b, a).setOverlay(o).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
        vc.addVertex(pose, dx, dy, dz).setUv(dU, dV).setColor(r, g, b, a).setOverlay(o).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
        vc.addVertex(pose, cx, cy, cz).setUv(cU, cV).setColor(r, g, b, a).setOverlay(o).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
    }

    /** Camera-facing billboard rectangle along the beam axis. */
    private fun renderBillboardBeam(
        vc: VertexConsumer,
        pose: PoseStack.Pose,
        camLocalX: Float, camLocalY: Float, camLocalZ: Float,
        fromX: Float, fromY: Float, fromZ: Float,
        toX: Float, toY: Float, toZ: Float,
        time: Float,
        r: Int, g: Int, b: Int, a: Int,
        width: Float,
        rotateUv: Boolean = false,
    ) {
        val dx = toX - fromX; val dy = toY - fromY; val dz = toZ - fromZ
        val len = sqrt(dx * dx + dy * dy + dz * dz)
        if (len < 0.01f) return
        val dirX = dx / len; val dirY = dy / len; val dirZ = dz / len

        val midX = (fromX + toX) / 2f
        val midY = (fromY + toY) / 2f
        val midZ = (fromZ + toZ) / 2f
        val toCamX = camLocalX - midX
        val toCamY = camLocalY - midY
        val toCamZ = camLocalZ - midZ

        var px = dirY * toCamZ - dirZ * toCamY
        var py = dirZ * toCamX - dirX * toCamZ
        var pz = dirX * toCamY - dirY * toCamX
        val plen = sqrt(px * px + py * py + pz * pz)
        if (plen < 0.001f) return
        val hw = width / 2f
        px = px / plen * hw; py = py / plen * hw; pz = pz / plen * hw

        val uMax = 5f / 16f
        val uvScroll = time * BEAM_SCROLL_SPEED
        val v0 = uvScroll
        val v1 = uvScroll + len * 0.5f
        val o = OverlayTexture.NO_OVERLAY

        val fU0: Float; val fV0: Float; val fU1: Float; val fV1: Float
        val tU0: Float; val tV0: Float; val tU1: Float; val tV1: Float
        if (rotateUv) {
            fU0 = v0;   fV0 = 0f;    fU1 = v0;   fV1 = uMax
            tU0 = v1;   tV0 = uMax;  tU1 = v1;   tV1 = 0f
        } else {
            fU0 = 0f;   fV0 = v0;    fU1 = uMax; fV1 = v0
            tU0 = uMax; tV0 = v1;    tU1 = 0f;   tV1 = v1
        }

        // Front face.
        vc.addVertex(pose, fromX - px, fromY - py, fromZ - pz)
            .setUv(fU0, fV0).setColor(r, g, b, a).setOverlay(o).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
        vc.addVertex(pose, fromX + px, fromY + py, fromZ + pz)
            .setUv(fU1, fV1).setColor(r, g, b, a).setOverlay(o).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
        vc.addVertex(pose, toX + px, toY + py, toZ + pz)
            .setUv(tU0, tV0).setColor(r, g, b, a).setOverlay(o).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
        vc.addVertex(pose, toX - px, toY - py, toZ - pz)
            .setUv(tU1, tV1).setColor(r, g, b, a).setOverlay(o).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
        // Back face.
        vc.addVertex(pose, fromX + px, fromY + py, fromZ + pz)
            .setUv(fU1, fV1).setColor(r, g, b, a).setOverlay(o).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
        vc.addVertex(pose, fromX - px, fromY - py, fromZ - pz)
            .setUv(fU0, fV0).setColor(r, g, b, a).setOverlay(o).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
        vc.addVertex(pose, toX - px, toY - py, toZ - pz)
            .setUv(tU1, tV1).setColor(r, g, b, a).setOverlay(o).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
        vc.addVertex(pose, toX + px, toY + py, toZ + pz)
            .setUv(tU0, tV0).setColor(r, g, b, a).setOverlay(o).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
    }
}
