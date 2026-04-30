package damien.nodeworks.card

import damien.nodeworks.screen.InstructionSetOpenData
import damien.nodeworks.screen.InstructionSetScreenHandler
import damien.nodeworks.platform.PlatformServices
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.StringTag
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag
import net.minecraft.world.item.component.CustomData
import net.minecraft.world.item.component.TooltipDisplay
import net.minecraft.world.level.Level
import java.util.function.Consumer

/**
 * Instruction Set, stores a 3x3 crafting grid template.
 * Right-click while holding to open the recipe editor.
 * Not a NodeCard, cannot be placed in node slots.
 */
class InstructionSet(properties: Properties) : Item(properties) {

    override fun use(level: Level, player: Player, hand: InteractionHand): InteractionResult {
        if (level.isClientSide) return InteractionResult.SUCCESS

        val stack = player.getItemInHand(hand)
        val recipe = getRecipe(stack)
        val subs = getSubstitutions(stack)
        val serverPlayer = player as ServerPlayer

        PlatformServices.menu.openExtendedMenu(
            serverPlayer,
            Component.translatable("container.nodeworks.instruction_set"),
            InstructionSetOpenData(BlockPos.ZERO, -1, -1, recipe, subs),
            InstructionSetOpenData.STREAM_CODEC,
            { syncId, inv, p -> InstructionSetScreenHandler.createHandheld(syncId, inv, hand, stack) }
        )

        return InteractionResult.CONSUME
    }

    override fun appendHoverText(stack: ItemStack, context: Item.TooltipContext, display: TooltipDisplay, tooltip: Consumer<Component>, flag: TooltipFlag) {
        super.appendHoverText(stack, context, display, tooltip, flag)
        val recipe = getRecipe(stack)
        val ingredients = recipe.filter { it.isNotEmpty() }.mapNotNull { id ->
            val identifier = Identifier.tryParse(id) ?: return@mapNotNull null
            BuiltInRegistries.ITEM.getValue(identifier)
        }.distinct()

        if (ingredients.isNotEmpty()) {
            tooltip.accept(Component.translatable("tooltip.nodeworks.instruction_set.input")
                .withStyle(ChatFormatting.GRAY))
            for (item in ingredients) {
                tooltip.accept(Component.literal("  ").append(Component.translatable(item.descriptionId)).withStyle(ChatFormatting.DARK_GRAY))
            }

            val outputId = getOutput(stack)
            if (outputId.isNotEmpty()) {
                val outputIdentifier = Identifier.tryParse(outputId)
                if (outputIdentifier != null) {
                    val outputItem = BuiltInRegistries.ITEM.getValue(outputIdentifier)
                    if (outputItem != null) {
                        tooltip.accept(Component.translatable("tooltip.nodeworks.instruction_set.output")
                            .withStyle(ChatFormatting.GRAY))
                        tooltip.accept(Component.literal("  ").append(Component.translatable(outputItem.descriptionId)).withStyle(ChatFormatting.DARK_GRAY))
                    }
                }
            }
        }
    }

    companion object {
        private const val RECIPE_KEY = "recipe"
        private const val OUTPUT_KEY = "output"
        private const val SUBSTITUTIONS_KEY = "allowSubstitutions"

        fun getRecipe(stack: ItemStack): List<String> {
            val customData = stack.get(DataComponents.CUSTOM_DATA) ?: return List(9) { "" }
            val tag = customData.copyTag()
            val list = tag.getList(RECIPE_KEY).orElse(null) ?: return List(9) { "" }
            if (list.size != 9) return List(9) { "" }
            return (0 until 9).map { list.getStringOr(it, "") }
        }

        fun getOutput(stack: ItemStack): String {
            val customData = stack.get(DataComponents.CUSTOM_DATA) ?: return ""
            return customData.copyTag().getStringOr(OUTPUT_KEY, "")
        }

        /** Whether the instruction set should accept tag substitutions for its
         *  ingredients (e.g. any plank in `#minecraft:planks` for a chest
         *  recipe). Defaults to true, so a fresh card and any older saved card
         *  without the field both behave as substitution-enabled. */
        fun getSubstitutions(stack: ItemStack): Boolean {
            val customData = stack.get(DataComponents.CUSTOM_DATA) ?: return true
            return customData.copyTag().getBooleanOr(SUBSTITUTIONS_KEY, true)
        }

        fun setRecipe(stack: ItemStack, recipe: List<String>, output: String = "", allowSubstitutions: Boolean = true) {
            require(recipe.size == 9)
            val tag = CompoundTag()
            val list = ListTag()
            for (itemId in recipe) {
                list.add(StringTag.valueOf(itemId))
            }
            tag.put(RECIPE_KEY, list)
            tag.putString(OUTPUT_KEY, output)
            tag.putBoolean(SUBSTITUTIONS_KEY, allowSubstitutions)
            stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag))
        }
    }
}
