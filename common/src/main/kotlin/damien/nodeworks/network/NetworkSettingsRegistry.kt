package damien.nodeworks.network

import damien.nodeworks.render.NodeConnectionRenderer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Client-side registry of network settings keyed by network UUID.
 * Controllers register their settings here when they load or sync on the client.
 * Any renderer or screen can look up settings by UUID without BFS.
 */
object NetworkSettingsRegistry {

    data class NetworkSettings(
        val color: Int = NodeConnectionRenderer.DEFAULT_NETWORK_COLOR,
        val glowStyle: Int = 0,
        /** Whether to render the laser beams between this network's connectables.
         *  Nodes + glows are unaffected, only the inter-node beams toggle. */
        val laserEnabled: Boolean = true,
        /** Beam render style: 0 = Fancy (animated prism + billboarded glow),
         *  1 = Fast (single thin colored line). */
        val laserMode: Int = LASER_MODE_FANCY,
    )

    const val LASER_MODE_FANCY = 0
    const val LASER_MODE_FAST = 1

    private val registry = ConcurrentHashMap<UUID, NetworkSettings>()

    /**
     * Client-side hook fired whenever a network's settings change. Used to invalidate
     * the BlockTintCache for Connectable blocks belonging to that network, without
     * it, the tint source returns the new colour but the cached per-chunk tint still
     * shows the old one until the chunk re-renders for some unrelated reason.
     *
     * The hook is `null` on the logical server since this registry is also populated
     * from BE NBT load there, NeoForgeClientSetup wires it up at client init.
     */
    var onChanged: ((UUID) -> Unit)? = null

    /** Register or update settings for a network. Called by controllers on client sync. */
    fun update(networkId: UUID, settings: NetworkSettings) {
        val prev = registry.put(networkId, settings)
        if (prev != settings) onChanged?.invoke(networkId)
    }

    /**
     * Fire the [onChanged] hook for [networkId] without mutating the registry. Called
     * from non-controller Connectable BEs (Node, Terminal, Variable, …) after their
     * `loadAdditional` runs on the client, so that newly-connected blocks get their
     * tint cache refreshed in the same frame the BE syncs. No-op on the logical
     * server because [onChanged] is only set by [NodeConnectionRenderer.register].
     */
    fun notifyConnectableChanged(networkId: UUID?) {
        if (networkId != null) onChanged?.invoke(networkId)
    }

    /** Update just the color for a network. */
    fun updateColor(networkId: UUID, color: Int) {
        var changed = false
        registry.compute(networkId) { _, existing ->
            val base = existing ?: NetworkSettings()
            if (base.color != color) changed = true
            base.copy(color = color)
        }
        if (changed) onChanged?.invoke(networkId)
    }

    /** Update just the glow style for a network. */
    fun updateGlowStyle(networkId: UUID, glowStyle: Int) {
        var changed = false
        registry.compute(networkId) { _, existing ->
            val base = existing ?: NetworkSettings()
            if (base.glowStyle != glowStyle) changed = true
            base.copy(glowStyle = glowStyle)
        }
        if (changed) onChanged?.invoke(networkId)
    }

    /** Get settings for a network, or defaults if not registered. */
    fun get(networkId: UUID?): NetworkSettings {
        if (networkId == null) return NetworkSettings()
        return registry[networkId] ?: NetworkSettings()
    }

    /** Get the color for a network, or default. */
    fun getColor(networkId: UUID?): Int = get(networkId).color

    /** Get the glow style for a network, or default. */
    fun getGlowStyle(networkId: UUID?): Int = get(networkId).glowStyle

    /** Remove a network's settings (e.g. controller unloaded). */
    fun remove(networkId: UUID) {
        registry.remove(networkId)
    }

    /** Clear all entries (e.g. on world disconnect). */
    fun clear() {
        registry.clear()
    }
}
