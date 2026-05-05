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
     *  one, or shift+right-clicked on a block adjacent to one. Vanilla
     *  bypasses [NodeBlock.useItemOn] when the player crouches with an item in
     *  hand (sneaking routes straight to item.useOn), so both the
     *  shift+opposite-face path and the shift+adjacent-block path live here.
     *  Resolution is delegated to [NodeBlock.resolvePlacementTarget] so the
     *  preview renderer and the click handler can't disagree about where the
     *  card will land.
     *
     *  Returns PASS for non-Node, non-adjacent targets, which lets each
     *  subclass's [use] open its settings GUI / lets vanilla open the clicked
     *  block normally. */
    override fun useOn(context: UseOnContext): InteractionResult {
        val player = context.player ?: return InteractionResult.PASS
        val target = NodeBlock.resolvePlacementTarget(
            context.level,
            context.clickedPos,
            context.clickedFace,
            player.isShiftKeyDown,
        ) ?: return InteractionResult.PASS
        return NodeBlock.tryQuickPlaceCard(
            context.itemInHand,
            context.level,
            target,
            player,
        )
    }
}
