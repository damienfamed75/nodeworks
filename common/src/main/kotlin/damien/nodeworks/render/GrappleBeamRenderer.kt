package damien.nodeworks.render

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import damien.nodeworks.entity.GrappleBeamHookEntity
import damien.nodeworks.platform.PlatformServices
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.rendertype.RenderType
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.resources.Identifier
import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.Vec3
import kotlin.math.sqrt

/**
 * Draws the Grapple Beam between a player and their active hook.
 *
 * Two-layer billboard composition matching the Nodeworks pipe-laser style:
 *  - Opaque white inner core (thin, hard edge).
 *  - Translucent blue outer glow (wider, soft halo).
 *
 * Each layer is rendered as a series of camera-facing billboard quads,
 * one per chain segment, using the project's shared `laser_trail.png`
 * streak texture. UV V coordinates accumulate along the chain so the
 * texture appears continuous from staff to anchor instead of restarting
 * per segment, and a time-based scroll makes the streak visibly flow
 * along the beam.
 *
 * Backed by per-hook [GrappleBeamRope] state so the visible beam
 * inherits the cascading-bend dynamics. See [GrappleBeamRope] for the
 * relaxation model.
 */
object GrappleBeamRenderer {

    private const val MAX_DRAW_DISTANCE_SQ = 256.0 * 256.0

    // ---- color & width tuning -------------------------------------------

    /** Inner core color (RGB 0-255). White by default. */
    private const val INNER_R: Int = 255
    private const val INNER_G: Int = 255
    private const val INNER_B: Int = 255
    private const val INNER_A: Int = 255

    /** Outer glow color (RGB 0-255). Light Nodeworks blue. */
    private const val OUTER_R: Int = 0x4D
    private const val OUTER_G: Int = 0x90
    private const val OUTER_B: Int = 0xF0
    private const val OUTER_A: Int = 150

    /** Beam widths in block units. Half-pixel inner core, ~1.5px halo. */
    private const val INNER_WIDTH: Float = 0.5f / 16f
    private const val OUTER_WIDTH: Float = 1.5f / 16f

    /** Fractional extension applied to each segment's endpoints in the
     *  segment's own direction. Each segment renders slightly past its
     *  node-to-node bounds so adjacent segments overlap and don't show
     *  hairline gaps at chain joints, especially at sharp bends. */
    private const val SEGMENT_OVERLAP_FRAC: Float = 0.12f

    /** UV V-axis scroll rate (units / sec). Makes the streak flow along
     *  the beam toward the anchor. */
    private const val BEAM_SCROLL_SPEED: Float = 2.5f

    /** UV U range across the beam's width. Matches PipeLaserBeam so the
     *  same streak texture reads identically. */
    private const val UV_U_MAX: Float = 5f / 16f

    /** UV V density: how much V the texture advances per block of beam
     *  length. 0.5 = one block of beam is half a texture repeat. */
    private const val UV_V_DENSITY: Float = 0.5f

    // ---- shared texture / render types ----------------------------------

    private val LASER_TEXTURE: Identifier =
        Identifier.fromNamespaceAndPath("nodeworks", "textures/block/laser_trail.png")
    private val INNER_TYPE: RenderType = RenderTypes.beaconBeam(LASER_TEXTURE, false)
    private val OUTER_TYPE: RenderType = RenderTypes.beaconBeam(LASER_TEXTURE, true)

    // ---- state ----------------------------------------------------------

    private val ropes: MutableMap<Int, GrappleBeamRope> = HashMap()

    fun register() {
        PlatformServices.clientEvents.onWorldRender { poseStack, consumers, cameraPos ->
            if (poseStack == null || consumers == null) return@onWorldRender
            render(poseStack, consumers, cameraPos)
        }
    }

    /** Ticks every active rope's simulation by one step. Called from
     *  [damien.nodeworks.client.GrappleBeamInput.tick]. Also drops state
     *  for hooks that are no longer in the rendered level. */
    fun tickAllRopes() {
        val mc = Minecraft.getInstance()
        val level = mc.level ?: run { ropes.clear(); return }

        val seen = HashSet<Int>()
        for (entity in level.entitiesForRendering()) {
            if (entity !is GrappleBeamHookEntity) continue
            val owner: Entity = entity.owner ?: continue
            seen.add(entity.id)

            val staffPos = staffEmitterPos(owner, 1.0f)
            val anchorPos = entityCenter(entity, 1.0f)
            val rope = ropes.getOrPut(entity.id) { GrappleBeamRope() }
            rope.tick(staffPos, anchorPos)
        }

        if (ropes.size != seen.size) {
            val it = ropes.keys.iterator()
            while (it.hasNext()) {
                if (it.next() !in seen) it.remove()
            }
        }
    }

