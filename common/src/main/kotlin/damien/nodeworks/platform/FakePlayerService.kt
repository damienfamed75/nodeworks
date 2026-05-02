package damien.nodeworks.platform

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.block.state.BlockState
import java.util.UUID

/**
 * Resolves a server-side fake player keyed on a placer's UUID and runs script-driven
 * block mutations through it so claim mods (FTB Chunks, Open Parties Lite) and vanilla
 * spawn protection see a real actor with a real UUID.
 *
 * Loader-specific because FakePlayer construction differs (NeoForge:
 * `FakePlayerFactory.get`, Fabric: a Carpet-style `EntityPlayerMPFake` or custom impl).
 *
 * The fallback profile [NODEWORKS_FALLBACK_UUID] is used when the placer's UUID is null,
 * mirroring the convention CC: Tweaked uses for pre-update placed computers. Legacy
 * worlds load with null and any mutations they perform are attributed to the static
 * `Nodeworks` profile rather than crashing.
 */
interface FakePlayerService {
    /** Resolve a server-side FakePlayer for [ownerUuid] in [level], using the static
     *  fallback profile when [ownerUuid] is null. The returned player is cached per
     *  level by the underlying loader, repeated calls are cheap. */
    fun get(level: ServerLevel, ownerUuid: UUID?): ServerPlayer

    /** Pre-break permission check. Posts the loader's break event with the FakePlayer
     *  + checks vanilla spawn protection. Returns true when the caller should proceed
     *  with mutating the block. */
    fun mayBreak(level: ServerLevel, pos: BlockPos, state: BlockState, ownerUuid: UUID?): Boolean

    /** Permission-gated placement: spawn-protection check, then [mutate] runs, then the
     *  loader's post-place event fires. If a listener cancels the event, the snapshot
     *  is restored AND [onRollback] is invoked so the caller can refund items or undo
     *  other side effects. Returns true when the placement stands at the end of all
     *  checks, false when it was rejected at any stage.
     *
     *  [placedAgainst] is the state of the block the new placement rests against (one
     *  block back along the placement direction), used by the post-place event for
     *  context. [mutate] should return false to abort cleanly without invoking the
     *  event (e.g. when item extraction fails). */
    fun tryPlace(
        level: ServerLevel,
        pos: BlockPos,
        placedAgainst: BlockState,
        ownerUuid: UUID?,
        mutate: () -> Boolean,
        onRollback: () -> Unit,
    ): Boolean

    companion object {
        /** Deterministic UUID derived from `OfflinePlayer:Nodeworks`, matching Bukkit's
         *  offline-player UUID convention so it slots into existing permission tooling
         *  that expects this shape. */
        val NODEWORKS_FALLBACK_UUID: UUID =
            UUID.nameUUIDFromBytes("OfflinePlayer:Nodeworks".toByteArray(Charsets.UTF_8))

        /** Fallback display name for the legacy / null-owner path. Surfaces in server
         *  logs and claim-mod denial messages. */
        const val NODEWORKS_FALLBACK_NAME: String = "[Nodeworks]"
    }
}

/** No-op service used in unit tests / loaders that haven't registered an impl yet.
 *  Allows mutations and crashes loud on any FakePlayer access. Loaders must register
 *  before any script-driven world mutations run, so the throw flags forgotten wiring. */
object NoopFakePlayerService : FakePlayerService {
    override fun get(level: ServerLevel, ownerUuid: UUID?): ServerPlayer =
        throw IllegalStateException("FakePlayerService not registered, no FakePlayer available.")

    override fun mayBreak(level: ServerLevel, pos: BlockPos, state: BlockState, ownerUuid: UUID?): Boolean = true
    override fun tryPlace(
        level: ServerLevel,
        pos: BlockPos,
        placedAgainst: BlockState,
        ownerUuid: UUID?,
        mutate: () -> Boolean,
        onRollback: () -> Unit,
    ): Boolean = mutate()
}
