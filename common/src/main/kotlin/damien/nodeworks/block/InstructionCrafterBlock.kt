package damien.nodeworks.block

import com.mojang.serialization.MapCodec
import damien.nodeworks.block.entity.InstructionCrafterBlockEntity
import damien.nodeworks.item.NetworkWrenchItem
import net.minecraft.core.BlockPos
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.block.BaseEntityBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.VoxelShape

/**
 * Instruction Crafter — connects to the node network via lasers.
 * Scans adjacent Instruction Storage blocks for recipes.
 * Scripts call network:craft() which routes through this block.
 */
class InstructionCrafterBlock(properties: Properties) : BaseEntityBlock(properties) {

    companion object {
        val CODEC: MapCodec<InstructionCrafterBlock> = simpleCodec(::InstructionCrafterBlock)

        // 12x12x12 pixel centered cube (2..14 on each axis)
        val SHAPE: VoxelShape = Block.box(2.0, 2.0, 2.0, 14.0, 14.0, 14.0)
    }

    override fun codec(): MapCodec<out BaseEntityBlock> = CODEC
    override fun getRenderShape(state: BlockState): RenderShape = RenderShape.MODEL

    override fun getShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape = SHAPE
    override fun getCollisionShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape = SHAPE

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity {
        return InstructionCrafterBlockEntity(pos, state)
    }

    override fun playerWillDestroy(level: Level, pos: BlockPos, state: BlockState, player: Player): BlockState {
        val entity = level.getBlockEntity(pos) as? InstructionCrafterBlockEntity
        entity?.blockDestroyed = true
        return super.playerWillDestroy(level, pos, state, player)
    }
}
