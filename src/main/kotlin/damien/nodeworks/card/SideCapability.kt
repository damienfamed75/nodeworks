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
 * Exposes item storage on the adjacent block via Fabric Transfer API or vanilla Container fallback.
 * [defaultFace] is the face of the target block that faces the node (used when script doesn't specify a face).
 */
data class InventorySideCapability(
    override val adjacentPos: BlockPos,
    val defaultFace: Direction
) : SideCapability {
    override val type: String = "inventory"
}
