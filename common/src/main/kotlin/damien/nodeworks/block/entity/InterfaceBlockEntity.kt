package damien.nodeworks.block.entity

import damien.nodeworks.card.StorageSideCapability
import damien.nodeworks.network.CardSnapshot
import damien.nodeworks.network.ChannelFilter
import damien.nodeworks.network.Connectable
import damien.nodeworks.network.NetworkDiscovery
import damien.nodeworks.network.NetworkSnapshot
import damien.nodeworks.network.NodeConnectionHelper
import damien.nodeworks.platform.ItemInfo
import damien.nodeworks.platform.PlatformServices
import damien.nodeworks.registry.ModBlockEntities
import damien.nodeworks.script.InterfaceItemStorageHandle
import damien.nodeworks.script.NetworkInventoryCache
import damien.nodeworks.script.NetworkStorageHelper
import net.minecraft.core.BlockPos
import net.minecraft.core.HolderLookup
import net.minecraft.core.NonNullList
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.Container
import net.minecraft.world.ContainerHelper
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput
import damien.nodeworks.compat.getBlockPosList
import damien.nodeworks.compat.getStringOrNull
import damien.nodeworks.compat.putBlockPosList
import java.util.UUID

/**
 * Interface. A device on its own network that presents that network's storage to
 * whatever faces it from outside.
 *
 * To another network's Storage Card, Export card or Import card facing it, the Interface
 * is an inventory backed by this network's storage: reads come from an index of the
 * storage, extraction takes from the Storage Cards in priority order, insertion goes
 * through the network's priority insert. Items never move between networks until one of
 * them wants them, and the two networks never merge, since a Node face with a card on it
 * is not a connection.
 *
 * To hoppers and other mods it is a small buffer, which this network drains into its
 * storage on a cadence, the way an Import Chest does.
 *
 * It exposes only storage this network owns directly. A Storage Card this network has on
 * some other Interface is left out of the index, the extraction order and the insert, so
 * chains of Interfaces read one level deep and never loop.
 */
