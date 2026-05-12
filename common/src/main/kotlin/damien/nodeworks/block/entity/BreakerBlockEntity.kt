package damien.nodeworks.block.entity

import damien.nodeworks.block.BreakerBlock
import damien.nodeworks.network.Connectable
import damien.nodeworks.network.NodeConnectionHelper
import damien.nodeworks.platform.PlatformServices
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

    /** UUID of the player who placed this breaker. Drives the FakePlayer identity used
     *  for [BlockEvent.BreakEvent] dispatch + spawn protection. Legacy null on pre-update
     *  worlds, falls back to the static "Nodeworks" profile via FakePlayerService. */
    var ownerUuid: UUID? = null

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

    /** Block-id / tag / pattern that gates the auto-break loop. Empty string
     *  leaves the Breaker idle (Lua-only). When non-empty, [serverTick]
     *  starts a break against the front block whenever its registry id
     *  matches the filter and the [redstoneMode] gate allows it. */
    var filterRule: String = ""
        set(value) {
            field = value
            markDirtyAndSync()
        }

    var redstoneMode: Int = REDSTONE_IGNORED
        set(value) {
            field = value.coerceIn(0, 2)
            markDirtyAndSync()
        }

    /** Toggles the [UserPreviewRenderer] wireframe over the single block at
     *  [targetPos]. Persisted so preview survives chunk unload. */
    var previewArea: Boolean = false
        set(value) {
            field = value
            if (level?.isClientSide == true) {
                damien.nodeworks.render.UserPreviewRenderer.TrackedBreakers.setPreview(this, value)
            }
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

    /** Last idle-tick we ran [tryAutoStart] on. Negative-init so the first
     *  tick post-load always polls. */
    @Transient
    private var lastIdlePollTick: Long = -IDLE_POLL_INTERVAL_TICKS.toLong()

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
        if (!isBreaking) {
            // Idle: poll the front block on a [IDLE_POLL_INTERVAL_TICKS]
            // cadence. The block at targetPos almost never changes between
            // ticks, so running the registry lookup + filter regex every
            // tick is wasted work. Lua-driven breaks ([BreakerHandle.break])
            // bypass this path, they call startBreak directly.
            if (level.gameTime - lastIdlePollTick < IDLE_POLL_INTERVAL_TICKS) return
            lastIdlePollTick = level.gameTime
            tryAutoStart(level)
            return
        }

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

    /** Filter + redstone gate check, fires [startBreak] when both pass and the
     *  front block resolves to a registry id matching the effective filter.
     *  An empty [filterRule] is treated as `*` (match anything), so a fresh
     *  Breaker switched to LOW/HIGH redstone starts mining whatever is in front
     *  without forcing the player to type a wildcard. */
    private fun tryAutoStart(level: ServerLevel) {
        // Ignored = script-only, mirrors UserBlockEntity. Auto-break stays off
        // until the player switches to LOW or HIGH; Lua [BreakerHandle.mine]
        // calls [startBreak] directly and bypasses this gate.
        if (redstoneMode == REDSTONE_IGNORED) return
        if (!redstoneAllows(level)) return
        val target = targetPos
        val state = level.getBlockState(target)
        if (state.isAir) return
        val effective = filterRule.ifEmpty { "*" }
        val blockId = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.block).toString()
        if (!damien.nodeworks.script.CardHandle.matchesFilter(blockId, effective)) return
        startBreak(level, handler = null)
    }

    private fun redstoneAllows(level: ServerLevel): Boolean {
        if (redstoneMode == REDSTONE_IGNORED) return true
        val powered = level.hasNeighborSignal(worldPosition)
        return when (redstoneMode) {
            REDSTONE_LOW -> !powered
            REDSTONE_HIGH -> powered
            else -> true
        }
    }

    /** Finalise the break: clear the overlay, drop loot, set the target to air,
     *  hand drops to the configured handler (or default-store via the network
     *  insert path).
     *
     *  Routes through [PlatformServices.fakePlayer] for permission gating: a claim
     *  mod or vanilla spawn protection can cancel the break, in which case nothing
     *  drops and the target is left untouched. The breaker still resets its progress
     *  state so the next tick can re-attempt (or a player can rebind the breaker to
     *  somewhere it has permission to mine). */
    private fun completeBreak(level: ServerLevel, target: BlockPos, state: BlockState) {
        // Clear the per-stage overlay before any branch, otherwise the crack
        // texture lingers on the live block when the break is rejected.
        level.destroyBlockProgress(breakerId, target, -1)

        // Permission gate: spawn protection + BreakBlockEvent fired with the owner's
        // FakePlayer. On rejection we drop the queued handler and reset progress
        // without producing drops or mutating the world.
        if (!PlatformServices.fakePlayer.mayBreak(level, target, state, ownerUuid)) {
            breakProgress = 0
            breakDurationTicks = 0
            targetSnapshot = null
            pendingHandler = null
            markDirtyAndSync()
            return
        }

        // Compute drops with the matching diamond tool so loot reflects the right
        // tool class: stone drops cobblestone (pickaxe), wood drops logs (axe),
        // dirt drops dirt (shovel). Falls back to diamond pickaxe if nothing
        // matches, defensive only since computeBreakDuration would have returned
        // null and the break wouldn't have started.
        val tool = pickToolPair(state)?.first ?: ItemStack(Items.DIAMOND_PICKAXE)
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

    /** Breaker joins the network through its back face only, mirroring the
     *  Placer / User: the front face is reserved for the mining action so
     *  pipes there would visually conflict, and routing-wise the player
     *  gets a clear "cable goes behind the device" rule. */
    override fun activeFaces(): Set<net.minecraft.core.Direction> =
        setOf(blockState.getValue(BreakerBlock.FACING).opposite)

    // --- Lifecycle ---
    override fun setLevel(level: net.minecraft.world.level.Level) {
        super.setLevel(level)
        if (level is ServerLevel) {
            NodeConnectionHelper.trackNode(level, worldPosition)
            NodeConnectionHelper.queueRevalidation(level, worldPosition)
        }
        // Client-only render trackers, see UserBlockEntity.setLevel for
        // the rationale.
        if (level.isClientSide) {
            damien.nodeworks.render.NodeConnectionRenderer.trackConnectable(level, worldPosition, true)
            damien.nodeworks.render.UserPreviewRenderer.TrackedBreakers.add(this)
        }
    }

    override fun setRemoved() {
        if (level?.isClientSide == true) {
            damien.nodeworks.render.NodeConnectionRenderer.trackConnectable(level, worldPosition, false)
            damien.nodeworks.render.UserPreviewRenderer.TrackedBreakers.remove(worldPosition)
        }
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
        output.putString("filterRule", filterRule)
        output.putInt("redstoneMode", redstoneMode)
        output.putBoolean("previewArea", previewArea)
        output.putInt("breakProgress", breakProgress)
        output.putInt("breakDurationTicks", breakDurationTicks)
        networkId?.let { output.putString("networkId", it.toString()) }
        ownerUuid?.let { output.putString("ownerUuid", it.toString()) }
        output.putBlockPosList("connections", connections)
    }

    override fun loadAdditional(input: ValueInput) {
        super.loadAdditional(input)
        deviceName = input.getStringOr("deviceName", "")
        channel = runCatching { DyeColor.byId(input.getIntOr("channel", 0)) }.getOrDefault(DyeColor.WHITE)
        filterRule = input.getStringOr("filterRule", "")
        redstoneMode = input.getIntOr("redstoneMode", REDSTONE_IGNORED).coerceIn(0, 2)
        previewArea = input.getBooleanOr("previewArea", false)
        breakProgress = input.getIntOr("breakProgress", 0)
        breakDurationTicks = input.getIntOr("breakDurationTicks", 0)
        // pendingHandler intentionally not loaded, LuaFunction can't serialise.
        // A break that was running mid-handler when the world saved resumes
        // routing to default (network storage) on next tick.
        pendingHandler = null
        networkId = input.getStringOrNull("networkId")?.takeIf { it.isNotEmpty() }?.let {
            try { UUID.fromString(it) } catch (_: Exception) { null }
        }
        ownerUuid = input.getStringOrNull("ownerUuid")?.takeIf { it.isNotEmpty() }?.let {
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
        const val REDSTONE_IGNORED = 0
        const val REDSTONE_LOW = 1
        const val REDSTONE_HIGH = 2

        /** Idle-poll cadence (ticks) for the auto-break scan. Avoids running
         *  the registry lookup + filter regex every tick when the front
         *  block hasn't changed; matches PlacerBlockEntity's PLACE_INTERVAL_TICKS
         *  rate so a Breaker + Placer pair gated by the same redstone fires
         *  in lockstep. */
        const val IDLE_POLL_INTERVAL_TICKS = 20

        /** Pick a diamond / wooden tool pair appropriate for [state]. Tries
         *  pickaxe, axe, then shovel and returns the first whose diamond
         *  variant is the correct tool for drops. Null when none of the three
         *  match (block needs a non-mining tool like shears, or is above
         *  diamond tier). The wooden variant of the same class drives the
         *  break-speed formula, so wood breaks at wooden-axe speed, dirt at
         *  wooden-shovel speed, etc. */
        fun pickToolPair(state: BlockState): Pair<ItemStack, ItemStack>? {
            val pick = ItemStack(Items.DIAMOND_PICKAXE)
            if (pick.isCorrectToolForDrops(state)) return pick to ItemStack(Items.WOODEN_PICKAXE)
            val axe = ItemStack(Items.DIAMOND_AXE)
            if (axe.isCorrectToolForDrops(state)) return axe to ItemStack(Items.WOODEN_AXE)
            val shovel = ItemStack(Items.DIAMOND_SHOVEL)
            if (shovel.isCorrectToolForDrops(state)) return shovel to ItemStack(Items.WOODEN_SHOVEL)
            return null
        }

        /** Compute how many ticks a break should take using the wooden-tier formula
         *  (slow but realistic) while gating tier eligibility on the matching diamond
         *  tool (so the breaker can mine anything a diamond pickaxe / axe / shovel
         *  could, including wood and dirt).
         *
         *  Returns null when the block is air, unbreakable (negative hardness), or
         *  above-tier (no matching diamond tool). Callers treat null as a silent
         *  no-op, matches the user's preference for failed breaks not to spam errors. */
        fun computeBreakDuration(level: net.minecraft.world.level.Level, pos: BlockPos, state: BlockState): Int? {
            if (state.isAir) return null
            val (_, woodenTool) = pickToolPair(state) ?: return null
            val hardness = state.getDestroySpeed(level, pos)
            if (hardness < 0f) return null
            val rawWoodSpeed = woodenTool.getDestroySpeed(state)
            // Floor at 1.0 so a 0-speed reading (modded edges where the tool's
            // class doesn't apply at all) still yields a finite tick count.
            val woodSpeed = rawWoodSpeed.coerceAtLeast(1f)
            val canHarvestWithWood = woodenTool.isCorrectToolForDrops(state)
            // Three-tier divisor scales the per-tick damage:
            //   - wooden harvests fully (e.g., cobblestone): fast path (30)
            //   - wooden's class applies but wrong tier (vanilla iron ore, obsidian): 100
            //   - wooden doesn't apply at all (modded above-tier blocks where
            //     even the tool class is wrong): extra-slow (200) so the floor
            //     above doesn't accidentally make them as fast as tier-applicable
            //     blocks of the same hardness.
            val divisor = when {
                canHarvestWithWood -> 30f
                rawWoodSpeed > 0f -> 100f
                else -> 200f
            }
            val damagePerTick = woodSpeed / (hardness * divisor)
            if (damagePerTick <= 0f) return null
            return ceil(1f / damagePerTick).toInt().coerceAtLeast(1)
        }
    }
}
