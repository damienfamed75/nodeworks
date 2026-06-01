package damien.nodeworks.block.entity

import damien.nodeworks.compat.getBlockPosList
import damien.nodeworks.compat.getStringOrNull
import damien.nodeworks.compat.putBlockPosList
import damien.nodeworks.network.ChannelFilter
import damien.nodeworks.network.Connectable
import damien.nodeworks.network.NetworkSettingsRegistry
import damien.nodeworks.network.NodeConnectionHelper
import damien.nodeworks.registry.ModBlockEntities
import damien.nodeworks.render.NodeConnectionRenderer
import damien.nodeworks.script.BlockCraftSubmitter
import damien.nodeworks.script.CraftingHelper
import net.minecraft.core.particles.DustParticleOptions
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
 * Backs the Craft Requester. Level-triggered: while the input signal stays
 * high, fires one batch, waits for completion, waits the cooldown, then fires
 * again.
 */
class CraftRequesterBlockEntity(
    pos: BlockPos,
    state: BlockState,
) : BlockEntity(ModBlockEntities.CRAFT_REQUESTER, pos, state), Connectable {

    companion object {
        private const val DEBOUNCE_TICKS = 10L
        private const val JOB_COOLDOWN_TICKS = 20L
        // FIFO cap so a broken setup doesn't grow NBT unbounded.
        private const val MAX_ERROR_LINES = 8
        private const val PARTICLE_BURST_TICKS = 20
    }

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

    // CPU rounds up to the nearest recipe-output multiple at execution.
    var batchSize: Int = 1
        set(value) {
            field = value.coerceAtLeast(1)
            markDirtyAndSync()
        }

    var outputChannel: ChannelFilter = ChannelFilter.All
        set(value) {
            field = value
            markDirtyAndSync()
        }

    @Transient
    internal var pendingJob: CraftingHelper.PendingHandlerJob? = null

    // Mirrored to the client so the BER and GUI can react.
    var hasActiveJob: Boolean = false
        private set

    @Transient
    internal var signalRiseTick: Long = -1L

    // Initialised to -COOLDOWN so the first check passes without overflow.
    @Transient
    internal var lastJobAttemptTick: Long = -JOB_COOLDOWN_TICKS

    // Rising edge (disconnected to connected) drops stale planning errors.
    @Transient
    internal var wasConnected: Boolean = false

    // Planning errors for the GUI's JobStatusIndicator. Persisted so a relog
    // doesn't drop the last failure. Cleared on next successful submission.
    val errorLines: MutableList<String> = mutableListOf()

    // Decremented per tick, set to PARTICLE_BURST_TICKS on craft failure.
    @Transient
    internal var particleTicksRemaining: Int = 0

    // True while any neighbour signal is non-zero. Synced to the client.
    var signalActive: Boolean = false
        private set

    fun serverTick(level: ServerLevel) {
        val tick = level.gameTime

        // Rising edge of network attachment: drop stale errors collected
        // while disconnected so a freshly-wired requester doesn't keep
        // reading as still-broken.
        val nowConnected = networkId != null
        if (nowConnected && !wasConnected && errorLines.isNotEmpty()) {
            errorLines.clear()
            pushClientUpdate(level)
        }
        wasConnected = nowConnected

        // Signal edge tracking.
        val signal = level.getBestNeighborSignal(worldPosition)
        if (signal > 0) {
            if (signalRiseTick < 0L) signalRiseTick = tick
            if (!signalActive) {
                signalActive = true
                pushClientUpdate(level)
            }
        } else {
            signalRiseTick = -1L
            if (signalActive) {
                signalActive = false
                if (errorLines.isNotEmpty()) errorLines.clear()
                pushClientUpdate(level)
            }
        }

        pendingJob?.let { job ->
            if (job.isComplete) {
                if (!job.success) {
                    val reason = CraftingHelper.lastFailReason
                        ?: "Craft job failed"
                    appendError(reason)
                    particleTicksRemaining = PARTICLE_BURST_TICKS
                }
                pendingJob = null
                setHasActiveJob(false, level)
                // Cooldown anchors to completion so the gap outlasts the
                // upstream signal source's reaction window (up to ~20 ticks
                // for a Storage Meter).
                lastJobAttemptTick = tick
            }
        }

        // Particle burst, halved to every-other-tick. Caps packet load when
        // N requesters fail simultaneously.
        if (particleTicksRemaining > 0) {
            if (particleTicksRemaining and 1 == 0) spawnRedstoneParticle(level)
            particleTicksRemaining--
        }

        if (signalActive
            && signalRiseTick >= 0L && tick - signalRiseTick >= DEBOUNCE_TICKS
            && pendingJob == null
            && tick - lastJobAttemptTick >= JOB_COOLDOWN_TICKS
            && !target.isEmpty
        ) {
            submitCraft(level)
            lastJobAttemptTick = tick
        }
    }

    private fun submitCraft(level: ServerLevel) {
        val result = BlockCraftSubmitter.submit(
            level, worldPosition, target, batchSize, outputChannel,
        )
        when (result) {
            is BlockCraftSubmitter.Result.Submitted -> {
                pendingJob = result.pending
                if (errorLines.isNotEmpty()) errorLines.clear()
                setHasActiveJob(true, level)
            }
            BlockCraftSubmitter.Result.NoCpu -> {
                appendError("No Crafting CPU on the network")
                particleTicksRemaining = PARTICLE_BURST_TICKS
            }
            // Silent, the Disconnected status conveys this.
            BlockCraftSubmitter.Result.NoNetwork -> {}
            is BlockCraftSubmitter.Result.Failed -> {
                appendError(result.reason)
                particleTicksRemaining = PARTICLE_BURST_TICKS
            }
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

    private fun spawnRedstoneParticle(level: ServerLevel) {
        level.sendParticles(
            DustParticleOptions(0xFF2020, 1.0f),
            worldPosition.x + 0.5,
            worldPosition.y + 0.5,
            worldPosition.z + 0.5,
            2,
            0.3, 0.3, 0.3,
            0.0,
        )
    }

    private fun pushClientUpdate(level: ServerLevel) {
        setChanged()
        level.sendBlockUpdated(worldPosition, blockState, blockState, Block.UPDATE_CLIENTS)
    }

    /** Sync settings changes (target, batch, channel) to clients without
     *  triggering a neighbour-update cascade. Cuts settings-payload work
     *  significantly on hot edits. */
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

    override fun activeFaces(): Set<net.minecraft.core.Direction> =
        net.minecraft.core.Direction.entries.toSet()

    // Bit i = forced-blocked on Direction.entries[i]. Mirrors PipeBlockEntity.
    private var forcedPipeBlockedMask: Int = 0

    override fun forcedPipeBlocked(side: net.minecraft.core.Direction): Boolean =
        (forcedPipeBlockedMask shr side.ordinal) and 1 != 0

    override fun toggleForcedPipeBlock(side: net.minecraft.core.Direction) {
        forcedPipeBlockedMask = forcedPipeBlockedMask xor (1 shl side.ordinal)
        setChanged()
        level?.sendBlockUpdated(worldPosition, blockState, blockState, Block.UPDATE_CLIENTS)
    }

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
        output.putInt("batch", batchSize)
        output.putInt("channel", outputChannel.toNbtInt())
        if (signalActive) output.putBoolean("active", true)
        if (hasActiveJob) output.putBoolean("activeJob", true)
        if (forcedPipeBlockedMask != 0) output.putInt("forcedPipeBlocked", forcedPipeBlockedMask)
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
        batchSize = input.getIntOr("batch", 1).coerceAtLeast(1)
        outputChannel = ChannelFilter.fromNbtInt(input.getIntOr("channel", -1))
        signalActive = input.getBooleanOr("active", false)
        hasActiveJob = input.getBooleanOr("activeJob", false)
        forcedPipeBlockedMask = input.getIntOr("forcedPipeBlocked", 0) and 0x3F
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
