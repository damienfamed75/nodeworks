package damien.nodeworks.block.entity

import damien.nodeworks.network.ChannelFilter
import damien.nodeworks.network.Connectable
import damien.nodeworks.network.NodeConnectionHelper
import damien.nodeworks.platform.PlatformServices
import damien.nodeworks.registry.ModBlockEntities
import damien.nodeworks.screen.ExportChestMenu
import damien.nodeworks.script.CardHandle
import damien.nodeworks.script.NetworkStorageHelper
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.HolderLookup
import net.minecraft.core.NonNullList
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.Container
import net.minecraft.world.ContainerHelper
import net.minecraft.world.entity.ContainerUser
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.ChestLidController
import net.minecraft.world.level.block.entity.ContainerOpenersCounter
import net.minecraft.world.level.block.entity.LidBlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput
import damien.nodeworks.compat.getBlockPosList
import damien.nodeworks.compat.getStringOrNull
import damien.nodeworks.compat.putBlockPosList
import java.util.UUID

/**
 * Export Chest. Mirror of [ImportChestBlockEntity] but pulls FROM the network
 * INTO the buffer (filtered by a Storage-Card-style pattern expression), then
 * optionally auto-pushes from the buffer to the inventory adjacent to the
 * configured [pushFace].
 *
 * Two flows per server tick:
 *  1. Pull: walk the network's storage cards, extract items matching [filter]
 *     into our 9-slot buffer until full.
 *  2. Push: if [pushFace] is set, drain the buffer into the adjacent
 *     inventory's item-handler capability on that face.
 *
 * Either flow can be a no-op without the other. The buffer is visible in the
 * GUI so players see exactly what's queued for export.
 */
