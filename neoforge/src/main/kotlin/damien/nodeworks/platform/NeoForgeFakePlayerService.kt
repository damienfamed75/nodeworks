package damien.nodeworks.platform

import com.mojang.authlib.GameProfile
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.common.util.BlockSnapshot
import net.neoforged.neoforge.common.util.FakePlayer
import net.neoforged.neoforge.common.util.FakePlayerFactory
import net.neoforged.neoforge.event.level.BlockEvent
import net.neoforged.neoforge.event.level.block.BreakBlockEvent
import java.util.UUID

/**
 * NeoForge-backed [FakePlayerService]. Cached FakePlayer per (level, profile) via
 * [FakePlayerFactory.get]. Break/place gating dispatches the standard NeoForge events
 * so claim mods (FTB Chunks, Open Parties Lite, GriefDefender) see Nodeworks mutations
 * as a real player UUID and resolve permissions accordingly.
 */
class NeoForgeFakePlayerService : FakePlayerService {

    override fun get(level: ServerLevel, ownerUuid: UUID?): ServerPlayer {
        val uuid = ownerUuid ?: FakePlayerService.NODEWORKS_FALLBACK_UUID
        // Always use the static `[Nodeworks]` display name. UUID is what claim mods
        // key on, the display name only surfaces in server logs / denial messages
        // and a consistent "[Nodeworks]" tag is more readable than a synthesised
        // username keyed on the UUID.
        val profile = GameProfile(uuid, FakePlayerService.NODEWORKS_FALLBACK_NAME)
        return FakePlayerFactory.get(level, profile)
    }

    override fun mayBreak(level: ServerLevel, pos: BlockPos, state: BlockState, ownerUuid: UUID?): Boolean {
        val player = get(level, ownerUuid)
        // Spawn protection. Vanilla `isUnderSpawnProtection` already handles the
        // op-level check internally so an op-owned terminal can mine inside spawn
        // (matches vanilla behaviour for the placer themselves).
        if (level.server.isUnderSpawnProtection(level, pos, player)) return false
        val event = BreakBlockEvent(level, pos, state, player)
        NeoForge.EVENT_BUS.post(event)
        return !event.isCanceled
    }

    override fun tryPlace(
        level: ServerLevel,
        pos: BlockPos,
        placedAgainst: BlockState,
        ownerUuid: UUID?,
        mutate: () -> Boolean,
        onRollback: () -> Unit,
    ): Boolean {
        val player = get(level, ownerUuid)
        if (level.server.isUnderSpawnProtection(level, pos, player)) return false

        // Capture the pre-place snapshot so cancellation can restore via the
        // BlockSnapshot's stored state. EntityPlaceEvent reads `getCurrentState()`
        // off the live level after [mutate] has run, so the snapshot's stored state
        // (the OLD state) is what gets restored on cancel.
        val snapshot = BlockSnapshot.create(level.dimension(), level, pos)
        if (!mutate()) return false

        val event = BlockEvent.EntityPlaceEvent(snapshot, placedAgainst, player)
        NeoForge.EVENT_BUS.post(event)
        if (event.isCanceled) {
            // Restore the snapshot's pre-place state and let the caller refund any
            // side effects (item extraction, etc.) via [onRollback].
            level.setBlock(pos, snapshot.state, Block.UPDATE_ALL)
            onRollback()
            return false
        }
        return true
    }
}
