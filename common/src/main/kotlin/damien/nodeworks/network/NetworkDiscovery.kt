package damien.nodeworks.network

import damien.nodeworks.block.entity.InstructionCrafterBlockEntity
import damien.nodeworks.block.entity.InstructionStorageBlockEntity
import damien.nodeworks.block.entity.NodeBlockEntity
import damien.nodeworks.card.SideCapability
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel

/**
 * Discovers all reachable nodes and crafters from a starting position
 * by walking the connection graph. Returns an ephemeral network snapshot.
 */
object NetworkDiscovery {

    fun discoverNetwork(level: ServerLevel, startPos: BlockPos): NetworkSnapshot {
        val visited = mutableSetOf<BlockPos>()
        val queue = ArrayDeque<BlockPos>()
        val nodes = mutableListOf<NodeSnapshot>()
        val crafters = mutableListOf<CrafterSnapshot>()

        queue.add(startPos)
        visited.add(startPos)

        while (queue.isNotEmpty()) {
            val pos = queue.removeFirst()
            val connectable = NodeConnectionHelper.getConnectable(level, pos) ?: continue

            when (connectable) {
                is NodeBlockEntity -> nodes.add(snapshotNode(connectable))
                is InstructionCrafterBlockEntity -> crafters.add(snapshotCrafter(connectable))
            }

            for (connection in connectable.getConnections()) {
                if (visited.add(connection)) {
                    queue.add(connection)
                }
            }
        }

        return NetworkSnapshot(nodes, crafters)
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

    private fun snapshotCrafter(entity: InstructionCrafterBlockEntity): CrafterSnapshot {
        val instructionSets = entity.getAllInstructionSets()
        return CrafterSnapshot(entity.blockPos, instructionSets)
    }
}

data class NetworkSnapshot(
    val nodes: List<NodeSnapshot>,
    val crafters: List<CrafterSnapshot> = emptyList()
) {

    /** Find a card by alias across the entire network. */
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

    /** Find an Instruction Set by alias or output item ID across all crafters. */
    fun findInstructionSet(identifier: String): InstructionSetMatch? {
        // Check alias first
        for (crafter in crafters) {
            for (info in crafter.instructionSets) {
                if (info.alias == identifier) {
                    return InstructionSetMatch(crafter, info)
                }
            }
        }
        // Then check output item ID
        for (crafter in crafters) {
            for (info in crafter.instructionSets) {
                if (info.outputItemId == identifier) {
                    return InstructionSetMatch(crafter, info)
                }
            }
        }
        return null
    }

    /** Find all Instruction Sets that output a specific item ID. */
    fun findInstructionSetsByOutput(outputItemId: String): List<InstructionSetMatch> {
        val results = mutableListOf<InstructionSetMatch>()
        for (crafter in crafters) {
            for (info in crafter.instructionSets) {
                if (info.outputItemId == outputItemId) {
                    results.add(InstructionSetMatch(crafter, info))
                }
            }
        }
        return results
    }
}

data class NodeSnapshot(
    val pos: BlockPos,
    val sides: Map<Direction, List<CardSnapshot>>
)

data class CrafterSnapshot(
    val pos: BlockPos,
    val instructionSets: List<InstructionStorageBlockEntity.InstructionSetInfo>
)

data class InstructionSetMatch(
    val crafter: CrafterSnapshot,
    val instructionSet: InstructionStorageBlockEntity.InstructionSetInfo
)

data class CardSnapshot(
    val capability: SideCapability,
    val alias: String?,
    val slotIndex: Int
)
