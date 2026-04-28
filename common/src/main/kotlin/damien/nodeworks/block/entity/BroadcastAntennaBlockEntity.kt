package damien.nodeworks.block.entity

import damien.nodeworks.item.BroadcastSourceKind
import damien.nodeworks.item.LinkCrystalItem
import damien.nodeworks.network.NetworkDiscovery
import damien.nodeworks.registry.ModBlockEntities
import damien.nodeworks.registry.ModItems
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.Container
import net.minecraft.world.ContainerHelper
import net.minecraft.core.NonNullList
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput
import damien.nodeworks.compat.getStringOrNull
import java.util.UUID

/**
 * Broadcast Antenna, broadcasts either Processing Sets from an adjacent
 * [ProcessingStorageBlockEntity] or the identity of an adjacent
 * [NetworkControllerBlockEntity], depending on what's next to it. The antenna itself is
 * NOT Connectable, it doesn't ride the laser network, it sits next to a network member
 * and exposes a handle via the Link Crystal slot.
 *
 * Two broadcast kinds are distinguished via [BroadcastSourceKind]:
 *   * [BroadcastSourceKind.PROCESSING_STORAGE], exposes the adjacent storage's
 *     Processing Sets. Consumed by Receiver Antennas (original behaviour).
 *   * [BroadcastSourceKind.NETWORK_CONTROLLER], exposes the adjacent controller's
 *     network identity. Consumed by the Handheld Inventory Terminal to open a
 *     remote view of that network.
 *
 * If both kinds are adjacent, [NETWORK_CONTROLLER] wins because it represents the
 * broader surface (the whole network, not a specific Processing Storage). If neither
 * is adjacent the antenna is "unsourced" and refuses to encode crystals.
 */
