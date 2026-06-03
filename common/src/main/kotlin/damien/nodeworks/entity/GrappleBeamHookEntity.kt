package damien.nodeworks.entity

import damien.nodeworks.registry.ModEntityTypes
import damien.nodeworks.registry.ModItems
import damien.nodeworks.registry.ModSoundEvents
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.sounds.SoundSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.projectile.ItemSupplier
import net.minecraft.world.entity.projectile.Projectile
import net.minecraft.world.entity.projectile.ProjectileUtil
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.Level
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.EntityHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3

/**
 * Grapple Beam hook projectile. Flies forward at [HOOK_SPEED]
 * blocks/tick, sticks to the first hit (block or entity), and idles
 * there until released.
 *
 * Server is authoritative for the attach state. The synched `attached`
 * flag flips on hit so the client renderer can switch from flight-mode
 * to taut-line mode and the beam endpoint stays stable.
 */
class GrappleBeamHookEntity : Projectile, ItemSupplier {

    override fun getItem(): ItemStack = ItemStack(ModItems.GRAPPLE_BEAM)


    constructor(type: EntityType<out GrappleBeamHookEntity>, level: Level) : super(type, level)

    constructor(level: Level, owner: LivingEntity) : super(ModEntityTypes.GRAPPLE_BEAM_HOOK, level) {
        setOwner(owner)
        setPos(owner.x, owner.eyeY - 0.1, owner.z)
        // Hook flies straight, no projectile arc.
        isNoGravity = true
    }

    companion object {
        /** Blocks per tick. 2.5 b/t ≈ 50 b/s, fast enough that a 32-block
         *  range tops out under one second of flight. */
        const val HOOK_SPEED: Double = 2.5

        /** Max ticks the hook can be in-flight before auto-discarding to
         *  avoid orphans (loaded-chunk edge case). 60 t = 3 s, comfortably
         *  above HOOK_SPEED × max grapple range. */
        const val MAX_FLIGHT_TICKS: Int = 60

        private val DATA_ATTACHED: EntityDataAccessor<Boolean> =
            SynchedEntityData.defineId(GrappleBeamHookEntity::class.java, EntityDataSerializers.BOOLEAN)

        private val DATA_ATTACHED_ENTITY_ID: EntityDataAccessor<Int> =
            SynchedEntityData.defineId(GrappleBeamHookEntity::class.java, EntityDataSerializers.INT)
    }

    override fun defineSynchedData(builder: SynchedEntityData.Builder) {
        builder.define(DATA_ATTACHED, false)
        builder.define(DATA_ATTACHED_ENTITY_ID, 0)
    }

    var attached: Boolean
        get() = entityData.get(DATA_ATTACHED)
        private set(value) { entityData.set(DATA_ATTACHED, value) }

    /** ID of the entity the hook latched onto, 0 if attached to a block
     *  or in flight. */
    var attachedEntityId: Int
        get() = entityData.get(DATA_ATTACHED_ENTITY_ID)
        private set(value) { entityData.set(DATA_ATTACHED_ENTITY_ID, value) }

    private var flightTicks: Int = 0

    /** Max grapple range snapshotted from the server config at spawn time. */
    var maxRange: Double = 24.0

    /** Current rope length for the swing constraint. -1 means "not yet
     *  attached / not yet sized"; [GrappleBeamPhysics] seeds it to the
     *  attach-time player-anchor distance on its first tick and reels it
     *  in from there. */
    var ropeLength: Double = -1.0

