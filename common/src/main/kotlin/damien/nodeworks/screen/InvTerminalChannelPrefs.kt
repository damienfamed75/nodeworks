package damien.nodeworks.screen

import damien.nodeworks.network.ChannelFilter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Each online player's Inventory Terminal channel per network, as their client reported
 * it on join. The client saves these and is the source of truth. The server only holds
 * a copy so a terminal opens straight onto the saved channel without waiting for the
 * client, and drops it when the player logs out.
 */
object InvTerminalChannelPrefs {
    /** Cap on entries taken from one client, the payload is player-controlled. */
    const val MAX_ENTRIES = 1024

    private val byPlayer = ConcurrentHashMap<UUID, MutableMap<UUID, ChannelFilter>>()
    private val lastReplace = ConcurrentHashMap<UUID, Long>()

    /** Adopt the client's full list. Clients send it once on join, so anything faster
     *  than once a second is a misbehaving client and is ignored. */
    fun replace(player: UUID, entries: Map<String, Int>, tick: Long) {
        val last = lastReplace[player]
        if (last != null && tick - last in 0 until 20) return
        lastReplace[player] = tick
        val parsed = HashMap<UUID, ChannelFilter>()
        for ((network, channel) in entries) {
            if (parsed.size >= MAX_ENTRIES) break
            val id = runCatching { UUID.fromString(network) }.getOrNull() ?: continue
            val filter = ChannelFilter.fromNbtInt(channel)
            if (filter != ChannelFilter.All) parsed[id] = filter
        }
        byPlayer[player] = ConcurrentHashMap(parsed)
    }

    fun get(player: UUID, network: UUID?): ChannelFilter =
        network?.let { byPlayer[player]?.get(it) } ?: ChannelFilter.All

    fun set(player: UUID, network: UUID?, filter: ChannelFilter) {
        if (network == null) return
        val prefs = byPlayer.getOrPut(player) { ConcurrentHashMap() }
        if (filter == ChannelFilter.All) prefs.remove(network)
        else if (network in prefs || prefs.size < MAX_ENTRIES) prefs[network] = filter
    }

    fun clear(player: UUID) {
        byPlayer.remove(player)
        lastReplace.remove(player)
    }
}