class ExportChestBlockEntity(
    pos: BlockPos,
    state: BlockState,
) : BlockEntity(ModBlockEntities.EXPORT_CHEST, pos, state), Container, Connectable, LidBlockEntity {

    companion object {
        /** Scan cursors kept per chest, see [pullCursors]. */
        private const val MAX_PULL_CURSORS = 1024
        /** How long an edited rule list waits before the chest pulls by it. */
        private const val RULE_SETTLE_TICKS = 40L

        const val SLOT_COUNT = 9
        const val DEFAULT_TICK_INTERVAL = 20
        const val MIN_TICK_INTERVAL = 1
        const val MAX_TICK_INTERVAL = 600

        const val REDSTONE_IGNORED = 0
        const val REDSTONE_LOW = 1
        const val REDSTONE_HIGH = 2

        const val EVENT_LID = 1

        fun shouldRunForRedstone(mode: Int, isPowered: Boolean): Boolean = when (mode) {
            REDSTONE_LOW -> !isPowered
            REDSTONE_HIGH -> isPowered
            else -> true
        }
    }

    fun lidAnimateTick() {
        chestLidController.tickLid()
    }

    private val items: NonNullList<ItemStack> = NonNullList.withSize(SLOT_COUNT, ItemStack.EMPTY)
    private val connections = LinkedHashSet<BlockPos>()
    override var blockDestroyed: Boolean = false
    override var networkId: UUID? = null

    /** Storage-Card-style filter rules, whitelist semantics. Each line is a
     *  single [CardHandle.matchesFilter] pattern, an item passes if it
     *  matches any rule. Empty list = no auto-pull (fresh chest doesn't
     *  drain the network). */
    var filterRules: List<String> = emptyList()
        set(value) {
            val next = value.toList()
            // An edited list waits a moment before it acts, so a rule being typed does
            // not pull the network through each of its half-finished forms. Saving is
            // untouched, only the pull holds off.
            if (next != field) rulesSettleAt = (level?.gameTime ?: 0L) + RULE_SETTLE_TICKS
            field = next
            markDirtyAndSync()
        }

    /** Game time from which the current rule list may pull. Not saved, a reload
     *  starts with settled rules. */
    private var rulesSettleAt: Long = 0L

    /** Where each faced inventory's scan stopped last run, and which card the next run
     *  starts at, so a pull the move deadline cuts short resumes instead of rescanning.
     *  Least recently used cursors go once the map is full, so a chest on a network of
     *  many thousand cards never holds a cursor for each. */
    private val pullCursors = object : LinkedHashMap<BlockPos, damien.nodeworks.platform.ScanCursor>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<BlockPos, damien.nodeworks.platform.ScanCursor>): Boolean =
            size > MAX_PULL_CURSORS
    }
    private val pullRotation = damien.nodeworks.script.NetworkTransfer.RoundRobinCursor()

    /** Channel scope for the network-pull side. ALL pulls from every storage
     *  card on the network, [ChannelFilter.Color] restricts to one channel. */
    var channel: ChannelFilter = ChannelFilter.All
        set(value) {
            field = value
            markDirtyAndSync()
        }

    /** Adjacent face to auto-push the buffer toward, or null = no auto-push
     *  (player must drain manually via hopper / pipe). */
    var pushFace: Direction? = null
        set(value) {
            field = value
            markDirtyAndSync()
        }

    var redstoneMode: Int = REDSTONE_IGNORED
        set(value) {
            field = value.coerceIn(0, 2)
            markDirtyAndSync()
        }

    var tickInterval: Int = DEFAULT_TICK_INTERVAL
        set(value) {
            field = value.coerceIn(MIN_TICK_INTERVAL, MAX_TICK_INTERVAL)
            markDirtyAndSync()
        }

    @Transient
    private var tickCounter: Int = 0

    /** Round-robin cursor across paired wireless receivers. Advances by one
     *  per slot pushed in [pushWireless], so a chest with 2 receivers + 4
     *  loaded slots distributes 2 slots to each receiver per active tick. */
    @Transient
    private var wirelessReceiverCursor: Int = 0

    @Transient
    private val chestLidController = ChestLidController()

    @Transient
    private val openersCounter: ContainerOpenersCounter = object : ContainerOpenersCounter() {
        override fun onOpen(level: Level, pos: BlockPos, state: BlockState) {
            playLidSound(level, pos, SoundEvents.COPPER_CHEST_OPEN)
        }
        override fun onClose(level: Level, pos: BlockPos, state: BlockState) {
            playLidSound(level, pos, SoundEvents.COPPER_CHEST_CLOSE)
        }
        override fun openerCountChanged(
            level: Level, pos: BlockPos, state: BlockState, prevCount: Int, newCount: Int,
        ) {
            level.blockEvent(pos, state.block, EVENT_LID, newCount)
        }
        override fun isOwnContainer(player: Player): Boolean {
            val menu = player.containerMenu
            return menu is ExportChestMenu && menu.devicePos == worldPosition
        }
    }

    private fun playLidSound(level: Level, pos: BlockPos, sound: SoundEvent) {
        val pitch = 0.9f + level.random.nextFloat() * 0.1f
        level.playSound(
            null,
            pos.x + 0.5, pos.y + 0.5, pos.z + 0.5,
            sound, SoundSource.BLOCKS,
            0.5f, pitch,
        )
    }

    private fun markDirtyAndSync() {
        setChanged()
        level?.sendBlockUpdated(worldPosition, blockState, blockState, Block.UPDATE_ALL)
    }

    /** Server tick. Caller (block ticker) checks the level is server-side. */
    fun serverTick(level: ServerLevel) {
        recheckOpen()

        tickCounter++
        if (tickCounter < tickInterval) return
        tickCounter = 0

        if (redstoneMode != REDSTONE_IGNORED) {
            val powered = level.hasNeighborSignal(worldPosition)
            if (!shouldRunForRedstone(redstoneMode, powered)) return
        }

        // Empty rule list = no auto-pull (fresh chest doesn't drain the
        // network). Push side is independent so a buffer with manually-placed
        // items still drains even with no rules.
        if (filterRules.isNotEmpty() && level.gameTime >= rulesSettleAt) pullFromNetwork(level)
        if (pushFace != null) pushToAdjacent(level, pushFace!!)
        // Wireless dispatch: if a Broadcast Antenna is sitting next to this
        // chest, fan items out to every Receiver Antenna paired with that
        // antenna's frequency. Independent of the local pushFace so a chest
        // can do both at once.
        pushWireless(level)
    }

    /** Pull filter-matched items from the network's storage cards into our buffer
     *  until either the buffer is full or the network is empty of matches. Filter
     *  syntax matches the Storage Card filter so the script-side autocomplete and
     *  the Export Chest's UI both speak the same dialect. */
    private fun pullFromNetwork(level: ServerLevel) {
        // Match Import Chest: use this BE's own networkId rather than
        // `snapshot.controller != null` so micro-networks (Processing
        // Handler-anchored) participate too.
        if (networkId == null) return
        val snapshot = damien.nodeworks.network.NetworkDiscovery.discoverNetwork(level, worldPosition)

        var totalSpace = 0L
        for (i in 0 until SLOT_COUNT) {
            val slot = items[i]
            totalSpace += if (slot.isEmpty) 64L else (slot.maxStackSize - slot.count).toLong()
        }
        if (totalSpace <= 0L) return

        val cache = damien.nodeworks.script.NetworkInventoryCache.existing(level, worldPosition)
        // The same per-network move time the Transfer Cards share, so a chest whose rules
        // match nothing cannot walk a million slots every interval unchecked. A run the
        // budget stops resumes from its cursors next interval.
        val tick = PlatformServices.modState.tickCount
        val budget = damien.nodeworks.script.NetworkRateLimits.forTransferCards(networkId)
        val placed = budget.timedMove(tick, atomic = false) {
            damien.nodeworks.script.NetworkTransfer.pull(
                level, snapshot, cache, filterRules, channel, totalSpace,
                spill = { stack ->
                    net.minecraft.world.Containers.dropItemStack(
                        level, worldPosition.x + 0.5, worldPosition.y + 0.5, worldPosition.z + 0.5, stack,
                    )
                },
                sink = ::placeIntoBuffer,
                cursorFor = { pos -> pullCursors.getOrPut(pos) { damien.nodeworks.platform.ScanCursor() } },
                rotation = pullRotation,
            )
        }
        if (placed > 0L) setChanged()
    }

    /** Try to merge [stack] into the buffer: stack-merge first, then fill empty
     *  slots. Returns the count actually placed (always <= stack.count). */
    private fun placeIntoBuffer(stack: ItemStack): Int {
        var remaining = stack.count
        for (i in 0 until SLOT_COUNT) {
            if (remaining <= 0) break
            val slot = items[i]
            if (!slot.isEmpty && ItemStack.isSameItemSameComponents(slot, stack)) {
                val space = slot.maxStackSize - slot.count
                val toAdd = minOf(remaining, space)
                if (toAdd > 0) {
                    slot.grow(toAdd)
                    remaining -= toAdd
                }
            }
        }
        for (i in 0 until SLOT_COUNT) {
            if (remaining <= 0) break
            if (items[i].isEmpty) {
                val cap = minOf(remaining, stack.maxStackSize)
                items[i] = stack.copyWithCount(cap)
                remaining -= cap
            }
        }
        return stack.count - remaining
    }

    /** Wireless dispatch via an adjacent Broadcast Antenna. Fans the chest's
     *  slots out round-robin to every paired Receiver Antenna that has an
     *  Import Chest below it. Two receivers split a 9-slot chest 5/4 per tick. */
    private fun pushWireless(level: ServerLevel) {
        val broadcast = adjacentBroadcastAntenna(level) ?: return
        val freq = broadcast.frequencyId
        val receivers = damien.nodeworks.network.WirelessBroadcastRegistry.getReceivers(freq)
        if (receivers.isEmpty()) return

        // Snapshot so the cursor sees a stable index space across the loop.
        val targets = receivers.mapNotNull { resolveWirelessTarget(level, broadcast, it) }
        if (targets.isEmpty()) return

        var changed = false
        for (i in 0 until SLOT_COUNT) {
            val slot = items[i]
            if (slot.isEmpty) continue
            val target = targets[wirelessReceiverCursor % targets.size]
            wirelessReceiverCursor = (wirelessReceiverCursor + 1) % targets.size
            val before = slot.count
            val inserted = PlatformServices.storage.insertItemStack(target, slot)
            if (inserted > 0) {
                slot.shrink(inserted)
                changed = true
            }
            // Some platforms mutate the stack instead of returning a count.
            if (slot.count != before - inserted && slot.count < before) changed = true
        }
        if (changed) setChanged()
    }

    /** Find an adjacent Broadcast Antenna whose detected source resolves
     *  back to this chest. Symmetric to [BroadcastAntennaBlockEntity.detectSource]. */
    /** True when a Broadcast Antenna beside this chest has picked it as its source. Safe
     *  on the client, where the renderer uses it: a chest with no network of its own but
     *  an antenna still has somewhere to send, so it lights up. */
    fun hasBroadcastAntenna(): Boolean {
        val lvl = level ?: return false
        for (dir in Direction.entries) {
            val neighborPos = worldPosition.relative(dir)
            if (!lvl.isLoaded(neighborPos)) continue
            val be = lvl.getBlockEntity(neighborPos) as? BroadcastAntennaBlockEntity ?: continue
            val source = be.detectSource() ?: continue
            if (source.first == damien.nodeworks.item.BroadcastSourceKind.EXPORT_CHEST && source.second == worldPosition) return true
        }
        return false
    }

    private fun adjacentBroadcastAntenna(level: ServerLevel): BroadcastAntennaBlockEntity? {
        for (dir in Direction.entries) {
            val neighborPos = worldPosition.relative(dir)
            if (!level.isLoaded(neighborPos)) continue
            val be = level.getBlockEntity(neighborPos) as? BroadcastAntennaBlockEntity ?: continue
            val source = be.detectSource() ?: continue
            if (source.first == damien.nodeworks.item.BroadcastSourceKind.EXPORT_CHEST
                && source.second == worldPosition) return be
        }
        return null
    }

    /** Resolve a registry entry to a writable item-storage handle, gated
     *  on range, dimension, chunk-load, and "Import Chest below the receiver". */
    private fun resolveWirelessTarget(
        level: ServerLevel,
        broadcast: BroadcastAntennaBlockEntity,
        receiver: damien.nodeworks.network.WirelessBroadcastRegistry.Receiver,
    ): damien.nodeworks.platform.ItemStorageHandle? {
        val targetLevel = level.server.getLevel(receiver.dimension) ?: return null
        if (!targetLevel.isLoaded(receiver.pos)) return null
        val recvBe = targetLevel.getBlockEntity(receiver.pos) as? ReceiverAntennaBlockEntity ?: return null
        // Receiver lost its crystal mid-broadcast?
        if (!recvBe.isPaired) return null

        // Cross-dimension gating mirrors ReceiverAntennaBlockEntity.getBroadcastAntenna.
        val sameDim = broadcast.level?.dimension() == receiver.dimension
        if (!sameDim && !broadcast.allowsCrossDimension) return null
        if (sameDim) {
            val dx = broadcast.blockPos.x - receiver.pos.x.toDouble()
            val dy = broadcast.blockPos.y - receiver.pos.y.toDouble()
            val dz = broadcast.blockPos.z - receiver.pos.z.toDouble()
            val range = broadcast.effectiveRange
            if (dx * dx + dy * dy + dz * dz > range * range) return null
        }

        // Receiver Antenna sits directly above its destination Import Chest.
        val destPos = receiver.pos.below()
        if (!targetLevel.isLoaded(destPos)) return null
        val destBe = targetLevel.getBlockEntity(destPos)
        if (destBe !is ImportChestBlockEntity) return null
        return PlatformServices.storage.getItemStorage(targetLevel, destPos, Direction.UP)
    }

    /** Drain buffer into the inventory adjacent to [face]. Uses the platform's
     *  item-storage handle (Fabric Transfer / NeoForge ItemHandler) so vanilla
     *  inventories, modded machines, and pipes all work. */
    private fun pushToAdjacent(level: ServerLevel, face: Direction) {
        val adjPos = worldPosition.relative(face)
        val dest = PlatformServices.storage.getItemStorage(level, adjPos, face.opposite) ?: return
        var changed = false
        for (i in 0 until SLOT_COUNT) {
            val slot = items[i]
            if (slot.isEmpty) continue
            val before = slot.count
            val inserted = PlatformServices.storage.insertItemStack(dest, slot)
            if (inserted > 0) {
                slot.shrink(inserted)
                changed = true
            }
            // Defensive, insertItemStack mutates the stack passed in OR returns
            // count, depending on platform. The shrink above handles the latter,
            // catch the former with a same-count check.
            if (slot.count != before - inserted && slot.count < before) changed = true
        }
        if (changed) setChanged()
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

    /** Import/export chests are network leaves: two chests placed face-to-face
     *  shouldn't auto-bridge networks, the player has to wire them with a
     *  cable explicitly. */
    override fun canConnectAdjacentTo(other: Connectable): Boolean =
        other !is ImportChestBlockEntity && other !is ExportChestBlockEntity

    // --- Lifecycle ---
    override fun setLevel(newLevel: net.minecraft.world.level.Level) {
        super.setLevel(newLevel)
        if (newLevel is ServerLevel) {
            NodeConnectionHelper.trackNode(newLevel, worldPosition)
            NodeConnectionHelper.queueRevalidation(newLevel, worldPosition)
        }
        damien.nodeworks.render.NodeConnectionRenderer.trackConnectable(level, worldPosition, true)
    }

    override fun setRemoved() {
        damien.nodeworks.render.NodeConnectionRenderer.trackConnectable(level, worldPosition, false)
        val lvl = level
        if (lvl is ServerLevel) {
            NodeConnectionHelper.removeAllConnections(lvl, this)
            NodeConnectionHelper.untrackNode(lvl, worldPosition)
        }
        super.setRemoved()
    }

    // --- Container ---
    override fun getContainerSize(): Int = SLOT_COUNT
    override fun isEmpty(): Boolean = items.all { it.isEmpty }
    override fun getItem(slot: Int): ItemStack =
        if (slot in items.indices) items[slot] else ItemStack.EMPTY

    override fun removeItem(slot: Int, amount: Int): ItemStack {
        val result = ContainerHelper.removeItem(items, slot, amount)
        if (!result.isEmpty) setChanged()
        return result
    }

    override fun removeItemNoUpdate(slot: Int): ItemStack = ContainerHelper.takeItem(items, slot)

    override fun setItem(slot: Int, stack: ItemStack) {
        if (slot in items.indices) {
            items[slot] = stack
            if (stack.count > maxStackSize) stack.count = maxStackSize
            setChanged()
        }
    }

    override fun stillValid(player: Player): Boolean =
        player.distanceToSqr(worldPosition.x + 0.5, worldPosition.y + 0.5, worldPosition.z + 0.5) <= 64.0

    override fun clearContent() {
        items.clear()
        setChanged()
    }

    override fun startOpen(user: ContainerUser) {
        if (isRemoved) return
        val living = user.livingEntity ?: return
        if (living is Player && living.isSpectator) return
        val lvl = level ?: return
        openersCounter.incrementOpeners(
            living, lvl, worldPosition, blockState,
            user.containerInteractionRange,
        )
    }

    override fun stopOpen(user: ContainerUser) {
        if (isRemoved) return
        val living = user.livingEntity ?: return
        if (living is Player && living.isSpectator) return
        val lvl = level ?: return
        openersCounter.decrementOpeners(living, lvl, worldPosition, blockState)
    }

    fun recheckOpen() {
        if (isRemoved) return
        val lvl = level ?: return
        openersCounter.recheckOpeners(lvl, worldPosition, blockState)
    }

    override fun getOpenNess(partialTick: Float): Float =
        chestLidController.getOpenness(partialTick)

    override fun triggerEvent(id: Int, type: Int): Boolean {
        return if (id == EVENT_LID) {
            chestLidController.shouldBeOpen(type > 0)
            true
        } else {
            super.triggerEvent(id, type)
        }
    }

    // --- Serialization ---
    /** The GUI settings, in the same keys [loadAdditional] reads, so they can ride on
     *  the dropped item and come back when it is placed. Null when every setting is at
     *  its default, a never-configured chest then stacks with fresh ones. Contents,
     *  connections and network membership stay behind on purpose. */
    fun settingsTag(): CompoundTag? {
        val configured = filterRules.isNotEmpty() || channel != ChannelFilter.All || pushFace != null ||
            redstoneMode != REDSTONE_IGNORED || tickInterval != DEFAULT_TICK_INTERVAL
        if (!configured) return null
        val tag = CompoundTag()
        tag.putString("filterRules", filterRules.joinToString("\n"))
        tag.putInt("channel", channel.toNbtInt())
        pushFace?.let { tag.putString("pushFace", it.name) }
        tag.putInt("redstoneMode", redstoneMode)
        tag.putInt("tickInterval", tickInterval)
        return tag
    }

    override fun saveAdditional(output: ValueOutput) {
        super.saveAdditional(output)
        ContainerHelper.saveAllItems(output, items)
        // Filter rules persist as a newline-joined string, simpler than wiring
        // a list codec and bounded by [SetExportChestFilterRulesPayload.MAX_RULES].
        output.putString("filterRules", filterRules.joinToString("\n"))
        output.putInt("channel", channel.toNbtInt())
        pushFace?.let { output.putString("pushFace", it.name) }
        output.putInt("redstoneMode", redstoneMode)
        output.putInt("tickInterval", tickInterval)
        networkId?.let { output.putString("networkId", it.toString()) }
        output.putBlockPosList("connections", connections)
    }

    override fun loadAdditional(input: ValueInput) {
        super.loadAdditional(input)
        items.clear()
        ContainerHelper.loadAllItems(input, items)
        filterRules = input.getStringOrNull("filterRules")
            ?.split("\n")
            ?.filter { it.isNotEmpty() }
            ?: emptyList()
        channel = ChannelFilter.fromNbtInt(input.getIntOr("channel", -1))
        pushFace = input.getStringOrNull("pushFace")?.let {
            runCatching { Direction.valueOf(it) }.getOrNull()?.takeIf { dir -> dir.axis.isHorizontal || dir.axis.isVertical }
        }
        redstoneMode = input.getIntOr("redstoneMode", REDSTONE_IGNORED).coerceIn(0, 2)
        tickInterval = input.getIntOr("tickInterval", DEFAULT_TICK_INTERVAL)
            .coerceIn(MIN_TICK_INTERVAL, MAX_TICK_INTERVAL)
        networkId = input.getStringOrNull("networkId")?.takeIf { it.isNotEmpty() }?.let {
            try { UUID.fromString(it) } catch (_: Exception) { null }
        }
        if (level != null) damien.nodeworks.network.NetworkSettingsRegistry.notifyBlockSynced(worldPosition)
        connections.clear()
        connections.addAll(input.getBlockPosList("connections"))
    }

    override fun getUpdateTag(registries: HolderLookup.Provider): CompoundTag = saveWithoutMetadata(registries)
    override fun getUpdatePacket(): Packet<ClientGamePacketListener> =
        ClientboundBlockEntityDataPacket.create(this)
}
