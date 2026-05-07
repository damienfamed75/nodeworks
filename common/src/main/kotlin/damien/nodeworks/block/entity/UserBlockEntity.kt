package damien.nodeworks.block.entity

import damien.nodeworks.block.UserBlock
import damien.nodeworks.compat.getBlockPosList
import damien.nodeworks.compat.getStringOrNull
import damien.nodeworks.compat.putBlockPosList
import damien.nodeworks.network.Connectable
import damien.nodeworks.network.NodeConnectionHelper
import damien.nodeworks.platform.PlatformServices
import damien.nodeworks.registry.ModBlockEntities
import damien.nodeworks.script.CardHandle
import damien.nodeworks.script.NetworkStorageHelper
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.HolderLookup
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.DyeColor
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.context.UseOnContext
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import java.util.UUID

/**
 * BE for [UserBlock]. Pulls items from network storage, drives a FakePlayer
 * to "right-click" the target in front of the device, returns the
 * post-use stack(s) back to the network. See [project_user_device.md]
 * memory note for the full design.
 *
 * Phase 1 ships the persistence + Connectable plumbing only. The
 * FakePlayer-driven use, hold mode, deny list, and animation arrive in
 * later phases.
 */
class UserBlockEntity(
    pos: BlockPos,
    state: BlockState,
) : BlockEntity(ModBlockEntities.USER, pos, state), Connectable {

    enum class UseMode { INSTANT, HOLD }

    companion object {
        const val REDSTONE_IGNORED = 0
        const val REDSTONE_LOW = 1
        const val REDSTONE_HIGH = 2
        const val USE_COOLDOWN_TICKS = 20

        /** Max blocks the User scans along its facing direction looking for a
         *  non-air block target. Matches a real player's reach so "User above
         *  air above ignitable block" Just Works without needing a separate
         *  config knob. */
        const val REACH = 2

        /** Hard cap on a single hold session (30 seconds at 20 TPS). Prevents
         *  a forgotten redstone-on-LOW from pinning a slot forever. The
         *  vanilla brush takes 200 ticks for one full pass, so 600 is roughly
         *  three full passes -- enough for any reasonable use case. */
        const val HOLD_TIMEOUT_TICKS = 600

        /** Extend (or retract) half-duration for the BER animation. 10 ticks
         *  @ 20 TPS = 0.5 s. The world effect fires at apex (`scheduleUse`
         *  trigger + EXTEND_TICKS), so a use cycle is `0.5 s extend → effect
         *  → 0.5 s retract` for INSTANT and `0.5 s extend → enter hold` for
         *  HOLD. */
        const val EXTEND_TICKS = 10

        /** Hold-at-apex pause (3 ticks @ 20 TPS = 0.15 s) inserted between
         *  the apex fire and the retract animation. Without this the arm
         *  whips through the peak too fast to read as a hit -- it looks
         *  like a teleport between extending and retracting. Only applied
         *  to the INSTANT-mode fire path; HOLD-mode is already static at
         *  apex while the hold runs, and cancel-mid-extend retracts from
         *  current position so an apex pause would be incongruous. */
        const val APEX_HOLD_TICKS = 3
    }

    private val connections = LinkedHashSet<BlockPos>()
    override var blockDestroyed: Boolean = false
    override var networkId: UUID? = null

    var ownerUuid: UUID? = null

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

    /** Filter pattern resolved against network storage to pick the item to
     *  use. Same syntax as Storage Card filters (item id, tag prefix, etc.). */
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

    var mode: UseMode = UseMode.INSTANT
        set(value) {
            field = value
            markDirtyAndSync()
        }

    /** Toggles the [UserPreviewRenderer] wireframe over the User's reach
     *  AABB. Persisted so preview survives chunk unload. */
    var previewArea: Boolean = false
        set(value) {
            field = value
            // Keep the renderer's preview-enabled tracker in sync. Client-only,
            // server BEs aren't tracked. Skipped during ctor (level == null).
            if (level?.isClientSide == true) {
                damien.nodeworks.render.UserPreviewRenderer.TrackedUsers.setPreview(this, value)
            }
            markDirtyAndSync()
        }

    /** Position the User is targeting, one block away in the FACING direction. */
    val targetPos: BlockPos
        get() = worldPosition.relative(blockState.getValue(UserBlock.FACING))

    val facing: Direction
        get() = blockState.getValue(UserBlock.FACING)

    // Transient runtime state, wired up in later phases.

    /** Last use server tick, drives the 1Hz cooldown. Negative so the first
     *  call after startup is always past the cooldown window without
     *  overflowing the `gameTime - lastUseTick` subtraction. */
    @Transient
    var lastUseTick: Long = -USE_COOLDOWN_TICKS.toLong()

    /** Stack currently held by the FakePlayer during a hold-mode use. Empty
     *  when not holding. Persisted to NBT so a world save mid-hold doesn't
     *  vapourise the stack: on load the BE sees a non-empty heldStack but a
     *  fresh FakePlayer with empty hands, [endHold] notices the mismatch and
     *  routes the buffered stack back to network storage (or drops it on the
     *  ground if the network is unreachable). */
    var heldStack: ItemStack = ItemStack.EMPTY

    /** Tick count since startUsingItem fired. Compared against
     *  NodeworksServerConfig.userHoldTimeoutTicks for the auto-stop. */
    @Transient
    var holdingTicks: Int = 0

    /** Server-tick the latest use animation started, syncs to client. The
     *  client BER reads this to time the arm extend / retract curve. */
    var animStartTick: Long = Long.MIN_VALUE
        private set

    /** Server-tick when the most recent use ended, drives the retract phase
     *  of the BER animation. Set on apex-fire completion (INSTANT) or on
     *  [endHold] (HOLD). [Long.MIN_VALUE] = no recent end. */
    var animEndTick: Long = Long.MIN_VALUE
        private set

    /** When set, the world effect fires at this game tick (extend animation
     *  apex). Until then the device is in "extending" state: [heldStack] is
     *  reserved from the network but the FakePlayer hand is still empty. */
    var pendingFireTick: Long = Long.MIN_VALUE
        private set

    /** Mode the pending fire will execute as. Lets [serverTick] dispatch the
     *  right path at apex without re-deriving from the BE's current [mode]
     *  (which the script could have flipped between trigger and apex). */
    var pendingFireMode: UseMode? = null
        private set

    fun markUseStarted(tick: Long) {
        animStartTick = tick
        animEndTick = Long.MIN_VALUE
        markDirtyAndSync()
    }

    /** True while the User has work in flight: EXTENDING (pending apex),
     *  HOLDING (active hold), or RETRACTING (any stack, including empty
     *  for bare-hand uses). The flag is the public "is this device busy"
     *  signal -- scripts and the redstone driver both consult it before
     *  sending a fresh trigger. Without the [animEndTick] check, a bare-
     *  hand RETRACT (heldStack empty) would silently allow a new trigger
     *  mid-animation and clobber the in-flight retract. */
    val isUsing: Boolean get() =
        pendingFireTick != Long.MIN_VALUE
            || !heldStack.isEmpty
            || animEndTick != Long.MIN_VALUE

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

    // --- Lifecycle ---
    override fun setLevel(level: net.minecraft.world.level.Level) {
        super.setLevel(level)
        if (level is ServerLevel) {
            NodeConnectionHelper.trackNode(level, worldPosition)
            NodeConnectionHelper.queueRevalidation(level, worldPosition)
        }
        // Tracked-set add gated on the client side: the renderer + the
        // NodeConnectionRenderer's `knownNodes` set are client-only state.
        // On a dedicated server with no client thread these would just be
        // dead memory; in single-player they'd race the integrated server
        // BE against the client BE writing the same global HashMap.
        if (level.isClientSide) {
            damien.nodeworks.render.NodeConnectionRenderer.trackConnectable(level, worldPosition, true)
            damien.nodeworks.render.UserPreviewRenderer.TrackedUsers.add(this)
        }
    }

    override fun setRemoved() {
        if (level?.isClientSide == true) {
            damien.nodeworks.render.NodeConnectionRenderer.trackConnectable(level, worldPosition, false)
            damien.nodeworks.render.UserPreviewRenderer.TrackedUsers.remove(worldPosition)
        }
        val lvl = level
        if (lvl is ServerLevel) {
            // Block destroyed by a player -> synchronously return any
            // reserved stack to network (or drop on ground). Chunk-unload
            // also calls setRemoved but saveAdditional has already snapped
            // heldStack into NBT by that point, so draining here would
            // duplicate the stack on the next chunk load (NBT restore +
            // forced finishRetract). Skipping the drain on chunk-unload
            // lets the post-load serverTick resume the cycle cleanly.
            if (blockDestroyed) {
                if (fp(lvl).isUsingItem) fp(lvl).releaseUsingItem()
                drainFakePlayerInventoryExceptMainHand(lvl, fp(lvl))
                fp(lvl).setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY)
                if (!heldStack.isEmpty) {
                    val snapshot = damien.nodeworks.network.NetworkDiscovery.discoverNetwork(lvl, worldPosition)
                    val inserted = if (snapshot.controller != null) {
                        NetworkStorageHelper.insertItemStack(lvl, snapshot, heldStack)
                    } else 0
                    if (inserted < heldStack.count) {
                        val leftover = heldStack.copyWithCount(heldStack.count - inserted)
                        lvl.addFreshEntity(
                            net.minecraft.world.entity.item.ItemEntity(
                                lvl,
                                worldPosition.x + 0.5,
                                worldPosition.y + 0.5,
                                worldPosition.z + 0.5,
                                leftover,
                            )
                        )
                    }
                }
            }
            NodeConnectionHelper.removeAllConnections(lvl, this)
            NodeConnectionHelper.untrackNode(lvl, worldPosition)
        }
        if (blockDestroyed) {
            heldStack = ItemStack.EMPTY
            pendingFireTick = Long.MIN_VALUE
            pendingFireMode = null
            animEndTick = Long.MIN_VALUE
        }
        super.setRemoved()
    }

    private fun fp(lvl: ServerLevel): net.minecraft.server.level.ServerPlayer =
        PlatformServices.fakePlayer.get(lvl, ownerUuid)

    // --- Serialization ---
    override fun saveAdditional(output: ValueOutput) {
        super.saveAdditional(output)
        output.putString("deviceName", deviceName)
        output.putInt("channel", channel.id)
        output.putString("filterRule", filterRule)
        output.putInt("redstoneMode", redstoneMode)
        output.putString("mode", mode.name)
        output.putBoolean("previewArea", previewArea)
        if (!heldStack.isEmpty) output.store("heldStack", ItemStack.OPTIONAL_CODEC, heldStack)
        output.putLong("animStartTick", animStartTick)
        output.putLong("animEndTick", animEndTick)
        output.putLong("pendingFireTick", pendingFireTick)
        pendingFireMode?.let { output.putString("pendingFireMode", it.name) }
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
        mode = runCatching { UseMode.valueOf(input.getStringOr("mode", UseMode.INSTANT.name)) }
            .getOrDefault(UseMode.INSTANT)
        previewArea = input.getBooleanOr("previewArea", false)
        heldStack = input.read("heldStack", ItemStack.OPTIONAL_CODEC).orElse(ItemStack.EMPTY)
        animStartTick = input.getLongOr("animStartTick", Long.MIN_VALUE)
        animEndTick = input.getLongOr("animEndTick", Long.MIN_VALUE)
        pendingFireTick = input.getLongOr("pendingFireTick", Long.MIN_VALUE)
        pendingFireMode = input.getStringOrNull("pendingFireMode")
            ?.let { runCatching { UseMode.valueOf(it) }.getOrNull() }
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

    // --- Use flow ---
    //
    // State machine (a User is in exactly one phase at any tick, encoded by
    // [pendingFireTick] / [heldStack] / [animEndTick]):
    //
    //   IDLE          MIN / empty / MIN
    //   EXTENDING     set / reserved / MIN
    //   HOLDING       MIN / live / MIN
    //   RETRACTING    MIN / post-use (or empty) / recent
    //
    // Invariant: while [heldStack] is non-empty OR [pendingFireTick] is set,
    // the reserved stack belongs to THIS device. Other Users querying the
    // network see one fewer item (the network's storage cards have already
    // been mutated) and cannot claim it until this device's RETRACTING phase
    // completes and [finishRetract] inserts the item back.

    /** Per-tick driver. Walks the state machine in priority order so each
     *  tick advances at most one phase (modulo the strict "transition to
     *  RETRACTING" cases where the apex / cancel in the same tick still
     *  yields back to the redstone driver via the `isUsing` guard). */
    fun serverTick(level: ServerLevel) {
        // 1. Redstone-fall while EXTENDING -> cancel into RETRACTING.
        if (pendingFireTick != Long.MIN_VALUE
            && redstoneMode != REDSTONE_IGNORED
            && !redstoneAllows(level)
        ) {
            cancelPending(level)
        }

        // 2. Apex dispatch when EXTENDING reaches its scheduled fire tick.
        if (pendingFireTick != Long.MIN_VALUE && level.gameTime >= pendingFireTick) {
            fireScheduledUse(level)
        }

        // 3. RETRACTING end -> drain heldStack back to network. Returning
        //    here yields the rest of the tick to other BEs so a second User
        //    waiting on the freshly-returned item can grab it before this
        //    device re-schedules. Without the yield, this device would
        //    finishRetract -> immediately re-schedule -> monopolise the
        //    shared item, defeating fair turn-taking.
        if (pendingFireTick == Long.MIN_VALUE
            && animEndTick != Long.MIN_VALUE
            && level.gameTime - animEndTick >= EXTEND_TICKS
        ) {
            finishRetract(level)
            return
        }

        // 4. HOLDING: drive per-tick onUseTick on the held item.
        if (pendingFireTick == Long.MIN_VALUE
            && !heldStack.isEmpty
            && animEndTick == Long.MIN_VALUE
        ) {
            tickHold(level)
            return
        }

        // 5. IDLE -> respond to fresh redstone triggers. EXTENDING and
        //    RETRACTING-with-stack both flag isUsing, blocking fresh uses
        //    until the cycle completes. Pre-cooldown gate skips the
        //    `hasNeighborSignal` 6-direction scan when scheduleUse would
        //    return false anyway, important for idle Users on a busy
        //    network where the redstone read alone is the bulk of cost.
        if (isUsing) return
        if (redstoneMode == REDSTONE_IGNORED) return
        if (level.gameTime - lastUseTick < USE_COOLDOWN_TICKS) return
        if (!redstoneAllows(level)) return
        when (mode) {
            UseMode.INSTANT -> tryUse(level)
            UseMode.HOLD -> tryStartHold(level)
        }
    }

    /** Schedule an INSTANT use at the next animation apex. */
    fun tryUse(level: ServerLevel): Boolean = scheduleUse(level, UseMode.INSTANT)

    /** Schedule a HOLD use at the next animation apex. Whether the item
     *  actually enters using-item state is decided at apex by
     *  [fireScheduledUse]; one-shot items (flint+steel, dye) fall back to
     *  the INSTANT path automatically. */
    fun tryStartHold(level: ServerLevel): Boolean = scheduleUse(level, UseMode.HOLD)

    private fun scheduleUse(level: ServerLevel, fireMode: UseMode): Boolean {
        if (level.gameTime - lastUseTick < USE_COOLDOWN_TICKS) return false
        val snapshot = damien.nodeworks.network.NetworkDiscovery.discoverNetwork(level, worldPosition)
        if (snapshot.controller == null) return false

        // Empty filter -> bare-hand use. No item to reserve, no deny-list
        // applies (no item to deny). The apex dispatches through the empty-
        // hand entry points (Block.useWithoutItem, Entity.interact with
        // empty mainhand), which covers buttons, doors, mounting horses,
        // and any other "right-click with no item" interactions.
        if (filterRule.isEmpty()) {
            heldStack = ItemStack.EMPTY
        } else {
            val pulled = pullStackFromNetwork(level, snapshot) ?: return false
            if (UserDenyList.isDenied(pulled, level)) {
                // Silent no-op: refund the pull so a misconfigured filter
                // doesn't strand the item between snapshots.
                NetworkStorageHelper.insertItemStack(level, snapshot, pulled)
                return false
            }
            heldStack = pulled
        }
        pendingFireTick = level.gameTime + EXTEND_TICKS
        pendingFireMode = fireMode
        animEndTick = Long.MIN_VALUE
        lastUseTick = level.gameTime
        markUseStarted(level.gameTime)
        markDirtyAndSync()
        return true
    }

    /** Apex dispatch. Positions the FP, sets the reserved stack on the main
     *  hand, fires the right `Item.useOn` / `interactLivingEntity` / `Item.use`
     *  branch. INSTANT and HOLD-fallback transition straight to RETRACTING
     *  (the post-use stack stays reserved on the BE through the retract
     *  animation, then drains in [finishRetract]). HOLD with using-item
     *  flag transitions to HOLDING. */
    private fun fireScheduledUse(level: ServerLevel) {
        val mode = pendingFireMode ?: UseMode.INSTANT
        pendingFireTick = Long.MIN_VALUE
        pendingFireMode = null
        // Empty heldStack is a valid state for bare-hand uses (filterRule
        // empty -> [scheduleUse] reserved nothing). Don't short-circuit.

        val fp = PlatformServices.fakePlayer.get(level, ownerUuid)
        positionFakePlayer(fp)
        fp.setItemInHand(InteractionHand.MAIN_HAND, heldStack)

        val target = resolveTarget(level)
        when (target) {
            is UseTarget.Entity -> performUseOnEntity(fp, target.entity)
            is UseTarget.Block -> performUseOnBlock(level, fp, target)
            UseTarget.Air -> performUseOnAir(level, fp)
        }

        if (mode == UseMode.HOLD && fp.isUsingItem) {
            // HOLDING: keep the live FP-hand stack as the BE's heldStack
            // mirror so the renderer + tickHold both see the same data. Drain
            // any non-main-hand inventory immediately (drops the item already
            // produced, e.g. shears + sheep wool) so they're not pinned to
            // the FP across our hold's lifetime.
            heldStack = fp.getItemInHand(InteractionHand.MAIN_HAND).copy()
            holdingTicks = 0
            drainFakePlayerInventoryExceptMainHand(level, fp)
            markDirtyAndSync()
            return
        }

        // INSTANT or HOLD fallback. The post-use stack stays reserved on the
        // BE (heldStack) so:
        //   * the renderer continues showing the item through retract
        //   * other Users querying the network still see "no item" until
        //     [finishRetract] re-inserts at the end of the retract window
        // Non-main-hand inventory drains now (the use's drops). FP main hand
        // is cleared so a future apex on the shared FakePlayer doesn't see
        // a leftover stack.
        val postUse = fp.getItemInHand(InteractionHand.MAIN_HAND).copy()
        fp.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY)
        drainFakePlayerInventoryExceptMainHand(level, fp)
        heldStack = postUse
        // animEndTick set into the future by APEX_HOLD_TICKS so the renderer
        // pauses the arm at full extension for that long before starting
        // the retract ramp. The serverTick retract-end check uses
        // `gameTime - animEndTick >= EXTEND_TICKS`, so finishRetract triggers
        // at apex + APEX_HOLD_TICKS + EXTEND_TICKS, naturally accounting
        // for the pause.
        animEndTick = level.gameTime + APEX_HOLD_TICKS
        markDirtyAndSync()
    }

    /** Cancel a pending (EXTENDING-phase) use. Transitions to RETRACTING with
     *  the reserved stack still held; [finishRetract] drains it at the end
     *  of the retract animation. Returns true when there was a pending use
     *  to cancel. */
    fun cancelPending(level: ServerLevel): Boolean {
        if (pendingFireTick == Long.MIN_VALUE) return false
        pendingFireTick = Long.MIN_VALUE
        pendingFireMode = null
        // Smooth-retract from wherever the extend animation currently sits
        // by back-dating animEndTick. The renderer's retract formula is
        // `1 - sinceEnd/EXTEND_TICKS`, so to make it return the current
        // linear extension `e` at this tick we set animEndTick such that
        // `sinceEnd = EXTEND_TICKS * (1 - e)`. Total retract duration then
        // scales with `e`, so the arm retracts at the same speed it was
        // extending instead of teleporting to full and falling.
        val sinceStart = (level.gameTime - animStartTick).coerceAtLeast(0L).toFloat()
        val rawExtension = (sinceStart / EXTEND_TICKS).coerceIn(0f, 1f)
        animEndTick = level.gameTime - (EXTEND_TICKS * (1f - rawExtension)).toLong()
        // heldStack stays reserved through retract -- network sees no item
        // available until [finishRetract] returns it.
        markDirtyAndSync()
        return true
    }

    /** Single Lua-facing stop that picks the right transition based on
     *  current phase. EXTENDING -> cancelPending. HOLDING -> endHold.
     *  Anything else (idle, retract) -> false. */
    fun stop(level: ServerLevel): Boolean {
        if (pendingFireTick != Long.MIN_VALUE) return cancelPending(level)
        if (!heldStack.isEmpty && animEndTick == Long.MIN_VALUE) return endHold(level)
        return false
    }

    /** Drain the BE's [heldStack] back to network at the end of RETRACTING.
     *  Inserts what fits into storage, drops the rest as ItemEntities at
     *  the User's centre so the item is never lost regardless of network
     *  state. Resets the BE to IDLE. */
    private fun finishRetract(level: ServerLevel) {
        if (!heldStack.isEmpty) {
            val snapshot = damien.nodeworks.network.NetworkDiscovery.discoverNetwork(level, worldPosition)
            val inserted = if (snapshot.controller != null) {
                NetworkStorageHelper.insertItemStack(level, snapshot, heldStack)
            } else 0
            if (inserted < heldStack.count) {
                val leftover = heldStack.copyWithCount(heldStack.count - inserted)
                level.addFreshEntity(
                    net.minecraft.world.entity.item.ItemEntity(
                        level,
                        worldPosition.x + 0.5,
                        worldPosition.y + 0.5,
                        worldPosition.z + 0.5,
                        leftover,
                    )
                )
            }
        }
        heldStack = ItemStack.EMPTY
        animEndTick = Long.MIN_VALUE
        markDirtyAndSync()
    }

    /** Drain every FP inventory slot EXCEPT the main hand. Used at apex /
     *  endHold to push drops back to network without disturbing the
     *  reserved [heldStack] mirror. Spillover drops as ItemEntities at the
     *  User's centre. */
    private fun drainFakePlayerInventoryExceptMainHand(
        level: ServerLevel,
        fp: net.minecraft.server.level.ServerPlayer,
    ) {
        val snapshot = damien.nodeworks.network.NetworkDiscovery.discoverNetwork(level, worldPosition)
        val inv = fp.inventory
        val mainHandSlot = inv.getSelectedSlot()
        for (slot in 0 until inv.containerSize) {
            if (slot == mainHandSlot) continue
            val stack = inv.getItem(slot)
            if (stack.isEmpty) continue
            val inserted = if (snapshot.controller != null) {
                NetworkStorageHelper.insertItemStack(level, snapshot, stack)
            } else 0
            val leftover = stack.count - inserted
            if (leftover > 0) {
                val drop = stack.copyWithCount(leftover)
                level.addFreshEntity(
                    net.minecraft.world.entity.item.ItemEntity(
                        level,
                        worldPosition.x + 0.5,
                        worldPosition.y + 0.5,
                        worldPosition.z + 0.5,
                        drop,
                    )
                )
            }
            inv.setItem(slot, ItemStack.EMPTY)
        }
    }

    /** Per-tick driver while [isUsing] is true. Manually invokes
     *  [Item.onUseTick] on the held stack so brush / etc. progress without
     *  needing a full [ServerPlayer.tick] (which would also drive movement,
     *  hunger, vehicle physics, sleep -- all unwanted side effects on a
     *  shared FakePlayer). Honours redstone-fall, item-broke, and timeout
     *  exits via [endHold]. */
    private fun tickHold(level: ServerLevel) {
        val fp = PlatformServices.fakePlayer.get(level, ownerUuid)
        val current = fp.getItemInHand(InteractionHand.MAIN_HAND)
        if (current.isEmpty || current.item != heldStack.item) {
            // Item broke or another system hijacked the FP's hand. Clean exit.
            endHold(level)
            return
        }
        if (!fp.isUsingItem) {
            // Item internally released (brush hit a non-block, food finished,
            // etc.). End the hold so heldStack returns to the network instead
            // of looping forever calling onUseTick on a no-longer-used item.
            endHold(level)
            return
        }
        // Redstone-fall: gate closed mid-hold, release.
        if (redstoneMode != REDSTONE_IGNORED && !redstoneAllows(level)) {
            endHold(level)
            return
        }
        // Timeout: forgotten hold, force release.
        if (holdingTicks >= HOLD_TIMEOUT_TICKS) {
            endHold(level)
            return
        }

        holdingTicks++
        // We can't mutate LivingEntity.useItemRemaining (protected, no public
        // setter), so synthesize the ticksRemaining the item expects from
        // our own counter. Brush reads `getUseDuration - ticksRemaining + 1`
        // to compute elapsed ticks, which round-trips correctly here. Bow's
        // draw-power formula is more sensitive to this timing but we don't
        // claim to support bows -- the deny list excludes ranged weapons.
        val useDuration = current.getUseDuration(fp)
        val ticksRemaining = (useDuration - holdingTicks).coerceAtLeast(0)
        current.onUseTick(level, fp, ticksRemaining)
        if (ticksRemaining <= 0) {
            // Use duration fully elapsed. Release the item so vanilla items
            // that fire on completion (food, potion) trigger correctly.
            endHold(level)
            return
        }
        // Copy so we own the BE-side mirror; without this, a Lua script that
        // swaps the shared FakePlayer's hand mid-hold would mutate our snapshot
        // by reference and the BE's view goes out of sync with the live FP stack.
        heldStack = fp.getItemInHand(InteractionHand.MAIN_HAND).copy()
    }

    /** Stop an in-progress hold. Transitions HOLDING -> RETRACTING with the
     *  post-hold stack reserved on the BE; [finishRetract] drains it at the
     *  end of the retract animation. Calls `releaseUsingItem` first so
     *  vanilla items that fire on release (bow, brush at backswing) trigger
     *  correctly. Safe to call when not holding. [lastUseTick] is NOT
     *  updated here -- the cooldown anchors to the start of the use, so a
     *  200-tick brush has long since exhausted the 20-tick cooldown by the
     *  time it ends. */
    fun endHold(level: ServerLevel): Boolean {
        if (heldStack.isEmpty) return false
        val fp = PlatformServices.fakePlayer.get(level, ownerUuid)
        if (fp.isUsingItem) fp.releaseUsingItem()

        // Capture the post-hold stack. If the FP's hand was hijacked or the
        // item broke, fall back to the BE's last-known mirror so we never
        // forget what was reserved.
        val current = fp.getItemInHand(InteractionHand.MAIN_HAND)
        val resolved = when {
            !current.isEmpty -> current.copy()
            else -> heldStack.copy()
        }

        // Drain non-main-hand inventory now (drops produced during the hold).
        // Main hand is cleared so a future apex on the shared FakePlayer
        // doesn't see a leftover stack.
        drainFakePlayerInventoryExceptMainHand(level, fp)
        fp.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY)

        // Reserve the post-hold result for the retract animation. The item
        // stays on this BE -- still invisible to other Users querying the
        // network -- until [finishRetract] drains it at retract end.
        heldStack = resolved
        holdingTicks = 0
        animEndTick = level.gameTime
        markDirtyAndSync()
        return true
    }

    /** Pull the first stack matching [filterRule] / [channel] off network
     *  storage, returns null when nothing matches. The full ItemStack with
     *  components is preserved via extractItemStacksMatching so durability,
     *  damage, custom name, etc. survive into the FakePlayer's hand. */
    private fun pullStackFromNetwork(
        level: ServerLevel,
        snapshot: damien.nodeworks.network.NetworkSnapshot,
    ): ItemStack? {
        val filterPred: (String) -> Boolean = { CardHandle.matchesFilter(it, filterRule) }
        for (card in NetworkStorageHelper.getStorageCards(snapshot)) {
            if (!isChannelMatching(card)) continue
            val storage = NetworkStorageHelper.getStorage(level, card) ?: continue
            val stacks = PlatformServices.storage.extractItemStacksMatching(storage, filterPred, 1L)
            for (stack in stacks) {
                if (!stack.isEmpty) return stack
            }
        }
        return null
    }

    private fun isChannelMatching(card: damien.nodeworks.network.CardSnapshot): Boolean {
        // Bare DyeColor channel match against the card's channel. WHITE is
        // the wildcard convention used elsewhere in the codebase.
        return channel == DyeColor.WHITE || card.channel == channel
    }

    private fun positionFakePlayer(fp: net.minecraft.server.level.ServerPlayer) {
        val (yaw, pitch) = yawPitchFor(facing)
        // Place FP eye just past the User block boundary in facing direction
        // so raycasts (BrushItem.calculateHitResult, bucket use) emerge
        // OUTSIDE the User and intersect the intended target block instead
        // of hitting the User's own interior face. setPos alone leaves
        // xo/yo/zo (the previous-tick fields used by lerping + raycast
        // helpers) stuck at zero -- snapTo also calls setOldPosAndRot and
        // reapplyPosition so the FP behaves as if it teleported there cleanly.
        val eyeHeight = fp.getEyeHeight(net.minecraft.world.entity.Pose.STANDING)
        val offset = 0.51
        val eyeX = worldPosition.x + 0.5 + facing.stepX * offset
        val eyeY = worldPosition.y + 0.5 + facing.stepY * offset
        val eyeZ = worldPosition.z + 0.5 + facing.stepZ * offset
        fp.snapTo(eyeX, eyeY - eyeHeight, eyeZ, yaw, pitch)
        fp.setYHeadRot(yaw)
    }

    private fun yawPitchFor(dir: Direction): Pair<Float, Float> = when (dir) {
        Direction.SOUTH -> 0f to 0f
        Direction.WEST -> 90f to 0f
        Direction.NORTH -> 180f to 0f
        Direction.EAST -> -90f to 0f
        Direction.UP -> 0f to -90f
        Direction.DOWN -> 0f to 90f
    }

    /** Targeting priority: closest interactable entity within 2 blocks of
     *  the front face, then adjacent block if non-air, else air. */
    private fun resolveTarget(level: ServerLevel): UseTarget {
        val front = worldPosition.relative(facing)
        val reachAabb = AABB(front).expandTowards(
            facing.stepX.toDouble(),
            facing.stepY.toDouble(),
            facing.stepZ.toDouble(),
        )
        val entities = level.getEntities(null, reachAabb) {
            it.isAlive && it is LivingEntity && it !is net.minecraft.server.level.ServerPlayer
        }
        if (entities.isNotEmpty()) {
            val closest = entities.minBy { it.distanceToSqr(
                worldPosition.x + 0.5, worldPosition.y + 0.5, worldPosition.z + 0.5,
            ) }
            return UseTarget.Entity(closest)
        }
        // Scan up to [REACH] blocks straight along facing, take the first
        // non-air block. Mirrors a real player's reach standing at the User's
        // position. UP face stays the smart default for horizontal facings,
        // vertical facings already pick the correct opposite via [smartHitFace].
        for (step in 1..REACH) {
            val probe = worldPosition.relative(facing, step)
            if (!level.getBlockState(probe).isAir) return UseTarget.Block(probe, smartHitFace(facing))
        }
        // Diagonal-below fallback (horizontal facings only): User sits on top
        // of a step, the column ahead is air but there's a floor block one
        // down. Click its top face so fire / hoe / water lands the way a
        // player standing on the User would expect.
        if (facing.axis != Direction.Axis.Y) {
            val below = front.below()
            if (!level.getBlockState(below).isAir) return UseTarget.Block(below, Direction.UP)
        }
        return UseTarget.Air
    }

    /** Pick a hit face that lets "place fire / item on top of target"
     *  semantics work for horizontal User facings. The naive choice
     *  ([facing.opposite]) puts the click face on the side of the target
     *  closest to the User, which means fire-placing items try to spawn
     *  fire inside the User block (occluded -> FAIL). UP for horizontal
     *  facings places fire on top of the target instead, matching how a
     *  player normally lights a block. Vertical facings invert. */
    private fun smartHitFace(facing: Direction): Direction = when (facing) {
        Direction.UP -> Direction.DOWN
        Direction.DOWN -> Direction.UP
        else -> Direction.UP
    }

    private fun performUseOnEntity(
        fp: net.minecraft.server.level.ServerPlayer,
        entity: net.minecraft.world.entity.Entity,
    ): Boolean {
        // Try [ItemStack.interactLivingEntity] first for items that drive
        // their own entity logic (dyes, name tags, shears). On PASS, fall
        // back to the entity's own interact dispatch which routes to
        // Mob/Animal mobInteract (cow milking, sheep dyeing, etc.).
        if (entity is LivingEntity) {
            val stack = fp.getItemInHand(InteractionHand.MAIN_HAND)
            val r = stack.interactLivingEntity(fp, entity, InteractionHand.MAIN_HAND)
            if (r.consumesAction()) return true
        }
        val hitVec = Vec3(entity.x, entity.y + entity.bbHeight * 0.5, entity.z)
        return entity.interact(fp, InteractionHand.MAIN_HAND, hitVec).consumesAction()
    }

    private fun performUseOnBlock(
        level: ServerLevel,
        fp: net.minecraft.server.level.ServerPlayer,
        target: UseTarget.Block,
    ): Boolean {
        val hit = BlockHitResult(hitVecOnFace(target.pos, target.hitFace), target.hitFace, target.pos, false)
        val stack = fp.getItemInHand(InteractionHand.MAIN_HAND)
        val state = level.getBlockState(target.pos)

        // Mirror vanilla's right-click dispatch order so block- and item-
        // driven interactions both fire correctly:
        //
        //   1. BlockState.useItemOn -- the block's response to being clicked
        //      with `stack`. TNT priming on flint+steel, composter intake,
        //      anvil rename, etc. live here. For empty stacks the default
        //      impl returns PASS so step 3 (useWithoutItem) is reached.
        //   2. ItemStack.useOn -- the item's own behaviour on a block.
        //      Flint+steel placing fire, hoes tilling dirt, dyes on signs.
        //   3. BlockState.useWithoutItem -- the canonical empty-hand entry.
        //      Door open / close, button press, lever toggle, container
        //      open. Only fires when `stack` is empty (otherwise step 2
        //      already consumed the action or returned a meaningful PASS).
        val blockResult = state.useItemOn(stack, level, fp, InteractionHand.MAIN_HAND, hit)
        if (blockResult.consumesAction()) return true
        if (!stack.isEmpty) {
            // Block placement is the Placer's job. Skip step 2 for BlockItems
            // so a User holding cobblestone can still trigger interactions on
            // the targeted block (step 1 above) but won't drop the cobblestone
            // into the world. Item-driven interactions on non-BlockItems
            // (flint+steel, hoes, dyes, buckets) are unaffected.
            if (stack.item is BlockItem) return false
            val ctx = UseOnContext(fp, InteractionHand.MAIN_HAND, hit)
            return stack.useOn(ctx).consumesAction()
        }
        return state.useWithoutItem(level, fp, hit).consumesAction()
    }

    /** Centre-of-face hit position so items that read the exact Vec3 (some
     *  custom blocks do for hitbox sub-regions) land on the intended side. */
    private fun hitVecOnFace(pos: BlockPos, face: Direction): Vec3 {
        val cx = pos.x + 0.5
        val cy = pos.y + 0.5
        val cz = pos.z + 0.5
        return when (face) {
            Direction.UP -> Vec3(cx, pos.y + 1.0, cz)
            Direction.DOWN -> Vec3(cx, pos.y.toDouble(), cz)
            Direction.NORTH -> Vec3(cx, cy, pos.z.toDouble())
            Direction.SOUTH -> Vec3(cx, cy, pos.z + 1.0)
            Direction.EAST -> Vec3(pos.x + 1.0, cy, cz)
            Direction.WEST -> Vec3(pos.x.toDouble(), cy, cz)
        }
    }

    private fun performUseOnAir(
        level: ServerLevel,
        fp: net.minecraft.server.level.ServerPlayer,
    ): Boolean {
        val result = fp.getItemInHand(InteractionHand.MAIN_HAND).use(level, fp, InteractionHand.MAIN_HAND)
        return result.consumesAction()
    }

    /** Gate based on the persisted redstone mode. IGNORED always passes,
     *  LOW requires no signal, HIGH requires a signal. */
    private fun redstoneAllows(level: ServerLevel): Boolean {
        if (redstoneMode == REDSTONE_IGNORED) return true
        val powered = level.hasNeighborSignal(worldPosition)
        return when (redstoneMode) {
            REDSTONE_LOW -> !powered
            REDSTONE_HIGH -> powered
            else -> true
        }
    }

    sealed class UseTarget {
        data class Entity(val entity: net.minecraft.world.entity.Entity) : UseTarget()
        data class Block(val pos: BlockPos, val hitFace: Direction) : UseTarget()
        data object Air : UseTarget()
    }
}
