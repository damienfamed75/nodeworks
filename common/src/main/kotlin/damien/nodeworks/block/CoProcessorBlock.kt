package damien.nodeworks.block

import com.mojang.serialization.MapCodec
import damien.nodeworks.block.entity.CoProcessorBlockEntity
import damien.nodeworks.block.entity.CpuComponentBlockEntity
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.util.RandomSource
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.BaseEntityBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BooleanProperty
import net.minecraft.world.level.block.state.properties.IntegerProperty

/**
 * Co-Processor — place adjacent to a Crafting Core (or any CPU component) to add
 * one parallel craft thread. Notification walks the whole component chain so cores
 * several blocks away still recalculate.
 */
class CoProcessorBlock(properties: Properties) : BaseEntityBlock(properties) {

    companion object {
        val CODEC: MapCodec<CoProcessorBlock> = simpleCodec(::CoProcessorBlock)

        /** Driven by the CPU Core's recalculateCapacity — mirrors the Core's formed state
         *  so the emissive variant lights up whenever the Co-Processor is contributing. */
        val FORMED: BooleanProperty = BooleanProperty.create("formed")

        /** 0 = safe, 1 = warm, 2 = hot, 3 = critical. Drives the tiered emissive overlays
         *  so players can see exactly which blocks need more Stabilizer neighbors. */
        val OVERHEAT_LEVEL: IntegerProperty = IntegerProperty.create("overheat_level", 0, 3)
    }

    init {
        registerDefaultState(stateDefinition.any()
            .setValue(FORMED, false)
            .setValue(OVERHEAT_LEVEL, 0))
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(FORMED, OVERHEAT_LEVEL)
    }

    override fun codec(): MapCodec<out BaseEntityBlock> = CODEC
    override fun getRenderShape(state: BlockState): RenderShape = RenderShape.MODEL

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity {
        return CoProcessorBlockEntity(pos, state)
    }

    override fun neighborChanged(state: BlockState, level: Level, pos: BlockPos, neighborBlock: Block, orientation: net.minecraft.world.level.redstone.Orientation?, movedByPiston: Boolean) {
        super.neighborChanged(state, level, pos, neighborBlock, orientation, movedByPiston)
        level.getBlockEntity(pos) as? CoProcessorBlockEntity ?: return
        val cores = CpuComponentBlockEntity.findConnectedCores(level, pos)
        if (cores.isEmpty()) {
            // Orphaned — no Core reachable. Self-clear formed AND overheat level since
            // the Core's recalculate BFS can no longer reach us to push the update.
            var next = state
            if (next.getValue(FORMED)) next = next.setValue(FORMED, false)
            if (next.getValue(OVERHEAT_LEVEL) != 0) next = next.setValue(OVERHEAT_LEVEL, 0)
            if (next !== state) level.setBlock(pos, next, Block.UPDATE_ALL)
        } else {
            cores.forEach { it.recalculateCapacity() }
        }
    }

    override fun animateTick(state: BlockState, level: Level, pos: BlockPos, random: RandomSource) {
        val lvl = state.getValue(OVERHEAT_LEVEL)
        if (lvl <= 0) return
        val x = pos.x + 0.5 + (random.nextDouble() - 0.5) * 0.8
        val y = pos.y + 1.0
        val z = pos.z + 0.5 + (random.nextDouble() - 0.5) * 0.8
        // Warm/hot: occasional light smoke. Critical: continuous heavy smoke.
        when (lvl) {
            1 -> if (random.nextInt(6) == 0) level.addParticle(ParticleTypes.SMOKE, x, y, z, 0.0, 0.02, 0.0)
            2 -> {
                level.addParticle(ParticleTypes.SMOKE, x, y, z, 0.0, 0.03, 0.0)
                if (random.nextInt(3) == 0) level.addParticle(ParticleTypes.LARGE_SMOKE, x, y, z, 0.0, 0.02, 0.0)
            }
            3 -> {
                level.addParticle(ParticleTypes.LARGE_SMOKE, x, y, z, 0.0, 0.02, 0.0)
                if (random.nextInt(4) == 0) level.addParticle(ParticleTypes.SMOKE, x, y, z, 0.0, 0.04, 0.0)
            }
        }
    }

    override fun playerWillDestroy(level: Level, pos: BlockPos, state: BlockState, player: Player): BlockState {
        if (!level.isClientSide) {
            CpuComponentBlockEntity.findConnectedCores(level, pos).forEach { it.recalculateCapacity() }
        }
        return super.playerWillDestroy(level, pos, state, player)
    }
}
