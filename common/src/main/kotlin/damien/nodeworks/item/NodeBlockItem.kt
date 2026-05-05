package damien.nodeworks.item

import damien.nodeworks.block.NodeBlock
import damien.nodeworks.block.PipeBlock
import damien.nodeworks.network.NodeConnectionHelper
import net.minecraft.world.InteractionResult
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.context.UseOnContext
import net.minecraft.world.level.block.Block

/**
 * BlockItem for the Node block. Two placement paths:
 *
 *  - **Right-click a [PipeBlock]**: swap the pipe in place for a Node, the
 *    Node inherits the pipe's connection booleans so the network stays
 *    intact through the substitution. This is the natural "upgrade pipe to
 *    node" flow.
 *  - **Shift+right-click a Pipe, or right-click anything else**: fall through
 *    to the standard [BlockItem.place] flow, which drops the Node at the
 *    adjacent position (or wherever vanilla decides). Lets players plant a
 *    standalone Node next to an existing pipe run without consuming it.
 */
class NodeBlockItem(block: Block, properties: Properties) : BlockItem(block, properties) {

    override fun useOn(context: UseOnContext): InteractionResult {
        val level = context.level
        val pos = context.clickedPos
        val state = level.getBlockState(pos)
        val player = context.player

        // Only the non-shift, click-on-pipe case triggers the in-place swap.
        // Shift override + non-pipe clicks both fall through to BlockItem's
        // default place flow.
        val swapPath = state.block is PipeBlock && player != null && !player.isShiftKeyDown
        if (!swapPath) return super.useOn(context)

        if (level.isClientSide) return InteractionResult.SUCCESS

        // Compute the Node state directly against the pipe's neighbours.
        // Going through BlockPlaceContext.getStateForPlacement would query
        // neighbours of `clickedPos.relative(clickedFace)` (the offset
        // placement position vanilla would use for non-replaceable blocks),
        // not of the pipe's actual position, so we'd see the wrong adjacency.
        val nodeState = NodeBlock.rebuildState(level, pos, block.defaultBlockState())

        // setBlock with UPDATE_ALL so the previous PipeBlockEntity is removed
        // (Block.setBlock detaches the BE on type change) and the new
        // NodeBlockEntity is created with a fresh networkId, the propagate
        // pass below repopulates it.
        level.setBlock(pos, nodeState, Block.UPDATE_ALL)
        if (!player!!.abilities.instabuild) context.itemInHand.shrink(1)

        if (level is net.minecraft.server.level.ServerLevel) {
            NodeConnectionHelper.propagateNetworkId(level, pos)
        }

        val placeSound = nodeState.soundType.placeSound
        level.playSound(null, pos, placeSound, net.minecraft.sounds.SoundSource.BLOCKS, 1.0f, 1.0f)
        return InteractionResult.SUCCESS
    }
}
