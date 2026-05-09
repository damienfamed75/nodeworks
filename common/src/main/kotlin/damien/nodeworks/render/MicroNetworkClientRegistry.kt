package damien.nodeworks.render

import java.util.UUID

/**
 * Client-side index of network UUIDs that belong to a Processing Handler's
 * micro-network. Renderers consult this to switch the laser texture from the
 * standard streak to the hazard-stripe pattern, so micro-networks read as a
 * visually distinct entity.
 *
 * Refcounted: multiple Processing Handlers anchoring the same micro-network
 * each register the shared id, and the entry only drops when the last one
 * unloads. Without the count, unloading one chunk's handler would clear the
 * micro flag even while another chunk's handler still anchors the same net.
 */
object MicroNetworkClientRegistry {

    /** Tint applied to Connectables on a micro-network when nothing more
     *  specific is available. Matches [PipeLaserBeam]'s `MICRO_GLOW_R/G/B`
     *  so the chest body, the in-pipe beam, and the inter-Node beam all read
     *  as the same hazard yellow (#FFDC3B). */
    const val MICRO_NETWORK_COLOR: Int = 0xFFDC3B

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
    fun isMicro(id: UUID?): Boolean = id != null && id in refCounts

    @Synchronized
    fun reset() {
        refCounts.clear()
    }
}
