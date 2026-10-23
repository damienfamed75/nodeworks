package damien.nodeworks.item

import damien.nodeworks.registry.ModDataComponents
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag
import net.minecraft.world.item.component.TooltipDisplay
import java.util.function.Consumer

/**
 * A program as an item. Written by the Portable Scripting Terminal, run by a Script
 * Runner. Without a [ScriptProgram] it is a Blank Script, the only thing "Save as new"
 * writes onto, so a saved script is never clobbered by accident.
 */
class ScriptItem(properties: Properties) : Item(properties) {

    override fun getName(stack: ItemStack): Component {
        val program = program(stack) ?: return super.getName(stack)
        return Component.translatable("item.nodeworks.script.named", program.name)
    }

    override fun appendHoverText(
        stack: ItemStack,
        context: TooltipContext,
        display: TooltipDisplay,
        tooltip: Consumer<Component>,
        flag: TooltipFlag,
    ) {
        val program = program(stack)
        if (program == null) {
            tooltip.accept(Component.literal("Save a script onto it from a Portable Scripting Terminal.").withStyle(ChatFormatting.DARK_GRAY))
            return
        }
        val tabs = program.tabs.size
        tooltip.accept(
            Component.literal("$tabs ${if (tabs == 1) "tab" else "tabs"}, ${program.totalChars} characters")
                .withStyle(ChatFormatting.GRAY),
        )
        if (program.authorName.isNotEmpty()) {
            tooltip.accept(Component.literal("by ${program.authorName}").withStyle(ChatFormatting.DARK_GRAY))
        }
    }

    companion object {
        fun program(stack: ItemStack): ScriptProgram? =
            if (!stack.isEmpty && stack.item is ScriptItem) stack.get(ModDataComponents.SCRIPT_PROGRAM) else null

        fun isBlank(stack: ItemStack): Boolean = !stack.isEmpty && stack.item is ScriptItem && program(stack) == null
    }
}
