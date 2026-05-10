package damien.nodeworks.render

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import damien.nodeworks.network.NetworkSettingsRegistry
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.rendertype.RenderType
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.resources.Identifier
import net.minecraft.world.phys.Vec3
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * "Laser inside the pipe network" renderer. Both [PipeRenderer] and
 * [NodeRenderer] feed this with their connected-direction set, the network
 * tint, and the laser mode pulled from [NetworkSettingsRegistry].
 *
 * Per-direction half-beams run from the block centre out to the face
 * boundary. Each beam renders in one of two styles:
 *
 *  * **Fancy** ([NetworkSettingsRegistry.LASER_MODE_FANCY]): the original
 *    pre-pipe-refactor look. A rotating opaque 4-sided prism core (white,
 *    UV-scrolled) wrapped in a camera-billboarded translucent outer glow
 *    (network-tinted, sin-pulsing alpha + width). Submitted as two render
 *    passes (opaque + translucent).
 *  * **Fast** ([NetworkSettingsRegistry.LASER_MODE_FAST]): a single thin
 *    camera-billboarded quad per beam. No animation, no glow, no prism.
 *
 * Plus an optional white centre-core cube via [PipeLaserCoreRenderType] to
 * mask the seam where multiple half-beams meet at the block centre.
 */
object PipeLaserBeam {

    private val LASER_TEXTURE: Identifier =
        Identifier.fromNamespaceAndPath("nodeworks", "textures/block/laser_trail.png")
    /** Hazard-stripe texture for micro-networks anchored by Processing
     *  Handlers. Used only on the prism core, not the outer glow. */
    private val MICRO_TEXTURE: Identifier =
        Identifier.fromNamespaceAndPath("nodeworks", "textures/block/hazard_stripes.png")

    /** Translucent (back-faces visible) and opaque variants of the beacon-beam
     *  render type. The fancy outer glow uses translucent for the soft halo,
     *  the prism core uses opaque so the rotating geometry reads as solid. */
    private val TRANSLUCENT_TYPE: RenderType = RenderTypes.beaconBeam(LASER_TEXTURE, true)
    private val OPAQUE_TYPE: RenderType = RenderTypes.beaconBeam(LASER_TEXTURE, false)
    private val MICRO_OPAQUE_TYPE: RenderType = RenderTypes.beaconBeam(MICRO_TEXTURE, false)
    private val MICRO_TRANSLUCENT_TYPE: RenderType = RenderTypes.beaconBeam(MICRO_TEXTURE, true)

    /** Glow color used around the prism on micro-networks. Solid yellow so
     *  the halo reads as a hazard-stripe accent without competing with the
     *  prism's hazard texture. (#FFDC3B) */
    private const val MICRO_GLOW_R = 0xFF
    private const val MICRO_GLOW_G = 0xDC
    private const val MICRO_GLOW_B = 0x3B

    /** Reference width in blocks. The fancy outer glow scales it by
     *  [GLOW_WIDTH_FACTOR] × `sizePulse`, the prism core by
     *  [PRISM_WIDTH_FACTOR], fast mode by [FAST_WIDTH_FACTOR]. */
    private const val BEAM_WIDTH = 1f / 16f

    /** UV-V scroll rate along the beam length (units / sec). Faster = the
     *  streak texture flows past more visibly. */
    private const val BEAM_SCROLL_SPEED = 0.4f

    /** Prism core width as a multiple of [BEAM_WIDTH]. */
    private const val PRISM_WIDTH_FACTOR = 0.5f

    /** Fast-mode beam width as a multiple of [BEAM_WIDTH]. */
    private const val FAST_WIDTH_FACTOR = 0.75f

    // -------- Fancy outer-glow tuning --------

    /** Base width of the fancy outer glow as a multiple of [BEAM_WIDTH]. The
     *  rendered width sweeps `[GLOW_WIDTH_BASE × GLOW_SIZE_MIN,
     *  GLOW_WIDTH_BASE × GLOW_SIZE_MAX]` over the pulse cycle. */
    private const val GLOW_WIDTH_FACTOR = 3.5f

    /** Size pulse range: `sizePulse` sweeps between these two each cycle.
     *  Set both to the same value to disable the breathing effect. */
    private const val GLOW_SIZE_MIN = 0.85f
    private const val GLOW_SIZE_MAX = 1.20f

