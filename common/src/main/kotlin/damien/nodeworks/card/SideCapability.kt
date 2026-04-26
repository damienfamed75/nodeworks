package damien.nodeworks.card

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
    val priority: Int = 0
) : SideCapability {
    override val type: String = "storage"
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
