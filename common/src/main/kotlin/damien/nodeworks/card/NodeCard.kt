package damien.nodeworks.card

import damien.nodeworks.block.NodeBlock
import net.minecraft.world.InteractionResult
import net.minecraft.world.item.Item
import net.minecraft.world.item.context.UseOnContext

/**
 * Base class for all node connection cards. Cards go into slot 0 of a node's side
 * to register the adjacent block as an accessible capability on the network.
 *
 * Subclass for each capability type (inventory, energy, fluid, etc.).
 */
abstract class NodeCard(properties: Properties) : Item(properties) {

    /**
     * The capability type identifier used by the scripting system to filter cards.
     */
    abstract val cardType: String

    /** Quick-place into a Node's empty slot when the card is right-clicked on
     *  one. Vanilla bypasses [NodeBlock.useItemOn] when the player crouches
     *  with an item in hand (sneaking routes straight to item.useOn), so the
     *  shift+right-click "place on opposite face" path has to live on the
     *  item side. Both paths funnel through [NodeBlock.tryQuickPlaceCard], so
     *  the shift-flip and item-frame sound stay consistent.
     *
     *  Returns PASS for non-Node targets, which lets each subclass's [use]
     *  open its settings GUI as before when right-clicked elsewhere.
     */
    override fun useOn(context: UseOnContext): InteractionResult {
        val state = context.level.getBlockState(context.clickedPos)
        if (state.block !is NodeBlock) return InteractionResult.PASS
        val player = context.player ?: return InteractionResult.PASS
        return NodeBlock.tryQuickPlaceCard(
            context.itemInHand,
            context.level,
            context.clickedPos,
            player,
            context.clickedFace,
        )
    }
}
