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

    /** Per-thread guard against unbounded recursion through paired antennas. Two
     *  networks linked by Broadcast/Receiver pairs in both directions, then merged
     *  via adjacency, would otherwise bounce [discoverNetwork] back and forth via
     *  [BroadcastAntennaBlockEntity.getProviderTerminalPositions] until the stack
     *  overflows. Skipping any antenna already mid-walk is safe, the in-flight
     *  outer walk already covers that network. Keyed on (dimension, pos) so two
     *  cross-dim antennas at the same coordinates don't collide. */
    private val activeProviderWalks: ThreadLocal<MutableSet<Pair<net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level>, BlockPos>>> =
        ThreadLocal.withInitial { mutableSetOf() }

    /** Per-tick snapshot cache keyed by `(dimension, network UUID)`. Concurrent
     *  because [Connectable.loadAdditional] runs on async chunk IO threads. */
    private val cache: java.util.concurrent.ConcurrentHashMap<
        Pair<net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level>, UUID>,
        CachedSnapshot,
    > = java.util.concurrent.ConcurrentHashMap()

    private data class CachedSnapshot(val tick: Long, val snapshot: NetworkSnapshot)

    fun invalidate(networkId: UUID) {
        cache.entries.removeIf { it.key.second == networkId }
    }

    fun invalidateAll() {
        cache.clear()
    }

    fun discoverNetwork(level: ServerLevel, startPos: BlockPos): NetworkSnapshot {
        val callerEntity = NodeConnectionHelper.getConnectable(level, startPos)
        val callerId = callerEntity?.networkId
        if (callerId != null) {
            val cached = cache[level.dimension() to callerId]
            if (cached != null && cached.tick == level.gameTime) return cached.snapshot
        }

        val snapshot = doDiscoverNetwork(level, startPos)

        // Key by the resolved controller id (stable across conflicts) so all
        // BEs on the network converge on the same cache entry regardless of
        // which startPos was passed in.
        val cacheId = snapshot.controller?.networkId ?: callerId
        if (cacheId != null) {
            cache[level.dimension() to cacheId] = CachedSnapshot(level.gameTime, snapshot)
        }
        return snapshot
    }

    private fun doDiscoverNetwork(level: ServerLevel, startPos: BlockPos): NetworkSnapshot {
        val visited = mutableSetOf<BlockPos>()
        // BFS threads `entryFace` so boundary Connectables (Processing Handler)
        // can hide their other-side neighbors. See [NodeConnectionHelper.propagateNetworkId]
        // for the same shape.
        val queue = ArrayDeque<Pair<BlockPos, Direction?>>()
        val nodes = mutableListOf<NodeSnapshot>()
        val crafters = mutableListOf<CrafterSnapshot>()
        val cpus = mutableListOf<CpuSnapshot>()
        val variables = mutableListOf<VariableSnapshot>()
        val breakers = mutableListOf<BreakerSnapshot>()
        val placers = mutableListOf<PlacerSnapshot>()
        val users = mutableListOf<UserSnapshot>()
        val processingApis = mutableListOf<ProcessingApiSnapshot>()
        val terminalPositions = mutableListOf<BlockPos>()
        var controller: ControllerSnapshot? = null
        // A second controller in the same subgraph drops the snapshot's controller so
        // the network reads as offline and downstream consumers refuse to run.
        var controllerCount = 0
        // Cluster anchors so each multi-block storage's recipes get recorded once,
        // not once per cluster member the BFS happens to visit.
        val processingClustersSeen = mutableSetOf<BlockPos>()
        val instructionClustersSeen = mutableSetOf<BlockPos>()

        queue.add(startPos to null)
        visited.add(startPos)

        while (queue.isNotEmpty()) {
            val (pos, entryFace) = queue.removeFirst()
            val connectable = NodeConnectionHelper.getConnectable(level, pos) ?: continue

            when (connectable) {
                is NodeBlockEntity -> nodes.add(snapshotNode(connectable))
                is InstructionStorageBlockEntity -> {
                    if (instructionClustersSeen.add(connectable.getClusterAnchor())) {
                        val clusterSets = connectable.getAllInstructionSets()
                        if (clusterSets.isNotEmpty()) {
                            crafters.add(CrafterSnapshot(connectable.blockPos, clusterSets))
                        }
                    }
                }
                is ProcessingStorageBlockEntity -> {
                    if (processingClustersSeen.add(connectable.getClusterAnchor())) {
                        val clusterApis = connectable.getAllProcessingApis()
                        if (clusterApis.isNotEmpty()) {
                            processingApis.add(ProcessingApiSnapshot(connectable.blockPos, clusterApis))
                        }
                    }
                }
                is ReceiverAntennaBlockEntity -> {
                    val broadcast = connectable.getBroadcastAntenna(level)
                    val broadcastLevel = broadcast?.level as? ServerLevel
                    if (broadcast != null && broadcastLevel != null) {
                        val activeWalks = activeProviderWalks.get()
                        val walkKey = broadcastLevel.dimension() to broadcast.blockPos
                        if (activeWalks.add(walkKey)) {
                            try {
                                val remoteApis = broadcast.getAvailableApis()
                                if (remoteApis.isNotEmpty()) {
                                    val remoteTerminals = broadcast.getProviderTerminalPositions()
                                    processingApis.add(
                                        ProcessingApiSnapshot(
                                            broadcast.blockPos,
                                            remoteApis,
                                            remoteTerminals,
                                            broadcastLevel.dimension(),
                                            broadcast.getSourceNetworkId(),
                                        )
                                    )
                                }
                            } finally {
                                activeWalks.remove(walkKey)
                            }
                        }
                    }
                }
                is TerminalBlockEntity -> terminalPositions.add(connectable.blockPos)
                is NetworkControllerBlockEntity -> {
                    controllerCount++
                    controller = if (controllerCount == 1)
                        ControllerSnapshot(connectable.blockPos, connectable.permanentId)
                    else null
                }
                is CraftingCoreBlockEntity -> cpus.add(CpuSnapshot(
                    connectable.blockPos, connectable.bufferUsed, connectable.bufferCapacity, connectable.isCrafting
                ))
                is VariableBlockEntity -> if (connectable.variableName.isNotEmpty()) {
                    variables.add(
                        VariableSnapshot(
                            connectable.blockPos,
                            connectable.variableName,
                            connectable.variableType,
                            connectable.channel,
                        )
                    )
                }
                is damien.nodeworks.block.entity.BreakerBlockEntity -> {
                    // Always snapshot breakers (unlike variables which require a name) so
                    // auto-aliasing produces breaker_N for unnamed devices. The user-set
                    // [deviceName] becomes the alias if non-empty.
                    breakers.add(
                        BreakerSnapshot(
                            connectable.blockPos,
                            connectable.deviceName.takeIf { it.isNotEmpty() },
                            connectable.channel,
                        )
                    )
                }
                is damien.nodeworks.block.entity.PlacerBlockEntity -> {
                    placers.add(
                        PlacerSnapshot(
                            connectable.blockPos,
                            connectable.deviceName.takeIf { it.isNotEmpty() },
                            connectable.channel,
                        )
                    )
                }
                is damien.nodeworks.block.entity.UserBlockEntity -> {
                    users.add(
                        UserSnapshot(
                            connectable.blockPos,
                            connectable.deviceName.takeIf { it.isNotEmpty() },
                            connectable.channel,
                        )
                    )
                }
            }

            for (connection in connectable.connectionsFromFace(entryFace)) {
                if (connection in visited) continue
                if (!level.isLoaded(connection)) continue
                if (level is net.minecraft.server.level.ServerLevel
                    && NodeConnectionHelper.isPairBlocked(level, pos, connection)
                ) continue
                visited.add(connection)
                queue.add(connection to faceFromTo(connection, pos))
            }
            // Face-adjacent Connectables share the subgraph without a laser between
            // them. Both endpoints must opt in via [Connectable.usesAdjacency], so a
            // Node next to a Controller doesn't silently bridge two networks.
            // Both endpoints must also accept the pair via [Connectable.canConnectAdjacentTo],
            // so leaves like the import/export chests don't auto-bridge each other's
            // networks just by sitting face-to-face. Mirrors the gate in
            // [NodeConnectionHelper.adjacentConnectableNeighbors].
            if (connectable.usesAdjacency()) {
                for (dir in Direction.entries) {
                    if (!connectable.adjacencyFaceAllowed(dir, entryFace)) continue
                    val adjPos = pos.relative(dir)
                    if (adjPos in visited) continue
                    if (!level.isLoaded(adjPos)) continue
                    val neighbor = level.getBlockEntity(adjPos) as? Connectable ?: continue
                    if (!neighbor.usesAdjacency()) continue
                    if (!neighbor.adjacencyFaceAllowed(dir.opposite, dir.opposite)) continue
                    if (!connectable.canConnectAdjacentTo(neighbor)) continue
                    if (!neighbor.canConnectAdjacentTo(connectable)) continue
                    if (connectable.forcedPipeBlocked(dir)) continue
                    if (neighbor.forcedPipeBlocked(dir.opposite)) continue
                    visited.add(adjPos)
                    queue.add(adjPos to dir.opposite)
                }
            }
        }

        // Auto-generate aliases for unnamed cards (e.g., io_1, io_2, storage_1) AND
        // unnamed devices (breaker_1, placer_1, user_1, ...). Devices share the same
        // counter namespace as cards so the alias prefix uniquely identifies the type.
        assignAutoAliases(nodes, breakers, placers, users)

        return NetworkSnapshot(nodes, crafters, variables, breakers, placers, users, cpus, processingApis, terminalPositions, controller)
    }

    /** Assign `<base>_N` auto-aliases so every card / breaker / placer on the
     *  network has a unique script-facing identifier. See [assignAliasSuffixes]
     *  for the rule. Cards, breakers and placers share the base namespace
     *  here, a card named `miner` and a breaker named `miner` group under
     *  the same base and suffix together, matching `network:get`'s
     *  cross-type lookup. */
    private fun assignAutoAliases(
        nodes: List<NodeSnapshot>,
        breakers: List<BreakerSnapshot>,
        placers: List<PlacerSnapshot>,
        users: List<UserSnapshot>,
    ) {
        val slots = mutableListOf<AliasSlot>()
        for (node in nodes) {
            for ((_, cards) in node.sides) {
                for (card in cards) {
                    slots.add(AliasSlot(
                        literalName = card.alias,
                        baseWhenUnnamed = autoAliasPrefix(card.capability.type),
                        setAutoAlias = { card.autoAlias = it },
                    ))
                }
            }
        }
        for (b in breakers) {
            slots.add(AliasSlot(
                literalName = b.name,
                baseWhenUnnamed = autoAliasPrefix("breaker"),
                setAutoAlias = { b.autoAlias = it },
            ))
        }
        for (p in placers) {
            slots.add(AliasSlot(
                literalName = p.name,
                baseWhenUnnamed = autoAliasPrefix("placer"),
                setAutoAlias = { p.autoAlias = it },
            ))
        }
        for (u in users) {
            slots.add(AliasSlot(
                literalName = u.name,
                baseWhenUnnamed = autoAliasPrefix("user"),
                setAutoAlias = { u.autoAlias = it },
            ))
        }
        assignAliasSuffixes(slots)
    }

    /** Direction from [from] to [to], or null when they're not face-adjacent.
     *  Same shape as the helper in [NodeConnectionHelper]; used to thread
     *  entry-face through the BFS queue. */
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

    private fun snapshotNode(entity: NodeBlockEntity): NodeSnapshot {
        val sides = mutableMapOf<Direction, List<CardSnapshot>>()

        for (dir in Direction.entries) {
            val capabilities = entity.getSideCapabilities(dir)
            if (capabilities.isEmpty()) continue
            sides[dir] = capabilities.map { info ->
                CardSnapshot(info.capability, info.alias, info.slotIndex, info.channel)
            }
        }

        return NodeSnapshot(entity.blockPos, sides)
    }

}