    override fun tick() {
        super.tick()

        if (attached) {
            deltaMovement = Vec3.ZERO
            // Follow the attached entity so the anchor and beam endpoint
            // track it. Discard if the entity vanished.
            val followId = attachedEntityId
            if (followId != 0) {
                val target = level().getEntity(followId)
                if (target == null || target.isRemoved) {
                    if (!level().isClientSide) discard()
                    return
                }
                setPos(target.x, target.y + target.bbHeight * 0.5, target.z)
            }
            return
        }

        flightTicks++
        if (flightTicks > MAX_FLIGHT_TICKS && !level().isClientSide) {
            discard()
            return
        }

        val current = position()
        val next = current.add(deltaMovement)

        // Block raycast for this tick's segment.
        val blockHit: BlockHitResult = level().clip(
            ClipContext(
                current, next,
                ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE,
                this,
            )
        )
        val segmentEnd: Vec3 = if (blockHit.type != HitResult.Type.MISS) blockHit.location else next
        // Entity scan along the same segment, capped by the block hit.
        // Mirrors GrappleBeamItem's instant-fire filter: skipped entirely
        // when the policy forbids it, players never grappleable.
        val entityHit: EntityHitResult? = if (damien.nodeworks.script.ServerPolicy.current.grappleEntities) {
            ProjectileUtil.getEntityHitResult(
                level(), this, current, segmentEnd,
                boundingBox.expandTowards(deltaMovement).inflate(1.0),
            ) { e -> e.isPickable && e !== owner && e is LivingEntity && e !is Player }
        } else null

        when {
            entityHit != null -> onHit(entityHit)
            blockHit.type != HitResult.Type.MISS -> onHit(blockHit)
            else -> setPos(next.x, next.y, next.z)
        }
    }

    override fun onHitEntity(result: EntityHitResult) {
        super.onHitEntity(result)
        if (level().isClientSide) return
        setPos(result.location.x, result.location.y, result.location.z)
        attached = true
        attachedEntityId = result.entity.id
        deltaMovement = Vec3.ZERO
        playActivateSound()
    }

    override fun onHitBlock(result: BlockHitResult) {
        super.onHitBlock(result)
        if (level().isClientSide) return
        setPos(result.location.x, result.location.y, result.location.z)
        attached = true
        attachedEntityId = 0
        deltaMovement = Vec3.ZERO
        playActivateSound()
    }

    /** Spawn-time attach used by the raycast-style fire path: skip projectile
     *  flight entirely, the hook starts already latched to [point]. Optional
     *  [entityId] is set when latching to a LivingEntity instead of a block. */
    fun attachInstantly(point: Vec3, entityId: Int) {
        setPos(point.x, point.y, point.z)
        deltaMovement = Vec3.ZERO
        attached = true
        attachedEntityId = entityId
        if (!level().isClientSide) playActivateSound()
    }

    /** Broadcast the activate impulse from the hook's world position so
     *  the sound originates at the point of latching rather than at the
     *  player. Passing `null` for the omitted player so the wielder also
     *  hears it. */
    private fun playActivateSound() {
        level().playSound(
            null, x, y, z,
            ModSoundEvents.GRAPPLE_BEAM_ACTIVATE,
            SoundSource.PLAYERS, 2.0f, 1.0f,
        )
    }

    /** Server-side removal hook: fire the deactivate impulse when an
     *  attached hook is despawned for any reason (release packet,
     *  attached entity removed, flight timeout). The `attached` gate
     *  skips the impulse for in-flight discards so a miss doesn't make
     *  a release sound. */
    override fun remove(reason: RemovalReason) {
        if (!level().isClientSide && attached) {
            level().playSound(
                null, x, y, z,
                ModSoundEvents.GRAPPLE_BEAM_DEACTIVATE,
                SoundSource.PLAYERS, 2.0f, 1.0f,
            )
        }
        super.remove(reason)
    }

    /** The entity the hook is following, or null if attached to a block /
     *  still in flight. Resolved fresh each tick so the client side stays
     *  consistent with `entityData`. */
    fun attachedEntity(): Entity? {
        val id = attachedEntityId
        if (id == 0) return null
        return level().getEntity(id)
    }

    override fun isAttackable(): Boolean = false
    override fun isPickable(): Boolean = false
}
