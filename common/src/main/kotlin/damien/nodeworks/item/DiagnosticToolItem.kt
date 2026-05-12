package damien.nodeworks.item

import damien.nodeworks.network.Connectable
import damien.nodeworks.network.NetworkDiscovery
import damien.nodeworks.network.NodeConnectionHelper
import damien.nodeworks.block.entity.CoveredPipeBlockEntity
import damien.nodeworks.block.entity.NodeBlockEntity
import damien.nodeworks.block.entity.NetworkControllerBlockEntity
import damien.nodeworks.block.entity.PipeBlockEntity
import damien.nodeworks.platform.PlatformServices
import damien.nodeworks.screen.DiagnosticMenu
import damien.nodeworks.screen.DiagnosticOpenData
import net.minecraft.core.BlockPos
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
        val clickedPos = context.clickedPos
        val player = context.player ?: return InteractionResult.PASS

        if (level.isClientSide) return InteractionResult.SUCCESS

        // Normal case: clicked block is directly on the network (Node, Terminal, …).
        // Special case: clicked block is a CPU component (Buffer / Stabilizer /
        // Substrate / Co-Processor), those aren't Connectable themselves but they
        // attach to a Crafting Core which IS Connectable. Walk the multiblock to find
        // that Core and use its position as the diagnostic entry point so the player
        // can click ANY piece of a CPU cluster to inspect the network.
        val pos = run {
            val direct = NodeConnectionHelper.getConnectable(level, clickedPos)
            if (direct != null) return@run clickedPos

            val be = level.getBlockEntity(clickedPos)
            if (be is damien.nodeworks.block.entity.CpuComponentBlockEntity) {
                val cores = damien.nodeworks.block.entity.CpuComponentBlockEntity.findConnectedCores(level, clickedPos)
                cores.firstOrNull()?.blockPos?.also { return@run it }
            }
            null
        }

        if (pos == null) {
            player.sendOverlayMessage(Component.translatable("message.nodeworks.diagnostic_no_network"))
            return InteractionResult.FAIL
        }

        val connectable = NodeConnectionHelper.getConnectable(level, pos) ?: run {
            player.sendOverlayMessage(Component.translatable("message.nodeworks.diagnostic_no_network"))
            return InteractionResult.FAIL
        }

        val serverLevel = level as ServerLevel
        val serverPlayer = player as ServerPlayer

        // Discover the full network
        val snapshot = NetworkDiscovery.discoverNetwork(serverLevel, pos)

        // Build topology data for the client
        val blocks = mutableListOf<DiagnosticOpenData.NetworkBlock>()
        val aliasCounters = mutableMapOf<String, Int>() // for auto-generating card aliases

        // Walk the entire network using the same adjacency-aware traversal
        // [NetworkDiscovery] uses. The previous implementation followed only
        // laser links via [Connectable.getConnections], which silently bails
        // the moment the BFS hits a Pipe (pipes connect via adjacency, not
        // links) and leaves the topology view stuck on the click target.
        val visited = mutableSetOf<BlockPos>()
        val queue = ArrayDeque<Pair<BlockPos, Direction?>>()
        visited.add(pos)
        queue.add(pos to null)

        while (queue.isNotEmpty()) {
            val (curPos, entryFace) = queue.removeFirst()
            val ce = NodeConnectionHelper.getConnectable(level, curPos) ?: continue
            forEachConnectableNeighbor(serverLevel, curPos, ce, entryFace) { nextPos, nextEntry ->
                if (visited.add(nextPos)) queue.add(nextPos to nextEntry)
            }
        }

        // Pipes have no per-block logic to inspect, so render them as plain
        // lines between the blocks they sit between. The non-pipe set is the
        // topology's node set; logical connections for each entry are computed
        // by tracing through pipe chains to the next non-pipe Connectable.
        val nonPipePositions = visited.filterTo(mutableSetOf()) { p ->
            val be = level.getBlockEntity(p)
            be !is PipeBlockEntity && be !is CoveredPipeBlockEntity
        }

        for (blockPos in nonPipePositions) {
            val entity = NodeConnectionHelper.getConnectable(level, blockPos) ?: continue

            val type = when (entity) {
                // FocusNode extends Node, check the subclass first so Focus Nodes
                // don't fall into the regular Node bucket and lose their identity.
                is damien.nodeworks.block.entity.FocusNodeBlockEntity -> "focus_node"
                is NodeBlockEntity -> "node"
                is NetworkControllerBlockEntity -> "controller"
                is damien.nodeworks.block.entity.TerminalBlockEntity -> "terminal"
                is damien.nodeworks.block.entity.CraftingCoreBlockEntity -> "crafting_core"
                is damien.nodeworks.block.entity.InstructionStorageBlockEntity -> "instruction_storage"
                is damien.nodeworks.block.entity.ProcessingStorageBlockEntity -> "processing_storage"
                is damien.nodeworks.block.entity.VariableBlockEntity -> "variable"
                is damien.nodeworks.block.entity.ReceiverAntennaBlockEntity -> "receiver_antenna"
                is damien.nodeworks.block.entity.InventoryTerminalBlockEntity -> "inventory_terminal"
                is damien.nodeworks.block.entity.BreakerBlockEntity -> "breaker"
                is damien.nodeworks.block.entity.PlacerBlockEntity -> "placer"
                is damien.nodeworks.block.entity.UserBlockEntity -> "user"
                is damien.nodeworks.block.entity.ImportChestBlockEntity -> "import_chest"
                is damien.nodeworks.block.entity.ExportChestBlockEntity -> "export_chest"
                is damien.nodeworks.block.entity.ProcessingHandlerBlockEntity -> "processing_handler"
                else -> "unknown"
            }

            val connections = computeLogicalNeighbors(serverLevel, blockPos, nonPipePositions)

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
                    // Mirror the player-visible rows in [NetworkControllerScreen]
                    // so the diagnostic and the controller GUI stay in sync.
                    // Redstone and Node Glow are intentionally hidden in the
                    // controller GUI (no consumer / design in flux), so the
                    // diagnostic skips them too.
                    if (entity.networkName.isNotEmpty()) details.add("Name: ${entity.networkName}")
                    details.add("Network ID: ${entity.networkId?.toString()?.take(8) ?: "none"}...")
                    details.add("__color:${entity.networkColor}")
                    details.add("Craft Retries: ${entity.handlerRetryLimit}")
                    details.add("Chunk Loading: ${if (entity.chunkLoadingEnabled) "on" else "off"}")
                    val fancyLasers = entity.laserMode == damien.nodeworks.network.NetworkSettingsRegistry.LASER_MODE_FANCY
                    details.add("Fancy Lasers: ${if (fancyLasers) "on" else "off"}")
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
                    details.add("Efficiency: ${(entity.throttle * 100f).toInt()}%")
                    if (entity.lastFailureReason.isNotEmpty()) {
                        // Prefixed marker so the diagnostic screen can pick this out and
                        // render the warning icon in the topology view + error styling.
                        details.add("__error:${entity.lastFailureReason}")
                    }
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
                    if (entity.variableName.isNotEmpty()) {
                        details.add(aliasMarker(VARIABLE_TINT, entity.variableName))
                    }
                    details.add("Type: ${entity.variableType}")
                }
                is damien.nodeworks.block.entity.BreakerBlockEntity -> {
                    if (entity.deviceName.isNotEmpty()) details.add(aliasMarker(BREAKER_TINT, entity.deviceName))
                    details.add(channelMarker(entity.channel))
                    details.add("Filter: ${formatFilter(entity.filterRule)}")
                    details.add("Redstone: ${formatRedstoneMode(entity.redstoneMode)}")
                    if (entity.isBreaking) details.add("Mining: ${(entity.progressFraction * 100f).toInt()}%")
                }
                is damien.nodeworks.block.entity.PlacerBlockEntity -> {
                    if (entity.deviceName.isNotEmpty()) details.add(aliasMarker(PLACER_TINT, entity.deviceName))
                    details.add(channelMarker(entity.channel))
                    details.add("Filter: ${formatFilter(entity.filterRule)}")
                    details.add("Redstone: ${formatRedstoneMode(entity.redstoneMode)}")
                }
                is damien.nodeworks.block.entity.UserBlockEntity -> {
                    if (entity.deviceName.isNotEmpty()) details.add(aliasMarker(USER_TINT, entity.deviceName))
                    details.add(channelMarker(entity.channel))
                    details.add("Filter: ${formatFilter(entity.filterRule)}")
                    details.add("Mode: ${entity.mode.name.lowercase().replaceFirstChar { it.uppercase() }}")
                    details.add("Redstone: ${formatRedstoneMode(entity.redstoneMode)}")
                }
                is damien.nodeworks.block.entity.ImportChestBlockEntity -> {
                    details.add(channelFilterMarker(entity.channel))
                    details.add("Tick interval: ${entity.tickInterval}")
                    details.add("Round-robin: ${if (entity.roundRobin) "on" else "off"}")
                    details.add("Redstone: ${formatRedstoneMode(entity.redstoneMode)}")
                }
                is damien.nodeworks.block.entity.ExportChestBlockEntity -> {
                    details.add(channelFilterMarker(entity.channel))
                    details.add("Push face: ${entity.pushFace?.name?.lowercase() ?: "(none)"}")
                    details.add("Tick interval: ${entity.tickInterval}")
                    details.add("Redstone: ${formatRedstoneMode(entity.redstoneMode)}")
                    if (entity.filterRules.isEmpty()) {
                        details.add("Filter: (none, idle)")
                    } else {
                        details.add("Filter rules: ${entity.filterRules.size}")
                        for (rule in entity.filterRules) details.add("  $rule")
                    }
                }
                is damien.nodeworks.block.entity.ProcessingHandlerBlockEntity -> {
                    // Recipe NAME is hidden, processing-set names from JEI etc.
                    // overflow the inspector. Players already know which recipe
                    // the handler is bound to by clicking it, the diagnostic is
                    // about routing, so list the input and output ITEMS with
                    // their channels instead.
                    val apiName = entity.processingApiName
                    val api = if (apiName.isEmpty()) null else snapshot.processingApis
                        .asSequence()
                        .flatMap { it.apis.asSequence() }
                        .firstOrNull { it.name == apiName }
                    if (apiName.isEmpty()) {
                        details.add("Not bound")
                    } else if (api == null) {
                        details.add("Recipe missing")
                    } else {
                        if (api.inputs.isNotEmpty()) {
                            details.add("Inputs:")
                            for ((itemId, _) in api.inputs) {
                                val channel = entity.getInputChannel(itemId)
                                details.add(processingHandlerItemMarker(itemId, channel))
                            }
                        }
                        if (api.outputs.isNotEmpty()) {
                            details.add("Outputs:")
                            for ((itemId, _) in api.outputs) {
                                details.add(processingHandlerItemMarker(itemId, entity.outputChannel))
                            }
                        }
                    }
                }
                else -> {}
            }

            blocks.add(DiagnosticOpenData.NetworkBlock(blockPos, type, connections, cards, details))
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

        // Send the menu open with an EMPTY block list. Large networks (thousands
        // of pipes and nodes) can easily blow past the custom-payload size limit
        // if the entire topology rides on the open packet. The blocks stream in
        // afterwards via DiagnosticTopologyChunkPayload, chunked at TOPOLOGY_CHUNK_SIZE.
        val openData = DiagnosticOpenData(
            blocks = emptyList(),
            networkName = networkName,
            networkColor = networkColor,
            networkPos = pos,
            craftableItems = craftableItems.sorted(),
            cpuInfos = cpuInfos,
            terminalInfos = terminalInfos,
            recentErrors = recentErrors,
        )

        PlatformServices.menu.openExtendedMenu(
            serverPlayer,
            Component.translatable("container.nodeworks.diagnostic"),
            openData,
            DiagnosticOpenData.STREAM_CODEC
        ) { syncId, inv, _ -> DiagnosticMenu.createServer(syncId, pos) }

        // Stream the topology in chunks. NeoForge's payload bus preserves
        // packet order so the client appends in the order we send. Chunk size
        // is bounded by block count, not encoded bytes -- pipe-heavy
        // connections inflate per-block size but the bound keeps each chunk
        // well under the default 1 MiB payload ceiling even worst-case.
        val chunks = blocks.chunked(TOPOLOGY_CHUNK_SIZE)
        if (chunks.isEmpty()) {
            // Click target wasn't on a real network; flush an empty terminal
            // chunk so the client clears its loading indicator.
            PlatformServices.serverNetworking.sendToPlayer(
                serverPlayer,
                damien.nodeworks.network.DiagnosticTopologyChunkPayload(emptyList(), isLast = true),
            )
        } else {
            for ((idx, chunk) in chunks.withIndex()) {
                PlatformServices.serverNetworking.sendToPlayer(
                    serverPlayer,
                    damien.nodeworks.network.DiagnosticTopologyChunkPayload(chunk, isLast = idx == chunks.lastIndex),
                )
            }
        }

        return InteractionResult.SUCCESS
    }

    /** Empty filters render as `*` in the diagnostic so they read as
     *  "match anything", matching the Storage Card pattern syntax players
     *  already know. */
    private fun formatFilter(rule: String): String =
        if (rule.isEmpty()) "*" else rule

    /** Maps the 0/1/2 [redstoneMode] enum to a human label. Matches the values
     *  in [BreakerBlockEntity]/[PlacerBlockEntity]/[UserBlockEntity]: 0 is
     *  Ignored, 1 is active-on-low, 2 is active-on-high. */
    private fun formatRedstoneMode(mode: Int): String = when (mode) {
        1 -> "Active Low"
        2 -> "Active High"
        else -> "Ignored"
    }

    /** Inspector marker for the device name. The renderer paints "Name:" gray
     *  and the value in [tintRgb], so each device matches its Scripting
     *  Terminal sidebar colour. */
    private fun aliasMarker(tintRgb: Int, name: String): String = "__alias:$tintRgb:$name"

    /** Inspector marker for a [DyeColor] channel. The renderer paints "Channel:"
     *  gray, then a wool swatch tinted with [color] and the colour name. */
    private fun channelMarker(color: net.minecraft.world.item.DyeColor): String {
        val rgb = color.textureDiffuseColor and 0xFFFFFF
        val label = color.name.lowercase().replaceFirstChar { it.uppercase() }
        return "__channel:$rgb:$label"
    }

    /** Inspector marker for a [ChannelFilter]. [ChannelFilter.All] renders as
     *  "Any" instead of a swatch. */
    private fun channelFilterMarker(channel: damien.nodeworks.network.ChannelFilter): String = when (channel) {
        is damien.nodeworks.network.ChannelFilter.All -> "__channel:ALL"
        is damien.nodeworks.network.ChannelFilter.Color -> channelMarker(channel.color)
    }

    /** Marker for one Processing Handler ingredient row, used for both the
     *  inputs and outputs lists. Renders as `[item icon] [wool swatch] <ChannelName>`.
     *  Uses `|` between fields so the item id's namespace colon doesn't
     *  collide with the marker syntax. */
    private fun processingHandlerItemMarker(itemId: String, channel: net.minecraft.world.item.DyeColor): String {
        val rgb = channel.textureDiffuseColor and 0xFFFFFF
        val label = channel.name.lowercase().replaceFirstChar { it.uppercase() }
        return "__phitem:$itemId|$rgb|$label"
    }

    private companion object {
        /** Block count per topology chunk. Each NetworkBlock encodes to roughly
         *  300-500 bytes once pipe-path waypoints are included, so 256 blocks
         *  per chunk lands at ~75-130 KB which stays comfortably under
         *  NeoForge's default payload ceiling. */
        const val TOPOLOGY_CHUNK_SIZE = 256

        /** Per-device tint colours mirror the Scripting Terminal sidebar so
         *  the diagnostic colour-codes devices the same way the script editor
         *  does. See [damien.nodeworks.screen.TerminalScreen] sidebar entries
         *  for the source-of-truth values. */
        const val VARIABLE_TINT = 0xFFAA33
        const val BREAKER_TINT = 0xC97847
        const val PLACER_TINT = 0x6BBCD0
        const val USER_TINT = 0x79E324
    }

    /** Visits every Connectable face-reachable from ([pos], [entryFace]) under the
     *  same rules [NetworkDiscovery] uses: laser links via [Connectable.connectionsFromFace]
     *  plus adjacency expansion gated by `usesAdjacency` / `adjacencyFaceAllowed` /
     *  `canConnectAdjacentTo` / `forcedPipeBlocked`. [visit] is called once per
     *  reachable neighbour with the neighbour's pos and the entry face that should
     *  be threaded into a subsequent walk from it. */
    private inline fun forEachConnectableNeighbor(
        level: ServerLevel,
        pos: BlockPos,
        connectable: Connectable,
        entryFace: Direction?,
        visit: (BlockPos, Direction?) -> Unit,
    ) {
        for (next in connectable.connectionsFromFace(entryFace)) {
            if (!level.isLoaded(next)) continue
            if (NodeConnectionHelper.isPairBlocked(level, pos, next)) continue
            visit(next, faceFromTo(next, pos))
        }
        if (!connectable.usesAdjacency()) return
        for (dir in Direction.entries) {
            if (!connectable.adjacencyFaceAllowed(dir, entryFace)) continue
            val adjPos = pos.relative(dir)
            if (!level.isLoaded(adjPos)) continue
            val neighbor = level.getBlockEntity(adjPos) as? Connectable ?: continue
            if (!neighbor.usesAdjacency()) continue
            if (!neighbor.adjacencyFaceAllowed(dir.opposite, dir.opposite)) continue
            if (!connectable.canConnectAdjacentTo(neighbor)) continue
            if (!neighbor.canConnectAdjacentTo(connectable)) continue
            if (connectable.forcedPipeBlocked(dir)) continue
            if (neighbor.forcedPipeBlocked(dir.opposite)) continue
            visit(adjPos, dir.opposite)
        }
    }

    /** Trace through pipe chains starting at [start] until each branch reaches
     *  a non-pipe Connectable in [nonPipes]. Returns the polyline path for
     *  each logical neighbour, ordered from the first hop out of [start] to
     *  the non-pipe target inclusive, so the renderer can draw segments along
     *  the actual pipe layout instead of a diagonal to the endpoint. */
    private fun computeLogicalNeighbors(
        level: ServerLevel,
        start: BlockPos,
        nonPipes: Set<BlockPos>,
    ): List<List<BlockPos>> {
        val result = mutableListOf<List<BlockPos>>()
        // Per-position path so we can replay the route when we hit a non-pipe.
        // BFS keeps the path-to-here for each queued cell; intermediate pipes
        // accumulate, the moment we reach a non-pipe we snapshot the path.
        val seen = mutableSetOf(start)
        data class Step(val pos: BlockPos, val entryFace: Direction?, val pathSoFar: List<BlockPos>)
        val queue = ArrayDeque<Step>()
        queue.add(Step(start, null, emptyList()))
        while (queue.isNotEmpty()) {
            val (cur, entryFace, path) = queue.removeFirst()
            val ce = NodeConnectionHelper.getConnectable(level, cur) ?: continue
            if (cur != start && cur in nonPipes) continue
            forEachConnectableNeighbor(level, cur, ce, entryFace) { nextPos, nextEntry ->
                if (!seen.add(nextPos)) return@forEachConnectableNeighbor
                val nextPath = path + nextPos
                if (nextPos in nonPipes) {
                    result.add(nextPath)
                } else {
                    queue.add(Step(nextPos, nextEntry, nextPath))
                }
            }
        }
        return result
    }

    /** Direction from [from] to [to], or null when they aren't face-adjacent.
     *  Mirrors [NetworkDiscovery.faceFromTo]. */
    private fun faceFromTo(from: BlockPos, to: BlockPos): Direction? {
        val dx = to.x - from.x
        val dy = to.y - from.y
        val dz = to.z - from.z
        return when {
            dx == 1 && dy == 0 && dz == 0 -> Direction.EAST
            dx == -1 && dy == 0 && dz == 0 -> Direction.WEST
            dx == 0 && dy == 1 && dz == 0 -> Direction.UP
            dx == 0 && dy == -1 && dz == 0 -> Direction.DOWN
            dx == 0 && dy == 0 && dz == 1 -> Direction.SOUTH
            dx == 0 && dy == 0 && dz == -1 -> Direction.NORTH
            else -> null
        }
    }
}
