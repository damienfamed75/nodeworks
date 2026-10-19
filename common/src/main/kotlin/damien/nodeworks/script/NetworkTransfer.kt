package damien.nodeworks.script

import damien.nodeworks.card.StorageSideCapability
import damien.nodeworks.network.CardSnapshot
import damien.nodeworks.network.ChannelFilter
import damien.nodeworks.network.NetworkSnapshot
import damien.nodeworks.platform.PlatformServices
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.ItemStack

/**
 * The two declarative moves between an inventory and the network's Storage Cards:
 * items going in, spread by priority or round robin, and filter-matched items
 * coming out. The Import and Export Chests run these on their tick, so every
 * device that moves items without a script shares one set of rules, and a fix to
 * accept and deny handling or channel scoping lands everywhere at once.
 */
object NetworkTransfer {

    /** Where the next round robin insert starts. Owned and persisted by the device,
     *  the mover only advances it. */
    class RoundRobinCursor(var index: Int = 0)

    /** The Storage Cards a move may use: on [channel], and never one facing a position
     *  in [exclude], so an inventory that both holds a Storage Card and feeds the network
     *  does not trade with itself. */
    fun destinationCards(
        snapshot: NetworkSnapshot,
        channel: ChannelFilter,
        exclude: Set<BlockPos> = emptySet(),
    ): List<CardSnapshot> = NetworkStorageHelper.getStorageCards(snapshot).filter { card ->
        channel.matches(card.channel) &&
            (exclude.isEmpty() || (card.capability as? StorageSideCapability)?.adjacentPos !in exclude)
    }

    /**
     * Insert as much of [stack] as the network takes, shrinking [stack] in place.
     * Returns the count moved.
     *
     * Without a [cursor] the Storage Cards are tried in priority order and a card
     * takes as much as it can. With a [cursor] one item moves per step and the
     * cursor advances past the card that took it, so items spread across cards
     * instead of piling on the highest priority one. A card that refuses (channel,
     * rule, full) is skipped without consuming a step, and because the cursor
     * lands after the accepting card a refusing card at the cursor can never park
     * the rotation. Both paths honour every Storage Card's accept and deny rules.
     */
    fun insert(
        level: ServerLevel,
        snapshot: NetworkSnapshot,
        cache: NetworkInventoryCache?,
        stack: ItemStack,
        channel: ChannelFilter,
        cursor: RoundRobinCursor? = null,
        exclude: Set<BlockPos> = emptySet(),
    ): Int {
        if (stack.isEmpty) return 0
        if (cursor == null) {
            val moved = NetworkStorageHelper.insertItemStack(level, snapshot, stack, cache, channel, exclude)
            if (moved > 0) stack.shrink(moved)
            return moved
        }
        val cards = destinationCards(snapshot, channel, exclude)
        if (cards.isEmpty()) return 0
        val registries = level.registryAccess()
        var total = 0
        while (!stack.isEmpty) {
            val startIndex = cursor.index.mod(cards.size)
            var placed = false
            for (offset in 0 until cards.size) {
                val idx = (startIndex + offset).mod(cards.size)
                val card = cards[idx]
                val cap = card.capability as? StorageSideCapability
                val itemId = BuiltInRegistries.ITEM.getKey(stack.item)?.toString()
                // Read before shrinking, an emptied stack reports an empty patch.
                val patch = stack.componentsPatch
                val hasData = !patch.isEmpty
                if (cap != null && !cap.acceptsItem(stack, registries)) continue
                val storage = NetworkStorageHelper.getStorage(level, card) ?: continue
                val moved = PlatformServices.storage.insertItemStack(storage, stack.copyWithCount(1))
                if (moved > 0) {
                    stack.shrink(moved)
                    if (itemId != null) {
                        cache?.onInserted(itemId, hasData, moved.toLong(), patch)
                        cache?.onChannelDelta(card.channel, itemId, patch, moved.toLong())
                    }
                    cursor.index = (idx + 1).mod(cards.size)
                    total += moved
                    placed = true
                    break
                }
            }
            if (!placed) break
        }
        return total
    }

    /**
     * Pull items matching any of [rules] out of the network's Storage Cards, up to
     * [maxItems], handing each extracted stack to [sink]. The sink returns how many
     * it kept, anything it could not take goes straight back to network storage.
     * Returns the total the sink kept. Rules use the Storage Card syntax and match
     * the full stack, so `id[components]` narrows to that variant. An empty rule
     * list pulls nothing, the caller decides whether that means idle.
     */
    fun pull(
        level: ServerLevel,
        snapshot: NetworkSnapshot,
        cache: NetworkInventoryCache?,
        rules: List<String>,
        channel: ChannelFilter,
        maxItems: Long,
        exclude: Set<BlockPos> = emptySet(),
        sink: (ItemStack) -> Int,
    ): Long {
        if (rules.isEmpty() || maxItems <= 0L) return 0L
        val registries = level.registryAccess()
        val matches: (ItemStack) -> Boolean = { stack -> rules.any { CardHandle.matchesFilter(stack, it, registries) } }
        var remaining = maxItems
        var total = 0L
        for (card in destinationCards(snapshot, channel, exclude)) {
            if (remaining <= 0L) break
            val storage = NetworkStorageHelper.getStorage(level, card) ?: continue
            val extracted = PlatformServices.storage.extractStacksByPredicate(storage, matches, remaining)
            for (stack in extracted) {
                if (stack.isEmpty) continue
                val placed = sink(stack)
                if (placed > 0) {
                    val itemId = BuiltInRegistries.ITEM.getKey(stack.item)?.toString()
                    if (itemId != null) {
                        cache?.onExtracted(itemId, !stack.componentsPatch.isEmpty, placed.toLong(), stack.componentsPatch)
                    }
                    remaining -= placed
                    total += placed
                }
                if (placed < stack.count) {
                    // The sink filled mid-stack, the rest goes back where it came from.
                    val leftover = stack.copyWithCount(stack.count - placed)
                    NetworkStorageHelper.insertItemStack(level, snapshot, leftover, cache)
                }
            }
        }
        return total
    }
}
