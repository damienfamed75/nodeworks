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
 * Substrate, the positional puzzle piece of the CPU. Contributes two things:
 *  1. A small unique-type slot bonus ([CpuRules.SUBSTRATE_TYPE_CONTRIBUTION]) regardless of
 *     placement, so adding one is never worthless.
 *  2. A throttle bonus for every face it shares with a Buffer or Co-Processor
 *     ([CpuRules.SUBSTRATE_BONUS_PER_FACE]). Substrate-to-Substrate faces do NOT count,
 *     the mechanic rewards sandwiching Substrate between heat-generating components, which
 *     forces the player to think about layout rather than block-quantity.
 *
 * No heat, no cooling, purely throttle scaling.
 */
class SubstrateBlockEntity(
    pos: BlockPos,
    state: BlockState
) : BlockEntity(ModBlockEntities.SUBSTRATE, pos, state), CpuComponentBlockEntity {

    private fun notifyAdjacentCores() {
        val lvl = level ?: return
        CpuComponentBlockEntity.findConnectedCores(lvl, worldPosition).forEach { it.recalculateCapacity() }
    }

    override fun saveAdditional(output: ValueOutput) {
        super.saveAdditional(output)
    }

    override fun loadAdditional(input: ValueInput) {
        super.loadAdditional(input)
    }

    override fun getUpdateTag(registries: HolderLookup.Provider): CompoundTag = saveWithoutMetadata(registries)
    override fun getUpdatePacket(): Packet<ClientGamePacketListener> = ClientboundBlockEntityDataPacket.create(this)
}
