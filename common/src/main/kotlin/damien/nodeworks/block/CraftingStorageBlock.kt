package damien.nodeworks.block

import com.mojang.serialization.MapCodec
import damien.nodeworks.block.entity.CraftingCoreBlockEntity
import damien.nodeworks.block.entity.CraftingStorageBlockEntity
import net.minecraft.core.BlockPos
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.BaseEntityBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.BlockHitResult

/**
 * Crafting Storage — adds buffer capacity to an adjacent Crafting CPU.
 * Place adjacent to a Crafting Core (or other Crafting Storage blocks).
 * The tier is set by the crafting storage upgrade item used during placement.
 */
class CraftingStorageBlock(properties: Properties) : BaseEntityBlock(properties) {

    companion object {
        val CODEC: MapCodec<CraftingStorageBlock> = simpleCodec(::CraftingStorageBlock)
    }

    override fun codec(): MapCodec<out BaseEntityBlock> = CODEC
    override fun getRenderShape(state: BlockState): RenderShape = RenderShape.MODEL

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity {
        return CraftingStorageBlockEntity(pos, state)
    }

    override fun neighborChanged(state: BlockState, level: Level, pos: BlockPos, neighborBlock: Block, neighborPos: BlockPos, movedByPiston: Boolean) {
        super.neighborChanged(state, level, pos, neighborBlock, neighborPos, movedByPiston)
        // Notify adjacent cores to recalculate when neighbors change
        val entity = level.getBlockEntity(pos) as? CraftingStorageBlockEntity ?: return
        for (dir in net.minecraft.core.Direction.entries) {
            val neighbor = pos.relative(dir)
            val core = level.getBlockEntity(neighbor) as? CraftingCoreBlockEntity ?: continue
            core.recalculateCapacity()
        }
    }

    override fun playerWillDestroy(level: Level, pos: BlockPos, state: BlockState, player: Player): BlockState {
        // Notify adjacent cores that capacity changed
        if (!level.isClientSide) {
            for (dir in net.minecraft.core.Direction.entries) {
                val neighbor = pos.relative(dir)
                val core = level.getBlockEntity(neighbor) as? CraftingCoreBlockEntity ?: continue
                core.recalculateCapacity()
            }
        }
        return super.playerWillDestroy(level, pos, state, player)
    }
}
