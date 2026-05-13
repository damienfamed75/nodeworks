package damien.nodeworks.block.entity

import damien.nodeworks.network.ChannelFilter
import damien.nodeworks.network.Connectable
import damien.nodeworks.network.NodeConnectionHelper
import damien.nodeworks.registry.ModBlockEntities
import damien.nodeworks.screen.ImportChestMenu
import damien.nodeworks.script.NetworkStorageHelper
import net.minecraft.core.BlockPos
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
 * Import Chest. A small 9-slot buffer chest that's also a Connectable network
 * device. Items dropped into it (manually, via hopper, or via pipes from other
 * mods) are pushed into network storage on a configurable tick cadence. When
 * the network can't accept an item (filters, full, etc.) it stays visible in
 * the chest so the player can see exactly what's stuck.
 *
 * Settings (edited in the GUI):
 *  * [channel] scopes inserts to one channel of storage cards (or all).
 *  * [redstoneMode] 0 ignored / 1 active on low / 2 active on high. Same
 *    semantics as [NetworkControllerBlockEntity.redstoneMode].
 *  * [roundRobin] when on, advances [roundRobinIndex] each insert so items
 *    spread across destination storage cards instead of filling first-fit.
 *  * [tickInterval] ticks between insert attempts. Default 20 (1Hz). Bounded
 *    [MIN_TICK_INTERVAL]..[MAX_TICK_INTERVAL].
 */
