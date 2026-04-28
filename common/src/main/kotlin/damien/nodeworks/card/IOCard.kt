package damien.nodeworks.card

import damien.nodeworks.platform.PlatformServices
import damien.nodeworks.screen.CardSettingsOpenData
import damien.nodeworks.screen.CardSettingsMenu
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level

/**
 * IO Card, provides direct scriptable access to an adjacent block's inventory.
 * Works with any block exposing item storage: chests, barrels, hoppers, furnaces, modded machines.
 * The scripting system specifies which face of the target block to access at runtime.
 */
class IOCard(properties: Properties) : NodeCard(properties) {
    override val cardType: String = "io"

    override fun use(level: Level, player: Player, hand: InteractionHand): InteractionResult {
        return openCardSettings(level, player, hand)
    }
}

/**
 * Shared `use()` body for cards whose only GUI is the generic Card Settings (channel
 * picker). Mirrors [StorageCard.use] but routes to [CardSettingsMenu]. Lives at the
 * file level so each thin card class doesn't have to duplicate the open-extended-menu
 * boilerplate.
 */
internal fun openCardSettings(level: Level, player: Player, hand: InteractionHand): InteractionResult {
    if (level.isClientSide) return InteractionResult.SUCCESS
    val serverPlayer = player as ServerPlayer
    PlatformServices.menu.openExtendedMenu(
        serverPlayer,
        Component.translatable("container.nodeworks.card_settings"),
        CardSettingsOpenData(hand.ordinal),
        CardSettingsOpenData.STREAM_CODEC,
        { syncId, inv, _ -> CardSettingsMenu(syncId, inv, hand) },
    )
    return InteractionResult.CONSUME
}
