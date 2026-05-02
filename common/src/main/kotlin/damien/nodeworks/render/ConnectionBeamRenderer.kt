package damien.nodeworks.render

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import damien.nodeworks.network.Connectable
import damien.nodeworks.network.NetworkSettingsRegistry
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.core.BlockPos
import net.minecraft.resources.Identifier
import net.minecraft.world.phys.AABB
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Per-BlockEntity laser rendering for the network-connection beams between nodes, controllers,
 * terminals, and other [Connectable]s.
 *
 * Every [Connectable] block entity whose renderer delegates to this helper emits beams for
 * connections where its own position is the "lower" endpoint (see [isLessThan]), each
 * connection pair thus renders exactly once despite both ends independently extracting their
 * own beam list. Each participating BER should also return `true` from `shouldRenderOffScreen`
 * so 26.1's frustum culler keeps the BER alive while its outgoing beams are in frame.
 *
 * The original laser rendering lived in [NodeConnectionRenderer] as a world-level
 * `RenderLevelStageEvent` subscription, moving it into per-BE BERs means GuideME scene
 * renders (and any other non-main-world render pass that dispatches through BERs) also get
 * the lasers. The world-level renderer still owns LOS cache maintenance, reachability
 * tracking, selection/pin highlights, and monitor-count text overlay.
 */
object ConnectionBeamRenderer {

    /** Texture used for both the scrolling prism core and the billboarded outer glow. */
    private val LASER_TEXTURE = Identifier.fromNamespaceAndPath("nodeworks", "textures/block/laser_trail.png")

    /** Beam width in blocks (pixels / 16). */
    private const val BEAM_WIDTH = 1.0f / 16f
    private const val BEAM_SCROLL_SPEED = 0.8f

    /**
     * Pre-extracted render data for a single beam. All coordinates are **block-relative**
     * (target offset from the source node in block units) so the BER's pose transform,
     * already centered on the source block, needs no extra translation to render.
     *
     * [mode] follows the source network's render style (fancy / fast). Stored per beam
     * to mirror the per-beam color fields, [submit] partitions on it.
     */
    data class Beam(
        val toDx: Float,
        val toDy: Float,
        val toDz: Float,
        val r: Int,
        val g: Int,
        val b: Int,
        val blocked: Boolean,
        val mode: Int = NetworkSettingsRegistry.LASER_MODE_FANCY,
    )

    /** Canonical keys (min(a,b), max(a,b) hash) for beam pairs already drawn in the current
     *  render frame. First BER to submit a given pair wins the draw, every later BER that
     *  extracts the *same* pair skips it. Cleared at the top of each frame by [startFrame].
     *
     *  Why this over the old "extract only from the lex-lower end" rule: lex-dedup meant a
     *  single forgotten BER wiring (as happened with Monitor) or an unloaded lower endpoint
     *  would silently drop a beam. Now both endpoints always extract, so as long as *either*
     *  BER runs, the beam renders, no silent failures. */
    private val drawnThisFrame = HashSet<Long>()

    /** Called by the platform layer once per render frame, before any BER submits. Neoforge
     *  wires this through `RenderFrameEvent.Pre`, see `NeoForgeClientSetup`. */
    fun startFrame() {
        drawnThisFrame.clear()
    }

