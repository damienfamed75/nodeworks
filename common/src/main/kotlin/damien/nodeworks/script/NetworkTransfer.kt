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
        /** The cards to rotate over, when the caller has already narrowed them. Null
         *  means every card on [channel] outside [exclude]. */
        cards: List<CardSnapshot>? = null,
    ): Int {
        if (stack.isEmpty) return 0
        if (cursor == null) {
            val moved = NetworkStorageHelper.insertItemStack(level, snapshot, stack, cache, channel, exclude)
            if (moved > 0) stack.shrink(moved)
            return moved
        }
        val cards = cards ?: destinationCards(snapshot, channel, exclude)
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
     * list pulls nothing, the caller decides whether that means idle. Anything that
     * fits neither the sink, the card it came from, nor the rest of the network is
     * handed to [spill], so nothing is ever lost.
     */
    fun pull(
        level: ServerLevel,
        snapshot: NetworkSnapshot,
        cache: NetworkInventoryCache?,
        rules: List<String>,
        channel: ChannelFilter,
        maxItems: Long,
        exclude: Set<BlockPos> = emptySet(),
        spill: (ItemStack) -> Unit,
        sink: (ItemStack) -> Int,
        /** The scan cursor for the inventory a card faces, so a run cut short by the move
         *  deadline carries on where it stopped next time rather than rescanning. */
        cursorFor: ((BlockPos) -> damien.nodeworks.platform.ScanCursor?)? = null,
        /** Where in the card list a run starts. Normally the top, so pulls follow card
         *  priority. A run the deadline stops partway leaves it at the card it did not
         *  reach, so over a few runs every card gets a turn, and a run that covers all the
         *  cards puts it back at the top. */
        rotation: RoundRobinCursor? = null,
    ): Long {
        // A blank rule is one still being typed. It must not read as "everything".
        val active = rules.filter { it.isNotBlank() }
        if (active.isEmpty() || maxItems <= 0L) return 0L
        val registries = level.registryAccess()
        val matches: (ItemStack) -> Boolean = { stack -> active.any { CardHandle.matchesFilter(stack, it, registries) } }
        var remaining = maxItems
        var total = 0L
        val cards = destinationCards(snapshot, channel, exclude)
        if (cards.isEmpty()) return 0L
        val start = rotation?.index?.takeIf { it in cards.indices } ?: 0
        var covered = 0
        while (covered < cards.size) {
            if (remaining <= 0L) break
            // Between cards only, so a card that was started is finished or left to its cursor.
            if (covered > 0 && damien.nodeworks.platform.MoveDeadline.expired()) break
            val card = cards[(start + covered) % cards.size]
            covered++
            val storage = NetworkStorageHelper.getStorage(level, card) ?: continue
            val cap = card.capability as? StorageSideCapability
            val extracted = PlatformServices.storage.extractStacksByPredicate(
                storage, matches, remaining, cap?.let { cursorFor?.invoke(it.adjacentPos) },
            )
            for (stack in extracted) {
                if (stack.isEmpty) continue
                val itemId = BuiltInRegistries.ITEM.getKey(stack.item)?.toString()
                val patch = stack.componentsPatch
                val hasData = !patch.isEmpty
                // The whole stack has left the card, so the cache hears the whole stack
                // leave, and then hears each part that comes back. Reporting only what the
                // sink kept, and then letting the network insert report its share as new,
                // over-counted the spill until the next poll.
                if (itemId != null && cache != null) {
                    cache.onExtracted(itemId, hasData, stack.count.toLong(), patch)
                    cache.onChannelDelta(card.channel, itemId, patch, -stack.count.toLong())
                }
                val placed = sink(stack)
                if (placed > 0) {
                    remaining -= placed
                    total += placed
                    // The sink is not network storage, so it tells the deadline itself.
                    damien.nodeworks.platform.MoveDeadline.progressed()
                }
                if (placed < stack.count) {
                    // The sink filled mid-stack. Back into the card it came from first,
                    // then anywhere on the network, and the caller gets the rest.
                    var leftover = stack.copyWithCount(stack.count - placed)
                    val home = PlatformServices.storage.insertItemStack(storage, leftover.copy())
                    if (home > 0 && itemId != null && cache != null) {
                        cache.onInserted(itemId, hasData, home.toLong(), patch)
                        cache.onChannelDelta(card.channel, itemId, patch, home.toLong())
                    }
                    if (home < leftover.count) {
                        leftover = leftover.copyWithCount(leftover.count - home)
                        // Reports its own insert to the cache.
                        val elsewhere = NetworkStorageHelper.insertItemStack(level, snapshot, leftover, cache)
                        if (elsewhere < leftover.count) spill(leftover.copyWithCount(leftover.count - elsewhere))
                    }
                }
            }
        }
        if (rotation != null) rotation.index = if (covered >= cards.size || remaining <= 0L) 0 else (start + covered) % cards.size
        return total
    }
}
