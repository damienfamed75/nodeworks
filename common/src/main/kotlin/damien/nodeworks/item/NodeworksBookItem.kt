package damien.nodeworks.item

import damien.nodeworks.platform.PlatformServices
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag
import net.minecraft.world.item.component.TooltipDisplay
import net.minecraft.world.level.Level
import java.util.function.Consumer

/**
 * Right-click opens the Nodeworks guidebook on the overview page. Client-only
 * action, the server-side path returns SUCCESS without doing any state work.
 */
class NodeworksBookItem(properties: Properties) : Item(properties) {

    override fun use(
        level: Level,
        player: Player,
        hand: InteractionHand,
    ): InteractionResult {
        // Page-turn ASMR. Played from the world so nearby observers hear it too,
        // matches how vanilla books feel when other players read them in front of you.
        level.playSound(
            null,
            player.x, player.y, player.z,
            SoundEvents.BOOK_PAGE_TURN,
            SoundSource.PLAYERS,
            1f, 1f,
        )
        if (level.isClientSide) {
            PlatformServices.guidebook.open(GUIDEBOOK_REF)
        }
        return InteractionResult.SUCCESS
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun appendHoverText(
        stack: ItemStack,
        context: TooltipContext,
        display: TooltipDisplay,
        tooltip: Consumer<Component>,
        flag: TooltipFlag,
    ) {
        tooltip.accept(
            Component.translatable("item.nodeworks.nodeworks_book.tooltip")
                .withStyle(ChatFormatting.GRAY)
        )
    }

    companion object {
        private const val GUIDEBOOK_REF = "nodeworks:index.md"
    }
}
