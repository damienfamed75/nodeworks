package damien.nodeworks.item

import damien.nodeworks.entity.GrappleBeamHookEntity
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.player.Player
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Server-side tracker for in-flight / attached Grapple Beam hooks. One
 * hook per player at a time, dropped on player disconnect by the
 * loader-side lifecycle handler. [GrappleBeamPhysics] reads this each
 * server tick to apply the pull force.
 */
object GrappleBeamSessions {

    /** Player UUID, hook entity id. Both ConcurrentHashMap accesses happen on
     *  the main server thread today but the map is shared with the player-
     *  disconnect hook on the netty handler thread, so it has to be
     *  concurrent. */
    private val active: ConcurrentHashMap<UUID, Int> = ConcurrentHashMap()

    /** Returns the player's currently-tracked hook (in-flight or attached),
     *  or null if none. Validates the entity still exists, clearing the
     *  entry if the hook was removed without going through [release]. */
    fun current(player: Player): GrappleBeamHookEntity? {
        val id = active[player.uuid] ?: return null
        val level = player.level() as? ServerLevel ?: return null
        val entity = level.getEntity(id) as? GrappleBeamHookEntity
        if (entity == null || entity.isRemoved) {
            active.remove(player.uuid, id)
            return null
        }
        return entity
    }

    /** Replaces (and discards) any prior hook for this player, then tracks
     *  the new one. */
    fun start(player: Player, hook: GrappleBeamHookEntity) {
        release(player)
        active[player.uuid] = hook.id
    }

    /** Discards the player's current hook and clears the entry. No-op when
     *  the player has no active hook. */
    fun release(player: Player) {
        val id = active.remove(player.uuid) ?: return
        val level = player.level() as? ServerLevel ?: return
        val entity = level.getEntity(id) as? GrappleBeamHookEntity
        if (entity != null && !entity.isRemoved) {
            entity.discard()
        }
    }

    /** Loader-side lifecycle hook: clear the entry on player disconnect.
     *  The hook entity itself is still discarded by the player's own logout
     *  flow, this just drops the orphaned tracking row. */
    fun clearForPlayer(playerUuid: UUID) {
        active.remove(playerUuid)
    }

    /** Snapshot of active (playerUuid, hookEntityId) pairs. Snapshotted so the
     *  server tick loop can release entries during iteration without
     *  ConcurrentModificationException. */
    fun snapshot(): List<Pair<UUID, Int>> = active.entries.map { it.key to it.value }
}
