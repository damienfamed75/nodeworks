package damien.nodeworks.item

import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag
import net.minecraft.world.item.component.TooltipDisplay
import net.minecraft.world.level.block.Block
import java.util.function.Consumer

/**
 * BlockItem variant for the Terminal that inspects its saved `BLOCK_ENTITY_DATA`
 * component on hover and surfaces "Contains scripts" + per-script hints when the
 * terminal was broken after any script had been written.
 *
 * The server-side drop path ([damien.nodeworks.block.entity.TerminalBlockEntity.dropAsItem])
 * only attaches a BLOCK_ENTITY_DATA tag when at least one script is non-empty or
 * auto-run is enabled, so the mere presence of the component is a good signal.
 */
class TerminalBlockItem(block: Block, properties: Properties) : BlockItem(block, properties) {

    @Suppress("DEPRECATION")
    override fun appendHoverText(
        stack: ItemStack,
        context: TooltipContext,
        display: TooltipDisplay,
        builder: Consumer<Component>,
        tooltipFlag: TooltipFlag
    ) {
        super.appendHoverText(stack, context, display, builder, tooltipFlag)
        val typedEntityData = stack.get(DataComponents.BLOCK_ENTITY_DATA) ?: return
        // The Terminal's saveAdditional writes a "scripts" list with one child per
        // script, we peek at the tag to count non-empty ones without instantiating a
        // full BE. `copyTagWithoutId` is the non-deprecated read path in 26.1.
        val tag = typedEntityData.copyTagWithoutId()
        val scriptsList = tag.getList("scripts").orElse(null) ?: return
        var nonEmptyCount = 0
        val previewNames = mutableListOf<String>()
        for (i in 0 until scriptsList.size) {
            val child = scriptsList.getCompound(i).orElse(null) ?: continue
            val name = child.getString("name").orElse("")
            val textLen = child.getString("text").orElse("").length
            if (textLen > 0) {
                nonEmptyCount++
                if (previewNames.size < 3 && name.isNotEmpty()) previewNames.add(name)
            }
        }
        if (nonEmptyCount == 0) return

        val label = if (nonEmptyCount == 1) "Contains 1 script" else "Contains $nonEmptyCount scripts"
        builder.accept(Component.literal(label).withStyle(net.minecraft.ChatFormatting.AQUA))
        if (previewNames.isNotEmpty()) {
            val suffix = if (nonEmptyCount > previewNames.size) ", …" else ""
            builder.accept(
                Component.literal("  " + previewNames.joinToString(", ") + suffix)
                    .withStyle(net.minecraft.ChatFormatting.DARK_AQUA)
            )
        }
    }
}