    /** Alpha pulse range, in 0..255 vertex-alpha units. The colored billboard
     *  sweeps between min and max each cycle. Lower min = more "off"
     *  feel between pulses, higher max = brighter peak. Top-end is 255. */
    // private const val GLOW_ALPHA_MIN = 36
    // private const val GLOW_ALPHA_MAX = 156
    private const val GLOW_ALPHA_MIN = 115
    private const val GLOW_ALPHA_MAX = 175

    /** Pulse cycle frequency in radians per second (sin argument is
     *  `time * GLOW_PULSE_FREQ`). 2.0 ≈ one full cycle every ~3 seconds. */
    private const val GLOW_PULSE_FREQ = 2.0

    /** Centre-cube half-extent in block units. 1px (= 2px cube) covers the
     *  half-beams' centre-side ends. */
    private const val CORE_HALF = 1f / 16f

    private const val GLOW_SIZE_AMP = (GLOW_SIZE_MAX - GLOW_SIZE_MIN) / 2f
    private const val GLOW_SIZE_MID = (GLOW_SIZE_MAX + GLOW_SIZE_MIN) / 2f
    private const val GLOW_ALPHA_AMP = (GLOW_ALPHA_MAX - GLOW_ALPHA_MIN) / 2f
    private const val GLOW_ALPHA_MID = (GLOW_ALPHA_MAX + GLOW_ALPHA_MIN) / 2f

    fun submit(
        poseStack: PoseStack,
        collector: SubmitNodeCollector,
        blockPos: BlockPos,
        cameraPos: Vec3,
        directions: Set<Direction>,
        color: Int,
        laserMode: Int,
        drawCenterCore: Boolean,
        isMicro: Boolean = false,
    ) {
        if (directions.isEmpty()) return
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        val time = (System.currentTimeMillis() % 100_000) / 1000f
        val phase = sin(time * GLOW_PULSE_FREQ).toFloat()
        val glowAlpha = (phase * GLOW_ALPHA_AMP + GLOW_ALPHA_MID).toInt().coerceIn(0, 255)
        val sizePulse = phase * GLOW_SIZE_AMP + GLOW_SIZE_MID

        // Camera in block-local coords. The PoseStack is already translated
        // to the block origin so vertex math stays in 0..1 space.
        val camLocalX = (cameraPos.x - blockPos.x).toFloat()
        val camLocalY = (cameraPos.y - blockPos.y).toFloat()
        val camLocalZ = (cameraPos.z - blockPos.z).toFloat()

        // Prism uses the hazard texture on micros, standard streak otherwise.
        val opaqueType = if (isMicro) MICRO_OPAQUE_TYPE else OPAQUE_TYPE
        // Glow always uses the standard streak texture - the hazard pattern
        // would compete with the prism's pattern. On micros we tint it yellow
        // so the halo still reads as a hazard accent.
        val glowR = if (isMicro) MICRO_GLOW_R else r
        val glowG = if (isMicro) MICRO_GLOW_G else g
        val glowB = if (isMicro) MICRO_GLOW_B else b

        if (laserMode == NetworkSettingsRegistry.LASER_MODE_FANCY) {
            // Pass 1: opaque rotating white prism core, one per direction.
            // Micros rotate the UV 90° so the hazard stripes run along the
            // beam instead of perpendicular.
            collector.submitCustomGeometry(poseStack, opaqueType) { pose, vc ->
                for (dir in directions) {
                    val (sx, sy, sz, ex, ey, ez) = halfBeamEndpoints(dir)
                    renderPrismBeam(
                        vc, pose, dir, blockPos,
                        sx, sy, sz, ex, ey, ez,
                        time,
                        255, 255, 255, 255,
                        BEAM_WIDTH * PRISM_WIDTH_FACTOR,
                        rotateUv = isMicro,
                    )
                }
            }
            // Pass 2: translucent network-tinted billboarded glow (yellow on micro).
            // Glow always uses the streak texture - no UV rotation needed.
            collector.submitCustomGeometry(poseStack, TRANSLUCENT_TYPE) { pose, vc ->
                for (dir in directions) {
                    val (sx, sy, sz, ex, ey, ez) = halfBeamEndpoints(dir)
                    renderBillboardBeam(
                        vc, pose,
                        camLocalX, camLocalY, camLocalZ,
                        sx, sy, sz, ex, ey, ez,
                        time,
                        glowR, glowG, glowB, glowAlpha,
                        BEAM_WIDTH * GLOW_WIDTH_FACTOR * sizePulse,
                        dir = dir,
                        blockPos = blockPos,
                    )
                }
            }
        } else {
            // Fast: one thin billboarded quad per direction, no animation.
            // Micros use the hazard texture on the billboard with white tint
            // and rotated UV so stripes run along the beam; standard mode
            // uses the streak texture tinted with the network color.
            val fastType = if (isMicro) MICRO_TRANSLUCENT_TYPE else TRANSLUCENT_TYPE
            val fastR = if (isMicro) 255 else r
            val fastG = if (isMicro) 255 else g
            val fastB = if (isMicro) 255 else b
            collector.submitCustomGeometry(poseStack, fastType) { pose, vc ->
                for (dir in directions) {
                    val (sx, sy, sz, ex, ey, ez) = halfBeamEndpoints(dir)
                    renderBillboardBeam(
                        vc, pose,
                        camLocalX, camLocalY, camLocalZ,
                        sx, sy, sz, ex, ey, ez,
                        time,
                        fastR, fastG, fastB, 220,
                        BEAM_WIDTH * FAST_WIDTH_FACTOR,
                        dir = dir,
                        blockPos = blockPos,
                        rotateUv = isMicro,
                    )
                }
            }
        }

        if (drawCenterCore) {
            // Yellow centre cube on micros so the junction-mask reads as part
            // of the same hazard-themed beam; standard nets keep white.
            val coreR = if (isMicro) MICRO_GLOW_R else 255
            val coreG = if (isMicro) MICRO_GLOW_G else 255
            val coreB = if (isMicro) MICRO_GLOW_B else 255
            collector.submitCustomGeometry(poseStack, PipeLaserCoreRenderType.RENDER_TYPE) { pose, vc ->
                emitCenterCube(pose, vc, coreR, coreG, coreB)
            }
        }
    }

