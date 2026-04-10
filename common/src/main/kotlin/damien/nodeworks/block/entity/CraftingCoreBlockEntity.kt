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
import java.util.UUID

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
    override var networkId: UUID? = null

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

    /** The item currently being crafted (display name for GUI). */
    var currentCraftItem: String = ""
        private set

    /** Incremented on each cancel — lets in-flight ProcessingJobs detect stale state. */
    var jobGeneration: Int = 0
        private set

    // --- Pending processing job metadata (persisted for resume after restart) ---

    /** The top-level item ID that was originally requested (e.g. "minecraft:iron_helmet"). */
    var originalCraftId: String = ""
        private set

    /** The top-level count originally requested. */
    var originalCraftCount: Int = 0
        private set

    /** Expected outputs per operation: list of (itemId, count). */
    var pendingOutputs: List<Pair<String, Int>> = emptyList()
        private set

    /** Number of async processing operations still pending. */
    var pendingCount: Int = 0
        private set

    /** Block positions + faces that job:pull() should poll for outputs. */
    var pendingPullTargets: List<Pair<BlockPos, net.minecraft.core.Direction>> = emptyList()
        private set

    /** Whether resume has been scheduled this session (prevents duplicate resume). */
    @Transient var resumeScheduled: Boolean = false

    /** Set the top-level craft request for resume after restart. Called once per craft. */
    fun setOriginalCraft(itemId: String, count: Int) {
        if (originalCraftId.isEmpty()) {
            originalCraftId = itemId
            originalCraftCount = count
            setChanged()
        }
    }

    /**
     * Called when job:pull() registers an async poll. Captures the pull target
     * coordinates and increments the pending count.
     */
    fun addPendingOp(outputs: List<Pair<String, Int>>, pullTargets: List<Pair<BlockPos, net.minecraft.core.Direction>>) {
        if (pendingOutputs.isEmpty()) pendingOutputs = outputs
        if (pendingPullTargets.isEmpty() && pullTargets.isNotEmpty()) pendingPullTargets = pullTargets
        pendingCount++
        setChanged()
    }

    /** Called when one async operation completes. Clears metadata when all are done. */
    fun completePendingOp() {
        pendingCount = maxOf(0, pendingCount - 1)
        if (pendingCount <= 0) clearPendingJob()
        else setChanged()
    }

    /** Wipe all pending job metadata (but keep originalCraft — cleared by clearOriginalCraft). */
    fun clearPendingJob() {
        if (pendingOutputs.isNotEmpty() || pendingCount > 0 || pendingPullTargets.isNotEmpty()) {
            pendingOutputs = emptyList()
            pendingCount = 0
            pendingPullTargets = emptyList()
            setChanged()
        }
    }

    /** Wipe everything including the original craft request. Called when the entire craft is done or cancelled. */
    fun clearAllCraftState() {
        originalCraftId = ""
        originalCraftCount = 0
        clearPendingJob()
    }

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

    fun setCrafting(crafting: Boolean, itemName: String = "") {
        isCrafting = crafting
        currentCraftItem = if (crafting) itemName else ""
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
        jobGeneration++
        clearAllCraftState()
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
            // Schedule resume of pending jobs (deferred — neighbors need time to load)
            if (isCrafting && pendingCount > 0 && pendingPullTargets.isNotEmpty() && !resumeScheduled) {
                resumeScheduled = true
                // Defer 2 seconds (40 ticks) to ensure chunk neighbors are loaded
                damien.nodeworks.script.ResumeScheduler.scheduler.addPendingJob(
                    damien.nodeworks.script.SchedulerImpl.PendingJob(
                        pollFn = pollFnResume@{
                            // Wait until tick 40+ before attempting resume
                            if (damien.nodeworks.script.ResumeScheduler.scheduler.currentTick < 40) return@pollFnResume false
                            resumePendingJobs()
                            true
                        },
                        timeoutAt = Long.MAX_VALUE,
                        label = "cpu-resume:$worldPosition"
                    )
                )
            }
        }
        damien.nodeworks.render.NodeConnectionRenderer.trackConnectable(worldPosition, true)
    }

    /**
     * Reconstruct processing job polls from persisted pull targets.
     * Called once after world load when the CPU has pending operations.
     */
    private fun resumePendingJobs() {
        val lvl = level as? ServerLevel ?: return
        if (pendingCount <= 0 || pendingPullTargets.isEmpty()) return

        val logger = org.slf4j.LoggerFactory.getLogger("nodeworks-resume")
        logger.info("CPU at {}: resuming {} pending ops with {} pull targets",
            worldPosition, pendingCount, pendingPullTargets.size)

        // Build fresh storage getters from persisted coordinates
        val getters = pendingPullTargets.map { (pos, face) ->
            damien.nodeworks.script.CardHandle.StorageGetter {
                damien.nodeworks.platform.PlatformServices.storage.getItemStorage(lvl, pos, face)
            }
        }

        // Synthetic API info — only outputs matter for polling
        val apiInfo = damien.nodeworks.block.entity.ProcessingStorageBlockEntity.ProcessingApiInfo(
            name = "resume",
            inputs = emptyList(),
            outputs = pendingOutputs,
            timeout = 6000
        )

        val snapshot = damien.nodeworks.network.NetworkDiscovery.discoverNetwork(lvl, worldPosition)

        for (i in 0 until pendingCount) {
            val pending = damien.nodeworks.script.CraftingHelper.PendingHandlerJob()
            pending.onCompleteCallback = {
                // When all pending pull ops are done, re-invoke the original craft
                if (this.pendingCount <= 0) {
                    onPendingPullsComplete(lvl, logger)
                }
            }

            val job = damien.nodeworks.script.ProcessingJob(
                apiInfo, this, lvl,
                damien.nodeworks.script.ResumeScheduler.scheduler,
                pending
            )
            job.startPoll(getters)
        }
    }

    /**
     * Called when all persisted pull operations complete after a resume.
     * Re-invokes CraftingHelper.craft() with the original request so the
     * remaining craft steps (assembly, more sub-crafts) continue.
     */
    private fun onPendingPullsComplete(lvl: ServerLevel, logger: org.slf4j.Logger) {
        val craftId = originalCraftId
        val craftCount = originalCraftCount

        if (craftId.isEmpty()) {
            logger.info("CPU at {}: pulls complete but no original craft — flushing buffer", worldPosition)
            flushBufferAndRelease(lvl)
            return
        }

        logger.info("CPU at {}: pulls complete, re-invoking craft('{}', {})", worldPosition, craftId, craftCount)

        // Re-invoke craft with the original request, reusing this CPU
        val snap = damien.nodeworks.network.NetworkDiscovery.discoverNetwork(lvl, worldPosition)
        damien.nodeworks.script.CraftingHelper.currentPendingJob = null
        val result = damien.nodeworks.script.CraftingHelper.craft(
            craftId, craftCount, lvl, snap,
            cpuPos = worldPosition,
            callerScheduler = damien.nodeworks.script.ResumeScheduler.scheduler
        )
        val pending = damien.nodeworks.script.CraftingHelper.currentPendingJob
        damien.nodeworks.script.CraftingHelper.currentPendingJob = null

        if (result != null) {
            // Craft completed synchronously — flush buffer and release CPU
            logger.info("CPU at {}: re-craft completed synchronously", worldPosition)
            flushBufferAndRelease(lvl)
        } else if (pending != null) {
            // Async — wait for it, then flush
            pending.onCompleteCallback = { _ ->
                flushBufferAndRelease(lvl)
                logger.info("CPU at {}: re-craft async completed", worldPosition)
            }
        } else {
            // Craft failed — flush and release
            val reason = damien.nodeworks.script.CraftingHelper.lastFailReason
            logger.warn("CPU at {}: re-craft failed: {}", worldPosition, reason)
            flushBufferAndRelease(lvl)
        }
    }

    /** Flush all buffer contents to network storage, clear craft state, release CPU. */
    private fun flushBufferAndRelease(lvl: ServerLevel) {
        val snap = damien.nodeworks.network.NetworkDiscovery.discoverNetwork(lvl, worldPosition)
        val leftovers = clearBuffer()
        for ((itemId, count) in leftovers) {
            val id = net.minecraft.resources.ResourceLocation.tryParse(itemId) ?: continue
            val item = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(id) ?: continue
            var remaining = count
            while (remaining > 0) {
                val batch = minOf(remaining, item.getDefaultMaxStackSize())
                val stack = net.minecraft.world.item.ItemStack(item, batch)
                val inserted = damien.nodeworks.script.NetworkStorageHelper.insertItemStack(lvl, snap, stack, null)
                remaining -= inserted
                if (inserted == 0) break
            }
        }
        clearAllCraftState()
        setCrafting(false)
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
        tag.putString("currentCraftItem", currentCraftItem)
        networkId?.let { tag.putString("networkId", it.toString()) }

        // Save buffer contents
        if (buffer.isNotEmpty()) {
            val bufferTag = CompoundTag()
            for ((itemId, count) in buffer) {
                bufferTag.putInt(itemId, count)
            }
            tag.put("buffer", bufferTag)
        }

        // Save original craft request + pending job metadata (server-only)
        if (originalCraftId.isNotEmpty()) {
            tag.putString("originalCraftId", originalCraftId)
            tag.putInt("originalCraftCount", originalCraftCount)
        }
        if (pendingCount > 0) {
            tag.putInt("pendingCount", pendingCount)
            val outputsTag = CompoundTag()
            outputsTag.putInt("size", pendingOutputs.size)
            for ((i, pair) in pendingOutputs.withIndex()) {
                outputsTag.putString("id$i", pair.first)
                outputsTag.putInt("ct$i", pair.second)
            }
            tag.put("pendingOutputs", outputsTag)

            val targetsTag = CompoundTag()
            targetsTag.putInt("size", pendingPullTargets.size)
            for ((i, pair) in pendingPullTargets.withIndex()) {
                targetsTag.putLong("p$i", pair.first.asLong())
                targetsTag.putInt("f$i", pair.second.ordinal)
            }
            tag.put("pendingPullTargets", targetsTag)
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
        currentCraftItem = if (tag.contains("currentCraftItem")) tag.getString("currentCraftItem") else ""
        networkId = tag.getString("networkId").takeIf { it.isNotEmpty() }?.let {
            try { UUID.fromString(it) } catch (_: Exception) { null }
        }

        buffer.clear()
        if (tag.contains("buffer")) {
            val bufferTag = tag.getCompound("buffer")
            for (key in bufferTag.allKeys) {
                buffer[key] = bufferTag.getInt(key)
            }
        }

        // Load original craft request + pending job metadata
        originalCraftId = if (tag.contains("originalCraftId")) tag.getString("originalCraftId") else ""
        originalCraftCount = if (tag.contains("originalCraftCount")) tag.getInt("originalCraftCount") else 0
        pendingCount = if (tag.contains("pendingCount")) tag.getInt("pendingCount") else 0
        if (pendingCount > 0 && tag.contains("pendingOutputs")) {
            val outputsTag = tag.getCompound("pendingOutputs")
            val size = outputsTag.getInt("size")
            pendingOutputs = (0 until size).mapNotNull { i ->
                val id = outputsTag.getString("id$i")
                val ct = outputsTag.getInt("ct$i")
                if (id.isNotEmpty()) id to ct else null
            }
        } else {
            pendingOutputs = emptyList()
        }
        if (pendingCount > 0 && tag.contains("pendingPullTargets")) {
            val targetsTag = tag.getCompound("pendingPullTargets")
            val size = targetsTag.getInt("size")
            pendingPullTargets = (0 until size).map { i ->
                BlockPos.of(targetsTag.getLong("p$i")) to
                    net.minecraft.core.Direction.values()[targetsTag.getInt("f$i")]
            }
        } else {
            pendingPullTargets = emptyList()
        }
        resumeScheduled = false
    }

    override fun getUpdateTag(registries: HolderLookup.Provider): CompoundTag {
        val tag = saveWithoutMetadata(registries)
        // Strip server-only pending job metadata from client sync
        tag.remove("originalCraftId")
        tag.remove("originalCraftCount")
        tag.remove("pendingCount")
        tag.remove("pendingOutputs")
        tag.remove("pendingPullTargets")
        return tag
    }

    override fun getUpdatePacket(): Packet<ClientGamePacketListener> {
        return ClientboundBlockEntityDataPacket.create(this)
    }
}
