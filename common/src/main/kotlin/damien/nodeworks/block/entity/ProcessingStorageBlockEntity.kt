package damien.nodeworks.block.entity

import damien.nodeworks.card.ProcessingSet
import damien.nodeworks.network.Connectable
import damien.nodeworks.network.NodeConnectionHelper
import damien.nodeworks.registry.ModBlockEntities
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.HolderLookup
import net.minecraft.core.NonNullList
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.Container
import net.minecraft.world.ContainerHelper
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput
import damien.nodeworks.compat.getBlockPosList
import damien.nodeworks.compat.getStringOrNull
import damien.nodeworks.compat.putBlockPosList
import java.util.UUID

/**
 * Block entity for Processing Storage. Holds up to 8 Processing Sets.
 * Connects to the network via laser (Connectable). Adjacent Processing Storage blocks
 * form a cluster, the connected one discovers API cards from the entire cluster.
 */
class ProcessingStorageBlockEntity(
    pos: BlockPos,
    state: BlockState
) : BlockEntity(ModBlockEntities.PROCESSING_STORAGE, pos, state), Container, Connectable {

    companion object {
        const val TOTAL_SLOTS = 8

        /** Generate a name from outputs, e.g. "api_iron_ingot2_copper_ingot1" */
        fun generateAutoName(outputs: List<Pair<String, Int>>): String {
            val parts = outputs.map { (itemId, count) ->
                val shortId = itemId.substringAfter(':')
                "$shortId$count"
            }
            return "api_${parts.joinToString("_")}"
        }
    }

    private val items = NonNullList.withSize(TOTAL_SLOTS, ItemStack.EMPTY)
    private val connections = LinkedHashSet<BlockPos>()
    override var blockDestroyed: Boolean = false
    override var networkId: UUID? = null

    /** Returns all non-empty Processing Sets in THIS storage block.
     *  Inputs and outputs are component-aware [RecipeIngredient]s and the
     *  [ProcessingApiInfo.name] carries the `recipe_<hash>` identity. */
    fun getProcessingApis(): List<ProcessingApiInfo> {
        val result = mutableListOf<ProcessingApiInfo>()
        val registries = level?.registryAccess() ?: return emptyList()
        for (i in 0 until TOTAL_SLOTS) {
            val stack = items[i]
            if (stack.isEmpty || stack.item !is ProcessingSet) continue
            val inputs = ProcessingSet.getInputs(stack, registries)
            val outputs = ProcessingSet.getOutputs(stack, registries)
            val timeout = ProcessingSet.getTimeout(stack)
            val serial = ProcessingSet.isSerial(stack)
            val fuzzy = ProcessingSet.isFuzzy(stack)
            if (outputs.isEmpty()) continue
            val name = ProcessingSet.computeRecipeId(stack, registries)
            result.add(ProcessingApiInfo(name, inputs, outputs, timeout, serial, fuzzy))
        }
        return result
    }

    /** All Processing Sets from this block plus every face-adjacent ProcessingStorage in the cluster. */
    fun getAllProcessingApis(): List<ProcessingApiInfo> {
        val lvl = level ?: return getProcessingApis()
        val all = mutableListOf<ProcessingApiInfo>()
        for (pos in clusterPositions(lvl)) {
            val entity = lvl.getBlockEntity(pos) as? ProcessingStorageBlockEntity ?: continue
            all.addAll(entity.getProcessingApis())
        }
        return all
    }

    /** Lex-lowest position in this cluster. Stable cluster identity for network-walk
     *  consumers that need to enumerate each cluster exactly once. */
    fun getClusterAnchor(): BlockPos {
        val lvl = level ?: return worldPosition
        return clusterPositions(lvl).minByOrNull { it.asLong() } ?: worldPosition
    }

    private fun clusterPositions(lvl: net.minecraft.world.level.Level): Set<BlockPos> {
        val cluster = mutableSetOf(worldPosition)
        val queue = ArrayDeque<BlockPos>()
        queue.add(worldPosition)
        while (queue.isNotEmpty()) {
            val pos = queue.removeFirst()
            for (dir in Direction.entries) {
                val neighbor = pos.relative(dir)
                if (neighbor in cluster) continue
                if (!lvl.isLoaded(neighbor)) continue
                if (lvl.getBlockEntity(neighbor) !is ProcessingStorageBlockEntity) continue
                cluster.add(neighbor)
                queue.add(neighbor)
            }
        }
        return cluster
    }

    /** Runtime description of a Processing Set recipe. Inputs and outputs
     *  carry full [ItemStack]s via [RecipeIngredient] so component-bearing
     *  recipes (different potions, dyed armor) flow through the planner
     *  and handler dispatch with their identity intact. The [name] field
     *  carries the `recipe_<hash>` identity and the [fuzzy] flag signals
     *  "accept any component variant" semantics. */
    data class ProcessingApiInfo(
        val name: String,
        val inputs: List<damien.nodeworks.script.RecipeIngredient>,
        val outputs: List<damien.nodeworks.script.RecipeIngredient>,
        val timeout: Int,
        val serial: Boolean = false,
        val fuzzy: Boolean = false,
        /** Optional pre-computed `(itemId, count)` projections. Non-null only
         *  for fixtures built via [fromPairs] (unit tests, which can't touch
         *  [net.minecraft.core.registries.BuiltInRegistries] at runtime).
         *  When null, [inputsAsPairs] / [outputsAsPairs] derive from the
         *  full [inputs] / [outputs] ingredient lists. */
        private val inputsPairsOverride: List<Pair<String, Int>>? = null,
        private val outputsPairsOverride: List<Pair<String, Int>>? = null,
    ) {
        /** All output item IDs. Strips components, used by consumers that just
         *  need to know which items the recipe can produce by name. */
        val outputItemIds: List<String> get() = outputsAsPairs.map { it.first }

        /** Legacy `(itemId, count)` projection for consumers that haven't
         *  been widened to component-aware reads yet. Components are dropped. */
        val inputsAsPairs: List<Pair<String, Int>>
            get() = inputsPairsOverride ?: inputs.map { it.itemId to it.count }
        val outputsAsPairs: List<Pair<String, Int>>
            get() = outputsPairsOverride ?: outputs.map { it.itemId to it.count }

        companion object {
            /** Test/legacy fixture: build a [ProcessingApiInfo] from
             *  `(itemId, count)` pairs without constructing real
             *  [damien.nodeworks.script.RecipeIngredient]s. Routes the pairs
             *  through the [inputsPairsOverride] / [outputsPairsOverride]
             *  fields so [inputsAsPairs] still returns them, while [inputs]
             *  / [outputs] stay empty (no [net.minecraft.world.item.ItemStack]
             *  construction, no [net.minecraft.core.registries.BuiltInRegistries]
             *  lookup). Used by [damien.nodeworks.script.diagnostics] unit
             *  tests whose runtime classpath lacks Minecraft. */
            @JvmStatic
            fun fromPairs(
                name: String,
                inputs: List<Pair<String, Int>>,
                outputs: List<Pair<String, Int>> = emptyList(),
                timeout: Int = 0,
                serial: Boolean = false,
                fuzzy: Boolean = false,
            ): ProcessingApiInfo = ProcessingApiInfo(
                name = name,
                inputs = emptyList(),
                outputs = emptyList(),
                timeout = timeout,
                serial = serial,
                fuzzy = fuzzy,
                inputsPairsOverride = inputs,
                outputsPairsOverride = outputs,
            )
        }
    }

    // --- Connectable ---

    override fun getConnections(): Set<BlockPos> = connections

    override fun addConnection(pos: BlockPos): Boolean {
        if (!connections.add(pos)) return false
        setChanged()
        level?.sendBlockUpdated(worldPosition, blockState, blockState, Block.UPDATE_ALL)
        return true
    }

    override fun removeConnection(pos: BlockPos): Boolean {
        if (!connections.remove(pos)) return false
        setChanged()
        level?.sendBlockUpdated(worldPosition, blockState, blockState, Block.UPDATE_ALL)
        return true
    }

    override fun hasConnection(pos: BlockPos): Boolean = connections.contains(pos)

    // --- Lifecycle ---

    override fun setLevel(level: net.minecraft.world.level.Level) {
        super.setLevel(level)
        if (level is ServerLevel) {
            NodeConnectionHelper.trackNode(level, worldPosition)
            NodeConnectionHelper.queueRevalidation(level, worldPosition)
        }
        damien.nodeworks.render.NodeConnectionRenderer.trackConnectable(level, worldPosition, true)
    }

    override fun setRemoved() {
        damien.nodeworks.render.NodeConnectionRenderer.trackConnectable(level, worldPosition, false)
        val lvl = level
        if (lvl is ServerLevel) {
            NodeConnectionHelper.removeAllConnections(lvl, this)
            NodeConnectionHelper.untrackNode(lvl, worldPosition)
            // Queue each cluster-adjacent sibling for revalidation next tick. By
            // the time the drain runs, this BE is fully gone, so the siblings'
            // propagate BFS won't cross back through us, halves of a split
            // cluster correctly re-derive their own networkId (or lose it).
            // Deferring via the revalidation queue (instead of propagating now)
            // avoids traversing this about-to-be-removed position as a cluster
            // neighbor.
            for (dir in Direction.entries) {
                val neighbor = worldPosition.relative(dir)
                if (lvl.isLoaded(neighbor) && lvl.getBlockEntity(neighbor) is ProcessingStorageBlockEntity) {
                    NodeConnectionHelper.queueRevalidation(lvl, neighbor)
                }
            }
        }
        super.setRemoved()
    }

    // --- Container ---

    override fun getContainerSize(): Int = TOTAL_SLOTS

    override fun isEmpty(): Boolean = items.all { it.isEmpty }

    override fun getItem(slot: Int): ItemStack {
        return if (slot in items.indices) items[slot] else ItemStack.EMPTY
    }

    override fun removeItem(slot: Int, amount: Int): ItemStack {
        val result = ContainerHelper.removeItem(items, slot, amount)
        if (!result.isEmpty) {
            setChanged()
            // Push update so the Script Terminal's client-side scan sees the new set of
            // recipes without requiring a world reload or re-open of the storage block.
            level?.sendBlockUpdated(worldPosition, blockState, blockState, Block.UPDATE_ALL)
        }
        return result
    }

    override fun removeItemNoUpdate(slot: Int): ItemStack {
        return ContainerHelper.takeItem(items, slot)
    }

    override fun setItem(slot: Int, stack: ItemStack) {
        if (slot in items.indices) {
            items[slot] = stack
            setChanged()
            level?.sendBlockUpdated(worldPosition, blockState, blockState, Block.UPDATE_ALL)
        }
    }

    override fun stillValid(player: Player): Boolean {
        return player.distanceToSqr(worldPosition.x + 0.5, worldPosition.y + 0.5, worldPosition.z + 0.5) <= 64.0
    }

    override fun clearContent() {
        items.clear()
    }

    // --- Serialization ---

    override fun saveAdditional(output: ValueOutput) {
        super.saveAdditional(output)
        ContainerHelper.saveAllItems(output, items)
        output.putBlockPosList("connections", connections)
        networkId?.let { output.putString("networkId", it.toString()) }
    }

    override fun loadAdditional(input: ValueInput) {
        super.loadAdditional(input)
        items.clear()
        ContainerHelper.loadAllItems(input, items)
        networkId = input.getStringOrNull("networkId")?.takeIf { it.isNotEmpty() }?.let {
            try { UUID.fromString(it) } catch (_: Exception) { null }
        }
        damien.nodeworks.network.NetworkSettingsRegistry.notifyConnectableChanged(networkId)
        connections.clear()
        connections.addAll(input.getBlockPosList("connections"))
    }

    override fun getUpdateTag(registries: HolderLookup.Provider): CompoundTag {
        return saveWithoutMetadata(registries)
    }

    override fun getUpdatePacket(): Packet<ClientGamePacketListener> {
        return ClientboundBlockEntityDataPacket.create(this)
    }
}
