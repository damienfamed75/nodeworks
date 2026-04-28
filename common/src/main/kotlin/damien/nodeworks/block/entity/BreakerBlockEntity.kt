package damien.nodeworks.block.entity

import damien.nodeworks.block.BreakerBlock
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
import net.minecraft.world.item.DyeColor
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput
import org.luaj.vm2.LuaFunction
import damien.nodeworks.compat.getBlockPosList
import damien.nodeworks.compat.getStringOrNull
import damien.nodeworks.compat.putBlockPosList
import java.util.UUID
import kotlin.math.ceil

class BreakerBlockEntity(
    pos: BlockPos,
    state: BlockState,
) : BlockEntity(ModBlockEntities.BREAKER, pos, state), Connectable {

    private val connections = LinkedHashSet<BlockPos>()
    override var blockDestroyed: Boolean = false
    override var networkId: UUID? = null

    /** Custom alias for `network:get(name)`. Empty string falls back to the
     *  auto-alias (`breaker_N`) assigned by [NetworkDiscovery.assignAutoAliases]. */
    var deviceName: String = ""
        set(value) {
            field = value.take(32)
            markDirtyAndSync()
        }

    var channel: DyeColor = DyeColor.WHITE
        set(value) {
            field = value
            markDirtyAndSync()
        }

    /** Tick counter for the in-progress break (0 = idle). When this reaches
     *  [breakDurationTicks] the block actually breaks, while `> 0`, the breaker
     *  pushes per-stage destroy-progress to the level so the vanilla crack overlay
     *  shows on the target. */
    var breakProgress: Int = 0
        private set

    /** Total tick count this break is supposed to take, snapshotted at break-start
     *  via [computeBreakDuration]. Stored so a single tick advance produces the
     *  correct stage division regardless of mid-break block changes. */
    var breakDurationTicks: Int = 0
        private set

    /** Optional Lua function set by `breaker:mine():connect(fn)`. When non-null
     *  the drops route to this handler instead of being inserted into network
     *  storage. Cleared after the break completes (or [cancel] runs). Note: stored
     *  in memory only, the BlockEntity doesn't try to serialise the LuaFunction
     *  across world reloads, so a server restart mid-break drops the handler and
     *  falls back to the default network-store routing on the next break.*/
    @Transient
    var pendingHandler: LuaFunction? = null

    val isBreaking: Boolean get() = breakProgress > 0

    /** 0..1 progress fraction. Returns 0 when idle. */
    val progressFraction: Float get() =
        if (breakDurationTicks <= 0) 0f else breakProgress.toFloat() / breakDurationTicks.toFloat()

    /** Stable per-device id used as the `breakerId` argument to
     *  `level.destroyBlockProgress`. Hashing the world position keeps it stable
     *  across server restarts and distinct across breakers. */
    private val breakerId: Int get() = worldPosition.hashCode()

    /** Position the breaker is targeting, one block away in the FACING direction
     *  declared on the BlockState. Lazily resolved each tick rather than cached
     *  because the FACING property is immutable per state but the state itself
     *  can rotate if a future feature ever rotates blocks. */
    val targetPos: BlockPos
        get() = worldPosition.relative(blockState.getValue(BreakerBlock.FACING))

    /** Snapshot of the BlockState we're breaking, captured at break-start. Used
     *  to detect mid-break drift (player swap, world physics) and abort if so. */
    private var targetSnapshot: BlockState? = null

    /** Begin a break of the block at [targetPos]. No-op when already breaking, the
     *  target is air / unbreakable, or above-tier (silent, the API contract says
     *  diamond pickaxe equivalence is the cap). Returns true when a break actually
     *  started so [BreakerHandle.break] can return a live builder vs a no-op. */
    fun startBreak(level: ServerLevel, handler: LuaFunction? = null): Boolean {
        if (isBreaking) return false
        val target = targetPos
        val state = level.getBlockState(target)
        val duration = computeBreakDuration(level, target, state) ?: return false
        targetSnapshot = state
        breakDurationTicks = duration
        breakProgress = 1
        pendingHandler = handler
        markDirtyAndSync()
        return true
    }

    /** Cancel any in-flight break. Safe to call when idle. */
    fun cancel() {
        if (!isBreaking) return
        val lvl = level
        if (lvl is ServerLevel) {
            // -1 wipes the destroy-progress overlay so the target stops showing cracks.
            lvl.destroyBlockProgress(breakerId, targetPos, -1)
        }
        breakProgress = 0
        breakDurationTicks = 0
        targetSnapshot = null
        pendingHandler = null
        markDirtyAndSync()
    }

    /** Per-tick advance. Called from [BreakerBlock.getTicker]'s server-side ticker. */
    fun serverTick(level: ServerLevel) {
        if (!isBreaking) return

        // Detect target drift, if the block has changed underneath us (player
        // swapped it, piston pushed something else into place), abort cleanly.
        val target = targetPos
        val current = level.getBlockState(target)
        if (current != targetSnapshot) {
            cancel()
            return
        }

        breakProgress++
        // Push the visible crack stage. `destroyBlockProgress` accepts 0..9 for
        // the 10 break-stage textures vanilla ships, we map our tick counter
        // proportionally and cap at 9.
        val stage = ((breakProgress.toLong() * 10L) / breakDurationTicks.toLong()).toInt().coerceIn(0, 9)
        level.destroyBlockProgress(breakerId, target, stage)

        if (breakProgress >= breakDurationTicks) {
            completeBreak(level, target, current)
        }
    }

    /** Finalise the break: clear the overlay, drop loot, set the target to air,
     *  hand drops to the configured handler (or default-store via the network
     *  insert path). */
    private fun completeBreak(level: ServerLevel, target: BlockPos, state: BlockState) {
        // Clear the per-stage overlay before changing the block, otherwise the
        // crack texture lingers on the new (air) block for a frame or two.
        level.destroyBlockProgress(breakerId, target, -1)

        // Compute drops with a diamond-pickaxe loot context so players get the
        // tier-appropriate items (cobblestone from stone, ore drops with proper
        // tier checks, etc.).
        val tool = ItemStack(Items.DIAMOND_PICKAXE)
        val targetEntity = level.getBlockEntity(target)
        val drops = Block.getDrops(state, level, target, targetEntity, null, tool)

        // Play vanilla break particles + sound so the break reads visually.
        level.levelEvent(net.minecraft.world.level.block.LevelEvent.PARTICLES_DESTROY_BLOCK, target, Block.getId(state))

        // Remove the block. UPDATE_ALL fires neighbor updates so connected redstone
        // / pistons / observers see the change.
        level.setBlock(target, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL)

        // Route drops. The handler-or-default split lives here so the
        // [pendingHandler] state stays internal, neither the BreakerHandle nor
        // the Lua side has to know how to "store to network."
        val handler = pendingHandler
        if (handler != null) {
            damien.nodeworks.script.BreakerHandle.dispatchDropsToHandler(level, this, drops, handler)
        } else {
            damien.nodeworks.script.BreakerHandle.routeDropsToNetwork(level, this, drops)
        }

        // Reset state for the next break.
        breakProgress = 0
        breakDurationTicks = 0
        targetSnapshot = null
        pendingHandler = null
        markDirtyAndSync()
    }

    private fun markDirtyAndSync() {
        setChanged()
        level?.sendBlockUpdated(worldPosition, blockState, blockState, Block.UPDATE_ALL)
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
            NodeConnectionHelper.queueRevalidation(level, worldPosition)
        }
        damien.nodeworks.render.NodeConnectionRenderer.trackConnectable(worldPosition, true)
    }

    override fun setRemoved() {
        damien.nodeworks.render.NodeConnectionRenderer.trackConnectable(worldPosition, false)
        val lvl = level
        if (lvl is ServerLevel) {
            // Wipe any leftover crack overlay if we're mid-break when the breaker is broken.
            if (isBreaking) lvl.destroyBlockProgress(breakerId, targetPos, -1)
            NodeConnectionHelper.removeAllConnections(lvl, this)
            NodeConnectionHelper.untrackNode(lvl, worldPosition)
        }
        super.setRemoved()
    }

    // --- Serialization ---
    override fun saveAdditional(output: ValueOutput) {
        super.saveAdditional(output)
        output.putString("deviceName", deviceName)
        output.putInt("channel", channel.id)
        output.putInt("breakProgress", breakProgress)
        output.putInt("breakDurationTicks", breakDurationTicks)
        networkId?.let { output.putString("networkId", it.toString()) }
        output.putBlockPosList("connections", connections)
    }

    override fun loadAdditional(input: ValueInput) {
        super.loadAdditional(input)
        deviceName = input.getStringOr("deviceName", "")
        channel = runCatching { DyeColor.byId(input.getIntOr("channel", 0)) }.getOrDefault(DyeColor.WHITE)
        breakProgress = input.getIntOr("breakProgress", 0)
        breakDurationTicks = input.getIntOr("breakDurationTicks", 0)
        // pendingHandler intentionally not loaded, LuaFunction can't serialise.
        // A break that was running mid-handler when the world saved resumes
        // routing to default (network storage) on next tick.
        pendingHandler = null
        networkId = input.getStringOrNull("networkId")?.takeIf { it.isNotEmpty() }?.let {
            try { UUID.fromString(it) } catch (_: Exception) { null }
        }
        damien.nodeworks.network.NetworkSettingsRegistry.notifyConnectableChanged(networkId)
        connections.clear()
        connections.addAll(input.getBlockPosList("connections"))
    }

    override fun getUpdateTag(registries: HolderLookup.Provider): CompoundTag = saveWithoutMetadata(registries)
    override fun getUpdatePacket(): Packet<ClientGamePacketListener> =
        ClientboundBlockEntityDataPacket.create(this)

    companion object {
        /** Compute how many ticks a break should take using the wooden-pickaxe formula
         *  (slow but realistic) while gating tier eligibility on diamond pickaxe (so
         *  the breaker can mine anything a diamond pick could).
         *
         *  Returns null when the block is air, unbreakable (negative hardness), or
         *  above-tier (no diamond drops). Callers treat null as a silent no-op,
         *  matches the user's preference for failed breaks not to spam errors. */
        fun computeBreakDuration(level: net.minecraft.world.level.Level, pos: BlockPos, state: BlockState): Int? {
            if (state.isAir) return null
            val diamondPick = ItemStack(Items.DIAMOND_PICKAXE)
            if (!diamondPick.isCorrectToolForDrops(state)) return null
            val hardness = state.getDestroySpeed(level, pos)
            if (hardness < 0f) return null
            val woodenPick = ItemStack(Items.WOODEN_PICKAXE)
            // Wooden pick speed is 0 for blocks it doesn't apply to (e.g. dirt). Floor
            // at 1.0 so we still produce a finite tick count via the slow-path divisor.
            val woodSpeed = woodenPick.getDestroySpeed(state).coerceAtLeast(1f)
            val canHarvestWithWood = woodenPick.isCorrectToolForDrops(state)
            val divisor = if (canHarvestWithWood) 30f else 100f
            val damagePerTick = woodSpeed / (hardness * divisor)
            if (damagePerTick <= 0f) return null
            return ceil(1f / damagePerTick).toInt().coerceAtLeast(1)
        }
    }
}