    private fun render(poseStack: PoseStack, consumers: MultiBufferSource, cameraPos: Vec3) {
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return
        val partial = mc.deltaTracker.getGameTimeDeltaPartialTick(true)
        val time = (System.currentTimeMillis() % 100_000L) / 1000f

        for (entity in level.entitiesForRendering()) {
            if (entity !is GrappleBeamHookEntity) continue

            val dx = entity.x - cameraPos.x
            val dy = entity.y - cameraPos.y
            val dz = entity.z - cameraPos.z
            if (dx * dx + dy * dy + dz * dz > MAX_DRAW_DISTANCE_SQ) continue

            val rope = ropes[entity.id] ?: continue
            val owner: Entity = entity.owner ?: continue

            // Live endpoint positions sampled every frame so the beam
            // ends stay locked to the cube and the hook regardless of
            // tick rate. The rope simulation runs at 20Hz and would
            // otherwise leave node 0 one tick behind the cube (visible
            // as a rubber-band between the cube and the beam start
            // whenever the camera moved). Middle nodes still use the
            // simulation, so the cascading-bend look is preserved.
            val liveStaffPos = staffEmitterPos(owner, partial)
            val liveAnchorPos = entityCenter(entity, partial)

            // Two passes per hook: outer glow first so the inner core
            // draws over it. Same chain math runs twice with different
            // widths / colors / render types.
            val outerVc = consumers.getBuffer(OUTER_TYPE)
            renderRope(
                outerVc, poseStack, cameraPos, rope, partial, time,
                liveStaffPos, liveAnchorPos,
                OUTER_WIDTH, OUTER_R, OUTER_G, OUTER_B, OUTER_A,
            )
            val innerVc = consumers.getBuffer(INNER_TYPE)
            renderRope(
                innerVc, poseStack, cameraPos, rope, partial, time,
                liveStaffPos, liveAnchorPos,
                INNER_WIDTH, INNER_R, INNER_G, INNER_B, INNER_A,
            )
        }
    }