data class ControllerSnapshot(
    val pos: BlockPos,
    val networkId: UUID?
)

data class CpuSnapshot(
    val pos: BlockPos,
    val bufferUsed: Long,
    val bufferCapacity: Long,
    val isBusy: Boolean
)

data class VariableSnapshot(
    val pos: BlockPos,
    val name: String,
    val type: VariableType,
    /** Channel grouping color, mirroring [CardSnapshot.channel]. Variables are devices
     *  rather than slotted cards, so the channel lives on the [VariableBlockEntity]
     *  itself and is set via the variable GUI's channel picker. */
    val channel: net.minecraft.world.item.DyeColor = net.minecraft.world.item.DyeColor.WHITE,
)

/** Snapshot for a Breaker device. [name] is the user-set alias from the GUI.
 *  [autoAlias] is set by [NetworkDiscovery.assignAutoAliases] when the breaker is
 *  unnamed OR shares its [name] with another breaker on the network. The
 *  disambiguating `_N` suffix is what the script-facing identifier resolves to.
 *  [effectiveAlias] picks the auto-suffixed form first so duplicates each get a
 *  unique scripting handle even when the player typed identical names.
 *  Channel groups breakers for `network:channel(...)` lookups. */
data class BreakerSnapshot(
    val pos: BlockPos,
    val name: String?,
    val channel: net.minecraft.world.item.DyeColor = net.minecraft.world.item.DyeColor.WHITE,
) {
    var autoAlias: String? = null
    val effectiveAlias: String get() = autoAlias ?: name ?: "breaker"
}

