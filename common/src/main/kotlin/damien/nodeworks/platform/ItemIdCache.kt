package damien.nodeworks.platform

import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.item.Item
import java.util.concurrent.ConcurrentHashMap

/**
 * Cached registry-id strings for [Item]. `BuiltInRegistries.ITEM.getKey(item).toString()`
 * allocates a fresh string per call and shows up as a hot allocation in storage
 * scans, route filtering, and per-tick BE polling. Items live for the JVM's
 * lifetime so a strongly-keyed map is safe.
 */
object ItemIdCache {
    private val cache = ConcurrentHashMap<Item, String>()

    fun get(item: Item): String? {
        cache[item]?.let { return it }
        val id = BuiltInRegistries.ITEM.getKey(item)?.toString() ?: return null
        cache[item] = id
        return id
    }

    fun clear() {
        cache.clear()
    }
}
