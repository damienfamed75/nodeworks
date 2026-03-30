package damien.nodeworks.network

import damien.nodeworks.block.entity.ProcessingStorageBlockEntity
import damien.nodeworks.block.entity.InstructionStorageBlockEntity
import damien.nodeworks.block.entity.NetworkControllerBlockEntity
import damien.nodeworks.block.entity.NodeBlockEntity
import damien.nodeworks.block.entity.CraftingCoreBlockEntity
import damien.nodeworks.block.entity.ReceiverAntennaBlockEntity
import damien.nodeworks.block.entity.TerminalBlockEntity
import damien.nodeworks.block.entity.VariableBlockEntity
import damien.nodeworks.block.entity.VariableType
import damien.nodeworks.card.SideCapability
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import java.util.UUID

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
        val cpus = mutableListOf<CpuSnapshot>()
        val variables = mutableListOf<VariableSnapshot>()
        val processingApis = mutableListOf<ProcessingApiSnapshot>()
        val terminalPositions = mutableListOf<BlockPos>()
        var controller: ControllerSnapshot? = null

        queue.add(startPos)
        visited.add(startPos)

        while (queue.isNotEmpty()) {
            val pos = queue.removeFirst()
            val connectable = NodeConnectionHelper.getConnectable(level, pos) ?: continue

            when (connectable) {
                is NodeBlockEntity -> nodes.add(snapshotNode(connectable))
                is InstructionStorageBlockEntity -> {
                    // Storage block connected via laser — scan its cluster for all recipes
                    val clusterSets = connectable.getAllInstructionSets()
                    if (clusterSets.isNotEmpty()) {
                        crafters.add(CrafterSnapshot(connectable.blockPos, clusterSets))
                    }
                }
                is ProcessingStorageBlockEntity -> {
                    val clusterApis = connectable.getAllProcessingApis()
                    if (clusterApis.isNotEmpty()) {
                        processingApis.add(ProcessingApiSnapshot(connectable.blockPos, clusterApis))
                    }
                }
                is ReceiverAntennaBlockEntity -> {
                    val serverLevel = level
                    val broadcast = connectable.getBroadcastAntenna(serverLevel)
                    if (broadcast != null) {
                        val remoteApis = broadcast.getAvailableApis()
                        if (remoteApis.isNotEmpty()) {
                            val remoteTerminals = broadcast.getProviderTerminalPositions()
                            processingApis.add(ProcessingApiSnapshot(broadcast.blockPos, remoteApis, remoteTerminals))
                        }
                    }
                }
                is TerminalBlockEntity -> terminalPositions.add(connectable.blockPos)
                is NetworkControllerBlockEntity -> controller = ControllerSnapshot(connectable.blockPos, connectable.networkId)
                is CraftingCoreBlockEntity -> cpus.add(CpuSnapshot(
                    connectable.blockPos, connectable.bufferUsed, connectable.bufferCapacity, connectable.isCrafting
                ))
                is VariableBlockEntity -> if (connectable.variableName.isNotEmpty()) {
                    variables.add(VariableSnapshot(connectable.blockPos, connectable.variableName, connectable.variableType))
                }
            }

            for (connection in connectable.getConnections()) {
                if (connection in visited) continue
                // Only mark visited after LOS passes — another path may have clear LOS
                if (!NodeConnectionHelper.checkLineOfSight(level, pos, connection)) continue
                visited.add(connection)
                queue.add(connection)
            }
        }

        // Auto-generate aliases for unnamed cards (e.g., io_1, io_2, storage_1)
        assignAutoAliases(nodes)

        return NetworkSnapshot(nodes, crafters, variables, cpus, processingApis, terminalPositions, controller)
    }

    private fun assignAutoAliases(nodes: List<NodeSnapshot>) {
        val counters = mutableMapOf<String, Int>()
        for (node in nodes) {
            for ((_, cards) in node.sides) {
                for (card in cards) {
                    if (card.alias == null) {
                        val type = card.capability.type
                        val count = counters.getOrDefault(type, 0) + 1
                        counters[type] = count
                        card.autoAlias = "${type}_$count"
                    }
                }
            }
        }
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

data class ControllerSnapshot(
    val pos: BlockPos,
    val networkId: UUID
)

data class CpuSnapshot(
    val pos: BlockPos,
    val bufferUsed: Int,
    val bufferCapacity: Int,
    val isBusy: Boolean
)

data class VariableSnapshot(
    val pos: BlockPos,
    val name: String,
    val type: VariableType
)

data class ProcessingApiSnapshot(
    val pos: BlockPos,
    val apis: List<ProcessingStorageBlockEntity.ProcessingApiInfo>,
    val remoteTerminalPositions: List<BlockPos>? = null
)

data class NetworkSnapshot(
    val nodes: List<NodeSnapshot>,
    val crafters: List<CrafterSnapshot> = emptyList(),
    val variables: List<VariableSnapshot> = emptyList(),
    val cpus: List<CpuSnapshot> = emptyList(),
    val processingApis: List<ProcessingApiSnapshot> = emptyList(),
    val terminalPositions: List<BlockPos> = emptyList(),
    val controller: ControllerSnapshot? = null
) {
    /** Whether this network has a controller and is online. */
    val isOnline: Boolean get() = controller != null

    /** The network's UUID, or null if no controller. */
    val networkId: UUID? get() = controller?.networkId

    /** Find a variable by name. */
    fun findVariable(name: String): VariableSnapshot? = variables.firstOrNull { it.name == name }

    /** Find an available (not busy) Crafting CPU with enough buffer capacity. */
    fun findAvailableCpu(requiredCapacity: Int = 0): CpuSnapshot? =
        cpus.firstOrNull { !it.isBusy && it.bufferCapacity - it.bufferUsed >= requiredCapacity }

    /** Find a card by alias (custom or auto-generated) across the entire network. */
    fun findByAlias(alias: String): CardSnapshot? {
        val matches = nodes.flatMap { node ->
            node.sides.values.flatten().filter { it.effectiveAlias == alias }
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

    /** Find a Processing Set that outputs a specific item ID (checks all outputs). */
    fun findProcessingApi(outputItemId: String): ProcessingApiMatch? {
        for (snapshot in processingApis) {
            for (api in snapshot.apis) {
                if (outputItemId in api.outputItemIds) {
                    return ProcessingApiMatch(snapshot, api)
                }
            }
        }
        return null
    }

    /** Get all Processing Sets across the entire network. */
    fun allProcessingApis(): List<ProcessingStorageBlockEntity.ProcessingApiInfo> {
        return processingApis.flatMap { it.apis }
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

data class ProcessingApiMatch(
    val apiStorage: ProcessingApiSnapshot,
    val api: ProcessingStorageBlockEntity.ProcessingApiInfo
)

data class CardSnapshot(
    val capability: SideCapability,
    val alias: String?,
    val slotIndex: Int
) {
    /** Auto-generated alias for unnamed cards (e.g., io_1, storage_2). */
    var autoAlias: String? = null

    /** The effective alias — custom name if set, otherwise auto-generated. */
    val effectiveAlias: String get() = alias ?: autoAlias ?: capability.type
}