    /** Walk the chain and emit one billboard quad per segment. UV V
     *  accumulates along the chain length so the streak texture stays
     *  continuous between segments.
     *
     *  Endpoint lag is removed by computing two corrections (the gap
     *  between the rope simulation's current endpoint and the live
     *  endpoint sampled this frame) and redistributing them along the
     *  rope with linear weights, full at the matching endpoint, zero
     *  at the opposite end. This gives an exact match at the visible
     *  attachment points without the hard discontinuity that a raw
     *  endpoint substitution caused. */
    private fun renderRope(
        vc: VertexConsumer,
        poseStack: PoseStack,
        cameraPos: Vec3,
        rope: GrappleBeamRope,
        partial: Float,
        time: Float,
        liveStaffPos: Vec3,
        liveAnchorPos: Vec3,
        width: Float,
        r: Int, g: Int, b: Int, a: Int,
    ) {
        val uvScroll = time * BEAM_SCROLL_SPEED
        var accumulatedV = -uvScroll
        val lastNode = GrappleBeamRope.NODE_COUNT - 1
        val invLast = 1.0 / lastNode.toDouble()

        val simStaff = rope.renderPositionAt(0, partial)
        val simAnchor = rope.renderPositionAt(lastNode, partial)
        val staffDx = liveStaffPos.x - simStaff.x
        val staffDy = liveStaffPos.y - simStaff.y
        val staffDz = liveStaffPos.z - simStaff.z
        val anchorDx = liveAnchorPos.x - simAnchor.x
        val anchorDy = liveAnchorPos.y - simAnchor.y
        val anchorDz = liveAnchorPos.z - simAnchor.z

        fun correctedAt(i: Int): Vec3 {
            if (i == 0) return liveStaffPos
            if (i == lastNode) return liveAnchorPos
            val sim = rope.renderPositionAt(i, partial)
            val t = i.toDouble() * invLast
            val staffW = 1.0 - t
            val anchorW = t
            return Vec3(
                sim.x + staffDx * staffW + anchorDx * anchorW,
                sim.y + staffDy * staffW + anchorDy * anchorW,
                sim.z + staffDz * staffW + anchorDz * anchorW,
            )
        }

        for (i in 0 until GrappleBeamRope.NODE_COUNT - 1) {
            val nodeA = correctedAt(i)
            val nodeB = correctedAt(i + 1)

            // Raw node-to-node displacement before overlap extension.
            val rawDx = (nodeB.x - nodeA.x).toFloat()
            val rawDy = (nodeB.y - nodeA.y).toFloat()
            val rawDz = (nodeB.z - nodeA.z).toFloat()
            val rawLen = sqrt(rawDx * rawDx + rawDy * rawDy + rawDz * rawDz)
            if (rawLen < 1e-3f) continue
            val extend = rawLen * SEGMENT_OVERLAP_FRAC
            val extX = rawDx / rawLen * extend
            val extY = rawDy / rawLen * extend
            val extZ = rawDz / rawLen * extend

            // Camera-relative coords, with each end pushed [extend] units
            // along the segment direction so neighbors overlap and joint
            // seams disappear.
            val ax = (nodeA.x - cameraPos.x).toFloat() - extX
            val ay = (nodeA.y - cameraPos.y).toFloat() - extY
            val az = (nodeA.z - cameraPos.z).toFloat() - extZ
            val bx = (nodeB.x - cameraPos.x).toFloat() + extX
            val by = (nodeB.y - cameraPos.y).toFloat() + extY
            val bz = (nodeB.z - cameraPos.z).toFloat() + extZ

            val segDx = bx - ax
            val segDy = by - ay
            val segDz = bz - az
            val segLen = sqrt(segDx * segDx + segDy * segDy + segDz * segDz)
            if (segLen < 1e-3f) continue

            // Camera position is at the origin in the camera-relative
            // frame, so the vector from segment midpoint to the camera
            // is just the negated midpoint.
            val midX = (ax + bx) * 0.5f
            val midY = (ay + by) * 0.5f
            val midZ = (az + bz) * 0.5f
            val toCamX = -midX
            val toCamY = -midY
            val toCamZ = -midZ

            // Perpendicular = (segment_dir cross to_camera), normalized
            // and scaled to width/2. Extruding the segment endpoints by
            // +/- perp gives a camera-facing quad.
            val pxRaw = segDy * toCamZ - segDz * toCamY
            val pyRaw = segDz * toCamX - segDx * toCamZ
            val pzRaw = segDx * toCamY - segDy * toCamX
            val pLen = sqrt(pxRaw * pxRaw + pyRaw * pyRaw + pzRaw * pzRaw)
            if (pLen < 1e-3f) {
                accumulatedV += segLen * UV_V_DENSITY
                continue
            }
            val halfW = width * 0.5f
            val px = pxRaw / pLen * halfW
            val py = pyRaw / pLen * halfW
            val pz = pzRaw / pLen * halfW

            val v0 = accumulatedV
            val v1 = accumulatedV + segLen * UV_V_DENSITY
            accumulatedV = v1

            val pose = poseStack.last()
            val o = OverlayTexture.NO_OVERLAY

            // Front face (counterclockwise winding when viewed from camera).
            vc.addVertex(pose, ax - px, ay - py, az - pz)
                .setUv(0f, v0).setColor(r, g, b, a).setOverlay(o).setUv2(240, 240)
                .setNormal(pose, 0f, 1f, 0f)
            vc.addVertex(pose, ax + px, ay + py, az + pz)
                .setUv(UV_U_MAX, v0).setColor(r, g, b, a).setOverlay(o).setUv2(240, 240)
                .setNormal(pose, 0f, 1f, 0f)
            vc.addVertex(pose, bx + px, by + py, bz + pz)
                .setUv(UV_U_MAX, v1).setColor(r, g, b, a).setOverlay(o).setUv2(240, 240)
                .setNormal(pose, 0f, 1f, 0f)
            vc.addVertex(pose, bx - px, by - py, bz - pz)
                .setUv(0f, v1).setColor(r, g, b, a).setOverlay(o).setUv2(240, 240)
                .setNormal(pose, 0f, 1f, 0f)
            // Back face, flipped winding so the quad reads from either side.
            vc.addVertex(pose, ax + px, ay + py, az + pz)
                .setUv(UV_U_MAX, v0).setColor(r, g, b, a).setOverlay(o).setUv2(240, 240)
                .setNormal(pose, 0f, 1f, 0f)
            vc.addVertex(pose, ax - px, ay - py, az - pz)
                .setUv(0f, v0).setColor(r, g, b, a).setOverlay(o).setUv2(240, 240)
                .setNormal(pose, 0f, 1f, 0f)
            vc.addVertex(pose, bx - px, by - py, bz - pz)
                .setUv(0f, v1).setColor(r, g, b, a).setOverlay(o).setUv2(240, 240)
                .setNormal(pose, 0f, 1f, 0f)
            vc.addVertex(pose, bx + px, by + py, bz + pz)
                .setUv(UV_U_MAX, v1).setColor(r, g, b, a).setOverlay(o).setUv2(240, 240)
                .setNormal(pose, 0f, 1f, 0f)
        }
    }

