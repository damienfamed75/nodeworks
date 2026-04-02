package damien.nodeworks.item

import damien.nodeworks.network.NetworkDiscovery
import damien.nodeworks.network.NodeConnectionHelper
import damien.nodeworks.block.entity.NodeBlockEntity
import damien.nodeworks.block.entity.NetworkControllerBlockEntity
import damien.nodeworks.platform.PlatformServices
import damien.nodeworks.screen.DiagnosticMenu
import damien.nodeworks.screen.DiagnosticOpenData
import net.minecraft.core.Direction
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionResult
import net.minecraft.world.item.Item
import net.minecraft.world.item.context.UseOnContext

class DiagnosticToolItem(properties: Properties) : Item(properties) {

    override fun useOn(context: UseOnContext): InteractionResult {
        val level = context.level
        val pos = context.clickedPos
        val player = context.player ?: return InteractionResult.PASS

        if (level.isClientSide) return InteractionResult.SUCCESS

        val connectable = NodeConnectionHelper.getConnectable(level, pos)
        if (connectable == null) {
            player.displayClientMessage(Component.translatable("message.nodeworks.diagnostic_no_network"), true)
            return InteractionResult.FAIL
        }

        val serverLevel = level as ServerLevel
        val serverPlayer = player as ServerPlayer

        // Discover the full network
        val snapshot = NetworkDiscovery.discoverNetwork(serverLevel, pos)

        // Build topology data for the client
        val blocks = mutableListOf<DiagnosticOpenData.NetworkBlock>()
        val aliasCounters = mutableMapOf<String, Int>() // for auto-generating card aliases
        val visited = mutableSetOf<net.minecraft.core.BlockPos>()
        val queue = ArrayDeque<net.minecraft.core.BlockPos>()
        queue.add(pos)
        visited.add(pos)

        while (queue.isNotEmpty()) {
            val blockPos = queue.removeFirst()
            val entity = NodeConnectionHelper.getConnectable(level, blockPos) ?: continue

            val type = when (entity) {
                is NodeBlockEntity -> "node"
                is NetworkControllerBlockEntity -> "controller"
                is damien.nodeworks.block.entity.TerminalBlockEntity -> "terminal"
                is damien.nodeworks.block.entity.CraftingCoreBlockEntity -> "crafting_core"
                is damien.nodeworks.block.entity.CraftingStorageBlockEntity -> "crafting_storage"
                is damien.nodeworks.block.entity.InstructionStorageBlockEntity -> "instruction_storage"
                is damien.nodeworks.block.entity.ProcessingStorageBlockEntity -> "processing_storage"
                is damien.nodeworks.block.entity.VariableBlockEntity -> "variable"
                is damien.nodeworks.block.entity.ReceiverAntennaBlockEntity -> "receiver_antenna"
                is damien.nodeworks.block.entity.BroadcastAntennaBlockEntity -> "broadcast_antenna"
                is damien.nodeworks.block.entity.InventoryTerminalBlockEntity -> "inventory_terminal"
                else -> "unknown"
            }

            val connections = entity.getConnections().toList()

            val cards = if (entity is NodeBlockEntity) {
                val cardList = mutableListOf<DiagnosticOpenData.CardInfo>()
                for (side in Direction.entries) {
                    for (card in entity.getCards(side)) {
                        val alias = card.alias ?: run {
                            val type = card.card.cardType
                            val count = aliasCounters.getOrDefault(type, 0) + 1
                            aliasCounters[type] = count
                            "${type}_$count"
                        }
                        val adjPos = blockPos.relative(side)
                        val adjState = level.getBlockState(adjPos)
                        val adjBlockId = if (!adjState.isAir) {
                            net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(adjState.block)?.toString() ?: ""
                        } else ""
                        cardList.add(DiagnosticOpenData.CardInfo(side.ordinal, card.card.cardType, alias, adjBlockId))
                    }
                }
                cardList
            } else emptyList()

            // Build detail lines based on block type
            val details = mutableListOf<String>()
            when (entity) {
                is NetworkControllerBlockEntity -> {
                    if (entity.networkName.isNotEmpty()) details.add("Name: ${entity.networkName}")
                    details.add("Network ID: ${entity.networkId?.toString()?.take(8) ?: "none"}...")
                    details.add("__color:${entity.networkColor}")
                    details.add("__glow:${entity.nodeGlowStyle}:${entity.networkColor}")
                    val redstoneNames = arrayOf("Ignored", "Active High", "Active Low")
                    details.add("Redstone: ${redstoneNames.getOrElse(entity.redstoneMode) { "Unknown" }}")
                }
                is damien.nodeworks.block.entity.TerminalBlockEntity -> {
                    val scriptCount = entity.scripts.size
                    details.add("Scripts: $scriptCount")
                    for (name in entity.scripts.keys) {
                        val len = entity.scripts[name]?.length ?: 0
                        details.add("  $name (${len} chars)")
                    }
                    if (entity.autoRun) details.add("Auto-run: enabled")
                }
                is damien.nodeworks.block.entity.CraftingCoreBlockEntity -> {
                    details.add("Buffer: ${entity.bufferUsed} / ${entity.bufferCapacity}")
                    details.add("Formed: ${if (entity.isFormed) "yes" else "no"}")
                    if (entity.isCrafting) details.add("Crafting: ${entity.currentCraftItem}")
                }
                is damien.nodeworks.block.entity.InstructionStorageBlockEntity -> {
                    val sets = entity.getAllInstructionSets()
                    details.add("Recipes: ${sets.size}")
                    for (set in sets) {
                        val name = set.alias ?: set.outputItemId.substringAfter(':')
                        details.add("  $name")
                    }
                }
                is damien.nodeworks.block.entity.ProcessingStorageBlockEntity -> {
                    val apis = entity.getAllProcessingApis()
                    details.add("Processing Sets: ${apis.size}")
                    for (api in apis) {
                        details.add("  ${api.name}")
                    }
                }
                is damien.nodeworks.block.entity.VariableBlockEntity -> {
                    details.add("Name: ${entity.variableName}")
                    details.add("Type: ${entity.variableType}")
                }
                is damien.nodeworks.block.entity.CraftingStorageBlockEntity -> {
                    details.add("Tier: ${entity.tier}")
                }
                else -> {}
            }

            blocks.add(DiagnosticOpenData.NetworkBlock(blockPos, type, connections, cards, details))

            for (conn in connections) {
                if (visited.add(conn)) {
                    if (NodeConnectionHelper.checkLineOfSight(level, blockPos, conn)) {
                        queue.add(conn)
                    }
                }
            }
        }

        // Get network info from controller
        val controller = snapshot.controller
        var networkName = ""
        var networkColor = 0xFFFFFF
        if (controller != null) {
            val controllerEntity = level.getBlockEntity(controller.pos) as? NetworkControllerBlockEntity
            if (controllerEntity != null) {
                networkName = controllerEntity.networkName
                networkColor = controllerEntity.networkColor
            }
        }

        // Collect all craftable item IDs from instruction sets and processing sets
        val craftableItems = mutableSetOf<String>()
        for (crafter in snapshot.crafters) {
            for (info in crafter.instructionSets) {
                if (info.outputItemId.isNotEmpty()) craftableItems.add(info.outputItemId)
            }
        }
        for (apiSnapshot in snapshot.processingApis) {
            for (api in apiSnapshot.apis) {
                craftableItems.addAll(api.outputItemIds)
            }
        }

        // Collect CPU info
        val cpuInfos = snapshot.cpus.map { cpu ->
            val entity = level.getBlockEntity(cpu.pos) as? damien.nodeworks.block.entity.CraftingCoreBlockEntity
            DiagnosticOpenData.CpuInfo(
                cpu.pos, cpu.bufferUsed, cpu.bufferCapacity, cpu.isBusy,
                entity?.currentCraftItem ?: "", entity?.isFormed ?: false
            )
        }

        // Collect terminal info
        val terminalInfos = snapshot.terminalPositions.mapNotNull { tPos ->
            val terminal = level.getBlockEntity(tPos) as? damien.nodeworks.block.entity.TerminalBlockEntity ?: return@mapNotNull null
            val isRunning = PlatformServices.modState.isScriptRunning(serverLevel, tPos)
            val handlers = if (isRunning) {
                val engine = PlatformServices.modState.getScriptEngine(serverLevel, tPos)
                (engine as? damien.nodeworks.script.ScriptEngine)?.processingHandlers?.keys?.toList() ?: emptyList()
            } else emptyList()
            DiagnosticOpenData.TerminalInfo(
                tPos, isRunning, terminal.scripts.keys.toList(), handlers, terminal.autoRun
            )
        }

        // Collect recent errors from the server-side buffer
        val terminalPosSet = snapshot.terminalPositions.toSet()
        val currentTick = serverLevel.server.tickCount.toLong()
        val recentErrors = damien.nodeworks.script.NetworkErrorBuffer.getRecentErrors(terminalPosSet, 10, currentTick).map { err ->
            DiagnosticOpenData.ErrorEntry(err.terminalPos, err.message, (currentTick - err.tickTime).toInt())
        }

        val openData = DiagnosticOpenData(blocks, networkName, networkColor, pos, craftableItems.sorted(), cpuInfos, terminalInfos, recentErrors)

        PlatformServices.menu.openExtendedMenu(
            serverPlayer,
            Component.translatable("container.nodeworks.diagnostic"),
            openData,
            DiagnosticOpenData.STREAM_CODEC
        ) { syncId, inv, _ -> DiagnosticMenu.createServer(syncId, pos) }

        return InteractionResult.SUCCESS
    }
}
