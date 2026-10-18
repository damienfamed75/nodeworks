package damien.nodeworks.item

import damien.nodeworks.block.entity.ExportChestBlockEntity
import damien.nodeworks.block.entity.ImportChestBlockEntity
import damien.nodeworks.network.ChannelFilter
import net.minecraft.ChatFormatting
import net.minecraft.core.component.DataComponents
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag
import net.minecraft.world.item.component.TooltipDisplay
import net.minecraft.world.level.block.Block
import java.util.function.Consumer

/**
 * BlockItem for the Import and Export Chests. A chest broken after it was configured
 * carries its settings in `BLOCK_ENTITY_DATA` (see the blocks' `getDrops`), and this
 * item lists them on hover so a configured chest is told apart from a fresh one.
 */
class ChestBlockItem(
    block: Block,
    properties: Properties,
    private val describe: (CompoundTag) -> List<String>,
) : BlockItem(block, properties) {

    override fun appendHoverText(
        stack: ItemStack,
        context: TooltipContext,
        display: TooltipDisplay,
        builder: Consumer<Component>,
        tooltipFlag: TooltipFlag,
    ) {
        super.appendHoverText(stack, context, display, builder, tooltipFlag)
        val data = stack.get(DataComponents.BLOCK_ENTITY_DATA) ?: return
        val lines = describe(data.copyTagWithoutId())
        if (lines.isEmpty()) return
        builder.accept(Component.literal("Configured").withStyle(ChatFormatting.AQUA))
        for (line in lines) {
            builder.accept(Component.literal("  $line").withStyle(ChatFormatting.DARK_AQUA))
        }
    }

    companion object {
        private fun channelLine(tag: CompoundTag): String? {
            val channel = ChannelFilter.fromNbtInt(tag.getIntOr("channel", -1))
            return (channel as? ChannelFilter.Color)?.let { "Channel: ${it.color.getName().replace('_', ' ')}" }
        }

        private fun redstoneLine(tag: CompoundTag): String? = when (tag.getIntOr("redstoneMode", 0)) {
            ExportChestBlockEntity.REDSTONE_HIGH -> "Redstone: high"
            ExportChestBlockEntity.REDSTONE_LOW -> "Redstone: low"
            else -> null
        }

        private fun intervalLine(tag: CompoundTag, default: Int): String? {
            val interval = tag.getIntOr("tickInterval", default)
            return if (interval != default) "Every $interval ticks" else null
        }

        fun describeExport(tag: CompoundTag): List<String> {
            val rules = tag.getStringOr("filterRules", "").split("\n").filter { it.isNotEmpty() }
            return listOfNotNull(
                when (rules.size) {
                    0 -> null
                    1 -> "Filter: ${rules[0]}"
                    else -> "${rules.size} filter rules"
                },
                channelLine(tag),
                tag.getStringOr("pushFace", "").takeIf { it.isNotEmpty() }?.let { "Pushes ${it.lowercase()}" },
                redstoneLine(tag),
                intervalLine(tag, ExportChestBlockEntity.DEFAULT_TICK_INTERVAL),
            )
        }

        fun describeImport(tag: CompoundTag): List<String> = listOfNotNull(
            channelLine(tag),
            if (tag.getBooleanOr("roundRobin", false)) "Round robin" else null,
            redstoneLine(tag),
            intervalLine(tag, ImportChestBlockEntity.DEFAULT_TICK_INTERVAL),
        )
    }
}
