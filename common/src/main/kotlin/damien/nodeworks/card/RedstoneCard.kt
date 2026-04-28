package damien.nodeworks.card

import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level

/**
 * Redstone Card, reads and writes redstone signals on the adjacent block.
 * Provides powered(), strength(), set(), and onChange() in the scripting API.
 */
class RedstoneCard(properties: Properties) : NodeCard(properties) {
    override val cardType: String = "redstone"

    override fun use(level: Level, player: Player, hand: InteractionHand): InteractionResult {
        return openCardSettings(level, player, hand)
    }
}