    /**
     * Single-direction laser stub from block centre toward [dir] for
     * [halfLength] block units, instead of the regular half-beam's full 0.5
     * to the face boundary. Used by directional devices like the User to
     * surface network connectivity inside the block body without painting a
     * full pipe-half beam to the face. The cross-section width and the
     * fancy / fast / hazard-on-micro treatments match [submit] exactly so
     * the stub reads as a slice of the same beam family.
     */
    fun submitStub(
        poseStack: PoseStack,
        collector: SubmitNodeCollector,
        blockPos: BlockPos,
        cameraPos: Vec3,
        dir: Direction,
        color: Int,
        laserMode: Int,
        halfLength: Float,
        isMicro: Boolean = false,
    ) {
        if (halfLength <= 0f) return
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        val time = (System.currentTimeMillis() % 100_000) / 1000f
        val phase = sin(time * GLOW_PULSE_FREQ).toFloat()
        val glowAlpha = (phase * GLOW_ALPHA_AMP + GLOW_ALPHA_MID).toInt().coerceIn(0, 255)
        val sizePulse = phase * GLOW_SIZE_AMP + GLOW_SIZE_MID

        val camLocalX = (cameraPos.x - blockPos.x).toFloat()
        val camLocalY = (cameraPos.y - blockPos.y).toFloat()
        val camLocalZ = (cameraPos.z - blockPos.z).toFloat()

        val sx = 0.5f
        val sy = 0.5f
        val sz = 0.5f
        val ex = 0.5f + dir.stepX * halfLength
        val ey = 0.5f + dir.stepY * halfLength
        val ez = 0.5f + dir.stepZ * halfLength

        val opaqueType = if (isMicro) MICRO_OPAQUE_TYPE else OPAQUE_TYPE
        val glowR = if (isMicro) MICRO_GLOW_R else r
        val glowG = if (isMicro) MICRO_GLOW_G else g
        val glowB = if (isMicro) MICRO_GLOW_B else b

        if (laserMode == NetworkSettingsRegistry.LASER_MODE_FANCY) {
            collector.submitCustomGeometry(poseStack, opaqueType) { pose, vc ->
                renderPrismBeam(
                    vc, pose, dir, blockPos,
                    sx, sy, sz, ex, ey, ez,
                    time,
                    255, 255, 255, 255,
                    BEAM_WIDTH * PRISM_WIDTH_FACTOR,
                    rotateUv = isMicro,
                )
            }
            collector.submitCustomGeometry(poseStack, TRANSLUCENT_TYPE) { pose, vc ->
                renderBillboardBeam(
                    vc, pose,
                    camLocalX, camLocalY, camLocalZ,
                    sx, sy, sz, ex, ey, ez,
                    time,
                    glowR, glowG, glowB, glowAlpha,
                    BEAM_WIDTH * GLOW_WIDTH_FACTOR * sizePulse,
                    dir = dir,
                    blockPos = blockPos,
                )
            }
        } else {
            val fastType = if (isMicro) MICRO_TRANSLUCENT_TYPE else TRANSLUCENT_TYPE
            val fastR = if (isMicro) 255 else r
            val fastG = if (isMicro) 255 else g
            val fastB = if (isMicro) 255 else b
            collector.submitCustomGeometry(poseStack, fastType) { pose, vc ->
                renderBillboardBeam(
                    vc, pose,
                    camLocalX, camLocalY, camLocalZ,
                    sx, sy, sz, ex, ey, ez,
                    time,
                    fastR, fastG, fastB, 220,
                    BEAM_WIDTH * FAST_WIDTH_FACTOR,
                    dir = dir,
                    blockPos = blockPos,
                    rotateUv = isMicro,
                )
            }
        }
    }

