package damien.nodeworks.block.entity

import damien.nodeworks.registry.ModBlockEntities
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
 * Stabilizer, contributes cooling to the adjacent CPU multiblock. The combined
 * cooling from multiple Stabilizers diminishes per block (see [damien.nodeworks.script.cpu.CpuRules.totalCooling]),
 * so brute-forcing with more Stabilizers is only effective up to a point.
 */
class StabilizerBlockEntity(
    pos: BlockPos,
    state: BlockState
) : BlockEntity(ModBlockEntities.STABILIZER, pos, state), CpuComponentBlockEntity {

    override fun saveAdditional(output: ValueOutput) {
        super.saveAdditional(output)
    }

    override fun loadAdditional(input: ValueInput) {
        super.loadAdditional(input)
    }

    override fun getUpdateTag(registries: HolderLookup.Provider): CompoundTag = saveWithoutMetadata(registries)
    override fun getUpdatePacket(): Packet<ClientGamePacketListener> = ClientboundBlockEntityDataPacket.create(this)
}