/** Snapshot for a Placer device. Same shape as [BreakerSnapshot], devices share
 *  the alias-resolution rule even though their script methods differ. */
data class PlacerSnapshot(
    val pos: BlockPos,
    val name: String?,
    val channel: net.minecraft.world.item.DyeColor = net.minecraft.world.item.DyeColor.WHITE,
) {
    var autoAlias: String? = null
    val effectiveAlias: String get() = autoAlias ?: name ?: "placer"
}

data class UserSnapshot(
    val pos: BlockPos,
    val name: String?,
    val channel: net.minecraft.world.item.DyeColor = net.minecraft.world.item.DyeColor.WHITE,
) {
    var autoAlias: String? = null
    val effectiveAlias: String get() = autoAlias ?: name ?: "user"
}

data class ProcessingApiSnapshot(
    val pos: BlockPos,
    val apis: List<ProcessingStorageBlockEntity.ProcessingApiInfo>,
    val remoteTerminalPositions: List<BlockPos>? = null,
    /** Dimension the remote provider network lives in, null for local APIs, non-null when
     *  this snapshot was pulled via a Receiver Antenna paired to a remote (possibly cross-
     *  dimensional) Broadcast Antenna. Consumers that need to resolve an active script
     *  engine at a remoteTerminalPosition MUST pass this dimension to `findProcessingEngine`
     *, otherwise the engine lookup uses the caller's dimension and returns null, and the
     *  craft tree marks the recipe as `process_no_handler`. */
    val remoteDimension: net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level>? = null,
    /** UUID of the remote provider network's controller. Mirrors [remoteDimension]'s role
     *  but for [damien.nodeworks.script.cpu.BlockHandlerRegistry] lookups: a Processing
     *  Handler bound on the provider network registers under the provider network's id,
     *  so the consumer must look it up there rather than under its own id. Null when the
     *  API is local OR when the provider network has no controller (registry has no entry
     *  to find anyway). */
    val remoteNetworkId: UUID? = null,
)

