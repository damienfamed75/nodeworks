package damien.nodeworks.block.entity

import damien.nodeworks.compat.getBlockPosList
import damien.nodeworks.compat.getLongOrNull
import damien.nodeworks.compat.getStringOrNull
import damien.nodeworks.compat.putBlockPosList
import damien.nodeworks.network.ChannelFilter
import damien.nodeworks.network.Connectable
import damien.nodeworks.network.NetworkDiscovery
import damien.nodeworks.network.NetworkSettingsRegistry
import damien.nodeworks.network.NodeConnectionHelper
import damien.nodeworks.registry.ModBlockEntities
import damien.nodeworks.render.NodeConnectionRenderer
import damien.nodeworks.script.BlockCraftSubmitter
import damien.nodeworks.script.CraftingHelper
import damien.nodeworks.script.NetworkStorageHelper
import net.minecraft.core.BlockPos
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput
import java.util.UUID

/**
 * Backs the Storage Meter. Holds the configured target item, threshold,
 * channel, the runtime "is below threshold" state that drives the block's
 * redstone signal, and the local-craft submission path that fires a craft
 * to keep the network stocked when a Crafting CPU is reachable.
 *
 * Cross-network workflow still works the same: with no local CPU the craft
 * silently no-ops (NoCpu) and the redstone signal alone drives an external
 * Craft Requester. Pair them when you want pulse-style remote crafting,
 * use the Meter standalone when you just want autostock.
 */
