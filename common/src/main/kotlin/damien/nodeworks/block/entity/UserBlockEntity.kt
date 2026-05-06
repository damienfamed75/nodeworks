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
     *  client BER reads this to time the arm extend/retract. */
    var animStartTick: Long = Long.MIN_VALUE
        private set

    fun markUseStarted(tick: Long) {
        animStartTick = tick
        markDirtyAndSync()
    }

    val isUsing: Boolean get() = !heldStack.isEmpty

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
            // Only drain on actual destruction (blockDestroyed is set by
            // [UserBlock.playerWillDestroy]). Chunk-unload calls setRemoved
            // too, but saveAdditional has already snapshotted heldStack to
            // NBT by that point, so draining here would duplicate the stack
            // on the next chunk load (NBT restore + tickHold drain). Letting
            // the unload path skip the drain keeps the stack in NBT and the
            // first post-load tick routes it to storage cleanly.
            if (blockDestroyed && isUsing) endHold(lvl)
            NodeConnectionHelper.removeAllConnections(lvl, this)
            NodeConnectionHelper.untrackNode(lvl, worldPosition)
        }
        if (blockDestroyed) heldStack = ItemStack.EMPTY
        super.setRemoved()
    }

    // --- Serialization ---
    override fun saveAdditional(output: ValueOutput) {
        super.saveAdditional(output)
        output.putString("deviceName", deviceName)
        output.putInt("channel", channel.id)
        output.putString("filterRule", filterRule)
        output.putInt("redstoneMode", redstoneMode)
        output.putString("mode", mode.name)
        if (!heldStack.isEmpty) output.store("heldStack", ItemStack.OPTIONAL_CODEC, heldStack)
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
        heldStack = input.read("heldStack", ItemStack.OPTIONAL_CODEC).orElse(ItemStack.EMPTY)
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

    /** Per-tick driver. INSTANT mode repeats [tryUse] at 1Hz (gated by
     *  [USE_COOLDOWN_TICKS]) while the redstone condition holds. HOLD mode
     *  starts a hold when the gate opens, advances [Item.onUseTick] each
     *  tick, and releases when the gate closes, the item breaks, or
     *  [HOLD_TIMEOUT_TICKS] elapses. IGNORED leaves the device Lua-driven
     *  (Phase 5) but still ticks an in-progress hold so a script-started
     *  hold completes correctly. */
    fun serverTick(level: ServerLevel) {
        if (isUsing) {
            tickHold(level)
            return
        }
        if (redstoneMode == REDSTONE_IGNORED) return
        if (!redstoneAllows(level)) return
        when (mode) {
            UseMode.INSTANT -> tryUse(level)
            UseMode.HOLD -> tryStartHold(level)
        }
    }

    /** Pull one stack matching [filterRule] from network storage, drive the
     *  FakePlayer through Item.useOn / interactLivingEntity / Item.use against
     *  whatever's in front, drain the result back to the network. Returns
     *  true when a use actually fired. Cooldown-gated to 1Hz. Hold mode is
     *  added in Phase 3, deny list in Phase 4. */
    fun tryUse(level: ServerLevel): Boolean {
        if (level.gameTime - lastUseTick < USE_COOLDOWN_TICKS) return false
        if (filterRule.isEmpty()) return false
        if (!redstoneAllows(level)) return false

        val snapshot = damien.nodeworks.network.NetworkDiscovery.discoverNetwork(level, worldPosition)
        if (snapshot.controller == null) return false

        val pulled = pullStackFromNetwork(level, snapshot) ?: return false
        if (UserDenyList.isDenied(pulled, level)) {
            // Silent no-op so a script (or a misconfigured filter) doesn't
            // stack-trace on every fire. Push back to network so the item
            // doesn't disappear into the cooldown gap.
            NetworkStorageHelper.insertItemStack(level, snapshot, pulled)
            return false
        }

        val fp = PlatformServices.fakePlayer.get(level, ownerUuid)
        positionFakePlayer(fp)
        fp.setItemInHand(InteractionHand.MAIN_HAND, pulled)

        val target = resolveTarget(level)
        val fired = when (target) {
            is UseTarget.Entity -> performUseOnEntity(fp, target.entity)
            is UseTarget.Block -> performUseOnBlock(level, fp, target)
            UseTarget.Air -> performUseOnAir(level, fp)
        }

        drainFakePlayerInventory(level, snapshot, fp)
        fp.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY)

        if (fired) {
            lastUseTick = level.gameTime
            markUseStarted(level.gameTime)
        }
        return fired
    }

    /** HOLD-mode entry. Pulls a stack, sets it on the FakePlayer, dispatches
     *  the initial use to enter `isUsingItem` state. If the item doesn't
     *  enter hold mode (instant-use item like flint+steel), drains the FP
     *  and bails so HOLD on the wrong item gracefully no-ops. Per-tick
     *  advancement happens in [tickHold]. */
    fun tryStartHold(level: ServerLevel): Boolean {
        if (level.gameTime - lastUseTick < USE_COOLDOWN_TICKS) return false
        if (filterRule.isEmpty()) return false

        val snapshot = damien.nodeworks.network.NetworkDiscovery.discoverNetwork(level, worldPosition)
        if (snapshot.controller == null) return false

        val pulled = pullStackFromNetwork(level, snapshot) ?: return false
        if (UserDenyList.isDenied(pulled, level)) {
            NetworkStorageHelper.insertItemStack(level, snapshot, pulled)
            return false
        }

        val fp = PlatformServices.fakePlayer.get(level, ownerUuid)
        positionFakePlayer(fp)
        fp.setItemInHand(InteractionHand.MAIN_HAND, pulled)

        val target = resolveTarget(level)
        when (target) {
            is UseTarget.Block -> performUseOnBlock(level, fp, target)
            is UseTarget.Entity -> performUseOnEntity(fp, target.entity)
            UseTarget.Air -> performUseOnAir(level, fp)
        }

        // Item entered hold state if isUsingItem flips to true (brush,
        // spyglass, bow, food, etc.). Otherwise it was a one-shot, drain and
        // exit so HOLD on flint+steel acts the same as INSTANT.
        if (!fp.isUsingItem) {
            drainFakePlayerInventory(level, snapshot, fp)
            fp.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY)
            return false
        }

        heldStack = fp.getItemInHand(InteractionHand.MAIN_HAND)
        holdingTicks = 0
        lastUseTick = level.gameTime
        markUseStarted(level.gameTime)
        setChanged()
        return true
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
        heldStack = fp.getItemInHand(InteractionHand.MAIN_HAND)
    }

    /** Stop an in-progress hold. Calls `releaseUsingItem` so vanilla items
     *  that fire on release (bow, brush at backswing) trigger correctly,
     *  drains the FP inventory back to the network, clears local state.
     *  Safe to call when not holding. */
    fun endHold(level: ServerLevel): Boolean {
        if (heldStack.isEmpty) return false
        val fp = PlatformServices.fakePlayer.get(level, ownerUuid)
        if (fp.isUsingItem) fp.releaseUsingItem()
        // Restore the buffered stack to the FP's hand if the FP doesn't
        // already have it. Covers the post-reload case where heldStack came
        // back from NBT but the freshly-fetched FakePlayer has empty hands,
        // and the FP-hijack case where another system overwrote our slot.
        val current = fp.getItemInHand(InteractionHand.MAIN_HAND)
        if (current.isEmpty || !ItemStack.isSameItemSameComponents(current, heldStack)) {
            fp.setItemInHand(InteractionHand.MAIN_HAND, heldStack.copy())
        }
        // Always drain. drainFakePlayerInventory inserts what fits into the
        // network and drops the rest as ItemEntities, so a missing controller
        // (network broken when the BE is removed) just falls through to the
        // ground drop instead of silently losing the stack.
        val snapshot = damien.nodeworks.network.NetworkDiscovery.discoverNetwork(level, worldPosition)
        drainFakePlayerInventory(level, snapshot, fp)
        fp.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY)
        heldStack = ItemStack.EMPTY
        holdingTicks = 0
        // [lastUseTick] intentionally NOT touched here. The cooldown anchors
        // to the start of the use ([tryUse] / [tryStartHold]), so a 200-tick
        // brush exhausts the 20-tick cooldown long before it ends. Resetting
        // here would force an extra 20-tick wait every cycle, which feels
        // like dropped events when the world has clearly moved on.
        setChanged()
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
        val ctx = UseOnContext(fp, InteractionHand.MAIN_HAND, hit)
        val result: InteractionResult = fp.getItemInHand(InteractionHand.MAIN_HAND).useOn(ctx)
        return result.consumesAction()
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

    /** Walk every slot in the FakePlayer's inventory after a use, push each
     *  non-empty stack back to network storage. Stacks that don't fit fall
     *  through to ground drop at the User block. */
    private fun drainFakePlayerInventory(
        level: ServerLevel,
        snapshot: damien.nodeworks.network.NetworkSnapshot,
        fp: net.minecraft.server.level.ServerPlayer,
    ) {
        val inv = fp.inventory
        for (slot in 0 until inv.containerSize) {
            val stack = inv.getItem(slot)
            if (stack.isEmpty) continue
            val inserted = NetworkStorageHelper.insertItemStack(level, snapshot, stack)
            val leftover = stack.count - inserted
            if (leftover > 0) {
                val drop = stack.copyWithCount(leftover)
                val item = net.minecraft.world.entity.item.ItemEntity(
                    level,
                    worldPosition.x + 0.5, worldPosition.y + 0.5, worldPosition.z + 0.5,
                    drop,
                )
                level.addFreshEntity(item)
            }
            inv.setItem(slot, ItemStack.EMPTY)
        }
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
