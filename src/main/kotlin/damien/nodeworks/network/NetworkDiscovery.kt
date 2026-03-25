package damien.nodeworks.network

import damien.nodeworks.block.entity.NodeBlockEntity
import damien.nodeworks.card.SideCapability
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel

/**
 * Discovers all reachable nodes from a starting position by walking the connection graph.
 * Returns a snapshot of the network's current state — ephemeral, never stored.
 */
object NetworkDiscovery {

    fun discoverNetwork(level: ServerLevel, startPos: BlockPos): NetworkSnapshot {
        val visited = mutableSetOf<BlockPos>()
        val queue = ArrayDeque<BlockPos>()
        val nodes = mutableListOf<NodeSnapshot>()

        queue.add(startPos)
        visited.add(startPos)

        while (queue.isNotEmpty()) {
            val pos = queue.removeFirst()
            val entity = NodeConnectionHelper.getNodeEntity(level, pos) ?: continue

            nodes.add(snapshotNode(entity))

            for (connection in entity.getConnections()) {
                if (visited.add(connection)) {
                    queue.add(connection)
                }
            }
        }

        return NetworkSnapshot(nodes)
    }

    private fun snapshotNode(entity: NodeBlockEntity): NodeSnapshot {
        val sides = mutableMapOf<Direction, List<CardSnapshot>>()

        for (dir in Direction.entries) {
            val capabilities = entity.getSideCapabilities(dir)
            if (capabilities.isEmpty()) continue
            sides[dir] = capabilities.map { info ->
                CardSnapshot(info.capability, info.alias, info.slotIndex)
            }
        }

        return NodeSnapshot(entity.blockPos, sides)
    }
}

data class NetworkSnapshot(val nodes: List<NodeSnapshot>) {

    /** Find a card by alias across the entire network. Returns null if not found or duplicate. */
    fun findByAlias(alias: String): CardSnapshot? {
        val matches = nodes.flatMap { node ->
            node.sides.values.flatten().filter { it.alias == alias }
        }
        return matches.singleOrNull()
    }

    /** All cards across the entire network. */
    fun allCards(): List<CardSnapshot> {
        return nodes.flatMap { node -> node.sides.values.flatten() }
    }
}

data class NodeSnapshot(
    val pos: BlockPos,
    val sides: Map<Direction, List<CardSnapshot>>
)

data class CardSnapshot(
    val capability: SideCapability,
    val alias: String?,
    val slotIndex: Int
)
