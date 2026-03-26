package damien.nodeworks.card

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction

/**
 * Represents a capability exposed by a card on one side of a node.
 * Capabilities are resolved lazily — the [adjacentPos] is stored, not the storage handle.
 * The actual storage is looked up at script execution time.
 */
sealed interface SideCapability {
    val type: String
    val adjacentPos: BlockPos
}

/**
 * IO Card capability — direct scriptable access to an adjacent block's item storage.
 * [defaultFace] is the face of the target block that faces the node.
 */
data class IOSideCapability(
    override val adjacentPos: BlockPos,
    val defaultFace: Direction
) : SideCapability {
    override val type: String = "io"
}

/**
 * Storage Card capability — passive network storage. Items are discoverable
 * by the network for crafting and network:count()/find() queries.
 * [priority] determines search order (higher = checked first, default 0).
 */
/**
 * Recipe Card capability — stores a crafting pattern for virtual crafting.
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
