package damien.nodeworks.block.entity

import damien.nodeworks.registry.ModBlockEntities
import net.minecraft.core.BlockPos
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState

/**
 * Crafting Storage — adds buffer capacity to an adjacent Crafting Core.
 * Part of the multiblock Crafting CPU. Does not connect to the network
 * directly — it's discovered by the Core via adjacency scanning.
 */
class CraftingStorageBlockEntity(
    pos: BlockPos,
    state: BlockState
) : BlockEntity(ModBlockEntities.CRAFTING_STORAGE, pos, state) {

    companion object {
        /** Storage capacity per tier (stack count of the upgrade item determines tier). */
        const val TIER_1_CAPACITY = 1024
        const val TIER_2_CAPACITY = 4096
        const val TIER_3_CAPACITY = 16384
        const val TIER_4_CAPACITY = 65536

        fun capacityForTier(tier: Int): Int = when (tier) {
            1 -> TIER_1_CAPACITY
            2 -> TIER_2_CAPACITY
            3 -> TIER_3_CAPACITY
            4 -> TIER_4_CAPACITY
            else -> TIER_1_CAPACITY
        }
    }

    /** The storage tier (1-4). Determines buffer capacity contributed to the CPU. */
    var tier: Int = 1
        private set

    /** Buffer capacity this block contributes. */
    val storageCapacity: Int get() = capacityForTier(tier)

    fun setTier(newTier: Int) {
        tier = newTier.coerceIn(1, 4)
        setChanged()
        level?.sendBlockUpdated(worldPosition, blockState, blockState, Block.UPDATE_ALL)
        // Notify adjacent Crafting Core to recalculate
        notifyAdjacentCores()
    }

    private fun notifyAdjacentCores() {
        val lvl = level ?: return
        for (dir in net.minecraft.core.Direction.entries) {
            val neighbor = worldPosition.relative(dir)
            val entity = lvl.getBlockEntity(neighbor)
            if (entity is CraftingCoreBlockEntity) {
                entity.recalculateCapacity()
            }
        }
    }

    override fun setRemoved() {
        super.setRemoved()
        // Notify adjacent cores when removed (deferred to avoid recursion during chunk load)
    }

    // --- Serialization ---

    override fun saveAdditional(tag: CompoundTag, registries: HolderLookup.Provider) {
        super.saveAdditional(tag, registries)
        tag.putInt("tier", tier)
    }

    override fun loadAdditional(tag: CompoundTag, registries: HolderLookup.Provider) {
        super.loadAdditional(tag, registries)
        tier = if (tag.contains("tier")) tag.getInt("tier").coerceIn(1, 4) else 1
    }

    override fun getUpdateTag(registries: HolderLookup.Provider): CompoundTag {
        return saveWithoutMetadata(registries)
    }

    override fun getUpdatePacket(): Packet<ClientGamePacketListener> {
        return ClientboundBlockEntityDataPacket.create(this)
    }
}