    /** Half-beam endpoints in block-local coords: centre to face boundary. */
    private fun halfBeamEndpoints(dir: Direction): SixFloats = SixFloats(
        0.5f, 0.5f, 0.5f,
        0.5f + dir.stepX * 0.5f, 0.5f + dir.stepY * 0.5f, 0.5f + dir.stepZ * 0.5f,
    )

    /** World-space coordinate along [axis] for a block-local point. Used to
     *  align the V scroll across adjacent pipes so a straight run reads as
     *  one continuous moving texture. */
    private fun worldAxisCoord(axis: Direction.Axis, blockPos: BlockPos, lx: Float, ly: Float, lz: Float): Float =
        when (axis) {
            Direction.Axis.X -> blockPos.x + lx
            Direction.Axis.Y -> blockPos.y + ly
            Direction.Axis.Z -> blockPos.z + lz
        }

    private data class SixFloats(
        val a: Float, val b: Float, val c: Float,
        val d: Float, val e: Float, val f: Float,
    )

    /** Solid rotating 4-sided rectangular prism along the beam axis. Ported
     *  from the pre-pipe-refactor `ConnectionBeamRenderer`, with the basis
     *  derivation flipped to depend on the beam's **axis** rather than its
     *  signed direction. Two adjacent pipes' half-beams (one EAST, one WEST)
     *  share the same XYZ axis but opposite step vectors, so the original
     *  `dir × ref` formula gave them mirrored bases and the prisms spun out
     *  of phase across the joint. Axis-keyed perpendiculars are identical
     *  on both sides, so the joint reads as one continuous rotating prism. */
    private fun renderPrismBeam(
        vc: VertexConsumer,
        pose: PoseStack.Pose,
        dir: Direction,
        blockPos: BlockPos,
        fromX: Float, fromY: Float, fromZ: Float,
        toX: Float, toY: Float, toZ: Float,
        time: Float,
        r: Int, g: Int, b: Int, a: Int,
        width: Float,
        rotateUv: Boolean = false,
    ) {
        val dx = toX - fromX;
        val dy = toY - fromY;
        val dz = toZ - fromZ
        val len = sqrt(dx * dx + dy * dy + dz * dz)
        if (len < 0.01f) return

        // Axis-keyed perpendicular basis. (a1, a2) span the plane normal
        // to [dir]'s axis, picked so opposite directions on the same axis
        // share identical bases. That's what keeps adjacent pipes' prisms
        // rotating in phase across the joint.
        var a1x: Float;
        var a1y: Float;
        var a1z: Float
        var a2x: Float;
        var a2y: Float;
        var a2z: Float
        when (dir.axis) {
            Direction.Axis.X -> {
                a1x = 0f; a1y = 1f; a1z = 0f; a2x = 0f; a2y = 0f; a2z = 1f
            }

            Direction.Axis.Y -> {
                a1x = 1f; a1y = 0f; a1z = 0f; a2x = 0f; a2y = 0f; a2z = 1f
            }

            Direction.Axis.Z -> {
                a1x = 1f; a1y = 0f; a1z = 0f; a2x = 0f; a2y = 1f; a2z = 0f
            }
        }

        // Rotate the basis around the beam axis by [time], gives the prism
        // its slow spin. cos/sin is shared across all renderers via the
        // wall-clock-derived [time] so every Pipe / Node in the chunk sees
        // the same orientation each frame.
        val angle = time * 1.0f
        val ca = cos(angle);
        val sa = sin(angle)
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
        // Anchor V to the WORLD axis coord so adjacent pipes' half-beams
        // line up at the joint - otherwise each pipe scrolls its own local
        // 0..0.25 V range and the texture pattern visibly seams every block.
        // The 0.5 scale matches the previous local-V density (one block of
        // beam spans 0.5 V, i.e. half a texture repeat at unit-tall textures).
        // Negative uvScroll term makes the texture flow in the +axis direction
        // over time; both halves of a junction inherit the same world V at
        // the shared face so the pattern is continuous across the joint.
        val uvScroll = time * BEAM_SCROLL_SPEED
        val v0 = 0.5f * worldAxisCoord(dir.axis, blockPos, fromX, fromY, fromZ) - uvScroll
        val v1 = 0.5f * worldAxisCoord(dir.axis, blockPos, toX, toY, toZ) - uvScroll
        val o = OverlayTexture.NO_OVERLAY

        // 4 corners of the prism's `from` and `to` cross-sections, named by
        // sign of (a1, a2): f0=(--), f1=(+-), f2=(++), f3=(-+).
        val f0x = fromX - a1x * hw - a2x * hw;
        val f0y = fromY - a1y * hw - a2y * hw;
        val f0z = fromZ - a1z * hw - a2z * hw
        val f1x = fromX + a1x * hw - a2x * hw;
        val f1y = fromY + a1y * hw - a2y * hw;
        val f1z = fromZ + a1z * hw - a2z * hw
        val f2x = fromX + a1x * hw + a2x * hw;
        val f2y = fromY + a1y * hw + a2y * hw;
        val f2z = fromZ + a1z * hw + a2z * hw
        val f3x = fromX - a1x * hw + a2x * hw;
        val f3y = fromY - a1y * hw + a2y * hw;
        val f3z = fromZ - a1z * hw + a2z * hw
        val t0x = toX - a1x * hw - a2x * hw;
        val t0y = toY - a1y * hw - a2y * hw;
        val t0z = toZ - a1z * hw - a2z * hw
        val t1x = toX + a1x * hw - a2x * hw;
        val t1y = toY + a1y * hw - a2y * hw;
        val t1z = toZ + a1z * hw - a2z * hw
        val t2x = toX + a1x * hw + a2x * hw;
        val t2y = toY + a1y * hw + a2y * hw;
        val t2z = toZ + a1z * hw + a2z * hw
        val t3x = toX - a1x * hw + a2x * hw;
        val t3y = toY - a1y * hw + a2y * hw;
        val t3z = toZ - a1z * hw + a2z * hw

        // Each side is double-sided: front quad, then back quad with reverse
        // winding so the prism reads from any angle.
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
        // Default mapping has U=width (a→b) and V=length (a→d). [rotateUv]
        // swaps those so the texture's natural "horizontal" axis runs along
        // the beam length - used by the hazard-stripe variant whose source
        // image has stripes that need to read along the beam, not across.
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
        // Front winding.
        vc.addVertex(pose, ax, ay, az).setUv(aU, aV).setColor(r, g, b, a).setOverlay(o).setUv2(240, 240)
            .setNormal(pose, 0f, 1f, 0f)
        vc.addVertex(pose, bx, by, bz).setUv(bU, bV).setColor(r, g, b, a).setOverlay(o).setUv2(240, 240)
            .setNormal(pose, 0f, 1f, 0f)
        vc.addVertex(pose, cx, cy, cz).setUv(cU, cV).setColor(r, g, b, a).setOverlay(o).setUv2(240, 240)
            .setNormal(pose, 0f, 1f, 0f)
        vc.addVertex(pose, dx, dy, dz).setUv(dU, dV).setColor(r, g, b, a).setOverlay(o).setUv2(240, 240)
            .setNormal(pose, 0f, 1f, 0f)
        // Back winding so back-face culling doesn't drop the side when viewed
        // from the prism's interior (the rotating prism shows both faces).
        vc.addVertex(pose, bx, by, bz).setUv(bU, bV).setColor(r, g, b, a).setOverlay(o).setUv2(240, 240)
            .setNormal(pose, 0f, 1f, 0f)
        vc.addVertex(pose, ax, ay, az).setUv(aU, aV).setColor(r, g, b, a).setOverlay(o).setUv2(240, 240)
            .setNormal(pose, 0f, 1f, 0f)
        vc.addVertex(pose, dx, dy, dz).setUv(dU, dV).setColor(r, g, b, a).setOverlay(o).setUv2(240, 240)
            .setNormal(pose, 0f, 1f, 0f)
        vc.addVertex(pose, cx, cy, cz).setUv(cU, cV).setColor(r, g, b, a).setOverlay(o).setUv2(240, 240)
            .setNormal(pose, 0f, 1f, 0f)
    }

