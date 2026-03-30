package damien.nodeworks.block.entity

import damien.nodeworks.card.ProcessingSet
import damien.nodeworks.item.MemoryUpgradeItem
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

/**
 * Block entity for Processing Storage. Holds Processing Sets and an upgrade slot.
 * Connects to the network via laser (Connectable). Adjacent Processing Storage blocks
 * form a cluster — the connected one discovers API cards from the entire cluster.
 *
 * Base capacity: 12 slots. Each Memory Upgrade (up to 4) adds 6 slots: 12 → 18 → 24 → 30 → 36.
 */
class ProcessingStorageBlockEntity(
    pos: BlockPos,
    state: BlockState
) : BlockEntity(ModBlockEntities.PROCESSING_STORAGE, pos, state), Container, Connectable {

    companion object {
        const val BASE_SLOTS = 12
        const val UPGRADE_1_SLOTS = 18
        const val UPGRADE_2_SLOTS = 24
        const val UPGRADE_3_SLOTS = 30
        const val UPGRADE_4_SLOTS = 36
        const val MAX_SLOTS = UPGRADE_4_SLOTS
        const val UPGRADE_SLOT = MAX_SLOTS
        const val TOTAL_SLOTS = MAX_SLOTS + 1

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

    var upgradeLevel: Int = 0
        private set

    val activeSlotCount: Int get() = when (upgradeLevel) {
        0 -> BASE_SLOTS
        1 -> UPGRADE_1_SLOTS
        2 -> UPGRADE_2_SLOTS
        3 -> UPGRADE_3_SLOTS
        else -> UPGRADE_4_SLOTS
    }

    /** Returns all non-empty Processing Sets in THIS storage block. */
    fun getProcessingApis(): List<ProcessingApiInfo> {
        val result = mutableListOf<ProcessingApiInfo>()
        for (i in 0 until activeSlotCount) {
            val stack = items[i]
            if (stack.isEmpty || stack.item !is ProcessingSet) continue
            val explicitName = ProcessingSet.getCardName(stack)
            val inputs = ProcessingSet.getInputs(stack)
            val outputs = ProcessingSet.getOutputs(stack)
            val timeout = ProcessingSet.getTimeout(stack)
            val serial = ProcessingSet.isSerial(stack)
            if (outputs.isEmpty()) continue
            val name = explicitName.ifEmpty { generateAutoName(outputs) }
            result.add(ProcessingApiInfo(name, inputs, outputs, timeout, serial))
        }
        return result
    }

    /**
     * Returns all Processing Sets from this block AND all adjacent Processing Storage blocks
     * in the cluster (BFS through adjacent ProcessingStorageBlockEntity blocks).
     */
    fun getAllProcessingApis(): List<ProcessingApiInfo> {
        val lvl = level ?: return getProcessingApis()
        val all = mutableListOf<ProcessingApiInfo>()
        val visited = mutableSetOf(worldPosition)
        val queue = ArrayDeque<BlockPos>()
        queue.add(worldPosition)

        while (queue.isNotEmpty()) {
            val pos = queue.removeFirst()
            val entity = lvl.getBlockEntity(pos) as? ProcessingStorageBlockEntity ?: continue
            all.addAll(entity.getProcessingApis())

            for (dir in Direction.entries) {
                val neighbor = pos.relative(dir)
                if (neighbor in visited) continue
                visited.add(neighbor)
                if (lvl.isLoaded(neighbor) && lvl.getBlockEntity(neighbor) is ProcessingStorageBlockEntity) {
                    queue.add(neighbor)
                }
            }
        }
        return all
    }

    data class ProcessingApiInfo(
        val name: String,
        val inputs: List<Pair<String, Int>>,
        val outputs: List<Pair<String, Int>>,
        val timeout: Int,
        val serial: Boolean = false
    ) {
        /** All output item IDs. */
        val outputItemIds: List<String> get() = outputs.map { it.first }
    }

    // --- Connectable ---

    override fun getConnections(): Set<BlockPos> = connections.toSet()

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
        }
        damien.nodeworks.render.NodeConnectionRenderer.trackConnectable(worldPosition, true)
    }

    override fun setRemoved() {
        damien.nodeworks.render.NodeConnectionRenderer.trackConnectable(worldPosition, false)
        val lvl = level
        if (lvl is ServerLevel) {
            NodeConnectionHelper.removeAllConnections(lvl, this)
            NodeConnectionHelper.untrackNode(lvl, worldPosition)
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
            if (slot == UPGRADE_SLOT) recalculateUpgradeLevel()
            setChanged()
        }
        return result
    }

    override fun removeItemNoUpdate(slot: Int): ItemStack {
        val result = ContainerHelper.takeItem(items, slot)
        if (slot == UPGRADE_SLOT) recalculateUpgradeLevel()
        return result
    }

    override fun setItem(slot: Int, stack: ItemStack) {
        if (slot in items.indices) {
            items[slot] = stack
            if (slot == UPGRADE_SLOT) recalculateUpgradeLevel()
            setChanged()
        }
    }

    override fun stillValid(player: Player): Boolean {
        return player.distanceToSqr(worldPosition.x + 0.5, worldPosition.y + 0.5, worldPosition.z + 0.5) <= 64.0
    }

    override fun clearContent() {
        items.clear()
        upgradeLevel = 0
    }

    private fun recalculateUpgradeLevel() {
        val upgradeStack = items[UPGRADE_SLOT]
        upgradeLevel = if (upgradeStack.item is MemoryUpgradeItem) {
            minOf(upgradeStack.count, 4)
        } else {
            0
        }
    }

    // --- Serialization ---

    override fun saveAdditional(tag: CompoundTag, registries: HolderLookup.Provider) {
        super.saveAdditional(tag, registries)
        ContainerHelper.saveAllItems(tag, items, registries)
        tag.putLongArray("connections", connections.map { it.asLong() }.toLongArray())
    }

    override fun loadAdditional(tag: CompoundTag, registries: HolderLookup.Provider) {
        super.loadAdditional(tag, registries)
        items.clear()
        ContainerHelper.loadAllItems(tag, items, registries)
        recalculateUpgradeLevel()
        connections.clear()
        if (tag.contains("connections")) {
            tag.getLongArray("connections").forEach { connections.add(BlockPos.of(it)) }
        }
    }

    override fun getUpdateTag(registries: HolderLookup.Provider): CompoundTag {
        return saveWithoutMetadata(registries)
    }

    override fun getUpdatePacket(): Packet<ClientGamePacketListener> {
        return ClientboundBlockEntityDataPacket.create(this)
    }
}