    /** Third-person beam start offsets. Sits the beam well forward of
     *  the player's body where the held staff tip visibly is, with a
     *  small mainhand bias. */
    private const val TP_BODY_Y: Double = 1.28
    private const val TP_FORWARD: Double = 1.275
    private const val TP_RIGHT: Double = 0.325

    /** World-space position of the staff's emitter.
     *
     *   - **Local player in first-person**: pulled from
     *     [damien.nodeworks.client.GrappleBeamAnimState.getFirstPersonFocusPos],
     *     which is the cube's world position captured during item
     *     rendering. The capture happens inside
     *     [damien.nodeworks.client.GrappleBeamClientExtensions.applyForgeHandTransform];
     *     because Mojang seeds the item PoseStack with the inverse
     *     view rotation matrix, transforming the cube pivot through
     *     the pose at that point yields a view-space offset that we
     *     un-rotate with the current camera. Falls back to the formula
     *     path on the first frame before any capture has occurred, and
     *     when the camera is detached.
     *   - **Third-person view or other players**: formula using
     *     player position, chest-height Y, view-direction forward, and
     *     a horizontal-right mainhand offset. */
    private fun staffEmitterPos(owner: Entity, partial: Float): Vec3 {
        val mc = Minecraft.getInstance()
        val camera = mc.gameRenderer.mainCamera
        val isLocalFirstPerson = owner === mc.player &&
            mc.options.cameraType == net.minecraft.client.CameraType.FIRST_PERSON &&
            !camera.isDetached

        if (isLocalFirstPerson && damien.nodeworks.client.GrappleBeamAnimState.hasFocusCapture()) {
            return damien.nodeworks.client.GrappleBeamAnimState.getFirstPersonFocusPos(partial)
        }

        // Third-person / other players: formula path.
        val px = Mth.lerp(partial.toDouble(), owner.xOld, owner.x)
        val py = Mth.lerp(partial.toDouble(), owner.yOld, owner.y)
        val pz = Mth.lerp(partial.toDouble(), owner.zOld, owner.z)

        val look = owner.getViewVector(partial)
        val rightX = -look.z
        val rightZ = look.x
        val rightLen = kotlin.math.sqrt(rightX * rightX + rightZ * rightZ)
        val rx = if (rightLen > 1e-4) rightX / rightLen else 0.0
        val rz = if (rightLen > 1e-4) rightZ / rightLen else 0.0

        return Vec3(
            px + look.x * TP_FORWARD + rx * TP_RIGHT,
            py + TP_BODY_Y + look.y * TP_FORWARD,
            pz + look.z * TP_FORWARD + rz * TP_RIGHT,
        )
    }

    private fun entityCenter(entity: Entity, partial: Float): Vec3 {
        val x = Mth.lerp(partial.toDouble(), entity.xOld, entity.x)
        val y = Mth.lerp(partial.toDouble(), entity.yOld, entity.y)
        val z = Mth.lerp(partial.toDouble(), entity.zOld, entity.z)
        return Vec3(x, y, z)
    }
}
