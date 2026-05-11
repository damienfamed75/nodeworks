package damien.nodeworks.item

import damien.nodeworks.registry.ModDataComponents
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag
import net.minecraft.world.item.component.TooltipDisplay
import net.minecraft.world.level.block.Block

/**
 * Item form of the Covered Vacuum Pipe. Carries the camouflage block state
 * via [ModDataComponents.CAMO_BLOCK_STATE] - distinct-camo stacks are
 * treated as distinct items by vanilla so a stack of "Covered Pipe (Stone)"
 * sits beside "Covered Pipe (Oak Planks)" without merging. The Block
 * subclass reads this component in `setPlacedBy` to seed the placed BE.
 *
 * Tooltip surfaces the camo block's name so the player can tell stacks
 * apart at a glance. The base block-item handles `useOn` / placement
 * flow itself, no override needed.
 */
class CoveredPipeBlockItem(block: Block, properties: Properties) : BlockItem(block, properties) {

    override fun appendHoverText(
        stack: ItemStack,
        context: Item.TooltipContext,
        display: TooltipDisplay,
        builder: java.util.function.Consumer<Component>,
        flag: TooltipFlag,
    ) {
        super.appendHoverText(stack, context, display, builder, flag)
        val camo = stack.get(ModDataComponents.CAMO_BLOCK_STATE) ?: return
        val name = camo.block.name.copy().withStyle(ChatFormatting.GRAY)
        builder.accept(
            Component.translatable("tooltip.nodeworks.covered_pipe.camo", name)
                .withStyle(ChatFormatting.DARK_GRAY),
        )
    }
}
