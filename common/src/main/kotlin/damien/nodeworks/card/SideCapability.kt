package damien.nodeworks.card

import damien.nodeworks.script.CardHandle
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction

/**
 * Represents a capability exposed by a card on one side of a node.
 * Capabilities are resolved lazily, the [adjacentPos] is stored, not the storage handle.
 * The actual storage is looked up at script execution time.
 */
sealed interface SideCapability {
    val type: String
    val adjacentPos: BlockPos
}

/**
 * IO Card capability, direct scriptable access to an adjacent block's item storage.
 * [defaultFace] is the face of the target block that faces the node.
 */
data class IOSideCapability(
    override val adjacentPos: BlockPos,
    val defaultFace: Direction
) : SideCapability {
    override val type: String = "io"
}

/**
 * Storage Card capability, passive network storage. Items are discoverable
 * by the network for crafting and network:count()/find() queries.
 * [priority] determines search order (higher = checked first, default 0).
 */
/**
 * Recipe Card capability, stores a crafting pattern for virtual crafting.
 * [recipe] is a list of 9 item IDs (empty string = empty slot).
 */
data class RecipeSideCapability(
    override val adjacentPos: BlockPos,
    val recipe: List<String>
) : SideCapability {
    override val type: String = "recipe"
}

data class StorageSideCapability(
    override val adjacentPos: BlockPos,
    val defaultFace: Direction,
    val priority: Int = 0,
    /** Filter mode + rules + stackability + NBT gates carried from the card's
     *  CUSTOM_DATA at capability resolution time. The runtime gates inserts on
     *  [acceptsItem] before writing to the underlying inventory, so a card
     *  with a configured whitelist refuses non-matching items even when
     *  something tries to push them in. Defaults preserve legacy
     *  "accept anything" behavior for existing world saves. */
    val filterMode: StorageCard.Companion.FilterMode = StorageCard.Companion.FilterMode.ALLOW,
    val filterRules: List<String> = emptyList(),
    val stackability: StorageCard.Companion.StackabilityFilter = StorageCard.Companion.StackabilityFilter.ANY,
    val nbtFilter: StorageCard.Companion.NbtFilter = StorageCard.Companion.NbtFilter.ANY,
) : SideCapability {
    override val type: String = "storage"

    /** True when this card should accept the item ([itemId], [hasData]).
     *
     *  All four constraints AND together: stackability gate, NBT gate, rule
     *  list match (or empty list passes), then the ALLOW/DENY mode flips
     *  the rule-list result. A card with all defaults (empty rules, ANY/ANY
     *  gates, ALLOW mode) accepts everything, so untouched cards behave the
     *  way they always have.
     *
     *  We look up `maxStackSize` on the registered Item to evaluate
     *  stackability. For unknown ids the gate falls back to "any" (true)
     *  so a card never rejects an item it can't classify. */
    fun acceptsItem(itemId: String, hasData: Boolean = false): Boolean {
        if (!stackabilityMatches(itemId)) return false
        if (!nbtMatches(hasData)) return false
        if (filterRules.isEmpty()) return true
        val anyMatch = filterRules.any { rule -> CardHandle.matchesFilter(itemId, rule) }
        return when (filterMode) {
            StorageCard.Companion.FilterMode.ALLOW -> anyMatch
            StorageCard.Companion.FilterMode.DENY -> !anyMatch
        }
    }

    private fun stackabilityMatches(itemId: String): Boolean {
        if (stackability == StorageCard.Companion.StackabilityFilter.ANY) return true
        val identifier = net.minecraft.resources.Identifier.tryParse(itemId) ?: return true
        val item = net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(identifier) ?: return true
        val isStackable = item.defaultMaxStackSize > 1
        return when (stackability) {
            StorageCard.Companion.StackabilityFilter.STACKABLE -> isStackable
            StorageCard.Companion.StackabilityFilter.NON_STACKABLE -> !isStackable
            StorageCard.Companion.StackabilityFilter.ANY -> true
        }
    }

    private fun nbtMatches(hasData: Boolean): Boolean = when (nbtFilter) {
        StorageCard.Companion.NbtFilter.ANY -> true
        StorageCard.Companion.NbtFilter.HAS_DATA -> hasData
        StorageCard.Companion.NbtFilter.NO_DATA -> !hasData
    }
}

/**
 * Redstone Card capability, reads/writes redstone signals on the adjacent block.
 * [nodePos] is the position of the node itself (needed for writing output signals).
 * [nodeSide] is the direction from the node to the adjacent block.
 * [defaultFace] is the face of the target block that faces the node.
 */
data class RedstoneSideCapability(
    override val adjacentPos: BlockPos,
    val nodePos: BlockPos,
    val nodeSide: Direction,
    val defaultFace: Direction
) : SideCapability {
    override val type: String = "redstone"
}

/**
 * Observer Card capability, reads block state at the adjacent position. The
 * card has no inherent action surface, scripts pull data via `block()` /
 * `state()` and subscribe to changes via `onChange()`. [accessFace] is the
 * face of the target block that faces the node and is currently kept for
 * symmetry with other cards even though state reads don't need a side.
 */
data class ObserverSideCapability(
    override val adjacentPos: BlockPos,
    val accessFace: Direction
) : SideCapability {
    override val type: String = "observer"
}
