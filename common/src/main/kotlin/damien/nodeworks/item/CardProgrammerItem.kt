package damien.nodeworks.item

import damien.nodeworks.card.NodeCard
import damien.nodeworks.platform.PlatformServices
import damien.nodeworks.screen.CardProgrammerMenu
import damien.nodeworks.screen.CardProgrammerOpenData
import net.minecraft.core.component.DataComponents
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResultHolder
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.CustomData
import net.minecraft.world.item.component.ItemContainerContents
import net.minecraft.world.level.Level

class CardProgrammerItem(properties: Properties) : Item(properties) {

    override fun use(level: Level, player: Player, hand: InteractionHand): InteractionResultHolder<ItemStack> {
        val stack = player.getItemInHand(hand)
        if (level.isClientSide) return InteractionResultHolder.success(stack)

        val serverPlayer = player as ServerPlayer
        PlatformServices.menu.openExtendedMenu(
            serverPlayer,
            Component.translatable("container.nodeworks.card_programmer"),
            CardProgrammerOpenData(hand.ordinal),
            CardProgrammerOpenData.STREAM_CODEC,
            { syncId, inv, _ -> CardProgrammerMenu(syncId, inv, hand) }
        )
        return InteractionResultHolder.consume(stack)
    }

    companion object {
        fun getTemplate(stack: ItemStack): ItemStack {
            val contents = stack.get(DataComponents.CONTAINER) ?: return ItemStack.EMPTY
            val items = mutableListOf<ItemStack>()
            contents.stream().forEach { items.add(it) }
            return items.firstOrNull() ?: ItemStack.EMPTY
        }

        fun setTemplate(stack: ItemStack, template: ItemStack) {
            if (template.isEmpty) {
                stack.remove(DataComponents.CONTAINER)
            } else {
                stack.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(listOf(template)))
            }
        }

        fun getCounter(stack: ItemStack): Int {
            val data = stack.get(DataComponents.CUSTOM_DATA) ?: return 0
            return data.copyTag().getInt("counter")
        }

        fun setCounter(stack: ItemStack, counter: Int) {
            val tag = CompoundTag()
            tag.putInt("counter", counter.coerceAtLeast(0))
            stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag))
        }

        fun getTemplateCardColor(stack: ItemStack): Int {
            val template = getTemplate(stack)
            if (template.isEmpty) return -1
            val card = template.item as? NodeCard ?: return -1
            return when (card.cardType) {
                "storage" -> 0x8B5CF6   // purple
                "io" -> 0x22C55E        // green
                "redstone" -> 0xEF4444  // red
                "inventory" -> 0x3B82F6 // blue
                else -> -1
            }
        }
    }
}
