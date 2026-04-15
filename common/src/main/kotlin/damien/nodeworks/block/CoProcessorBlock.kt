package damien.nodeworks.block

import com.mojang.serialization.MapCodec
import damien.nodeworks.block.entity.CoProcessorBlockEntity
import damien.nodeworks.block.entity.CpuComponentBlockEntity
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.BaseEntityBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BooleanProperty

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

        /** True when this block's cooling received from adjacent Stabilizer faces is less
         *  than its heat (base + hotspot). Drives the red emissive variant so players can
         *  see exactly which blocks need more Stabilizer neighbors. */
        val OVERHEATING: BooleanProperty = BooleanProperty.create("overheating")
    }

    init {
        registerDefaultState(stateDefinition.any()
            .setValue(FORMED, false)
            .setValue(OVERHEATING, false))
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(FORMED, OVERHEATING)
    }

    override fun codec(): MapCodec<out BaseEntityBlock> = CODEC
    override fun getRenderShape(state: BlockState): RenderShape = RenderShape.MODEL

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity {
        return CoProcessorBlockEntity(pos, state)
    }

    override fun neighborChanged(state: BlockState, level: Level, pos: BlockPos, neighborBlock: Block, neighborPos: BlockPos, movedByPiston: Boolean) {
        super.neighborChanged(state, level, pos, neighborBlock, neighborPos, movedByPiston)
        level.getBlockEntity(pos) as? CoProcessorBlockEntity ?: return
        val cores = CpuComponentBlockEntity.findConnectedCores(level, pos)
        if (cores.isEmpty()) {
            // Orphaned — no Core reachable. Self-clear formed AND overheating flags since
            // the Core's recalculate BFS can no longer reach us to push the update.
            var next = state
            if (next.getValue(FORMED)) next = next.setValue(FORMED, false)
            if (next.getValue(OVERHEATING)) next = next.setValue(OVERHEATING, false)
            if (next !== state) level.setBlock(pos, next, Block.UPDATE_ALL)
        } else {
            cores.forEach { it.recalculateCapacity() }
        }
    }

    override fun playerWillDestroy(level: Level, pos: BlockPos, state: BlockState, player: Player): BlockState {
        if (!level.isClientSide) {
            CpuComponentBlockEntity.findConnectedCores(level, pos).forEach { it.recalculateCapacity() }
        }
        return super.playerWillDestroy(level, pos, state, player)
    }
}
