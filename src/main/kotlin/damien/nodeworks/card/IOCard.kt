package damien.nodeworks.card

/**
 * IO Card — provides direct scriptable access to an adjacent block's inventory.
 * Works with any block exposing item storage: chests, barrels, hoppers, furnaces, modded machines.
 * The scripting system specifies which face of the target block to access at runtime.
 */
class IOCard(properties: Properties) : NodeCard(properties) {
    override val cardType: String = "io"
}
