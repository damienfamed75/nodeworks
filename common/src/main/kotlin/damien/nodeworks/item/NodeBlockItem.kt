package damien.nodeworks.item

import damien.nodeworks.block.NodeBlock
import damien.nodeworks.block.PipeBlock
import damien.nodeworks.network.NodeConnectionHelper
import damien.nodeworks.registry.ModBlocks
import net.minecraft.world.InteractionResult
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.context.UseOnContext
import net.minecraft.world.level.block.Block

/**
 * BlockItem for the Node block. Plain right-click on a Pipe swaps it
 * in-place for a Node (preserving connection booleans), shift+right-click
 * on a Pipe or right-click on anything else falls through to vanilla's
 * standard adjacent-placement flow.
 */
class NodeBlockItem(block: Block, properties: Properties) : BlockItem(block, properties) {

    override fun useOn(context: UseOnContext): InteractionResult {
        val level = context.level
        val pos = context.clickedPos
        val state = level.getBlockState(pos)
        val player = context.player

        val swapPath = state.block is PipeBlock && player != null && !player.isShiftKeyDown
        if (!swapPath) return super.useOn(context)

        if (level.isClientSide) return InteractionResult.SUCCESS

        // Computing state via BlockPlaceContext.getStateForPlacement would
        // query neighbours of `clickedPos.relative(clickedFace)` (the offset
        // position used for non-replaceable blocks), not the pipe's own pos.
        val nodeState = NodeBlock.rebuildState(level, pos, block.defaultBlockState())

        // UPDATE_ALL ensures the old PipeBlockEntity detaches and the new
        // NodeBlockEntity gets a fresh networkId for the propagate below.
        level.setBlock(pos, nodeState, Block.UPDATE_ALL)
        if (!player!!.abilities.instabuild) context.itemInHand.shrink(1)

        if (level is net.minecraft.server.level.ServerLevel) {
            NodeConnectionHelper.propagateNetworkId(level, pos)
        }

        // Refund the displaced Pipe to the player's inventory, falling back to
        // a tossed item entity when there's no room. Skipped in creative since
        // the source item didn't decrement either.
        if (!player.abilities.instabuild) {
            val pipeStack = ItemStack(ModBlocks.PIPE.asItem(), 1)
            if (!player.inventory.add(pipeStack)) {
                player.drop(pipeStack, false)
            }
        }

        val placeSound = nodeState.soundType.placeSound
        level.playSound(null, pos, placeSound, net.minecraft.sounds.SoundSource.BLOCKS, 1.0f, 1.0f)
        return InteractionResult.SUCCESS
    }
}
