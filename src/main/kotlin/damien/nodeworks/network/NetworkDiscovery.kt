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
        val sides = mutableMapOf<Direction, NodeSideSnapshot>()

        for (dir in Direction.entries) {
            val capability = entity.getSideCapability(dir) ?: continue
            val alias = entity.getCardAlias(dir)
            sides[dir] = NodeSideSnapshot(capability, alias)
        }

        return NodeSnapshot(entity.blockPos, sides)
    }
}

data class NetworkSnapshot(val nodes: List<NodeSnapshot>) {

    /** Find a card by alias across the entire network. Returns null if not found or duplicate. */
    fun findByAlias(alias: String): NodeSideSnapshot? {
        val matches = nodes.flatMap { node ->
            node.sides.values.filter { it.alias == alias }
        }
        return matches.singleOrNull()
    }
}

data class NodeSnapshot(
    val pos: BlockPos,
    val sides: Map<Direction, NodeSideSnapshot>
)

data class NodeSideSnapshot(
    val capability: SideCapability,
    val alias: String?
)
