package damien.nodeworks.item

import damien.nodeworks.entity.GrappleBeamHookEntity
import damien.nodeworks.script.ServerPolicy
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.projectile.ProjectileUtil
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.Level
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.HitResult

/**
 * Fires a Grapple Beam hook via instant raycast on right-click. No projectile
 * flight: the server raytraces from the player's eye out to the configured
 * max distance, picks the first block or living entity hit, and spawns the
 * hook already-attached at that point.
 *
 * The hook entity continues to exist for the duration of the session so the
 * beam renderer can find it, but its projectile [tick] code is short-
 * circuited by the `attached` flag.
 */
class GrappleBeamItem(properties: Properties) : Item(properties) {

    override fun use(level: Level, player: Player, hand: InteractionHand): InteractionResult {
        if (level.isClientSide) {
            // FAIL on the client so vanilla doesn't run
            // ItemInHandRenderer.itemUsed(hand). That call resets
            // mainHandHeight to 0 and replays the equip-up bob on every
            // 4-tick Item.use re-fire while right-click is held, and
            // again on misses where no grapple happens. The
            // ServerboundUseItemPacket is sent BEFORE Item.use runs, so
            // the server still receives and processes every right-click.
            // FAIL only suppresses client cosmetic side-effects.
            return InteractionResult.FAIL
        }

        // Vanilla re-fires Item.use every 4 ticks while right-click is held;
        // ignore those re-fires while a session is already active.
        if (GrappleBeamSessions.current(player) != null) return InteractionResult.CONSUME

        val maxDistance = ServerPolicy.current.grappleMaxDistance.toDouble()
        val eye = player.eyePosition
        val look = player.lookAngle
        val rayEnd = eye.add(look.scale(maxDistance))

        val blockHit = level.clip(
            ClipContext(eye, rayEnd, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player)
        )
        val segmentEnd = if (blockHit.type != HitResult.Type.MISS) blockHit.location else rayEnd

        // Entity scan along the same segment, capped by the block hit so the
        // hook can't latch onto an entity behind a wall. Skipped entirely
        // when the server policy forbids entity grappling, so the beam
        // passes through mobs and lands on whatever block is behind them.
        // Players are always excluded, no grappling other players.
        val entityHit = if (ServerPolicy.current.grappleEntities) {
            val entityFilter = java.util.function.Predicate<Entity> { e ->
                e.isPickable && e !== player && e is LivingEntity && e !is Player
            }
            ProjectileUtil.getEntityHitResult(
                player, eye, segmentEnd,
                AABB(eye, segmentEnd).inflate(1.0), entityFilter, 0.0,
            )
        } else null

        val anchorPoint = when {
            entityHit != null -> entityHit.location
            blockHit.type != HitResult.Type.MISS -> blockHit.location
            else -> return InteractionResult.CONSUME
        }
        val attachedEntityId = entityHit?.entity?.id ?: 0

        player.swing(hand)

        val hook = GrappleBeamHookEntity(level, player).apply {
            setPos(anchorPoint.x, anchorPoint.y, anchorPoint.z)
            this.maxRange = maxDistance
            attachInstantly(anchorPoint, attachedEntityId)
        }
        level.addFreshEntity(hook)
        GrappleBeamSessions.start(player, hook)

        // No fire-time sound. The activate impulse plays on attach
        // (see GrappleBeamHookEntity.onHit*) and reads as one cohesive
        // hit + latch beat.

        return InteractionResult.CONSUME
    }

    override fun isFoil(stack: ItemStack): Boolean = false
}
