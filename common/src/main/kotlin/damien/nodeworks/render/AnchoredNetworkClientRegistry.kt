package damien.nodeworks.render

import java.util.UUID

/**
 * Client-side index of network UUIDs that a Node anchors because the network has no
 * Network Controller. Such a network has no settings to look up, so without this it
 * rendered in the unconnected grey and read as broken. Anchored ids render plain white
 * through [damien.nodeworks.network.NetworkSettingsRegistry.get].
 *
 * Refcounted like [MicroNetworkClientRegistry]: the anchoring Node registers on load and
 * on every id change, and the entry drops when it unloads or stops anchoring.
 */
object AnchoredNetworkClientRegistry {

    const val ANCHORED_NETWORK_COLOR: Int = 0xFFFFFF

    private val refCounts = HashMap<UUID, Int>()

    @Synchronized
    fun register(id: UUID) {
        refCounts.merge(id, 1, Int::plus)
    }

    @Synchronized
    fun unregister(id: UUID) {
        val current = refCounts[id] ?: return
        if (current <= 1) refCounts.remove(id) else refCounts[id] = current - 1
    }

    @Synchronized
    fun isAnchored(id: UUID?): Boolean = id != null && id in refCounts
}
