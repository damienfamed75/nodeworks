package damien.nodeworks.block.entity

import damien.nodeworks.network.Connectable
import damien.nodeworks.network.NodeConnectionHelper
import damien.nodeworks.registry.ModBlockEntities
import net.minecraft.core.BlockPos
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState

/**
 * Crafting Core — the brain of a multiblock Crafting CPU.
 * Connects to the network via laser. Discovers adjacent Crafting Storage blocks
 * to determine buffer capacity. Executes crafting jobs with items held in an
 * internal buffer to prevent race conditions.
 */
class CraftingCoreBlockEntity(
    pos: BlockPos,
    state: BlockState
) : BlockEntity(ModBlockEntities.CRAFTING_CORE, pos, state), Connectable {

    private val connections = LinkedHashSet<BlockPos>()
    override var blockDestroyed: Boolean = false

    // --- Buffer ---

    companion object {
        const val BASE_CAPACITY = 256
    }

    /** Virtual item buffer: itemId → count. Private to this CPU during crafting. */
    private val buffer = mutableMapOf<String, Int>()

    /** Current total items in buffer. */
    val bufferUsed: Int get() = buffer.values.sum()

    /** Max buffer capacity (base + adjacent Crafting Storage blocks). */
    var bufferCapacity: Int = BASE_CAPACITY
        private set

    /** Whether a crafting job is currently running. */
    var isCrafting: Boolean = false
        private set

    // --- Buffer operations ---

    fun addToBuffer(itemId: String, count: Int): Boolean {
        if (bufferUsed + count > bufferCapacity) return false
        buffer[itemId] = (buffer[itemId] ?: 0) + count
        setChanged()
        return true
    }

    fun removeFromBuffer(itemId: String, count: Int): Int {
        val has = buffer[itemId] ?: 0
        val removed = minOf(has, count)
        if (removed > 0) {
            val remaining = has - removed
            if (remaining > 0) buffer[itemId] = remaining else buffer.remove(itemId)
            setChanged()
        }
        return removed
    }

    fun getBufferCount(itemId: String): Int = buffer[itemId] ?: 0

    fun getBufferContents(): Map<String, Int> = buffer.toMap()

    fun clearBuffer(): Map<String, Int> {
        val contents = buffer.toMap()
        buffer.clear()
        setChanged()
        return contents
    }

    fun setCrafting(crafting: Boolean) {
        isCrafting = crafting
        markDirtyAndSync()
    }

    /** Cancel the current job: drop buffer contents as items and reset state. */
    fun cancelJob() {
        val lvl = level as? net.minecraft.server.level.ServerLevel ?: return
        val contents = clearBuffer()
        for ((itemId, count) in contents) {
            val id = net.minecraft.resources.ResourceLocation.tryParse(itemId) ?: continue
            val item = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(id) ?: continue
            var remaining = count
            while (remaining > 0) {
                val batch = minOf(remaining, item.getDefaultMaxStackSize())
                val stack = net.minecraft.world.item.ItemStack(item, batch)
                // Try to insert into network storage via connected nodes
                val snapshot = damien.nodeworks.network.NetworkDiscovery.discoverNetwork(lvl, worldPosition)
                val inserted = damien.nodeworks.script.NetworkStorageHelper.insertItemStack(lvl, snapshot, stack, null)
                if (inserted > 0) {
                    remaining -= inserted
                } else {
                    // Can't insert to storage — drop as item entity
                    net.minecraft.world.Containers.dropItemStack(lvl,
                        worldPosition.x + 0.5, worldPosition.y + 1.0, worldPosition.z + 0.5, stack)
                    remaining -= batch
                }
            }
        }
        setCrafting(false)
    }

    // --- Multiblock detection ---

    /**
     * Scans adjacent blocks to find Crafting Storage blocks and compute total capacity.
     * Called when the block is placed, a neighbor changes, or on world load.
     */
    /** Whether the CPU has at least one Crafting Storage block (required to be active). */
    var isFormed: Boolean = false
        private set

    fun recalculateCapacity() {
        var total = 0
        var storageCount = 0
        val visited = mutableSetOf(worldPosition)
        val queue = ArrayDeque<BlockPos>()
        queue.add(worldPosition)

        while (queue.isNotEmpty()) {
            val pos = queue.removeFirst()
            for (dir in net.minecraft.core.Direction.entries) {
                val neighbor = pos.relative(dir)
                if (neighbor in visited) continue
                visited.add(neighbor)
                val entity = level?.getBlockEntity(neighbor)
                if (entity is CraftingStorageBlockEntity) {
                    total += entity.storageCapacity
                    storageCount++
                    queue.add(neighbor) // Continue searching from this storage block
                }
            }
        }

        isFormed = storageCount > 0
        bufferCapacity = if (isFormed) BASE_CAPACITY + total else 0
        markDirtyAndSync()
        updateBlockState()
    }

    /**
     * Syncs the block entity's formed status to the block state,
     * which drives the model variant (emissive on/off) and triggers a chunk rebuild
     * so the block color provider re-evaluates the network color.
     */
    private fun updateBlockState() {
        val lvl = level ?: return
        val state = blockState
        if (state.block !is damien.nodeworks.block.CraftingCoreBlock) return
        val formedProp = damien.nodeworks.block.CraftingCoreBlock.FORMED
        if (state.getValue(formedProp) != isFormed) {
            lvl.setBlock(worldPosition, state.setValue(formedProp, isFormed), Block.UPDATE_ALL)
        }
    }

    // --- Connectable ---

    override fun getConnections(): Set<BlockPos> = connections.toSet()

    override fun addConnection(pos: BlockPos): Boolean {
        if (!connections.add(pos)) return false
        markDirtyAndSync()
        return true
    }

    override fun removeConnection(pos: BlockPos): Boolean {
        if (!connections.remove(pos)) return false
        markDirtyAndSync()
        return true
    }

    override fun hasConnection(pos: BlockPos): Boolean = connections.contains(pos)

    // --- Lifecycle ---

    override fun setLevel(level: net.minecraft.world.level.Level) {
        super.setLevel(level)
        if (level is ServerLevel) {
            NodeConnectionHelper.trackNode(level, worldPosition)
            // Don't recalculateCapacity here — neighbors may not be loaded yet.
            // Capacity is recalculated on neighborChanged or on first craft request.
        }
        damien.nodeworks.render.NodeConnectionRenderer.trackConnectable(worldPosition, true)
    }

    override fun setRemoved() {
        damien.nodeworks.render.NodeConnectionRenderer.trackConnectable(worldPosition, false)
        val lvl = level
        if (blockDestroyed && lvl is ServerLevel) {
            NodeConnectionHelper.removeAllConnections(lvl, this)
            NodeConnectionHelper.untrackNode(lvl, worldPosition)
        }
        super.setRemoved()
    }

    private fun markDirtyAndSync() {
        setChanged()
        level?.sendBlockUpdated(worldPosition, blockState, blockState, Block.UPDATE_ALL)
    }

    // --- Serialization ---

    override fun saveAdditional(tag: CompoundTag, registries: HolderLookup.Provider) {
        super.saveAdditional(tag, registries)
        tag.putLongArray("connections", connections.map { it.asLong() }.toLongArray())
        tag.putInt("bufferCapacity", bufferCapacity)
        tag.putBoolean("isFormed", isFormed)
        tag.putBoolean("isCrafting", isCrafting)

        // Save buffer contents
        if (buffer.isNotEmpty()) {
            val bufferTag = CompoundTag()
            for ((itemId, count) in buffer) {
                bufferTag.putInt(itemId, count)
            }
            tag.put("buffer", bufferTag)
        }
    }

    override fun loadAdditional(tag: CompoundTag, registries: HolderLookup.Provider) {
        super.loadAdditional(tag, registries)
        connections.clear()
        if (tag.contains("connections")) {
            tag.getLongArray("connections").forEach { connections.add(BlockPos.of(it)) }
        }
        bufferCapacity = if (tag.contains("bufferCapacity")) tag.getInt("bufferCapacity") else 0
        isFormed = tag.getBoolean("isFormed")
        isCrafting = tag.getBoolean("isCrafting")

        buffer.clear()
        if (tag.contains("buffer")) {
            val bufferTag = tag.getCompound("buffer")
            for (key in bufferTag.allKeys) {
                buffer[key] = bufferTag.getInt(key)
            }
        }
    }

    override fun getUpdateTag(registries: HolderLookup.Provider): CompoundTag {
        return saveWithoutMetadata(registries)
    }

    override fun getUpdatePacket(): Packet<ClientGamePacketListener> {
        return ClientboundBlockEntityDataPacket.create(this)
    }
}