class StorageMeterBlockEntity(
    pos: BlockPos,
    state: BlockState,
) : BlockEntity(ModBlockEntities.STORAGE_METER, pos, state), Connectable {

    companion object {
        private const val COUNT_INTERVAL_TICKS = 10L
        // Sustained ticks of below-threshold before flipping the signal on.
        private const val DEBOUNCE_TICKS = 10L
        private const val JOB_COOLDOWN_TICKS = 20L
        // FIFO cap so a broken setup doesn't grow NBT unbounded.
        private const val MAX_ERROR_LINES = 8
    }

    // Planning errors for the GUI's JobStatusIndicator. NoCpu / NoNetwork
    // are silent (cross-network workflow), only Failed appends here.
    val errorLines: MutableList<String> = mutableListOf()

    private val connections = LinkedHashSet<BlockPos>()
    override var blockDestroyed: Boolean = false
    override var networkId: UUID? = null

    // Stack count is ignored. Components carry through so variants (potions,
    // custom names, etc.) flow into the craft submission unchanged.
    var target: ItemStack = ItemStack.EMPTY
        set(value) {
            field = if (value.isEmpty) ItemStack.EMPTY else value.copyWithCount(1)
            markDirtyAndSync()
        }

    // 0 disables the meter.
    var threshold: Int = 0
        set(value) {
            field = value.coerceAtLeast(0)
            markDirtyAndSync()
        }

    // Scopes both the storage count and the craft delivery channel.
    var channel: ChannelFilter = ChannelFilter.All
        set(value) {
            field = value
            markDirtyAndSync()
        }

    // When false the meter is a pure redstone monitor (no local crafts).
    var autocraftEnabled: Boolean = true
        set(value) {
            field = value
            markDirtyAndSync()
        }

    var displayCount: Long = 0L

    var isBelowThreshold: Boolean = false
        internal set

    // Transient runtime fields the ticker uses, no need to persist them.

    /** Tick at which the count first crossed below the threshold this episode.
     *  Reset to -1 when the count recovers, the ticker requires 10 sustained
     *  ticks before flipping [isBelowThreshold] to debounce. */
    @Transient
    internal var belowThresholdSinceTick: Long = -1L

    /** Tick at which the count first crossed above the threshold this episode,
     *  symmetric with [belowThresholdSinceTick]. */
    @Transient
    internal var aboveThresholdSinceTick: Long = -1L

    @Transient
    internal var pendingJob: CraftingHelper.PendingHandlerJob? = null

    // Mirrored to the client so the BER and GUI can react.
    var hasActiveJob: Boolean = false
        private set

    // Initialised to -COOLDOWN so the first check passes without overflow.
    @Transient
    internal var lastJobAttemptTick: Long = -JOB_COOLDOWN_TICKS

    // Rising edge (disconnected to connected) drops stale planning errors.
    @Transient
    internal var wasConnected: Boolean = false

    fun serverTick(level: ServerLevel) {
        // Rising edge of network attachment drops stale errors.
        val nowConnected = networkId != null
        if (nowConnected && !wasConnected && errorLines.isNotEmpty()) {
            errorLines.clear()
            setChanged()
            level.sendBlockUpdated(worldPosition, blockState, blockState, Block.UPDATE_CLIENTS)
        }
        wasConnected = nowConnected

        // Disconnected: no redstone, no submissions. Status reads Disconnected.
        if (!nowConnected) {
            val wasBelow = isBelowThreshold
            val countChanged = displayCount != 0L
            if (wasBelow) {
                isBelowThreshold = false
                belowThresholdSinceTick = -1L
                aboveThresholdSinceTick = -1L
            }
            if (countChanged) displayCount = 0L
            if (wasBelow) {
                setChanged()
                notifyRedstoneChange(level)
            } else if (countChanged) {
                setChanged()
                level.sendBlockUpdated(worldPosition, blockState, blockState, Block.UPDATE_CLIENTS)
            }
            return
        }

        // Disabled meters are O(1) per tick. Any in-flight job still completes
        // server-side (delivery has already routed items to storage by the
        // time complete fires), we just stop tracking it.
        // Disabled: clear redstone + errors, status reads Idle.
        if (target.isEmpty || threshold <= 0) {
            val wasBelow = isBelowThreshold
            val hadErrors = errorLines.isNotEmpty()
            if (wasBelow) {
                isBelowThreshold = false
                belowThresholdSinceTick = -1L
                aboveThresholdSinceTick = -1L
            }
            if (hadErrors) errorLines.clear()
            if (wasBelow) {
                setChanged()
                notifyRedstoneChange(level)
            } else if (hadErrors) {
                setChanged()
                level.sendBlockUpdated(worldPosition, blockState, blockState, Block.UPDATE_CLIENTS)
            }
            return
        }

        val tick = level.gameTime

        pendingJob?.let { job ->
            if (job.isComplete) {
                if (!job.success) {
                    val reason = CraftingHelper.lastFailReason
                        ?: "Craft job failed during execution"
                    appendError(reason)
                }
                pendingJob = null
                setHasActiveJob(false, level)
                // Cooldown anchors to completion to keep the lock-step
                // semantics the Requester uses.
                lastJobAttemptTick = tick
                // Refresh count so a duplicate submit doesn't fire off stale
                // data once the cooldown elapses.
                updateCount(level, tick)
            }
        }

        if (tick % COUNT_INTERVAL_TICKS == 0L) updateCount(level, tick)

        if (autocraftEnabled
            && isBelowThreshold && pendingJob == null
            && tick - lastJobAttemptTick >= JOB_COOLDOWN_TICKS
        ) {
            submitCraft(level)
            lastJobAttemptTick = tick
        }
    }

    private fun updateCount(level: ServerLevel, tick: Long) {
        val snapshot = NetworkDiscovery.discoverNetwork(level, worldPosition)
        val controller = snapshot.controller
        val count = if (controller == null) 0L else {
            val itemId = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(target.item).toString()
            NetworkStorageHelper.countVariantAcrossNetwork(
                level, snapshot, itemId, target.componentsPatch, channel,
            )
        }
        if (count != displayCount) {
            displayCount = count
            // UPDATE_CLIENTS, no neighbour cascade for a count refresh.
            level.sendBlockUpdated(worldPosition, blockState, blockState, Block.UPDATE_CLIENTS)
            setChanged()
        }

        val currentlyBelow = count < threshold.toLong()
        if (currentlyBelow) {
            if (belowThresholdSinceTick < 0L) {
                belowThresholdSinceTick = tick
                aboveThresholdSinceTick = -1L
            }
            if (!isBelowThreshold && tick - belowThresholdSinceTick >= DEBOUNCE_TICKS) {
                isBelowThreshold = true
                notifyRedstoneChange(level)
            }
        } else {
            if (aboveThresholdSinceTick < 0L) {
                aboveThresholdSinceTick = tick
                belowThresholdSinceTick = -1L
            }
            if (isBelowThreshold && tick - aboveThresholdSinceTick >= DEBOUNCE_TICKS) {
                isBelowThreshold = false
                // Stock satisfied, stale planning errors no longer apply.
                if (errorLines.isNotEmpty()) errorLines.clear()
                setChanged()
                notifyRedstoneChange(level)
            }
        }
    }

    private fun submitCraft(level: ServerLevel) {
        val want = threshold.toLong() - displayCount
        if (want <= 0L) return
        val batch = want.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
        val result = BlockCraftSubmitter.submit(
            level, worldPosition, target, batch, channel,
        )
        when (result) {
            is BlockCraftSubmitter.Result.Submitted -> {
                pendingJob = result.pending
                if (errorLines.isNotEmpty()) errorLines.clear()
                setHasActiveJob(true, level)
            }
            // NoCpu, NoNetwork are silent. The cross-network workflow expects
            // the meter to drive redstone for another network's Requester.
            BlockCraftSubmitter.Result.NoCpu -> {}
            BlockCraftSubmitter.Result.NoNetwork -> {}
            is BlockCraftSubmitter.Result.Failed -> appendError(result.reason)
        }
    }

    private fun setHasActiveJob(active: Boolean, level: ServerLevel) {
        if (hasActiveJob == active) return
        hasActiveJob = active
        setChanged()
        level.sendBlockUpdated(worldPosition, blockState, blockState, Block.UPDATE_CLIENTS)
    }

    // Dedupes against the last entry, FIFO-capped at MAX_ERROR_LINES.
    private fun appendError(reason: String) {
        if (errorLines.lastOrNull() == reason) return
        errorLines.add(reason)
        while (errorLines.size > MAX_ERROR_LINES) errorLines.removeAt(0)
        val lvl = level ?: return
        setChanged()
        lvl.sendBlockUpdated(worldPosition, blockState, blockState, Block.UPDATE_CLIENTS)
    }

    private fun notifyRedstoneChange(level: ServerLevel) {
        level.sendBlockUpdated(worldPosition, blockState, blockState, Block.UPDATE_ALL)
        level.updateNeighborsAt(worldPosition, blockState.block)
    }

    // UPDATE_CLIENTS only, no neighbour cascade. Used for settings hot edits.
    private fun markDirtyAndSync() {
        setChanged()
        level?.sendBlockUpdated(worldPosition, blockState, blockState, Block.UPDATE_CLIENTS)
    }

    // --- Connectable ---

    override fun getConnections(): Set<BlockPos> = connections
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

    // Front face is reserved for the item icon, pipes can attach anywhere else.
    override fun activeFaces(): Set<net.minecraft.core.Direction> =
        net.minecraft.core.Direction.entries.toSet() -
            blockState.getValue(damien.nodeworks.block.StorageMeterBlock.FACING)

    // --- Lifecycle ---

    override fun setLevel(newLevel: net.minecraft.world.level.Level) {
        super.setLevel(newLevel)
        if (newLevel is ServerLevel) {
            NodeConnectionHelper.trackNode(newLevel, worldPosition)
            NodeConnectionHelper.queueRevalidation(newLevel, worldPosition)
        }
        NodeConnectionRenderer.trackConnectable(level, worldPosition, true)
    }

    override fun setRemoved() {
        NodeConnectionRenderer.trackConnectable(level, worldPosition, false)
        val lvl = level
        if (lvl is ServerLevel) {
            NodeConnectionHelper.removeAllConnections(lvl, this)
            NodeConnectionHelper.untrackNode(lvl, worldPosition)
        }
        super.setRemoved()
    }

    // --- Serialization ---

    override fun saveAdditional(output: ValueOutput) {
        super.saveAdditional(output)
        output.putBlockPosList("connections", connections)
        networkId?.let { output.putString("networkId", it.toString()) }
        if (!target.isEmpty) output.store("target", ItemStack.OPTIONAL_CODEC, target)
        output.putInt("threshold", threshold)
        output.putInt("channel", channel.toNbtInt())
        // Default-true on load, only the off state persists.
        if (!autocraftEnabled) output.putBoolean("autocraft", false)
        if (displayCount != 0L) output.putLong("count", displayCount)
        if (isBelowThreshold) output.putBoolean("low", true)
        if (hasActiveJob) output.putBoolean("active", true)
        if (errorLines.isNotEmpty()) {
            output.store(
                "errorLines",
                com.mojang.serialization.Codec.STRING.listOf(),
                errorLines.toList(),
            )
        }
    }

    override fun loadAdditional(input: ValueInput) {
        super.loadAdditional(input)
        connections.clear()
        connections.addAll(input.getBlockPosList("connections"))
        networkId = input.getStringOrNull("networkId")?.takeIf { it.isNotEmpty() }?.let {
            try { UUID.fromString(it) } catch (_: Exception) { null }
        }
        NetworkSettingsRegistry.notifyConnectableChanged(networkId)
        target = input.read("target", ItemStack.OPTIONAL_CODEC).orElse(ItemStack.EMPTY)
        threshold = input.getIntOr("threshold", 0)
        channel = ChannelFilter.fromNbtInt(input.getIntOr("channel", -1))
        autocraftEnabled = input.getBooleanOr("autocraft", true)
        displayCount = input.getLongOrNull("count") ?: 0L
        isBelowThreshold = input.getBooleanOr("low", false)
        hasActiveJob = input.getBooleanOr("active", false)
        errorLines.clear()
        val loaded = input.read(
            "errorLines",
            com.mojang.serialization.Codec.STRING.listOf(),
        ).orElse(emptyList())
        errorLines.addAll(loaded)
    }

    // --- Client sync ---

    override fun getUpdateTag(registries: HolderLookup.Provider): CompoundTag =
        saveWithoutMetadata(registries)

    override fun getUpdatePacket(): Packet<ClientGamePacketListener> =
        ClientboundBlockEntityDataPacket.create(this)
}
