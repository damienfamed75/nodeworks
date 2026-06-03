package damien.nodeworks.client

import damien.nodeworks.entity.GrappleBeamHookEntity
import net.minecraft.client.Minecraft
import net.minecraft.client.player.AbstractClientPlayer
import net.minecraft.client.player.LocalPlayer
import net.minecraft.util.Mth
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f
import org.joml.Matrix4fc
import org.joml.Vector3f
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * First-person item-arm animation state for the Grapple Beam.
 *
 * Two halves:
 *  - **Staff channels** (extension, tilt, scale) drive the held-staff
 *    posture via [GrappleBeamClientExtensions.applyForgeHandTransform].
 *  - **Cube channels** (spin, pulse, anchor yaw/pitch) drive the
 *    floating cube the beam originates from. The cube passively spins
 *    around its Y axis, and when a hook is attached it additionally
 *    lerps to face the anchor's direction (in the camera's frame) and
 *    adds a gentle scale pulse.
 *
 * Ticked once per client tick from [GrappleBeamInput.tick]. The render
 * side samples interpolated values via the *At(partial) accessors.
 */
object GrappleBeamAnimState {

    // ---- staff channels --------------------------------------------------

    private const val STAFF_LERP_RATE: Float = 0.35f
    private const val MAX_EXTENSION: Float = 0.18f
    private const val MAX_TILT: Float = (PI / 4).toFloat()
    private const val IDLE_SCALE: Float = 1.0f
    private const val ACTIVE_SCALE: Float = 1.06f

    /** Base downward tilt when grappling. Visible regardless of look
     *  direction, so the animation reads even when the player is
     *  staring straight at the anchor. Negative because positive X
     *  rotation in the staff's local frame after the display transform
     *  is "up", flipping the sign drops the tip. */
    private const val GRAPPLING_BASE_TILT: Float = -0.45f

    /** How much the anchor's pitch-vs-look offset additionally biases the
     *  staff tilt on top of [GRAPPLING_BASE_TILT]. Lower = pure
     *  fixed-down look, higher = staff visibly tracks the anchor angle. */
    private const val ANCHOR_TILT_WEIGHT: Float = 0.5f

    private var extension: Float = 0f
    private var tilt: Float = 0f
    private var scale: Float = IDLE_SCALE

    private var prevExtension: Float = 0f
    private var prevTilt: Float = 0f
    private var prevScale: Float = IDLE_SCALE

    // ---- cube channels ---------------------------------------------------

    /** Radians added to the passive Y-axis spin every tick. ~14 sec/rev. */
    private const val SPIN_PER_TICK: Float = (2.0 * PI / (14.0 * 20.0)).toFloat()

    /** Lerp rate for the anchor-facing yaw/pitch and the pulse channel. */
    private const val CUBE_LERP_RATE: Float = 0.35f

    /** Pulse envelope while grappling: a gentle sine-driven scale that
     *  modulates between 1 - PULSE_AMP and 1 + PULSE_AMP at ~1 cycle
     *  per 1.3 sec. */
    private const val PULSE_AMP: Float = 0.08f
    private const val PULSE_FREQ: Float = 0.4f

    /** Continuous tick counter feeding spin accumulation and pulse phase. */
    private var tickCount: Int = 0

    private var spinAngle: Float = 0f
    private var prevSpinAngle: Float = 0f

    private var pulse: Float = 1f
    private var prevPulse: Float = 1f

    private var anchorYaw: Float = 0f
    private var prevAnchorYaw: Float = 0f

    private var anchorPitch: Float = 0f
    private var prevAnchorPitch: Float = 0f

    // ---- tick ------------------------------------------------------------

