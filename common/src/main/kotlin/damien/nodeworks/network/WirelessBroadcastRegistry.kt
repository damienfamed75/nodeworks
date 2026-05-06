package damien.nodeworks.network

import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.Level
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Server-side index of Receiver Antennas paired to an EXPORT_CHEST-kind
 * Broadcast Antenna. The Export Chest reads this on tick to find where to
 * fan items out to. Keyed by the broadcast's `frequencyId`, value is the
 * set of receiver positions tagged with their dimension.
 */
object WirelessBroadcastRegistry {

    data class Receiver(val dimension: ResourceKey<Level>, val pos: BlockPos)

    private val byFrequency = ConcurrentHashMap<UUID, MutableSet<Receiver>>()

    fun register(frequencyId: UUID, dimension: ResourceKey<Level>, pos: BlockPos) {
        byFrequency.computeIfAbsent(frequencyId) {
            java.util.Collections.newSetFromMap(ConcurrentHashMap())
        }.add(Receiver(dimension, pos))
    }

    fun unregister(frequencyId: UUID, dimension: ResourceKey<Level>, pos: BlockPos) {
        val set = byFrequency[frequencyId] ?: return
        set.remove(Receiver(dimension, pos))
        if (set.isEmpty()) byFrequency.remove(frequencyId)
    }

    fun getReceivers(frequencyId: UUID): Collection<Receiver> =
        byFrequency[frequencyId] ?: emptySet()

    fun clear() {
        byFrequency.clear()
    }
}
