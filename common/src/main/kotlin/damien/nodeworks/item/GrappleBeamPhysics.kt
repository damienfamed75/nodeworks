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
 * Block anchors use a spring-damper pull on the player toward a goal
 * offset along the reversed look vector (camera rotation orbits the
 * player around the anchor). Entity anchors use a proportional pull
 * on the held entity toward a target floating in front of the
 * crosshair; the player doesn't move.
 *
 * Scroll wheel adjusts ropeLength: orbit radius for blocks, hold
 * distance for entities.
 */
object GrappleBeamPhysics {

    /** Goal offset from the anchor along the reversed look vector, as
     *  a fraction of rope length. Wider = larger orbit radius. */
    private const val LOOK_OFFSET_FACTOR: Double = 0.8

    /** Cap on (goal - eye) before [PULL_GAIN] is applied. */
    private const val MAX_DIFF: Double = 4.0

    /** Per-tick fraction of (goal - eye) added to velocity. */
    private const val PULL_GAIN: Double = 0.15

    /** Fraction of previous velocity retained per tick. Carries flings
     *  built from camera motion across a few ticks. */
    private const val VELOCITY_RETAIN: Double = 0.65

    /** Per-tick upward velocity that nets out vanilla gravity so the
     *  player hovers steady. */
    private const val GRAVITY_OFFSET: Double = 0.08

    /** Auto-release radius around a BLOCK anchor. */
    private const val AUTO_RELEASE_DISTANCE: Double = 1.5

    /** Minimum rope length when grabbing an entity, so a scrolled-in
     *  entity never lands in the player's face. */
    const val ENTITY_MIN_ROPE_LENGTH: Double = 1.5

    /** Auto-release multiplier on the hook's [maxRange]. */
    private const val MAX_RANGE_SLACK: Double = 1.5

    /** Fraction of the remaining (target - position) gap a held entity
     *  closes per tick. 0.45 settles in ~6 ticks. */
    private const val ENTITY_HOLD_PULL_GAIN: Double = 0.45

    /** Hard cap on the per-tick velocity applied to a held entity so a
     *  long-distance catch-up doesn't visibly teleport. */
    private const val ENTITY_HOLD_MAX_VEL: Double = 2.5

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

    /** Player stays put, the held entity is pulled toward a target in
     *  front of the crosshair. Proportional model (no inertia retained
     *  from the previous tick) so the entity tracks the aim smoothly
     *  without the overshoot the block-anchor spring would produce. */
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

        // Bias down by half the entity height so it floats centred on
        // the crosshair instead of hanging by its feet.
        val look = player.lookAngle
        val target = eye.add(look.scale(hook.ropeLength))
            .subtract(0.0, anchorEntity.bbHeight * 0.5, 0.0)

        var velocity = target.subtract(anchorEntity.position()).scale(ENTITY_HOLD_PULL_GAIN)
        val speed = velocity.length()
        if (speed > ENTITY_HOLD_MAX_VEL) {
            velocity = velocity.scale(ENTITY_HOLD_MAX_VEL / speed)
        }
        velocity = velocity.add(0.0, GRAVITY_OFFSET, 0.0)

        anchorEntity.deltaMovement = velocity
        anchorEntity.hurtMarked = true
        anchorEntity.fallDistance = 0.0
        return true
    }
}
