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
 * Focus Node — premium tier of [NodeBlock] that can additionally form
 * focused-laser links with other Focus Nodes via the wrench. The "focus"
 * framing comes from the visual: a lens-style core that focuses the network
 * laser into a long-distance beam. Inherits the multipart pipe-stub
 * blockstate, the per-face `pipe_*` adjacency gating, and the placement
 * flow (right-click a Pipe to swap, shift+right-click to place adjacent).
 *
 * Differences from regular Node: a 10×10×10 core hitbox (vs 8×8×8) so the
 * lens elements jutting out of the centre have something to collide with,
 * and a separate BE that persists laser-link connections.
 */
class FocusNodeBlock(properties: Properties) : NodeBlock(properties) {

    companion object {
        val FOCUS_CODEC: MapCodec<FocusNodeBlock> = simpleCodec(::FocusNodeBlock)

        /** 10×10×10 centred cube (pixels 3..13). Wider than the regular
         *  Node's 8×8×8 so the lens protrusions on each face have collision
         *  coverage and the body looks deliberately bigger when next to a
         *  regular Node. */
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