class BroadcastAntennaBlockEntity(
    pos: BlockPos,
    state: BlockState
) : BlockEntity(ModBlockEntities.BROADCAST_ANTENNA, pos, state), Container {

    var frequencyId: UUID = UUID.randomUUID()
        private set

    /** Slot 0 = Link Crystal (frequency chip). Slot 1 = optional range upgrade. */
    private val items = NonNullList.withSize(2, ItemStack.EMPTY)

    companion object {
        const val SLOT_CHIP = 0
        const val SLOT_UPGRADE = 1
        /** Default range in blocks when no upgrade is installed. 8-chunk radius. */
        const val BASE_RANGE_BLOCKS = 128.0
    }

    /** Effective broadcast radius in blocks. Infinite (Double.MAX_VALUE) with either upgrade. */
    val effectiveRange: Double get() {
        val upgrade = items[SLOT_UPGRADE].item
        return if (upgrade == ModItems.DIMENSION_RANGE_UPGRADE || upgrade == ModItems.MULTI_DIMENSION_RANGE_UPGRADE)
            Double.MAX_VALUE
        else
            BASE_RANGE_BLOCKS
    }

    /** Whether receivers in different dimensions can pair with this antenna. */
    val allowsCrossDimension: Boolean get() =
        items[SLOT_UPGRADE].item == ModItems.MULTI_DIMENSION_RANGE_UPGRADE

    /**
     * Detect what kind of broadcast source is currently adjacent to this antenna, and
     * return the position of that source block. Returns null when no valid source is in
     * range (antenna is unsourced and can't encode crystals).
     *
     * Checked in priority order: [NetworkControllerBlockEntity] first (broader scope),
     * then [ProcessingStorageBlockEntity] (legacy / processing-focused). Unloaded
     * neighbours are skipped, we can't inspect their BE type, and returning a stale
     * answer here would cause crystals to encode with the wrong kind.
     */
    fun detectSource(): Pair<BroadcastSourceKind, BlockPos>? {
        val lvl = level ?: return null
        // First pass: Controller wins because it's the more general source. A player who
        // deliberately wants the processing-set broadcast can still get it by siting the
        // antenna away from the Controller, the Controller grabs priority only when both
        // blocks happen to be adjacent to the same antenna.
        for (dir in Direction.entries) {
            val neighbor = worldPosition.relative(dir)
            if (!lvl.isLoaded(neighbor)) continue
            val entity = lvl.getBlockEntity(neighbor)
            if (entity is NetworkControllerBlockEntity) {
                return BroadcastSourceKind.NETWORK_CONTROLLER to neighbor
            }
        }
        for (dir in Direction.entries) {
            val neighbor = worldPosition.relative(dir)
            if (!lvl.isLoaded(neighbor)) continue
            val entity = lvl.getBlockEntity(neighbor)
            if (entity is ProcessingStorageBlockEntity) {
                return BroadcastSourceKind.PROCESSING_STORAGE to neighbor
            }
        }
        return null
    }

    /** Scan adjacent Processing Storage clusters for all Processing Sets. */
    fun getAvailableApis(): List<ProcessingStorageBlockEntity.ProcessingApiInfo> {
        val lvl = level ?: return emptyList()
        val result = mutableListOf<ProcessingStorageBlockEntity.ProcessingApiInfo>()
        val visited = mutableSetOf<BlockPos>()

        for (dir in Direction.entries) {
            val neighbor = worldPosition.relative(dir)
            if (neighbor in visited) continue
            if (!lvl.isLoaded(neighbor)) continue
            val entity = lvl.getBlockEntity(neighbor) as? ProcessingStorageBlockEntity ?: continue
            visited.add(neighbor)
            result.addAll(entity.getAllProcessingApis())
        }
        return result
    }

    /**
     * Discover the provider network's terminal positions.
     * Finds an adjacent Processing Storage block that's Connectable, then runs network discovery from it.
     */
    fun getProviderTerminalPositions(): List<BlockPos> {
        val lvl = level as? ServerLevel ?: return emptyList()
        for (dir in Direction.entries) {
            val neighbor = worldPosition.relative(dir)
            if (!lvl.isLoaded(neighbor)) continue
            val entity = lvl.getBlockEntity(neighbor)
            if (entity is ProcessingStorageBlockEntity && entity.getConnections().isNotEmpty()) {
                val snapshot = NetworkDiscovery.discoverNetwork(lvl, neighbor)
                return snapshot.terminalPositions
            }
        }
        return emptyList()
    }

    // --- Container (slot 0 = chip, slot 1 = upgrade) ---

    override fun getContainerSize(): Int = items.size
    override fun isEmpty(): Boolean = items.all { it.isEmpty }

    override fun getItem(slot: Int): ItemStack =
        if (slot in items.indices) items[slot] else ItemStack.EMPTY

    override fun removeItem(slot: Int, amount: Int): ItemStack {
        val result = ContainerHelper.removeItem(items, slot, amount)
        if (!result.isEmpty) setChanged()
        return result
    }

    override fun removeItemNoUpdate(slot: Int): ItemStack =
        ContainerHelper.takeItem(items, slot)

    override fun setItem(slot: Int, stack: ItemStack) {
        if (slot !in items.indices) return
        items[slot] = stack
        if (slot == SLOT_CHIP && stack.item is LinkCrystalItem) {
            val lvl = level ?: return
            // Only encode when a valid source is adjacent. An unsourced antenna leaves the
            // crystal blank so the player gets the "place me in a Broadcast Antenna"
            // tooltip rather than a paired-but-useless crystal pointing at nothing.
            val source = detectSource()
            if (source != null) {
                LinkCrystalItem.encode(stack, worldPosition, lvl.dimension(), frequencyId, source.first)
            }
        }
        setChanged()
    }

    override fun stillValid(player: Player): Boolean =
        player.distanceToSqr(worldPosition.x + 0.5, worldPosition.y + 0.5, worldPosition.z + 0.5) <= 64.0

    override fun clearContent() = items.clear()

    // --- Serialization ---

    override fun saveAdditional(output: ValueOutput) {
        super.saveAdditional(output)
        output.putString("frequency", frequencyId.toString())
        ContainerHelper.saveAllItems(output, items)
    }

    override fun loadAdditional(input: ValueInput) {
        super.loadAdditional(input)
        input.getStringOrNull("frequency")?.let { freqStr ->
            try { frequencyId = UUID.fromString(freqStr) }
            catch (_: Exception) { frequencyId = UUID.randomUUID() }
        }
        items.clear()
        ContainerHelper.loadAllItems(input, items)
    }

    override fun getUpdateTag(registries: HolderLookup.Provider): CompoundTag =
        saveWithoutMetadata(registries)

    override fun getUpdatePacket(): Packet<ClientGamePacketListener> =
        ClientboundBlockEntityDataPacket.create(this)
}
