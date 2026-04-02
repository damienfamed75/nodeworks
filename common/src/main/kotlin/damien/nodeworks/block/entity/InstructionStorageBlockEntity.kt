package damien.nodeworks.block.entity

import damien.nodeworks.card.InstructionSet
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
import java.util.UUID

/**
 * Block entity for Instruction Storage. Holds Instruction Sets and an upgrade slot.
 * Connects to the network via laser (Connectable). Adjacent Instruction Storage blocks
 * form a cluster — the connected one discovers recipes from the entire cluster.
 *
 * Base capacity: 12 slots. Each Memory Upgrade (up to 4) adds 6 slots: 12 → 18 → 24 → 30 → 36.
 */
class InstructionStorageBlockEntity(
    pos: BlockPos,
    state: BlockState
) : BlockEntity(ModBlockEntities.INSTRUCTION_STORAGE, pos, state), Container, Connectable {

    companion object {
        const val BASE_SLOTS = 12
        const val UPGRADE_1_SLOTS = 18
        const val UPGRADE_2_SLOTS = 24
        const val UPGRADE_3_SLOTS = 30
        const val UPGRADE_4_SLOTS = 36
        const val MAX_SLOTS = UPGRADE_4_SLOTS
        const val UPGRADE_SLOT = MAX_SLOTS
        const val TOTAL_SLOTS = MAX_SLOTS + 1
    }

    private val items = NonNullList.withSize(TOTAL_SLOTS, ItemStack.EMPTY)
    private val connections = LinkedHashSet<BlockPos>()
    override var blockDestroyed: Boolean = false
    override var networkId: UUID? = null

    var upgradeLevel: Int = 0
        private set

    val activeSlotCount: Int get() = when (upgradeLevel) {
        0 -> BASE_SLOTS
        1 -> UPGRADE_1_SLOTS
        2 -> UPGRADE_2_SLOTS
        3 -> UPGRADE_3_SLOTS
        else -> UPGRADE_4_SLOTS
    }

    /** Returns all non-empty Instruction Set recipes in THIS storage block. */
    fun getInstructionSets(): List<InstructionSetInfo> {
        val result = mutableListOf<InstructionSetInfo>()
        for (i in 0 until activeSlotCount) {
            val stack = items[i]
            if (stack.isEmpty || stack.item !is InstructionSet) continue
            val recipe = InstructionSet.getRecipe(stack)
            val output = InstructionSet.getOutput(stack)
            val alias = stack.hoverName.string.takeIf { it != "Instruction Set" }
            result.add(InstructionSetInfo(recipe, output, alias, i))
        }
        return result
    }

    /**
     * Returns all Instruction Sets from this block AND all adjacent Instruction Storage blocks
     * in the cluster (BFS through adjacent InstructionStorageBlockEntity blocks).
     */
    fun getAllInstructionSets(): List<InstructionSetInfo> {
        val lvl = level ?: return getInstructionSets()
        val all = mutableListOf<InstructionSetInfo>()
        val visited = mutableSetOf(worldPosition)
        val queue = ArrayDeque<BlockPos>()
        queue.add(worldPosition)

        while (queue.isNotEmpty()) {
            val pos = queue.removeFirst()
            val entity = lvl.getBlockEntity(pos) as? InstructionStorageBlockEntity ?: continue
            all.addAll(entity.getInstructionSets())

            for (dir in Direction.entries) {
                val neighbor = pos.relative(dir)
                if (neighbor in visited) continue
                visited.add(neighbor)
                if (lvl.isLoaded(neighbor) && lvl.getBlockEntity(neighbor) is InstructionStorageBlockEntity) {
                    queue.add(neighbor)
                }
            }
        }
        return all
    }

    data class InstructionSetInfo(
        val recipe: List<String>,
        val outputItemId: String,
        val alias: String?,
        val slotIndex: Int
    )

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
        networkId?.let { tag.putString("networkId", it.toString()) }
    }

    override fun loadAdditional(tag: CompoundTag, registries: HolderLookup.Provider) {
        super.loadAdditional(tag, registries)
        items.clear()
        ContainerHelper.loadAllItems(tag, items, registries)
        recalculateUpgradeLevel()
        networkId = tag.getString("networkId").takeIf { it.isNotEmpty() }?.let {
            try { UUID.fromString(it) } catch (_: Exception) { null }
        }
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
