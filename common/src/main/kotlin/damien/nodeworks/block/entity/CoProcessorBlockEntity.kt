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

/**
 * Co-Processor — adds one parallel craft thread and fixed heat to an adjacent CPU.
 */
class CoProcessorBlockEntity(
    pos: BlockPos,
    state: BlockState
) : BlockEntity(ModBlockEntities.CO_PROCESSOR, pos, state), CpuComponentBlockEntity {

    val threadContribution: Int get() = CpuRules.THREADS_PER_COPROCESSOR
    val heat: Int get() = CpuRules.COPROCESSOR_HEAT

    override fun saveAdditional(tag: CompoundTag, registries: HolderLookup.Provider) {
        super.saveAdditional(tag, registries)
    }

    override fun loadAdditional(tag: CompoundTag, registries: HolderLookup.Provider) {
        super.loadAdditional(tag, registries)
    }

    override fun getUpdateTag(registries: HolderLookup.Provider): CompoundTag = saveWithoutMetadata(registries)
    override fun getUpdatePacket(): Packet<ClientGamePacketListener> = ClientboundBlockEntityDataPacket.create(this)
}
