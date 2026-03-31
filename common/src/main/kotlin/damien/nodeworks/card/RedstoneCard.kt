package damien.nodeworks.card

/**
 * Redstone Card — reads and writes redstone signals on the adjacent block.
 * Provides powered(), strength(), set(), and onChange() in the scripting API.
 */
class RedstoneCard(properties: Properties) : NodeCard(properties) {
    override val cardType: String = "redstone"
}