data class NetworkSnapshot(
    val nodes: List<NodeSnapshot>,
    val crafters: List<CrafterSnapshot> = emptyList(),
    val variables: List<VariableSnapshot> = emptyList(),
    val breakers: List<BreakerSnapshot> = emptyList(),
    val placers: List<PlacerSnapshot> = emptyList(),
    val users: List<UserSnapshot> = emptyList(),
    val cpus: List<CpuSnapshot> = emptyList(),
    val processingApis: List<ProcessingApiSnapshot> = emptyList(),
    val terminalPositions: List<BlockPos> = emptyList(),
    val controller: ControllerSnapshot? = null
) {
    /** Whether this network has a controller and is online. */
    val isOnline: Boolean get() = controller != null

    /** The network's UUID, or null if no controller. */
    val networkId: UUID? get() = controller?.networkId

    /** All cards on this network, flattened across every node and every side.
     *  Lazy because the snapshot is shared across many lookups (especially
     *  inside [damien.nodeworks.script.cpu.CpuOpExecutor]'s per-tick cache),
     *  doing the [flatMap] once and reusing the list keeps craft hot loops O(N)
     *  in cards rather than O(N × ops). */
    private val flattenedCards: List<CardSnapshot> by lazy {
        nodes.flatMap { node -> node.sides.values.flatten() }
    }

    /** Storage cards on this network, sorted by priority (descending). Lazy
     *  because [damien.nodeworks.script.NetworkStorageHelper.getStorageCards]
     *  is called many times per Lua command and per device tick. */
    val storageCards: List<CardSnapshot> by lazy {
        flattenedCards
            .filter { it.capability is damien.nodeworks.card.StorageSideCapability }
            .sortedByDescending { (it.capability as damien.nodeworks.card.StorageSideCapability).priority }
    }

    /** Alias → card lookup, populated on first read. The literal [CardSnapshot.alias]
     *  wins, [effectiveAlias] entries only fill in keys the literal didn't claim,
     *  which mirrors the documented "first match" / "auto-suffixed fallback" rule
     *  the original linear scans implemented. */
    private val cardByAlias: Map<String, CardSnapshot> by lazy {
        val all = flattenedCards
        val map = HashMap<String, CardSnapshot>(all.size * 2)
        for (c in all) c.alias?.let { map.putIfAbsent(it, c) }
        for (c in all) map.putIfAbsent(c.effectiveAlias, c)
        map
    }

    private val breakerByAlias: Map<String, BreakerSnapshot> by lazy {
        val map = HashMap<String, BreakerSnapshot>(breakers.size * 2)
        for (b in breakers) b.name?.let { map.putIfAbsent(it, b) }
        for (b in breakers) map.putIfAbsent(b.effectiveAlias, b)
        map
    }

    private val placerByAlias: Map<String, PlacerSnapshot> by lazy {
        val map = HashMap<String, PlacerSnapshot>(placers.size * 2)
        for (p in placers) p.name?.let { map.putIfAbsent(it, p) }
        for (p in placers) map.putIfAbsent(p.effectiveAlias, p)
        map
    }

    private val userByAlias: Map<String, UserSnapshot> by lazy {
        val map = HashMap<String, UserSnapshot>(users.size * 2)
        for (u in users) u.name?.let { map.putIfAbsent(it, u) }
        for (u in users) map.putIfAbsent(u.effectiveAlias, u)
        map
    }

    /** Find a variable by name. */
    fun findVariable(name: String): VariableSnapshot? = variables.firstOrNull { it.name == name }

    /** Find a Breaker by alias. Literal name wins (so a player who named two
     *  breakers `miner` gets the first via `network:get("miner")`, matching
     *  the documented "first match" rule), then falls through to the
     *  auto-suffixed [effectiveAlias] lookup so `network:get("miner_2")`
     *  reaches the second one. */
    fun findBreaker(alias: String): BreakerSnapshot? = breakerByAlias[alias]

    /** Find a Placer by alias. Same literal-first / auto-suffixed-fallback rule
     *  as [findBreaker]. */
    fun findPlacer(alias: String): PlacerSnapshot? = placerByAlias[alias]

    /** Find a User by alias. Same resolution rule as [findBreaker] / [findPlacer]. */
    fun findUser(alias: String): UserSnapshot? = userByAlias[alias]

    /** Find an available (not busy) Crafting CPU with enough buffer capacity. */
    fun findAvailableCpu(requiredCapacity: Long = 0L): CpuSnapshot? =
        cpus.firstOrNull { !it.isBusy && it.bufferCapacity - it.bufferUsed >= requiredCapacity }

    /** Find a card by alias across the entire network. Literal-name match wins
     *  first (e.g. `network:get("cobblestone")` returns the first card the
     *  player named `cobblestone` even when several share the name), then the
     *  auto-suffixed [effectiveAlias] resolves the disambiguated forms
     *  (`cobblestone_2`). */
    fun findByAlias(alias: String): CardSnapshot? = cardByAlias[alias]

    /** All cards across the entire network. */
    fun allCards(): List<CardSnapshot> = flattenedCards

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

    /** Find a Processing Set by its recipe name (hash). The CPU executor uses
     *  this at op-run time so the recipe the planner chose during planning
     *  (potentially disambiguated by component patch via [pickBestRecipe])
     *  is the one actually executed. Looking up by output itemId at run time
     *  loses that decision and can pick a competing recipe with different
     *  inputs. */
    fun findProcessingApiByName(name: String): ProcessingApiMatch? {
        if (name.isEmpty()) return null
        for (snapshot in processingApis) {
            for (api in snapshot.apis) {
                if (api.name == name) return ProcessingApiMatch(snapshot, api)
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

    /** Find a Processing Set whose output matches [outputItemId] AND carries
     *  the same [componentsPatch]. Required when multiple recipes produce
     *  the same base item (different potions all output `minecraft:potion`).
     *  The itemId-only [findProcessingApi] would pick whichever recipe
     *  appears first, routing a Strength-potion request to the Fire-resistance
     *  recipe's handler. Passing an empty patch matches the first plain
     *  recipe variant. */
    fun findProcessingApiByOutput(
        outputItemId: String,
        componentsPatch: net.minecraft.core.component.DataComponentPatch,
    ): ProcessingApiMatch? = findAllProcessingApisByOutput(outputItemId, componentsPatch).firstOrNull()

    /** All Processing Sets whose output matches [outputItemId] + [componentsPatch].
     *  Returned in network walk order. Used by callers that need to disambiguate
     *  between competing recipes (e.g. when both `raw_iron → strength_potion`
     *  and `awkward_potion → strength_potion` exist on the same network).
     *  Caller is expected to apply feasibility / handler-presence tiebreakers. */
    fun findAllProcessingApisByOutput(
        outputItemId: String,
        componentsPatch: net.minecraft.core.component.DataComponentPatch,
    ): List<ProcessingApiMatch> {
        val requestedHash = damien.nodeworks.script.BufferKey.componentsHash(componentsPatch)
        val out = mutableListOf<ProcessingApiMatch>()
        for (snapshot in processingApis) {
            for (api in snapshot.apis) {
                for (ingr in api.outputs) {
                    if (ingr.itemId == outputItemId && ingr.componentsHash == requestedHash) {
                        out.add(ProcessingApiMatch(snapshot, api))
                        break
                    }
                }
            }
        }
        return out
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
    val slotIndex: Int,
    /** Channel grouping color. Defaults to [DyeColor.WHITE] for cards that haven't
     *  been dyed yet. Read at snapshot time from the card's `CUSTOM_DATA` via
     *  [damien.nodeworks.card.CardChannel.get]. Scripts use this to scope lookups
     *  through `network:channel(color)`. */
    val channel: net.minecraft.world.item.DyeColor = net.minecraft.world.item.DyeColor.WHITE,
) {
    /** Auto-generated alias. Set by [NetworkDiscovery.assignAutoAliases] for any
     *  card that needs disambiguation: every unnamed card (`io_1`, `storage_2`,
     *  ...), and any named card whose [alias] is shared by ≥1 sibling on the
     *  same network (`cobblestone` → `cobblestone_1` / `_2` / ...). Singleton
     *  named cards leave this null and resolve to their literal [alias]. */
    var autoAlias: String? = null

    /** The script-facing identifier. Auto-suffixed form when set, falling back
     *  to the literal [alias] for unique-named cards, then the capability type. */
    val effectiveAlias: String get() = autoAlias ?: alias ?: capability.type
}

/** Map a capability `type` string to the prefix used in auto-aliases.
 *
 *  Most types use their type string verbatim (`io` → `io_1`, `storage` → `storage_1`),
 *  but the terminal sidebar has a hard width limit and `observer_1` overflows the
 *  rendered column. Override observer to a shorter prefix, everything else passes
 *  through. Shared between [NetworkDiscovery.assignAutoAliases] and the terminal's
 *  client-side fallback aliasing pass so both surfaces always agree on what an
 *  unnamed card is called.
 */
fun autoAliasPrefix(type: String): String = when (type) {
    "observer" -> "observ"
    else -> type
}

/** One participant in [assignAliasSuffixes]. The caller wraps each card / breaker
 *  / placer in a slot, the helper figures out whether and what to write back via
 *  [setAutoAlias]. Ordering of slots in the list controls which entry takes the
 *  lowest available `_N` suffix in a duplicate group. */
data class AliasSlot(
    /** What the player typed (anvil rename, device GUI, etc.). Null when the
     *  entity has no user-set name. */
    val literalName: String?,
    /** Type prefix used as the base when [literalName] is null. `io`,
     *  `storage`, `breaker`, etc. Comes from [autoAliasPrefix]. */
    val baseWhenUnnamed: String,
    /** Receives the assigned `<base>_<N>` suffix. Not called for singleton
     *  named slots, which keep their bare literal name. */
    val setAutoAlias: (String) -> Unit,
)

/** Assign disambiguating `<base>_N` aliases across [slots]. Pure helper shared
 *  by [NetworkDiscovery.assignAutoAliases] (server-side) and the terminal's
 *  client-side scan, so both surfaces produce identical names without one
 *  drifting from the other.
 *
 *  Rule:
 *
 *    * Unnamed slots always get a suffix (`io_1`, `breaker_2`, ...).
 *    * Named slots only get a suffix when ≥2 share the same literal name.
 *      Singleton named slots keep their bare name as the effective alias.
 *    * The N counter for a base skips any number already taken by a literal
 *      name. So a player who explicitly named one card `cobblestone_2` parks
 *      index 2 in the `cobblestone` namespace, and two bare `cobblestone`
 *      cards resolve to `cobblestone_1` / `cobblestone_3` instead of
 *      colliding. Whatever the player typed wins.
 *
 *  Slots key into the same base namespace whether their literal name happens to
 *  match a type prefix (`io`) or is a player-typed name (`miner`), which mirrors
 *  the cross-type bare-name lookup `network:get` does.
 */
fun assignAliasSuffixes(slots: List<AliasSlot>) {
    // Pass 1: collect `<base>_<N>` indices already taken by literal names so
    // we can skip them when auto-assigning.
    val takenByBase = mutableMapOf<String, MutableSet<Int>>()
    val suffixRe = Regex("""^(.+)_(\d+)$""")
    for (slot in slots) {
        val literal = slot.literalName ?: continue
        val m = suffixRe.matchEntire(literal) ?: continue
        takenByBase.getOrPut(m.groupValues[1]) { mutableSetOf() }
            .add(m.groupValues[2].toInt())
    }

    // Pass 2: group by base. Named slots key on their literal name; unnamed
    // slots key on their type prefix. Cards named "io" therefore share the
    // "io" namespace with unnamed io cards.
    val groups = linkedMapOf<String, MutableList<AliasSlot>>()
    for (slot in slots) {
        val base = slot.literalName ?: slot.baseWhenUnnamed
        groups.getOrPut(base) { mutableListOf() }.add(slot)
    }

    // Pass 3: assign auto-aliases. Singleton named groups stay bare;
    // everything else gets a `_N` suffix that skips taken indices.
    for ((base, group) in groups) {
        if (group.size == 1 && group[0].literalName != null) continue
        val taken = takenByBase.getOrPut(base) { mutableSetOf() }
        var n = 1
        for (slot in group) {
            while (n in taken) n++
            slot.setAutoAlias("${base}_$n")
            taken.add(n)
            n++
        }
    }
}
