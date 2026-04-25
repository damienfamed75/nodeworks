package damien.nodeworks.card

import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level

/**
 * Observer Card — reads the block at the adjacent position and exposes its id and
 * state property table to scripts. Drives `block()`, `state()`, and `onChange()`
 * in the scripting API; the change callback is poll-based (one read per registered
 * card per tick) and fires when the block id or any property differs from the
 * previously seen state.
 */
class ObserverCard(properties: Properties) : NodeCard(properties) {
    override val cardType: String = "observer"

    override fun use(level: Level, player: Player, hand: InteractionHand): InteractionResult {
        return openCardSettings(level, player, hand)
    }
}
