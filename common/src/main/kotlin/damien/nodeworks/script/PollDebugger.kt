package damien.nodeworks.script

import damien.nodeworks.network.CardSnapshot
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import java.util.UUID

/**
 * Per-player toggle that streams the round-robin polling cache's per-tick activity
 * to chat. Wired by [NetworkInventoryCache.tick] after each tick: when the listener
 * set is empty the call is a no-op so the production hot path pays nothing.
 *
 * Toggled via `/nwdebug poll`. The format is one chat line per tick per network:
 *
 *   `[poll] T+1234 (-25,70,10) cycle 2/5 sc=5: io_1, io_2`
 *
 *   - `T+...` is the server game time.
 *   - The xyz triple is the network entry node so the listener can tell which
 *     network the line came from when standing in a multi-network base.
 *   - `cycle X/Y` is the cycle index (the slice that was just polled) over the
 *     current cycle's slice count.
 *   - `sc=N` is the active sliceCount, useful for confirming adaptive backoff.
 *   - The trailing list is the card aliases that were just scanned. For idle
 *     trailing ticks (sliceCount > cards.size), this prints `(idle)`.
 *
 * On the cycle's last tick the line gets a `[cycle complete, changed=...]` tag.
 */
object PollDebugger {

    private val listeners = mutableSetOf<UUID>()

    /** Toggle [player]'s listener state. Returns the new state (true = now listening). */
    fun toggle(player: ServerPlayer): Boolean {
        return if (player.uuid in listeners) {
            listeners.remove(player.uuid)
            false
        } else {
            listeners.add(player.uuid)
            true
        }
    }

    /** Cheap check used by the cache's hot path to skip work entirely when no
     *  one is listening. */
    fun hasListeners(): Boolean = listeners.isNotEmpty()

    /**
     * Emit one chat line per listener describing what was polled on this tick.
     *
     *  @param level network's server level (for resolving online players).
     *  @param networkEntryNode network's entry node, identifies the network in chat.
     *  @param tick server game time, for "T+..." prefix.
     *  @param cycleTick slice index that was just polled (BEFORE the cycle reset).
     *  @param sliceCount current per-cycle slice budget.
     *  @param polled cards that were actually scanned this tick (empty for idle ticks).
     *  @param cycleEnded true iff this tick was the final tick of the cycle.
     *  @param cycleChanged whether the cycle's diff produced changes (only meaningful when [cycleEnded] is true).
     */
    fun emit(
        level: ServerLevel,
        networkEntryNode: BlockPos,
        tick: Long,
        cycleTick: Int,
        sliceCount: Int,
        polled: List<CardSnapshot>,
        cycleEnded: Boolean,
        cycleChanged: Boolean,
    ) {
        if (listeners.isEmpty()) return

        val message = buildString {
            append("[poll] T+").append(tick)
            append(" (")
            append(networkEntryNode.x).append(',')
            append(networkEntryNode.y).append(',')
            append(networkEntryNode.z).append(") ")
            append("cycle ").append(cycleTick).append('/').append(sliceCount)
            append(" sc=").append(sliceCount).append(": ")
            if (polled.isEmpty()) {
                append("(idle)")
            } else {
                polled.joinTo(this, ", ") { it.effectiveAlias }
            }
            if (cycleEnded) {
                append(" [cycle complete, changed=").append(cycleChanged).append(']')
            }
        }
        val component = Component.literal(message).withStyle(ChatFormatting.GRAY)

        // Resolve listeners against the live player list and prune any UUIDs that
        // belong to logged-off players, otherwise the listener set leaks across
        // server restarts and disconnects.
        val server = level.server
        val stale = mutableListOf<UUID>()
        for (uuid in listeners) {
            val player = server.playerList.getPlayer(uuid)
            if (player == null) {
                stale.add(uuid)
            } else {
                player.sendSystemMessage(component)
            }
        }
        if (stale.isNotEmpty()) listeners.removeAll(stale.toSet())
    }
}
