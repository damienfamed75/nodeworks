package damien.nodeworks.block.entity

import damien.nodeworks.item.LinkChipItem
import damien.nodeworks.network.NetworkDiscovery
import damien.nodeworks.registry.ModBlockEntities
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
import java.util.UUID

/**
 * Broadcast Antenna — broadcasts Processing API Cards from adjacent API Storage blocks.
 * NOT Connectable — sits adjacent to API Storage, not on the laser network.
 * Has 1 slot for a Link Chip which gets encoded with the antenna's frequency on insertion.
 */
class BroadcastAntennaBlockEntity(
    pos: BlockPos,
    state: BlockState
) : BlockEntity(ModBlockEntities.BROADCAST_ANTENNA, pos, state), Container {

    var frequencyId: UUID = UUID.randomUUID()
        private set

    private val items = NonNullList.withSize(1, ItemStack.EMPTY)

    /** Scan adjacent API Storage clusters for all Processing API Cards. */
    fun getAvailableApis(): List<ApiStorageBlockEntity.ProcessingApiInfo> {
        val lvl = level ?: return emptyList()
        val result = mutableListOf<ApiStorageBlockEntity.ProcessingApiInfo>()
        val visited = mutableSetOf<BlockPos>()

        for (dir in Direction.entries) {
            val neighbor = worldPosition.relative(dir)
            if (neighbor in visited) continue
            if (!lvl.isLoaded(neighbor)) continue
            val entity = lvl.getBlockEntity(neighbor) as? ApiStorageBlockEntity ?: continue
            visited.add(neighbor)
            result.addAll(entity.getAllProcessingApis())
        }
        return result
    }

    /**
     * Discover the provider network's terminal positions.
     * Finds an adjacent API Storage block that's Connectable, then runs network discovery from it.
     */
    fun getProviderTerminalPositions(): List<BlockPos> {
        val lvl = level as? ServerLevel ?: return emptyList()
        for (dir in Direction.entries) {
            val neighbor = worldPosition.relative(dir)
            if (!lvl.isLoaded(neighbor)) continue
            val entity = lvl.getBlockEntity(neighbor)
            if (entity is ApiStorageBlockEntity && entity.getConnections().isNotEmpty()) {
                val snapshot = NetworkDiscovery.discoverNetwork(lvl, neighbor)
                return snapshot.terminalPositions
            }
        }
        return emptyList()
    }

    // --- Container (1 chip slot) ---

    override fun getContainerSize(): Int = 1
    override fun isEmpty(): Boolean = items[0].isEmpty

    override fun getItem(slot: Int): ItemStack =
        if (slot == 0) items[0] else ItemStack.EMPTY

    override fun removeItem(slot: Int, amount: Int): ItemStack {
        val result = ContainerHelper.removeItem(items, slot, amount)
        if (!result.isEmpty) setChanged()
        return result
    }

    override fun removeItemNoUpdate(slot: Int): ItemStack =
        ContainerHelper.takeItem(items, slot)

    override fun setItem(slot: Int, stack: ItemStack) {
        if (slot != 0) return
        items[0] = stack
        // Encode (or re-encode) the chip when a Link Chip is inserted
        if (stack.item is LinkChipItem) {
            val lvl = level ?: return
            LinkChipItem.encode(stack, worldPosition, lvl.dimension(), frequencyId)
        }
        setChanged()
    }

    override fun stillValid(player: Player): Boolean =
        player.distanceToSqr(worldPosition.x + 0.5, worldPosition.y + 0.5, worldPosition.z + 0.5) <= 64.0

    override fun clearContent() = items.clear()

    // --- Serialization ---

    override fun saveAdditional(tag: CompoundTag, registries: HolderLookup.Provider) {
        super.saveAdditional(tag, registries)
        tag.putString("frequency", frequencyId.toString())
        ContainerHelper.saveAllItems(tag, items, registries)
    }

    override fun loadAdditional(tag: CompoundTag, registries: HolderLookup.Provider) {
        super.loadAdditional(tag, registries)
        if (tag.contains("frequency")) {
            try { frequencyId = UUID.fromString(tag.getString("frequency")) }
            catch (_: Exception) { frequencyId = UUID.randomUUID() }
        }
        items.clear()
        ContainerHelper.loadAllItems(tag, items, registries)
    }

    override fun getUpdateTag(registries: HolderLookup.Provider): CompoundTag =
        saveWithoutMetadata(registries)

    override fun getUpdatePacket(): Packet<ClientGamePacketListener> =
        ClientboundBlockEntityDataPacket.create(this)
}
