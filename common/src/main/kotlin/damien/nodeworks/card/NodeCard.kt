package damien.nodeworks.card

import damien.nodeworks.block.NodeBlock
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.context.UseOnContext
import net.minecraft.world.level.Level

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

/**
 * Shift+right-click in the air resets a configured card / set back to a
 * blank state by stripping its [DataComponents.CUSTOM_DATA] component.
 * Items without any saved config aren't touched (no audible click, no
 * interaction consumed) so a fresh card retains its open-GUI behaviour.
 *
 * Returns a non-null [InteractionResult] when the reset path handled the
 * click. Callers `use()` should early-return on a non-null result and fall
 * through to their GUI-open flow otherwise.
 */
internal fun tryResetConfig(level: Level, player: Player, hand: InteractionHand): InteractionResult? {
    if (!player.isShiftKeyDown) return null
    val stack = player.getItemInHand(hand)
    if (stack.get(DataComponents.CUSTOM_DATA) == null) return null
    if (level.isClientSide) return InteractionResult.SUCCESS
    stack.remove(DataComponents.CUSTOM_DATA)
    level.playSound(
        null, player.x, player.y, player.z,
        SoundEvents.ITEM_FRAME_REMOVE_ITEM, SoundSource.PLAYERS, 0.6f, 1.2f,
    )
    (player as? net.minecraft.server.level.ServerPlayer)?.sendSystemMessage(
        Component.translatable("message.nodeworks.card_reset"), true,
    )
    return InteractionResult.CONSUME
}
