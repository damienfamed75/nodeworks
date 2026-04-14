package damien.nodeworks.block.entity

import damien.nodeworks.registry.ModBlockEntities
import damien.nodeworks.script.cpu.CpuRules
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
 * Co-Processor — each one adds one parallel craft thread (slot) to an adjacent CPU.
 * Tier only controls heat generation; every Co-Processor, regardless of tier,
 * contributes [CpuRules.THREADS_PER_COPROCESSOR] thread(s). See [CpuRules] for
 * the full rationale.
 */
class CoProcessorBlockEntity(
    pos: BlockPos,
    state: BlockState
) : BlockEntity(ModBlockEntities.CO_PROCESSOR, pos, state), CpuComponentBlockEntity {

    var tier: Int = CpuRules.MIN_TIER
        private set

    /** Threads contributed to the CPU. Constant today; kept as a function so future tiers can change it. */
    val threadContribution: Int get() = CpuRules.THREADS_PER_COPROCESSOR

    /** Heat this block generates at its current tier. Wired into Phase 4 throttle math. */
    val heat: Int get() = CpuRules.coProcessorHeat(tier)

    fun setTier(newTier: Int) {
        tier = CpuRules.clampTier(newTier)
        setChanged()
        level?.sendBlockUpdated(worldPosition, blockState, blockState, Block.UPDATE_ALL)
        notifyAdjacentCores()
    }

    private fun notifyAdjacentCores() {
        val lvl = level ?: return
        CpuComponentBlockEntity.findConnectedCores(lvl, worldPosition).forEach { it.recalculateCapacity() }
    }

    override fun saveAdditional(tag: CompoundTag, registries: HolderLookup.Provider) {
        super.saveAdditional(tag, registries)
        tag.putInt("tier", tier)
    }

    override fun loadAdditional(tag: CompoundTag, registries: HolderLookup.Provider) {
        super.loadAdditional(tag, registries)
        tier = if (tag.contains("tier")) CpuRules.clampTier(tag.getInt("tier")) else CpuRules.MIN_TIER
    }

    override fun getUpdateTag(registries: HolderLookup.Provider): CompoundTag {
        return saveWithoutMetadata(registries)
    }

    override fun getUpdatePacket(): Packet<ClientGamePacketListener> {
        return ClientboundBlockEntityDataPacket.create(this)
    }
}