    /**
     * Collect the beams this [Connectable] should render this frame. Called from each
     * BER's `extractRenderState`. Emits every outgoing connection, dedup is deferred
     * to [submit] via the frame-scoped [drawnThisFrame] set, so missing a BER wiring or
     * having one endpoint in an unloaded chunk never drops a beam as long as the other
     * endpoint renders.
     */
    fun extract(connectable: Connectable): List<Beam> {
        val be = connectable as? net.minecraft.world.level.block.entity.BlockEntity ?: return emptyList()
        val level = be.level ?: return emptyList()
        val myPos = be.blockPos

        // Per-network laser toggles. When the source network has lasers disabled,
        // skip the entire extract: no beam allocations, no dedup bookkeeping.
        // Nodes + glows render through a separate path (NodeConnectionRenderer)
        // so they remain visible.
        val networkId = connectable.networkId
        val mySettings = NetworkSettingsRegistry.get(networkId)
        if (networkId != null && !mySettings.laserEnabled) return emptyList()
        val laserMode = mySettings.laserMode
        val myNetworkColor = if (networkId != null) mySettings.color
            else NodeConnectionRenderer.DEFAULT_NETWORK_COLOR

        // The LOS cache + reachability BFS that drives [blocked] only run against the main
        // client level via NodeConnectionRenderer's world-render hook, GuideME scenes and
        // any other offscreen preview pass render BEs from a different level where that
        // cache is never populated, which would flip every beam to the blocked-red style.
        // Short-circuit the gate for non-main-world renders so preview scenes always show
        // beams as fully connected.
        val isMainWorld = level === Minecraft.getInstance().level

        val beams = mutableListOf<Beam>()
        for (targetPos in connectable.getConnections()) {
            val targetBe = level.getBlockEntity(targetPos) as? Connectable ?: continue

            val pairColor = when {
                // Connected networks share a color, pick the known one.
                connectable.networkId != null -> myNetworkColor
                targetBe.networkId != null -> NetworkSettingsRegistry.getColor(targetBe.networkId!!)
                else -> NodeConnectionRenderer.DEFAULT_NETWORK_COLOR
            }

            val blocked = isMainWorld && (
                NodeConnectionRenderer.isConnectionBlocked(myPos, targetPos) ||
                    !NodeConnectionRenderer.isReachable(myPos) ||
                    !NodeConnectionRenderer.isReachable(targetPos)
                )

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
                )
            )
        }
        return beams
    }

    /**
     * Emit geometry for each extracted [Beam]. Call from BER `submit` right after any
     * existing `submitCustomGeometry` calls, we batch all beams into one opaque + one
     * translucent render-type to minimise pipeline switches.
     *
     * `blockWorldPos` is the source BE's world position, `camWorld` is the camera's
     * world-space position (take from [CameraRenderState.pos]). Together they translate
     * the camera into the BER's block-relative coordinate space for billboard math.
     */
    fun submit(
        beams: List<Beam>,
        poseStack: PoseStack,
        submitNodeCollector: SubmitNodeCollector,
        blockWorldPos: BlockPos,
        camWorld: net.minecraft.world.phys.Vec3,
    ) {
        if (beams.isEmpty()) return

        // Claim each beam in the frame-scoped dedup set. A beam claimed by an earlier BER
        // this frame is filtered out here, only the first submitter draws it. Using the
        // canonical (sorted) pair key means it doesn't matter which end extracted first.
        val active = ArrayList<Beam>(beams.size)
        for (beam in beams) {
            val tx = blockWorldPos.x + beam.toDx.toInt()
            val ty = blockWorldPos.y + beam.toDy.toInt()
            val tz = blockWorldPos.z + beam.toDz.toInt()
            val targetPos = BlockPos(tx, ty, tz)
            val key = connectionKey(blockWorldPos, targetPos)
            if (drawnThisFrame.add(key)) active.add(beam)
        }
        if (active.isEmpty()) return

        val time = (System.currentTimeMillis() % 100_000) / 1000f
        val pulse = (sin((time * 2.0).toDouble()).toFloat() * 0.3f + 0.7f)
        val sizePulse = (sin((time * 2.0).toDouble()).toFloat() * 0.25f + 1.0f) // 0.75×–1.25×

        // Camera in block-local coords: (camWorld - thisBlockWorld). The BER's pose stack
        // is already translated to the block origin, so we don't re-apply camera position
        // in the vertex math, the GPU takes care of that via the view matrix.
        val camLocalX = (camWorld.x - blockWorldPos.x).toFloat()
        val camLocalY = (camWorld.y - blockWorldPos.y).toFloat()
        val camLocalZ = (camWorld.z - blockWorldPos.z).toFloat()

        val translucentType = RenderTypes.beaconBeam(LASER_TEXTURE, true)
        val opaqueType = RenderTypes.beaconBeam(LASER_TEXTURE, false)

        // Source position is always the centre of *this* block in BER-local space.
        val fx = 0.5f; val fy = 0.5f; val fz = 0.5f

        // Split by render mode in one pass so each mode submits at most one
        // batch per render type. Avoids paying the prism + glow cost for
        // fast-mode networks and skips the fast pass entirely for fancy-mode.
        val (fastBeams, fancyBeams) = active.partition { it.mode == NetworkSettingsRegistry.LASER_MODE_FAST }

        if (fancyBeams.isNotEmpty()) {
            // Prism core (white, rotating), only for unblocked beams.
            submitNodeCollector.submitCustomGeometry(poseStack, opaqueType) { pose, vc ->
                for (beam in fancyBeams) {
                    if (beam.blocked) continue
                    val tx = fx + beam.toDx; val ty = fy + beam.toDy; val tz = fz + beam.toDz
                    renderPrismBeam(
                        vc, pose,
                        fx, fy, fz, tx, ty, tz,
                        time,
                        255, 255, 255, 255,
                        BEAM_WIDTH * 0.5f
                    )
                }
            }

            // Billboarded glow, colored pulse for unblocked, red for blocked.
            submitNodeCollector.submitCustomGeometry(poseStack, translucentType) { pose, vc ->
                for (beam in fancyBeams) {
                    val tx = fx + beam.toDx; val ty = fy + beam.toDy; val tz = fz + beam.toDz
                    if (beam.blocked) {
                        renderBillboardBeam(
                            vc, pose,
                            camLocalX, camLocalY, camLocalZ,
                            fx, fy, fz, tx, ty, tz,
                            time,
                            180, 50, 50, 80,
                            BEAM_WIDTH * 2f
                        )
                    } else {
                        val alpha = (120 * pulse).toInt().coerceIn(0, 255)
                        renderBillboardBeam(
                            vc, pose,
                            camLocalX, camLocalY, camLocalZ,
                            fx, fy, fz, tx, ty, tz,
                            time,
                            beam.r, beam.g, beam.b, alpha,
                            BEAM_WIDTH * 3.5f * sizePulse
                        )
                    }
                }
            }
        }

        if (fastBeams.isNotEmpty()) {
            // Fast mode: a single thin opaque billboard per beam, no animation,
            // no glow pulse. Reuses [renderBillboardBeam] but with a fixed alpha
            // and width so the visual reads as a flat colored line.
            submitNodeCollector.submitCustomGeometry(poseStack, translucentType) { pose, vc ->
                for (beam in fastBeams) {
                    val tx = fx + beam.toDx; val ty = fy + beam.toDy; val tz = fz + beam.toDz
                    if (beam.blocked) {
                        renderBillboardBeam(
                            vc, pose,
                            camLocalX, camLocalY, camLocalZ,
                            fx, fy, fz, tx, ty, tz,
                            time,
                            180, 50, 50, 220,
                            BEAM_WIDTH * 0.75f
                        )
                    } else {
                        renderBillboardBeam(
                            vc, pose,
                            camLocalX, camLocalY, camLocalZ,
                            fx, fy, fz, tx, ty, tz,
                            time,
                            beam.r, beam.g, beam.b, 220,
                            BEAM_WIDTH * 0.75f
                        )
                    }
                }
            }
        }
    }

    /** Canonical key that hashes (min(a,b), max(a,b)) so A↔B and B↔A collide. */
    private fun connectionKey(a: BlockPos, b: BlockPos): Long {
        val (lo, hi) = if (isLessThan(a, b)) a to b else b to a
        return lo.asLong() xor (hi.asLong() * 31)
    }

    /** AABB encompassing this Connectable's block plus every block it has a connection to.
     *  BERs use this as `getRenderBoundingBox` so 26.1's frustum culler keeps the BER alive
     *  whenever any part of an outgoing beam is visible, the default unit-cube box culls
     *  the whole BER (and thus its beams) the moment the source block leaves the frustum. */
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

    /** Lexicographic position ordering. Each connection pair has exactly one "lower" end,
     *  that end's BER is responsible for drawing the beam. */
    private fun isLessThan(a: BlockPos, b: BlockPos): Boolean {
        if (a.x != b.x) return a.x < b.x
        if (a.y != b.y) return a.y < b.y
        return a.z < b.z
    }

    // ========== Geometry helpers, ported from the pre-refactor NodeConnectionRenderer ==========

    /** Solid rotating rectangular prism along the beam axis. */
    private fun renderPrismBeam(
        vc: VertexConsumer,
        pose: PoseStack.Pose,
        fromX: Float, fromY: Float, fromZ: Float,
        toX: Float, toY: Float, toZ: Float,
        time: Float,
        r: Int, g: Int, b: Int, a: Int,
        width: Float,
    ) {
        val dx = toX - fromX; val dy = toY - fromY; val dz = toZ - fromZ
        val len = sqrt(dx * dx + dy * dy + dz * dz)
        if (len < 0.01f) return

        val dirX = dx / len; val dirY = dy / len; val dirZ = dz / len

        val refX: Float; val refY: Float; val refZ: Float
        if (kotlin.math.abs(dirY) < 0.9f) { refX = 0f; refY = 1f; refZ = 0f }
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
        val cosA = kotlin.math.cos(angle); val sinA = sin(angle)
        val r1x = a1x * cosA + a2x * sinA
        val r1y = a1y * cosA + a2y * sinA
        val r1z = a1z * cosA + a2z * sinA
        val r2x = -a1x * sinA + a2x * cosA
        val r2y = -a1y * sinA + a2y * cosA
        val r2z = -a1z * sinA + a2z * cosA
        a1x = r1x; a1y = r1y; a1z = r1z
        a2x = r2x; a2y = r2y; a2z = r2z

        val hw = width / 2f

        val uMax = 5f / 16f
        val uvScroll = time * BEAM_SCROLL_SPEED
        val v0 = uvScroll
        val v1 = uvScroll + len * 0.5f

        val overlay = OverlayTexture.NO_OVERLAY

        val f0x = fromX - a1x * hw - a2x * hw; val f0y = fromY - a1y * hw - a2y * hw; val f0z = fromZ - a1z * hw - a2z * hw
        val f1x = fromX + a1x * hw - a2x * hw; val f1y = fromY + a1y * hw - a2y * hw; val f1z = fromZ + a1z * hw - a2z * hw
        val f2x = fromX + a1x * hw + a2x * hw; val f2y = fromY + a1y * hw + a2y * hw; val f2z = fromZ + a1z * hw + a2z * hw
        val f3x = fromX - a1x * hw + a2x * hw; val f3y = fromY - a1y * hw + a2y * hw; val f3z = fromZ - a1z * hw + a2z * hw
        val t0x = toX - a1x * hw - a2x * hw; val t0y = toY - a1y * hw - a2y * hw; val t0z = toZ - a1z * hw - a2z * hw
        val t1x = toX + a1x * hw - a2x * hw; val t1y = toY + a1y * hw - a2y * hw; val t1z = toZ + a1z * hw - a2z * hw
        val t2x = toX + a1x * hw + a2x * hw; val t2y = toY + a1y * hw + a2y * hw; val t2z = toZ + a1z * hw + a2z * hw
        val t3x = toX - a1x * hw + a2x * hw; val t3y = toY - a1y * hw + a2y * hw; val t3z = toZ - a1z * hw + a2z * hw

        // Side 1 (f0→f1→t1→t0 front, then back face)
        vc.addVertex(pose, f0x, f0y, f0z).setUv(0f, v0).setColor(r, g, b, a).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
        vc.addVertex(pose, f1x, f1y, f1z).setUv(uMax, v0).setColor(r, g, b, a).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
        vc.addVertex(pose, t1x, t1y, t1z).setUv(uMax, v1).setColor(r, g, b, a).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
        vc.addVertex(pose, t0x, t0y, t0z).setUv(0f, v1).setColor(r, g, b, a).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
        vc.addVertex(pose, f1x, f1y, f1z).setUv(uMax, v0).setColor(r, g, b, a).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
        vc.addVertex(pose, f0x, f0y, f0z).setUv(0f, v0).setColor(r, g, b, a).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
        vc.addVertex(pose, t0x, t0y, t0z).setUv(0f, v1).setColor(r, g, b, a).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
        vc.addVertex(pose, t1x, t1y, t1z).setUv(uMax, v1).setColor(r, g, b, a).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
        // Side 2
        vc.addVertex(pose, f1x, f1y, f1z).setUv(0f, v0).setColor(r, g, b, a).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
        vc.addVertex(pose, f2x, f2y, f2z).setUv(uMax, v0).setColor(r, g, b, a).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
        vc.addVertex(pose, t2x, t2y, t2z).setUv(uMax, v1).setColor(r, g, b, a).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
        vc.addVertex(pose, t1x, t1y, t1z).setUv(0f, v1).setColor(r, g, b, a).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
        vc.addVertex(pose, f2x, f2y, f2z).setUv(uMax, v0).setColor(r, g, b, a).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
        vc.addVertex(pose, f1x, f1y, f1z).setUv(0f, v0).setColor(r, g, b, a).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
        vc.addVertex(pose, t1x, t1y, t1z).setUv(0f, v1).setColor(r, g, b, a).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
        vc.addVertex(pose, t2x, t2y, t2z).setUv(uMax, v1).setColor(r, g, b, a).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
        // Side 3
        vc.addVertex(pose, f2x, f2y, f2z).setUv(0f, v0).setColor(r, g, b, a).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
        vc.addVertex(pose, f3x, f3y, f3z).setUv(uMax, v0).setColor(r, g, b, a).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
        vc.addVertex(pose, t3x, t3y, t3z).setUv(uMax, v1).setColor(r, g, b, a).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
        vc.addVertex(pose, t2x, t2y, t2z).setUv(0f, v1).setColor(r, g, b, a).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
        vc.addVertex(pose, f3x, f3y, f3z).setUv(uMax, v0).setColor(r, g, b, a).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
        vc.addVertex(pose, f2x, f2y, f2z).setUv(0f, v0).setColor(r, g, b, a).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
        vc.addVertex(pose, t2x, t2y, t2z).setUv(0f, v1).setColor(r, g, b, a).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
        vc.addVertex(pose, t3x, t3y, t3z).setUv(uMax, v1).setColor(r, g, b, a).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
        // Side 4
        vc.addVertex(pose, f3x, f3y, f3z).setUv(0f, v0).setColor(r, g, b, a).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
        vc.addVertex(pose, f0x, f0y, f0z).setUv(uMax, v0).setColor(r, g, b, a).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
        vc.addVertex(pose, t0x, t0y, t0z).setUv(uMax, v1).setColor(r, g, b, a).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
        vc.addVertex(pose, t3x, t3y, t3z).setUv(0f, v1).setColor(r, g, b, a).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
        vc.addVertex(pose, f0x, f0y, f0z).setUv(uMax, v0).setColor(r, g, b, a).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
        vc.addVertex(pose, f3x, f3y, f3z).setUv(0f, v0).setColor(r, g, b, a).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
        vc.addVertex(pose, t3x, t3y, t3z).setUv(0f, v1).setColor(r, g, b, a).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
        vc.addVertex(pose, t0x, t0y, t0z).setUv(uMax, v1).setColor(r, g, b, a).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
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

        val overlay = OverlayTexture.NO_OVERLAY

        // Front face
        vc.addVertex(pose, fromX - px, fromY - py, fromZ - pz)
            .setUv(0f, v0).setColor(r, g, b, a).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
        vc.addVertex(pose, fromX + px, fromY + py, fromZ + pz)
            .setUv(uMax, v0).setColor(r, g, b, a).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
        vc.addVertex(pose, toX + px, toY + py, toZ + pz)
            .setUv(uMax, v1).setColor(r, g, b, a).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
        vc.addVertex(pose, toX - px, toY - py, toZ - pz)
            .setUv(0f, v1).setColor(r, g, b, a).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)

        // Back face
        vc.addVertex(pose, fromX + px, fromY + py, fromZ + pz)
            .setUv(uMax, v0).setColor(r, g, b, a).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
        vc.addVertex(pose, fromX - px, fromY - py, fromZ - pz)
            .setUv(0f, v0).setColor(r, g, b, a).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
        vc.addVertex(pose, toX - px, toY - py, toZ - pz)
            .setUv(0f, v1).setColor(r, g, b, a).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
        vc.addVertex(pose, toX + px, toY + py, toZ + pz)
            .setUv(uMax, v1).setColor(r, g, b, a).setOverlay(overlay).setUv2(240, 240).setNormal(pose, 0f, 1f, 0f)
    }
}
