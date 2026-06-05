package damien.nodeworks.api

import damien.nodeworks.network.Connectable
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Process-wide registry of [DeviceType] implementations. Mods call [register] from their
 * init code. Nodeworks itself reads through [byTypeId] (Lua dispatch path) and [byBE]
 * (network discovery dispatch path) to drive the SPI.
 *
 * Reads happen on the main thread during ticks, registration happens during mod init.
 * Backed by a [CopyOnWriteArrayList] so an init-time register can't race with a
 * concurrent discovery walk while still keeping the lookup loops allocation-free.
 *
 * Two types matching the same BE class (e.g. one registers a superclass, another a
 * subclass) resolve to whichever was registered first. Don't rely on this behavior,
 * pick disjoint [DeviceType.beClass] values.
 */
object DeviceRegistry {
    private val types = CopyOnWriteArrayList<DeviceType<*>>()

    /** Add [type] to the registry. Safe to call multiple times with the same instance
     *  but pointless, the duplicate is left in the list and slows future lookups. */
    fun register(type: DeviceType<*>) {
        types.add(type)
    }

    /** Resolve a registered type by its [DeviceType.typeId]. Returns null when no mod
     *  has registered that id. Used by `network:device(typeId, name)` to find the
     *  handle factory. */
    fun byTypeId(id: String): DeviceType<*>? = types.firstOrNull { it.typeId == id }

    /** Resolve a registered type from a live [Connectable]. Used by the discovery walk
     *  to decide whether a visited BE participates in the SPI snapshot path. Match is
     *  by [Class.isInstance] so subclasses count. */
    fun byBE(connectable: Connectable): DeviceType<*>? =
        types.firstOrNull { it.beClass.isInstance(connectable) }

    /** Snapshot of all currently registered types. Used by tools and diagnostic
     *  surfaces that want to enumerate the SPI. */
    fun all(): List<DeviceType<*>> = types.toList()
}