class InterfaceBlockEntity(
    pos: BlockPos,
    state: BlockState,
) : BlockEntity(ModBlockEntities.INTERFACE, pos, state), Container, Connectable {

    companion object {
        const val SLOT_COUNT = 9
        /** Ticks between buffer drains. */
        const val DRAIN_INTERVAL = 20
    }

    private val items: NonNullList<ItemStack> = NonNullList.withSize(SLOT_COUNT, ItemStack.EMPTY)
    private val connections = LinkedHashSet<BlockPos>()
    override var blockDestroyed: Boolean = false
    override var networkId: UUID? = null

    @Transient
    private var tickCounter: Int = 0

    private val handle = InterfaceItemStorageHandle(this)

    /** The view another network's card gets when it faces this block. */
    fun storageHandle(): InterfaceItemStorageHandle = handle

    private fun markDirtyAndSync() {
        setChanged()
        level?.sendBlockUpdated(worldPosition, blockState, blockState, Block.UPDATE_ALL)
    }

    // --- Buffer drain ---

    fun serverTick(level: ServerLevel) {
        tickCounter++
        if (tickCounter < DRAIN_INTERVAL) return
        tickCounter = 0
        if (isEmpty || networkId == null) return
        val snapshot = snapshot(level)
        var changed = false
        for (i in 0 until SLOT_COUNT) {
            val stack = items[i]
            if (stack.isEmpty) continue
            val moved = insertIntoOwnStorage(level, snapshot, stack)
            if (moved > 0) {
                stack.shrink(moved)
                if (stack.isEmpty) items[i] = ItemStack.EMPTY
                changed = true
            }
        }
        if (changed) setChanged()
    }

    // --- The network behind the Interface ---

    private fun snapshot(level: ServerLevel): NetworkSnapshot = NetworkDiscovery.discoverNetwork(level, worldPosition)

    private var nativeFor: NetworkSnapshot? = null
    private var nativeCardList: List<CardSnapshot> = emptyList()
    private var interfacePositions: Set<BlockPos> = emptySet()

    /** Storage Cards of this network whose inventory is not another Interface, in
     *  priority order, and the positions of the Interfaces the rest face. Built once per
     *  network walk. */
    private fun resolveNative(level: ServerLevel, snapshot: NetworkSnapshot) {
        if (nativeFor === snapshot) return
        val native = ArrayList<CardSnapshot>()
        val foreign = HashSet<BlockPos>()
        for (card in snapshot.storageCards) {
            val cap = card.capability as? StorageSideCapability ?: continue
            if (level.getBlockEntity(cap.adjacentPos) is InterfaceBlockEntity) foreign.add(cap.adjacentPos) else native.add(card)
        }
        nativeFor = snapshot
        nativeCardList = native
        interfacePositions = foreign
    }

    /** The index this network's native storage is read through. Made when something first
     *  reads the Interface, dropped again when nothing has read it for a while. */
    private fun nativeIndex(level: ServerLevel): NetworkInventoryCache? {
        if (networkId == null) return null
        return NetworkInventoryCache.getOrCreateNative(level, worldPosition).also { it.markRead() }
    }

    private fun insertIntoOwnStorage(level: ServerLevel, snapshot: NetworkSnapshot, stack: ItemStack): Int {
        resolveNative(level, snapshot)
        val template = stack.copy()
        // The helper reports to the terminal cache itself. The native index is told here.
        val inserted = NetworkStorageHelper.insertItemStack(level, snapshot, stack, null, ChannelFilter.All, interfacePositions)
        if (inserted > 0) noteNative(level, template, inserted.toLong(), into = true)
        return inserted
    }

    private fun noteNative(level: ServerLevel, stack: ItemStack, count: Long, into: Boolean) {
        val native = NetworkInventoryCache.existingNative(level, worldPosition) ?: return
        val id = BuiltInRegistries.ITEM.getKey(stack.item)?.toString() ?: return
        val patch = stack.componentsPatch
        if (into) native.onInserted(id, !patch.isEmpty, count, patch) else native.onExtracted(id, !patch.isEmpty, count, patch)
    }

    // --- What the outside sees ---

    /** The network's native stock, one entry per item variant. */
    fun exposedEntries(): List<ItemInfo> {
        val level = level as? ServerLevel ?: return emptyList()
        val index = nativeIndex(level) ?: return emptyList()
        return index.getAllItems().filter { it.count > 0L }
    }

    /** A stack standing for [info], so stack predicates can be run against an entry. */
    fun representative(info: ItemInfo): ItemStack? {
        val id = net.minecraft.resources.Identifier.tryParse(info.itemId) ?: return null
        val item = BuiltInRegistries.ITEM.getValue(id) ?: return null
        val stack = ItemStack(item)
        if (!info.componentsPatch.isEmpty) stack.applyComponents(info.componentsPatch)
        return stack
    }

    /** Insert [stack] into this network's storage on behalf of an outside network. */
    fun insertFromOutside(stack: ItemStack): Int {
        val level = level as? ServerLevel ?: return 0
        if (networkId == null || stack.isEmpty) return 0
        val snapshot = snapshot(level)
        nativeIndex(level)
        return insertIntoOwnStorage(level, snapshot, stack.copy())
    }

    /** How much of [stack] the native storage would take, up to its count. */
    fun simulateInsertFromOutside(stack: ItemStack): Long {
        val level = level as? ServerLevel ?: return 0L
        if (networkId == null || stack.isEmpty) return 0L
        val snapshot = snapshot(level)
        resolveNative(level, snapshot)
        val registries = level.registryAccess()
        var room = 0L
        for (card in nativeCardList) {
            if (room >= stack.count) break
            val cap = card.capability as? StorageSideCapability ?: continue
            if (!cap.acceptsItem(stack, registries)) continue
            val storage = NetworkStorageHelper.getStorage(level, card) ?: continue
            room += PlatformServices.storage.simulateInsertStack(storage, stack.copyWithCount((stack.count - room).toInt()))
        }
        return minOf(room, stack.count.toLong())
    }

    /** Take up to [maxCount] items passing [filter] out of the native storage, Storage
     *  Cards in priority order. Both this network's caches hear the change. */
    fun extractFromOutside(filter: (ItemStack) -> Boolean, maxCount: Long): List<ItemStack> {
        val level = level as? ServerLevel ?: return emptyList()
        if (networkId == null || maxCount <= 0L) return emptyList()
        val snapshot = snapshot(level)
        resolveNative(level, snapshot)
        nativeIndex(level)
        val terminal = NetworkInventoryCache.existing(snapshot.networkId)
        val out = ArrayList<ItemStack>()
        var remaining = maxCount
        for (card in nativeCardList) {
            if (remaining <= 0L) break
            val storage = NetworkStorageHelper.getStorage(level, card) ?: continue
            val stacks = PlatformServices.storage.extractStacksByPredicate(storage, filter, remaining)
            for (stack in stacks) {
                if (stack.isEmpty) continue
                val id = BuiltInRegistries.ITEM.getKey(stack.item)?.toString()
                val patch = stack.componentsPatch
                if (id != null && terminal != null) {
                    terminal.onExtracted(id, !patch.isEmpty, stack.count.toLong(), patch)
                    terminal.onChannelDelta(card.channel, id, patch, -stack.count.toLong())
                }
                noteNative(level, stack, stack.count.toLong(), into = false)
                out.add(stack)
                remaining -= stack.count
            }
        }
        return out
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

    /** Joins its network through pipes and wrench links only. A Node placed against an
     *  Interface is nearly always the other network coming to read it, so adjacency to a
     *  Node never connects. Two leaves face to face never bridge either. */
    override fun canConnectAdjacentTo(other: Connectable): Boolean =
        other is PipeBlockEntity || other is CoveredPipeBlockEntity

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

    // --- Container, the buffer ---
    override fun getContainerSize(): Int = SLOT_COUNT
    override fun isEmpty(): Boolean = items.all { it.isEmpty }
    override fun getItem(slot: Int): ItemStack = if (slot in items.indices) items[slot] else ItemStack.EMPTY

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

    // --- Serialization ---
    override fun saveAdditional(output: ValueOutput) {
        super.saveAdditional(output)
        ContainerHelper.saveAllItems(output, items)
        networkId?.let { output.putString("networkId", it.toString()) }
        output.putBlockPosList("connections", connections)
    }

    override fun loadAdditional(input: ValueInput) {
        super.loadAdditional(input)
        items.clear()
        ContainerHelper.loadAllItems(input, items)
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