class ImportChestBlockEntity(
    pos: BlockPos,
    state: BlockState,
) : BlockEntity(ModBlockEntities.IMPORT_CHEST, pos, state), Container, Connectable, LidBlockEntity {

    companion object {
        const val SLOT_COUNT = 9
        const val DEFAULT_TICK_INTERVAL = 20
        const val MIN_TICK_INTERVAL = 1
        const val MAX_TICK_INTERVAL = 600

        /** [redstoneMode] values. 0 = ignored, 1 = active on low, 2 = active on high. */
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

    var channel: ChannelFilter = ChannelFilter.All
        set(value) {
            field = value
            markDirtyAndSync()
        }

    var redstoneMode: Int = REDSTONE_IGNORED
        set(value) {
            field = value.coerceIn(0, 2)
            markDirtyAndSync()
        }

    var roundRobin: Boolean = false
        set(value) {
            field = value
            markDirtyAndSync()
        }

    var roundRobinIndex: Int = 0
        // No setChanged on increment so the per-tick index update doesn't constantly
        // mark the chunk dirty. Persisted on save anyway.
        private set

    var tickInterval: Int = DEFAULT_TICK_INTERVAL
        set(value) {
            field = value.coerceIn(MIN_TICK_INTERVAL, MAX_TICK_INTERVAL)
            markDirtyAndSync()
        }

    // Not persisted, so a freshly-loaded chest waits up to [tickInterval]
    // ticks before its first insert.
    @Transient
    private var tickCounter: Int = 0

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
            return menu is ImportChestMenu && menu.devicePos == worldPosition
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
        // Recheck every tick so disconnected players don't strand the lid open.
        recheckOpen()

        tickCounter++
        if (tickCounter < tickInterval) return
        tickCounter = 0

        if (redstoneMode != REDSTONE_IGNORED) {
            val powered = level.hasNeighborSignal(worldPosition)
            if (!shouldRunForRedstone(redstoneMode, powered)) return
        }

        if (isEmpty) return
        // Bail when we're not on any network. Use this BE's own networkId
        // rather than `snapshot.controller != null`, since micro-networks
        // (anchored by a Processing Handler instead of a Network Controller)
        // are real, routable networks too - the controller-only gate
        // silently disabled the chest on every micro-net.
        if (networkId == null) return
        val snapshot = damien.nodeworks.network.NetworkDiscovery.discoverNetwork(level, worldPosition)

        // The cache lookup here is best-effort, used to fire onInserted hooks for the
        // Inventory Terminal's delta sync. Null when no consumer has opened a cache yet,
        // the helpers tolerate that.
        val cache = damien.nodeworks.script.NetworkInventoryCache.getOrCreate(level, worldPosition)

        if (roundRobin) {
            insertRoundRobin(level, snapshot, cache)
        } else {
            insertDefault(level, snapshot, cache)
        }
    }

    /** Default insertion: walk slots, push each via [NetworkStorageHelper.insertItemStack]
     *  which uses storage-card priority order. */
    private fun insertDefault(
        level: ServerLevel,
        snapshot: damien.nodeworks.network.NetworkSnapshot,
        cache: damien.nodeworks.script.NetworkInventoryCache?,
    ) {
        var changed = false
        for (i in 0 until SLOT_COUNT) {
            val stack = items[i]
            if (stack.isEmpty) continue
            val moved = NetworkStorageHelper.insertItemStack(level, snapshot, stack, cache, channel)
            if (moved > 0) {
                stack.shrink(moved)
                changed = true
            }
        }
        if (changed) setChanged()
    }

    /** Round-robin insertion: each item that moves advances the destination
     *  index, so items spread across storage cards instead of stacking on the
     *  highest-priority card. Cards that don't accept (channel mismatch,
     *  filter rejection, full storage) are skipped without consuming a step
     *  AND the cursor advances PAST the accepting card via `idx + 1`, so a
     *  denying card sitting at the cursor's position can't park the rotation
     *  on a perpetual no-op. (This is the same defensive pattern the
     *  Importer preset and BlockProcessingHandler use.) */
    private fun insertRoundRobin(
        level: ServerLevel,
        snapshot: damien.nodeworks.network.NetworkSnapshot,
        cache: damien.nodeworks.script.NetworkInventoryCache?,
    ) {
        val cards = NetworkStorageHelper.getStorageCards(snapshot)
            .filter { channel.matches(it.channel) }
        if (cards.isEmpty()) return

        var changed = false
        for (slot in 0 until SLOT_COUNT) {
            val stack = items[slot]
            if (stack.isEmpty) continue
            // Move ONE item per round-robin step. The chest's tick cadence (default 20)
            // bounds the cost, and per-item distribution matches `:roundrobin(1)`.
            while (!stack.isEmpty) {
                val startIndex = roundRobinIndex.mod(cards.size)
                var placed = false
                val registries = level.registryAccess()
                for (offset in 0 until cards.size) {
                    val idx = (startIndex + offset).mod(cards.size)
                    val card = cards[idx]
                    val cap = card.capability as? damien.nodeworks.card.StorageSideCapability
                    val itemId = net.minecraft.core.registries.BuiltInRegistries.ITEM
                        .getKey(stack.item)?.toString()
                    val hasData = !stack.componentsPatch.isEmpty
                    if (cap != null && !cap.acceptsItem(stack, registries)) continue
                    val storage = NetworkStorageHelper.getStorage(level, card) ?: continue
                    val moved = damien.nodeworks.platform.PlatformServices.storage.insertItemStack(
                        storage, stack.copyWithCount(1)
                    )
                    if (moved > 0) {
                        stack.shrink(moved)
                        if (itemId != null) {
                            cache?.onInserted(itemId, hasData, moved.toLong(), stack.componentsPatch)
                        }
                        roundRobinIndex = (idx + 1).mod(cards.size)
                        changed = true
                        placed = true
                        break
                    }
                }
                if (!placed) break // network has no room for this item, leave in chest
            }
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
    override fun saveAdditional(output: ValueOutput) {
        super.saveAdditional(output)
        ContainerHelper.saveAllItems(output, items)
        output.putInt("channel", channel.toNbtInt())
        output.putInt("redstoneMode", redstoneMode)
        output.putBoolean("roundRobin", roundRobin)
        output.putInt("roundRobinIndex", roundRobinIndex)
        output.putInt("tickInterval", tickInterval)
        networkId?.let { output.putString("networkId", it.toString()) }
        output.putBlockPosList("connections", connections)
    }

    override fun loadAdditional(input: ValueInput) {
        super.loadAdditional(input)
        items.clear()
        ContainerHelper.loadAllItems(input, items)
        channel = ChannelFilter.fromNbtInt(input.getIntOr("channel", -1))
        redstoneMode = input.getIntOr("redstoneMode", REDSTONE_IGNORED).coerceIn(0, 2)
        roundRobin = input.getBooleanOr("roundRobin", false)
        roundRobinIndex = input.getIntOr("roundRobinIndex", 0)
        tickInterval = input.getIntOr("tickInterval", DEFAULT_TICK_INTERVAL)
            .coerceIn(MIN_TICK_INTERVAL, MAX_TICK_INTERVAL)
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
}
