package damien.nodeworks.block

import com.mojang.serialization.MapCodec
import damien.nodeworks.block.entity.FocusNodeBlockEntity
import net.minecraft.core.BlockPos
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.block.BaseEntityBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.VoxelShape

/**
 * Premium tier of [NodeBlock]. Adds focused-laser links to other Focus
 * Nodes via the wrench, and uses a 10×10×10 hitbox so the lens protrusions
 * on the body have collision coverage. Everything else (multipart pipe
 * stubs, placement flow, card slots) is inherited.
 */
class FocusNodeBlock(properties: Properties) : NodeBlock(properties) {

    companion object {
        val FOCUS_CODEC: MapCodec<FocusNodeBlock> = simpleCodec(::FocusNodeBlock)

        val FOCUS_CORE_SHAPE: VoxelShape = Block.box(3.0, 3.0, 3.0, 13.0, 13.0, 13.0)
    }

    override fun codec(): MapCodec<out BaseEntityBlock> = FOCUS_CODEC

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity =
        FocusNodeBlockEntity(pos, state)

    override fun getShape(
        state: BlockState,
        level: BlockGetter,
        pos: BlockPos,
        context: CollisionContext,
    ): VoxelShape = shapeFor(state, FOCUS_CORE_SHAPE)

    override fun getCollisionShape(
        state: BlockState,
        level: BlockGetter,
        pos: BlockPos,
        context: CollisionContext,
    ): VoxelShape = shapeFor(state, FOCUS_CORE_SHAPE)
}
