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
 * Composed of two layers of camera-facing billboard quads (an opaque
 * white inner core and a translucent blue outer halo) tiled with the
 * shared `laser_trail.png` streak texture. UV V accumulates along the
 * chain so the streak reads as continuous staff-to-anchor and scrolls
 * with time. Per-hook [GrappleBeamRope] simulation provides the
 * cascading bend.
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
            val anchorPos = hookAnchorPos(entity, 1.0f)
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

            // Live endpoints sampled every frame so the beam ends stay
            // locked to the cube and the hook between rope-sim ticks.
            // Middle nodes still come from the simulation, preserving
            // the cascading bend.
            val liveStaffPos = staffEmitterPos(owner, partial)
            val liveAnchorPos = hookAnchorPos(entity, partial)

            // Outer coloured glow disabled while evaluating the look.
            // val outerVc = consumers.getBuffer(OUTER_TYPE)
            // renderRope(
            //     outerVc, poseStack, cameraPos, rope, partial, time,
            //     liveStaffPos, liveAnchorPos,
            //     OUTER_WIDTH, OUTER_R, OUTER_G, OUTER_B, OUTER_A,
            // )
            val innerVc = consumers.getBuffer(INNER_TYPE)
            renderRope(
                innerVc, poseStack, cameraPos, rope, partial, time,
                liveStaffPos, liveAnchorPos,
                INNER_WIDTH, INNER_R, INNER_G, INNER_B, INNER_A,
            )
        }
    }

    /** Walk the chain and emit one billboard quad per segment, with UV
     *  V accumulated so the streak stays continuous. The gap between
     *  each simulated endpoint and its live counterpart this frame is
     *  redistributed across the chain with linear weights (full at the
     *  matching end, zero at the opposite), so the endpoints land
     *  exactly on the cube and the hook without the discontinuity a
     *  raw endpoint substitution would produce. */
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

            // Camera-relative coords, each end pushed by [extend] so
            // neighbour segments overlap and joint seams disappear.
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

            // Camera is at the origin in camera-relative coords, so
            // the segment-to-camera vector is the negated midpoint.
            val midX = (ax + bx) * 0.5f
            val midY = (ay + by) * 0.5f
            val midZ = (az + bz) * 0.5f
            val toCamX = -midX
            val toCamY = -midY
            val toCamZ = -midZ

            // Perp = (segment_dir cross to_camera), scaled to width/2.
            // Extruding the endpoints by +/- perp gives a billboard.
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

    /** Third-person staff emitter offsets in body-local blocks. Right is
     *  negative because the cube lands slightly left of the body axis.
     *  Tuned by hand. */
    private const val TP_FORWARD: Double = 1.240
    private const val TP_RIGHT: Double = -0.320
    private const val TP_UP: Double = 1.290

    /** World-space position of the staff's emitter. Local first-person
     *  reuses the cube position captured during item rendering (see
     *  [damien.nodeworks.client.GrappleBeamAnimState.getFirstPersonFocusPos]);
     *  everything else rides body yaw so the beam stays glued to the
     *  staff while the head pitches independently. */
    private fun staffEmitterPos(owner: Entity, partial: Float): Vec3 {
        val mc = Minecraft.getInstance()
        val camera = mc.gameRenderer.mainCamera
        val isLocalFirstPerson = owner === mc.player &&
            mc.options.cameraType == net.minecraft.client.CameraType.FIRST_PERSON &&
            !camera.isDetached

        if (isLocalFirstPerson && damien.nodeworks.client.GrappleBeamAnimState.hasFocusCapture()) {
            return damien.nodeworks.client.GrappleBeamAnimState.getFirstPersonFocusPos(partial)
        }

        val px = Mth.lerp(partial.toDouble(), owner.xOld, owner.x)
        val py = Mth.lerp(partial.toDouble(), owner.yOld, owner.y)
        val pz = Mth.lerp(partial.toDouble(), owner.zOld, owner.z)

        val living = owner as? net.minecraft.world.entity.LivingEntity
        val yawDeg = if (living != null) {
            Mth.rotLerp(partial, living.yBodyRotO, living.yBodyRot)
        } else {
            Mth.lerp(partial, owner.yRotO, owner.yRot)
        }
        val yawRad = yawDeg * Mth.DEG_TO_RAD
        val forwardX = -kotlin.math.sin(yawRad).toDouble()
        val forwardZ = kotlin.math.cos(yawRad).toDouble()
        val rightX = kotlin.math.cos(yawRad).toDouble()
        val rightZ = kotlin.math.sin(yawRad).toDouble()

        return Vec3(
            px + forwardX * TP_FORWARD + rightX * TP_RIGHT,
            py + TP_UP,
            pz + forwardZ * TP_FORWARD + rightZ * TP_RIGHT,
        )
    }

    private fun entityCenter(entity: Entity, partial: Float): Vec3 {
        val x = Mth.lerp(partial.toDouble(), entity.xOld, entity.x)
        val y = Mth.lerp(partial.toDouble(), entity.yOld, entity.y)
        val z = Mth.lerp(partial.toDouble(), entity.zOld, entity.z)
        return Vec3(x, y, z)
    }

    /** World-space anchor for the beam's far end. Entity-attached hooks
     *  read the held entity directly so the beam tip tracks it through
     *  Mojang's entity interpolation instead of the one-tick lag the
     *  hook's own `tick()` follow would carry. The Y bias matches the
     *  offset [GrappleBeamHookEntity.tick] applies when it pins the
     *  hook to its target. */
    private fun hookAnchorPos(hook: GrappleBeamHookEntity, partial: Float): Vec3 {
        val attached = hook.attachedEntity()
        if (attached != null) {
            val x = Mth.lerp(partial.toDouble(), attached.xOld, attached.x)
            val y = Mth.lerp(partial.toDouble(), attached.yOld, attached.y)
            val z = Mth.lerp(partial.toDouble(), attached.zOld, attached.z)
            return Vec3(x, y + attached.bbHeight * 0.5, z)
        }
        return entityCenter(hook, partial)
    }
}
