package damien.nodeworks.card

import damien.nodeworks.platform.PlatformServices
import damien.nodeworks.screen.StorageCardMenu
import damien.nodeworks.screen.StorageCardOpenData
import net.minecraft.ChatFormatting
import net.minecraft.core.component.DataComponents
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag
import net.minecraft.world.item.component.CustomData
import net.minecraft.world.item.component.TooltipDisplay
import net.minecraft.world.level.Level
import java.util.function.Consumer

/**
 * Storage Card, registers an adjacent container as passive network storage.
 * Items in storage-card inventories are discoverable by the entire network
 * and available for crafting via Recipe Cards.
 *
 * Right-click in air to set priority (0-99).
 */
class StorageCard(properties: Properties) : NodeCard(properties) {
    override val cardType: String = "storage"

    override fun use(level: Level, player: Player, hand: InteractionHand): InteractionResult {
        if (level.isClientSide) return InteractionResult.SUCCESS

        val serverPlayer = player as ServerPlayer
        PlatformServices.menu.openExtendedMenu(
            serverPlayer,
            Component.translatable("container.nodeworks.storage_card"),
            StorageCardOpenData(hand.ordinal),
            StorageCardOpenData.STREAM_CODEC,
            { syncId, inv, _ -> StorageCardMenu(syncId, inv, hand) }
        )
        return InteractionResult.CONSUME
    }

    override fun appendHoverText(stack: ItemStack, context: TooltipContext, display: TooltipDisplay, tooltip: Consumer<Component>, flag: TooltipFlag) {
        super.appendHoverText(stack, context, display, tooltip, flag)
        val priority = getPriority(stack)
        tooltip.accept(Component.literal("Priority: $priority").withStyle(ChatFormatting.GRAY))
    }

    companion object {
        fun getPriority(stack: ItemStack): Int {
            val customData = stack.get(DataComponents.CUSTOM_DATA) ?: return 0
            return customData.copyTag().getIntOr("priority", 0)
        }

        fun setPriority(stack: ItemStack, priority: Int) {
            val clamped = priority.coerceIn(0, 999)
            // Read-modify-write so we don't clobber sibling keys like the channel
            // color set via [CardChannel.set]. Pre-channel this method always wrote
            // a fresh single-key tag, which is fine when nothing else lives in
            // CUSTOM_DATA, now we merge.
            val tag = stack.get(DataComponents.CUSTOM_DATA)?.copyTag() ?: CompoundTag()
            tag.putInt("priority", clamped)
            stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag))
        }
    }
}
