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
        val breakers = mutableListOf<BreakerSnapshot>()
        val placers = mutableListOf<PlacerSnapshot>()
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
                    // Storage block connected via laser, scan its cluster for all recipes
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
                            val broadcastLevel = broadcast.level as? ServerLevel
                            processingApis.add(
                                ProcessingApiSnapshot(
                                    broadcast.blockPos,
                                    remoteApis,
                                    remoteTerminals,
                                    broadcastLevel?.dimension()
                                )
                            )
                        }
                    }
                }
                is TerminalBlockEntity -> terminalPositions.add(connectable.blockPos)
                is NetworkControllerBlockEntity -> controller = ControllerSnapshot(connectable.blockPos, connectable.networkId)
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
            }

            for (connection in connectable.getConnections()) {
                if (connection in visited) continue
                // Only mark visited after LOS passes, another path may have clear LOS
                if (!NodeConnectionHelper.checkLineOfSight(level, pos, connection)) continue
                visited.add(connection)
                queue.add(connection)
            }
        }

        // Auto-generate aliases for unnamed cards (e.g., io_1, io_2, storage_1) AND
        // unnamed devices (breaker_1, placer_1, ...). Devices share the same counter
        // namespace as cards so the alias prefix uniquely identifies the type.
        assignAutoAliases(nodes, breakers, placers)

        return NetworkSnapshot(nodes, crafters, variables, breakers, placers, cpus, processingApis, terminalPositions, controller)
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
        assignAliasSuffixes(slots)
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
    val remoteDimension: net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level>? = null
)

data class NetworkSnapshot(
    val nodes: List<NodeSnapshot>,
    val crafters: List<CrafterSnapshot> = emptyList(),
    val variables: List<VariableSnapshot> = emptyList(),
    val breakers: List<BreakerSnapshot> = emptyList(),
    val placers: List<PlacerSnapshot> = emptyList(),
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
