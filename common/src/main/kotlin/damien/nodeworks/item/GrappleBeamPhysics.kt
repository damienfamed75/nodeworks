package damien.nodeworks.item

import damien.nodeworks.entity.GrappleBeamHookEntity
import damien.nodeworks.script.ServerPolicy
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.Vec3
import kotlin.math.max

/**
 * Grapple Beam pull physics. Server-authoritative.
 *
 * For BLOCK anchors the player is pulled toward a goal that floats
 * off the anchor along the reversed look vector, producing an orbit
 * as the camera rotates:
 *
 * ```
 * goal = anchor + look * (-(ropeLength * LOOK_OFFSET_FACTOR) - max(-look.y, 0))
 * diff = clamp(goal - eye, MAX_DIFF)
 * velocity = velocity * VELOCITY_RETAIN + diff * PULL_GAIN
 * ```
 *
 * For ENTITY anchors the player stays put and the grabbed entity is
 * springed to a target sitting `ropeLength` blocks in front of the
 * crosshair, a physics-gun style hold.
 *
 * Scroll wheel adjusts ropeLength, which is the orbit radius for
 * block anchors and the hold distance for entity anchors.
 */
object GrappleBeamPhysics {

    /** Offset of the goal from the anchor along the reversed look
     *  vector, as a fraction of the rope length. Higher values widen
     *  the orbit. */
    private const val LOOK_OFFSET_FACTOR: Double = 0.8

    /** Cap on the (goal - eye) vector magnitude before [PULL_GAIN] is
     *  applied. Larger cap allows higher peak velocity. */
    private const val MAX_DIFF: Double = 4.0

    /** Per-tick fraction of (goal - eye) added to velocity. Lower
     *  values reduce overshoot amplitude when hovering at the goal. */
    private const val PULL_GAIN: Double = 0.15

    /** Fraction of previous velocity retained per tick. Carries flings
     *  built from camera motion across a few ticks instead of snapping
     *  back to whatever the current goal dictates. */
    private const val VELOCITY_RETAIN: Double = 0.65

    /** Per-tick upward velocity that nets out vanilla gravity. Lets
     *  the player hover at a steady altitude rather than slowly
     *  drifting down. */
    private const val GRAVITY_OFFSET: Double = 0.08

    /** Auto-release radius around a BLOCK anchor. */
    private const val AUTO_RELEASE_DISTANCE: Double = 1.5

    /** Minimum rope length when grabbing an entity. The hold target
     *  sits at least this far in front of the eyes so a scrolled-in
     *  entity never lands in the player's face. */
    const val ENTITY_MIN_ROPE_LENGTH: Double = 1.5

    /** Auto-release multiplier on the hook's [maxRange]. */
    private const val MAX_RANGE_SLACK: Double = 1.5

    /** Returns true to keep the session alive, false to auto-release. */
    fun applyPullTick(player: Player, hook: GrappleBeamHookEntity): Boolean {
        if (!hook.attached) return true

        val eye = player.eyePosition
        val anchor = hook.position()
        val distance = anchor.subtract(eye).length()

        if (distance > hook.maxRange * MAX_RANGE_SLACK) return false

        if (hook.ropeLength < 0.0) {
            hook.ropeLength = distance
        }

        val anchorEntityId = hook.attachedEntityId
        val isEntityAnchor = anchorEntityId != 0 && ServerPolicy.current.grappleEntities

        if (isEntityAnchor) {
            return applyEntityHoldTick(player, hook, eye, anchorEntityId)
        }

        if (distance < AUTO_RELEASE_DISTANCE) return false

        val look = player.lookAngle
        val offsetMag = -(hook.ropeLength * LOOK_OFFSET_FACTOR) - max(-look.y, 0.0)
        val goal = anchor.add(look.scale(offsetMag))

        var difference = goal.subtract(eye)
        val diffLen = difference.length()
        if (diffLen > MAX_DIFF) difference = difference.scale(MAX_DIFF / diffLen)

        var velocity = player.deltaMovement.add(0.0, GRAVITY_OFFSET, 0.0)
        velocity = velocity.scale(VELOCITY_RETAIN).add(difference.scale(PULL_GAIN))

        player.deltaMovement = velocity
        player.hurtMarked = true
        player.fallDistance = 0.0
        return true
    }

    /** Physics-gun style entity hold: the player does not move, the
     *  grabbed entity is springed to a target floating in front of the
     *  crosshair at [GrappleBeamHookEntity.ropeLength] blocks. */
    private fun applyEntityHoldTick(
        player: Player,
        hook: GrappleBeamHookEntity,
        eye: Vec3,
        anchorEntityId: Int,
    ): Boolean {
        val anchorEntity = player.level().getEntity(anchorEntityId) as? LivingEntity
            ?: return false

        if (hook.ropeLength < ENTITY_MIN_ROPE_LENGTH) {
            hook.ropeLength = ENTITY_MIN_ROPE_LENGTH
        }

        // Bias the aim point down by half the entity's height so it
        // floats centred on the crosshair instead of hanging from it
        // by its feet.
        val look = player.lookAngle
        val target = eye.add(look.scale(hook.ropeLength))
            .subtract(0.0, anchorEntity.bbHeight * 0.5, 0.0)

        var diff = target.subtract(anchorEntity.position())
        val diffLen = diff.length()
        if (diffLen > MAX_DIFF) diff = diff.scale(MAX_DIFF / diffLen)

        var entityVel = anchorEntity.deltaMovement.add(0.0, GRAVITY_OFFSET, 0.0)
        entityVel = entityVel.scale(VELOCITY_RETAIN).add(diff.scale(PULL_GAIN))

        anchorEntity.deltaMovement = entityVel
        anchorEntity.hurtMarked = true
        anchorEntity.fallDistance = 0.0
        return true
    }
}