    fun tick() {
        prevExtension = extension
        prevTilt = tilt
        prevScale = scale
        prevSpinAngle = spinAngle
        prevPulse = pulse
        prevAnchorYaw = anchorYaw
        prevAnchorPitch = anchorPitch

        tickCount++

        val hook = activeHook()
        val grappling = hook != null

        val targetExtension = if (grappling) MAX_EXTENSION else 0f
        val targetScale = if (grappling) ACTIVE_SCALE else IDLE_SCALE
        val targetTilt = if (hook != null) {
            (GRAPPLING_BASE_TILT + computeStaffTilt(hook) * ANCHOR_TILT_WEIGHT)
                .coerceIn(-MAX_TILT, MAX_TILT)
        } else 0f
        extension += (targetExtension - extension) * STAFF_LERP_RATE
        tilt += (targetTilt - tilt) * STAFF_LERP_RATE
        scale += (targetScale - scale) * STAFF_LERP_RATE

        spinAngle += SPIN_PER_TICK

        val targetPulse = if (grappling) {
            1f + sin(tickCount * PULSE_FREQ.toDouble()).toFloat() * PULSE_AMP
        } else 1f
        pulse += (targetPulse - pulse) * CUBE_LERP_RATE

        val targetAnchorYaw: Float
        val targetAnchorPitch: Float
        if (hook != null) {
            val (yaw, pitch) = computeCubeAnchorRotation(hook)
            targetAnchorYaw = yaw
            targetAnchorPitch = pitch
        } else {
            targetAnchorYaw = 0f
            targetAnchorPitch = 0f
        }
        anchorYaw += shortestAngleDelta(anchorYaw, targetAnchorYaw) * CUBE_LERP_RATE
        anchorPitch += (targetAnchorPitch - anchorPitch) * CUBE_LERP_RATE
    }

    // ---- staff accessors (used by GrappleBeamClientExtensions) ----------

    fun extensionAt(partial: Float): Float = lerp(prevExtension, extension, partial)
    fun tiltAt(partial: Float): Float = lerp(prevTilt, tilt, partial)
    fun scaleAt(partial: Float): Float = lerp(prevScale, scale, partial)

    // ---- cube accessors (used by GrappleBeamItemModel) ------------------

    fun spinAt(partial: Float): Float = prevSpinAngle + (spinAngle - prevSpinAngle) * partial
    fun pulseAt(partial: Float): Float = lerp(prevPulse, pulse, partial)
    fun anchorYawAt(partial: Float): Float = prevAnchorYaw +
        shortestAngleDelta(prevAnchorYaw, anchorYaw) * partial
    fun anchorPitchAt(partial: Float): Float = lerp(prevAnchorPitch, anchorPitch, partial)

    fun isGrappling(): Boolean = activeHook() != null

    // ---- internals -------------------------------------------------------

    private fun activeHook(): GrappleBeamHookEntity? {
        val mc = Minecraft.getInstance()
        val playerUuid = mc.player?.uuid ?: return null
        val level = mc.level ?: return null
        for (e in level.entitiesForRendering()) {
            if (e is GrappleBeamHookEntity) {
                // UUID compare instead of identity. The client-side
                // owner ref isn't guaranteed to be the same Java object
                // as `mc.player`, it's resolved from a UUID lookup that
                // can return a different instance after entity sync.
                val owner = e.owner
                if (owner != null && owner.uuid == playerUuid) return e
            }
        }
        return null
    }

    /** Pitch delta from look to anchor, clamped to [MAX_TILT]. */
    private fun computeStaffTilt(hook: GrappleBeamHookEntity): Float {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return 0f
        val toAnchor = hook.position().subtract(player.eyePosition)
        val len = toAnchor.length()
        if (len < 1e-3) return 0f
        val anchorPitch = asin(toAnchor.y / len)
        val lookPitch = asin(player.lookAngle.y.coerceIn(-1.0, 1.0))
        val diff = (anchorPitch - lookPitch).toFloat()
        return diff.coerceIn(-MAX_TILT, MAX_TILT)
    }

    /** Anchor direction expressed as (yaw, pitch) in the camera's local
     *  frame. Yaw around player-up, pitch around camera-right. Used to
     *  orient the cube so it visually points at the anchor regardless
     *  of where the camera is looking. */
    private fun computeCubeAnchorRotation(hook: GrappleBeamHookEntity): Pair<Float, Float> {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return 0f to 0f
        val toAnchor = hook.position().subtract(player.eyePosition)
        if (toAnchor.lengthSqr() < 1e-6) return 0f to 0f
        val toAnchorN = toAnchor.normalize()

        // Camera basis. forward = lookAngle, right = horizontal vector
        // 90 degrees clockwise from yaw, up = forward cross right.
        val forward = player.lookAngle
        val playerYawRad = Math.toRadians(player.yRot.toDouble())
        val right = Vec3(-cos(playerYawRad), 0.0, -sin(playerYawRad))
        val up = forward.cross(right).normalize()

        val x = toAnchorN.dot(right).toFloat()
        val y = toAnchorN.dot(up).toFloat()
        val z = toAnchorN.dot(forward).toFloat()

        val yaw = atan2(x, z)
        val pitch = atan2(y, sqrt(x * x + z * z))
        return yaw to pitch
    }

