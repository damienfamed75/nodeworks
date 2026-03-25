package damien.nodeworks.card

import net.minecraft.world.item.Item

/**
 * Base class for all node connection cards. Cards go into slot 0 of a node's side
 * to register the adjacent block as an accessible capability on the network.
 *
 * Subclass for each capability type (inventory, energy, fluid, etc.).
 */
abstract class NodeCard(properties: Properties) : Item(properties) {

    /**
     * The capability type identifier used by the scripting system to filter cards.
     */
    abstract val cardType: String
}
