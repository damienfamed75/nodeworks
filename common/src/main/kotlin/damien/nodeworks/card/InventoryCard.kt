package damien.nodeworks.card

/**
 * Registers the adjacent block as an accessible item inventory on the network.
 * Works with any block exposing item storage: chests, barrels, hoppers, furnaces, modded machines.
 * The scripting system specifies which face of the target block to access at runtime.
 */
class InventoryCard(properties: Properties) : NodeCard(properties) {
    override val cardType: String = "inventory"
}
