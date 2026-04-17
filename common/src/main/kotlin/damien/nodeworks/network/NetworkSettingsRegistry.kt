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
        val glowStyle: Int = 0
        // Future: logLevel, etc.
    )

    private val registry = ConcurrentHashMap<UUID, NetworkSettings>()

    /** Register or update settings for a network. Called by controllers on client sync. */
    fun update(networkId: UUID, settings: NetworkSettings) {
        registry[networkId] = settings
    }

    /** Update just the color for a network. */
    fun updateColor(networkId: UUID, color: Int) {
        registry.compute(networkId) { _, existing ->
            (existing ?: NetworkSettings()).copy(color = color)
        }
    }

    /** Update just the glow style for a network. */
    fun updateGlowStyle(networkId: UUID, glowStyle: Int) {
        registry.compute(networkId) { _, existing ->
            (existing ?: NetworkSettings()).copy(glowStyle = glowStyle)
        }
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