    /** Camera-facing billboard rectangle along the beam axis. Used for the
     *  fancy-mode outer glow and the entirety of fast mode. */
    private fun renderBillboardBeam(
        vc: VertexConsumer,
        pose: PoseStack.Pose,
        camLocalX: Float, camLocalY: Float, camLocalZ: Float,
        fromX: Float, fromY: Float, fromZ: Float,
        toX: Float, toY: Float, toZ: Float,
        time: Float,
        r: Int, g: Int, b: Int, a: Int,
        width: Float,
        dir: Direction,
        blockPos: BlockPos,
        rotateUv: Boolean = false,
    ) {
        val dx = toX - fromX;
        val dy = toY - fromY;
        val dz = toZ - fromZ
        val len = sqrt(dx * dx + dy * dy + dz * dz)
        if (len < 0.01f) return
        val dirX = dx / len;
        val dirY = dy / len;
        val dirZ = dz / len

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
        val flow = if (dir == Direction.SOUTH || dir == Direction.EAST || dir == Direction.UP) -1f else 1f
        val v0 = uvScroll
        val v1 = uvScroll + flow * len * 0.5f
        val o = OverlayTexture.NO_OVERLAY

        // U/V mapping: default is U=width, V=length. [rotateUv] swaps so the
        // texture's "horizontal" axis runs along the beam (used by hazard
        // stripes whose pattern needs to read along the beam in fast mode).
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

    /** Solid cube of half-extent [CORE_HALF] centred at (0.5, 0.5, 0.5).
     *  POSITION_COLOR vertex format (no UV, no normal) since
     *  [PipeLaserCoreRenderType] uses DEBUG_FILLED_SNIPPET. */
    private fun emitCenterCube(pose: PoseStack.Pose, vc: VertexConsumer, r: Int, g: Int, b: Int) {
        val mn = 0.5f - CORE_HALF
        val mx = 0.5f + CORE_HALF
        val a = 255
        // +Z
        vc.addVertex(pose, mx, mn, mx).setColor(r, g, b, a)
        vc.addVertex(pose, mx, mx, mx).setColor(r, g, b, a)
        vc.addVertex(pose, mn, mx, mx).setColor(r, g, b, a)
        vc.addVertex(pose, mn, mn, mx).setColor(r, g, b, a)
        // -Z
        vc.addVertex(pose, mn, mn, mn).setColor(r, g, b, a)
        vc.addVertex(pose, mn, mx, mn).setColor(r, g, b, a)
        vc.addVertex(pose, mx, mx, mn).setColor(r, g, b, a)
        vc.addVertex(pose, mx, mn, mn).setColor(r, g, b, a)
        // +X
        vc.addVertex(pose, mx, mn, mn).setColor(r, g, b, a)
        vc.addVertex(pose, mx, mx, mn).setColor(r, g, b, a)
        vc.addVertex(pose, mx, mx, mx).setColor(r, g, b, a)
        vc.addVertex(pose, mx, mn, mx).setColor(r, g, b, a)
        // -X
        vc.addVertex(pose, mn, mn, mx).setColor(r, g, b, a)
        vc.addVertex(pose, mn, mx, mx).setColor(r, g, b, a)
        vc.addVertex(pose, mn, mx, mn).setColor(r, g, b, a)
        vc.addVertex(pose, mn, mn, mn).setColor(r, g, b, a)
        // +Y
        vc.addVertex(pose, mn, mx, mx).setColor(r, g, b, a)
        vc.addVertex(pose, mx, mx, mx).setColor(r, g, b, a)
        vc.addVertex(pose, mx, mx, mn).setColor(r, g, b, a)
        vc.addVertex(pose, mn, mx, mn).setColor(r, g, b, a)
        // -Y
        vc.addVertex(pose, mn, mn, mn).setColor(r, g, b, a)
        vc.addVertex(pose, mx, mn, mn).setColor(r, g, b, a)
        vc.addVertex(pose, mx, mn, mx).setColor(r, g, b, a)
        vc.addVertex(pose, mn, mn, mx).setColor(r, g, b, a)
    }
}