    /** Wrap-aware lerp delta for angles. Picks the shorter of the two
     *  ways around so the cube doesn't take the long route when the
     *  anchor crosses the +/- pi boundary. */
    private fun shortestAngleDelta(from: Float, to: Float): Float {
        var d = to - from
        while (d > PI.toFloat()) d -= (2 * PI).toFloat()
        while (d < -PI.toFloat()) d += (2 * PI).toFloat()
        return d
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    // ---- cube focus capture ---------------------------------------------
    //
    // Captures the cube's view-space position during item rendering,
    // re-projects with the current camera rotation at beam-render time.
    // View-space is invariant under camera rotation so the beam stays
    // glued to the cube during fast camera flicks. Mojang's
    // GameRenderer.renderItemInHand seeds the item PoseStack with
    // `cameraState.viewRotationMatrix.invert()` (rotation only, no
    // translation), so `pose * pivot` yields
    // `viewToWorldRot * viewSpaceCubePos`. We undo that rotation with
    // `camera.rotation().transformInverse` and save the result.

    /** Cube pivot in baked /16 space, identical to
     *  [damien.nodeworks.client.model.GrappleBeamItemModel.CUBE_PIVOT_*]. */
    private const val CUBE_PIVOT_X: Float = 7.95f / 16f
    private const val CUBE_PIVOT_Y: Float = 23.85f / 16f
    private const val CUBE_PIVOT_Z: Float = 8f / 16f

    /** Staff `firstperson_*hand` display transform from
     *  models/item/grapple_beam.json. Right-hand values, the left-hand
     *  JSON entry's pre-negated Y/Z rotations are double-negated by
     *  Mojang's [ItemTransform.apply] so the effective rotation is the
     *  same for both hands. Only X translation flips sign. */
    private const val FP_TRANSLATE_X: Float = 4f / 16f
    private const val FP_TRANSLATE_Y: Float = 0f / 16f
    private const val FP_TRANSLATE_Z: Float = -1f / 16f
    private const val FP_ROTATE_X_DEG: Float = 30f
    private const val FP_ROTATE_Y_DEG: Float = 104f
    private const val FP_ROTATE_Z_DEG: Float = -40f
    private const val DEG_TO_RAD: Float = (PI / 180.0).toFloat()

    /** View-space offsets applied to the captured cube position at
     *  beam-render time, in block units. Camera-local sign convention:
     *  -Z is forward into the scene, +X is the camera's right, -Y is
     *  below the camera centre. Forward pulls the segment-0 billboard's
     *  per-frame orientation twist inside the cube body; the small
     *  negative right offset compensates for the residual screen-space
     *  drift left by the FOV correction. Tuned by hand. */
    private const val FP_FORWARD_NUDGE: Float = -0.180f
    private const val FP_RIGHT_NUDGE: Float = -0.160f
    private const val FP_DOWN_NUDGE: Float = 0f

    /** `tan(70° / 2)`. MC 26.x renders the item-in-hand pass with a
     *  fixed 70° HUD FOV (see `Camera.calculateHudFov`). The world
     *  pass uses the player's configured FOV. The captured view-space
     *  cube position lands at the right screen point under the HUD
     *  projection; to make the SAME world point land at the same
     *  screen point under the world projection we scale view-space X
     *  and Y by `tan(worldFov/2) / HUD_TAN_HALF_FOV`. At worldFov=70
     *  the ratio is 1 (no correction); at worldFov=86 the ratio is
     *  ~1.33, pushing the beam start outward to match. */
    private const val HUD_TAN_HALF_FOV: Float = 0.7002075382097097f

    private val focusPos = Vector3f()
    @Volatile
    private var focusCaptured: Boolean = false

    /** Replays the rest of the render chain after our hand-transform
     *  edits so the captured point lands exactly where the cube will:
     *  1. Vanilla's `applyItemArmTransform` (runs after our hook when
     *     we return `false`): one translation `(±0.56, -0.52 +
     *     equip*-0.6, -0.72)` where X sign flips with the arm.
     *  2. Staff display transform: translate, rotateXYZ, scale,
     *     translate(-0.5, -0.5, -0.5).
     *  3. Cube layer transform from [GrappleBeamItemModel.buildCubeTransform]
     *     is identity at the pivot because the rotate/scale sandwich
     *     cancels and CUBE_LIFT_Y is 0.
     *  4. Strip the capture-time camera rotation, leaving a view-space
     *     (camera-frame) position.
     *  5. Strip the per-frame transforms that vanilla pre-applied to
     *     the pose stack BEFORE our hook (sway, then bobView). What
     *     remains is driven by our own slow-lerping animation state,
     *     so re-applying CURRENT-frame versions at render time leaves
     *     no residual lag during fast camera flicks or walking.
     *
     *  Swing animation is not replicated; it fires on attack which is
     *  rare while grappling and reproducing it would mean copying the
     *  unstable inline math from `renderArmWithItem`.
     *
     *  @param equipProcess the `inverseArmHeight` value vanilla feeds
     *      into `applyItemArmTransform` (Forge passes it as
     *      `equipProcess`).
     */
    fun captureFocusPos(pose: Matrix4fc, leftHand: Boolean, equipProcess: Float, partialTick: Float) {
        val mc = Minecraft.getInstance()
        val matrix = Matrix4f(pose)
        val sign = if (leftHand) -1f else 1f
        // (1) applyItemArmTransform
        matrix.translate(sign * 0.56f, -0.52f + equipProcess * -0.6f, -0.72f)
        // (2) staff display transform. Mirrors ItemTransform.apply():
        //     translate, rotateXYZ, scale, translate(-0.5,-0.5,-0.5).
        //     The trailing half-block centring shifts block-as-item
        //     models from corner-origin to centre-origin; without it
        //     the captured pivot is off by ~0.866 world units rotated
        //     through the display rotation.
        matrix.translate(sign * FP_TRANSLATE_X, FP_TRANSLATE_Y, FP_TRANSLATE_Z)
        matrix.rotateXYZ(
            FP_ROTATE_X_DEG * DEG_TO_RAD,
            FP_ROTATE_Y_DEG * DEG_TO_RAD,
            FP_ROTATE_Z_DEG * DEG_TO_RAD,
        )
        // scale = (1, 1, 1) for our display, skipped.
        matrix.translate(-0.5f, -0.5f, -0.5f)
        // (3) cube pivot, invariant under the cube layer transform.
        val center = Vector3f(CUBE_PIVOT_X, CUBE_PIVOT_Y, CUBE_PIVOT_Z)
        matrix.transformPosition(center)
        // (4) strip capture-time camera rotation.
        mc.gameRenderer.mainCamera.rotation().transformInverse(center)
        // (5) strip vanilla's pre-hook transforms. Pose chain was
        // viewRotMatInv * bobView * sway, so invert by sway-first
        // (applied last) then bobView.
        val player = mc.player
        if (player != null) {
            stripSway(center, player, partialTick)
            stripBobView(center, player, partialTick)
        }
        focusPos.set(center)
        focusCaptured = true
    }

    /** Matches the per-frame sway rotation in
     *  [net.minecraft.client.renderer.ItemInHandRenderer.renderHandsWithItems]:
     *  `(viewRot - bob) * 0.1` degrees, converted to radians. */
    private fun handSwayAngleRad(viewRot: Float, bob: Float): Float =
        (viewRot - bob) * 0.1f * DEG_TO_RAD

    /** Sway was `R_X(swayX) * R_Y(swayY)`. Invert by applying
     *  `R_X(-swayX)` then `R_Y(-swayY)` on the left. */
    private fun stripSway(v: Vector3f, player: LocalPlayer, partial: Float) {
        val swayX = handSwayAngleRad(player.getViewXRot(partial),
            Mth.lerp(partial, player.xBobO, player.xBob))
        val swayY = handSwayAngleRad(player.getViewYRot(partial),
            Mth.lerp(partial, player.yBobO, player.yBob))
        v.rotateX(-swayX)
        v.rotateY(-swayY)
    }

    /** Forward sway, R_X then R_Y on the left. */
    private fun applySway(v: Vector3f, player: LocalPlayer, partial: Float) {
        val swayX = handSwayAngleRad(player.getViewXRot(partial),
            Mth.lerp(partial, player.xBobO, player.xBob))
        val swayY = handSwayAngleRad(player.getViewYRot(partial),
            Mth.lerp(partial, player.yBobO, player.yBob))
        v.rotateY(swayY)
        v.rotateX(swayX)
    }

    /** bobView was `T(tx, ty, 0) * R_Z(rz) * R_X(rx)`. Each
     *  `Vector3f.rotate*` / translate-by-subtraction left-multiplies,
     *  so invert outside-in: `T^-1` first, then `R_Z^-1`, then `R_X^-1`. */
    private fun stripBobView(v: Vector3f, player: AbstractClientPlayer, partial: Float) {
        val p = bobViewParams(player, partial) ?: return
        v.x -= p.tx
        v.y -= p.ty
        v.rotateZ(-p.rz)
        v.rotateX(-p.rx)
    }

    /** Forward bobView, inside-out: `R_X`, then `R_Z`, then `T`. */
    private fun applyBobView(v: Vector3f, player: AbstractClientPlayer, partial: Float) {
        val p = bobViewParams(player, partial) ?: return
        v.rotateX(p.rx)
        v.rotateZ(p.rz)
        v.x += p.tx
        v.y += p.ty
    }

    private data class BobViewParams(val tx: Float, val ty: Float, val rz: Float, val rx: Float)

    /** Replicates [GameRenderer.bobView]'s translations and rotations
     *  for the local player. Returns null when bob is below an epsilon
     *  (option disabled, just-stopped-walking decay, or standing
     *  still), in which case bobView is effectively identity. */
    private fun bobViewParams(player: AbstractClientPlayer, partial: Float): BobViewParams? {
        if (!Minecraft.getInstance().options.bobView().get()) return null
        val avatar = player.avatarState()
        val walkDist = avatar.getBackwardsInterpolatedWalkDistance(partial)
        val bob = avatar.getInterpolatedBob(partial)
        if (bob < 1e-5f) return null
        val phase = walkDist * PI.toFloat()
        val tx = sin(phase) * bob * 0.5f
        val ty = -kotlin.math.abs(cos(phase) * bob)
        val rz = sin(phase) * bob * 3f * DEG_TO_RAD
        val rx = kotlin.math.abs(cos(phase - 0.2f) * bob) * 5f * DEG_TO_RAD
        return BobViewParams(tx, ty, rz, rx)
    }

    /** True once [captureFocusPos] has fired at least once. Lets the
     *  renderer fall back to the third-person formula on the first
     *  frame, before there's anything to recover. */
    fun hasFocusCapture(): Boolean = focusCaptured

    /** Re-projects the saved view-space cube position into the world
     *  using the current camera state. The beam render hook fires
     *  during level rendering BEFORE the item-in-hand pass refreshes
     *  [focusPos], so the saved value is one frame stale; using
     *  view-space storage plus current-frame rotation closes that lag
     *  as long as the cube's view-space position is roughly stable
     *  frame-to-frame, which it is. */
    fun getFirstPersonFocusPos(partial: Float): Vec3 {
        val mc = Minecraft.getInstance()
        val camera = mc.gameRenderer.mainCamera
        val viewPos = Vector3f(focusPos)
        // Live-tunable view-space offsets. Forward (-Z) pulls the beam
        // start back toward the camera so the segment-0 billboard's
        // twist is hidden inside the cube body; right (+X) and down
        // (-Y) are camera-local nudges. Applied before bobView/sway
        // so they ride with the cube.
        viewPos.x += FP_RIGHT_NUDGE
        viewPos.y -= FP_DOWN_NUDGE
        viewPos.z -= FP_FORWARD_NUDGE
        // FOV correction. Item-in-hand renders with a fixed 70° HUD
        // projection; the world (and our beam) renders with the
        // player's configured FOV. To make the captured view-space
        // point land at the same SCREEN position under the world
        // projection as it did under the HUD projection, scale X and
        // Y by tan(worldFov/2) / tan(HUD_FOV/2). Reads tan(worldFov/2)
        // straight off the projection matrix's [1][1] = 1/tan(fov/2).
        val projM11 = mc.gameRenderer.gameRenderState
            .levelRenderState.cameraRenderState.projectionMatrix.m11()
        if (projM11 > 1e-4f) {
            val ratio = 1f / (projM11 * HUD_TAN_HALF_FOV)
            viewPos.x *= ratio
            viewPos.y *= ratio
        }
        // Re-apply CURRENT frame's bobView then sway so the resulting
        // view-space point matches what vanilla will render this
        // frame: bobView * sway * stable.
        val player = mc.player
        if (player != null) {
            applyBobView(viewPos, player, partial)
            applySway(viewPos, player, partial)
        }
        camera.rotation().transform(viewPos)
        val camPos = camera.position()
        return Vec3(
            camPos.x + viewPos.x.toDouble(),
            camPos.y + viewPos.y.toDouble(),
            camPos.z + viewPos.z.toDouble(),
        )
    }

    @Suppress("unused")
    private val keepVec3Import: Vec3 = Vec3.ZERO
}
