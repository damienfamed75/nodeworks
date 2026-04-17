package damien.nodeworks.block.entity

import damien.nodeworks.registry.ModBlockEntities
import damien.nodeworks.script.cpu.CpuRules
import net.minecraft.core.BlockPos
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput

/**
 * Buffer block (code name: CraftingStorage — kept for save-compat) — contributes
 * count capacity, unique-types capacity, and heat to an adjacent Crafting CPU.
 *
 * Values are fixed per block ([CpuRules.BUFFER_COUNT_CAPACITY] etc.); scaling comes
 * from adding more Buffers. The puzzle is balancing the heat this generates against
 * Stabilizer cooling (which diminishes) and Substrate adjacency bonuses.
 */
class CraftingStorageBlockEntity(
    pos: BlockPos,
    state: BlockState
) : BlockEntity(ModBlockEntities.CRAFTING_STORAGE, pos, state), CpuComponentBlockEntity {

    val storageCapacity: Long get() = CpuRules.BUFFER_COUNT_CAPACITY
    val storageTypes: Int get() = CpuRules.BUFFER_TYPES_CAPACITY
    val storageHeat: Int get() = CpuRules.BUFFER_HEAT

    override fun saveAdditional(output: ValueOutput) {
        super.saveAdditional(output)
    }

    override fun loadAdditional(input: ValueInput) {
        super.loadAdditional(input)
    }

    override fun getUpdateTag(registries: HolderLookup.Provider): CompoundTag = saveWithoutMetadata(registries)
    override fun getUpdatePacket(): Packet<ClientGamePacketListener> = ClientboundBlockEntityDataPacket.create(this)
}
