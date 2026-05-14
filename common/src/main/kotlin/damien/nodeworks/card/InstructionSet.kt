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
        tryResetConfig(level, player, hand)?.let { return it }
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
        // Text lines were per-item names; the [getTooltipImage] icon strip
        // replaces them. Nothing else lives here right now.
    }

    /** Provides the recipe-icon grid for the tooltip. Preserves the 3×3 slot
     *  layout so the recipe reads positionally (a 2×2 of planks shows up
     *  in the top-left quadrant) and uses the persisted output count so a
     *  recipe yielding 3 doors shows the correct "3" badge. Returns absent
     *  when the card hasn't been configured. */
    override fun getTooltipImage(stack: ItemStack): java.util.Optional<net.minecraft.world.inventory.tooltip.TooltipComponent> {
        val recipe = getRecipe(stack)
        val inputs = recipe.map { id ->
            if (id.isEmpty()) ItemStack.EMPTY
            else Identifier.tryParse(id)
                ?.let { BuiltInRegistries.ITEM.getValue(it) }
                ?.let { ItemStack(it) }
                ?: ItemStack.EMPTY
        }
        val outputId = getOutput(stack)
        val outputStack = if (outputId.isNotEmpty()) {
            Identifier.tryParse(outputId)
                ?.let { BuiltInRegistries.ITEM.getValue(it) }
                ?.let { ItemStack(it, getOutputCount(stack)) }
        } else null
        val anyInput = inputs.any { !it.isEmpty }
        if (!anyInput && outputStack == null) return java.util.Optional.empty()
        val outputs = if (outputStack != null) listOf(outputStack) else emptyList()
        return java.util.Optional.of(damien.nodeworks.screen.tooltip.RecipeIconTooltip(inputs, outputs))
    }

    companion object {
        private const val RECIPE_KEY = "recipe"
        private const val OUTPUT_KEY = "output"
        private const val OUTPUT_COUNT_KEY = "outputCount"
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

        /** Per-craft output count resolved when the recipe was saved. Defaults
         *  to 1 for cards saved before the count was persisted, which matches
         *  the previous render where every recipe rendered as a 1-count
         *  output regardless of the underlying crafting yield. */
        fun getOutputCount(stack: ItemStack): Int {
            val customData = stack.get(DataComponents.CUSTOM_DATA) ?: return 1
            return customData.copyTag().getIntOr(OUTPUT_COUNT_KEY, 1).coerceIn(1, 99)
        }

        /** Whether the instruction set should accept tag substitutions for its
         *  ingredients (e.g. any plank in `#minecraft:planks` for a chest
         *  recipe). Defaults to true, so a fresh card and any older saved card
         *  without the field both behave as substitution-enabled. */
        fun getSubstitutions(stack: ItemStack): Boolean {
            val customData = stack.get(DataComponents.CUSTOM_DATA) ?: return true
            return customData.copyTag().getBooleanOr(SUBSTITUTIONS_KEY, true)
        }

        fun setRecipe(
            stack: ItemStack,
            recipe: List<String>,
            output: String = "",
            outputCount: Int = 1,
            allowSubstitutions: Boolean = true,
        ) {
            require(recipe.size == 9)
            val tag = CompoundTag()
            val list = ListTag()
            for (itemId in recipe) {
                list.add(StringTag.valueOf(itemId))
            }
            tag.put(RECIPE_KEY, list)
            tag.putString(OUTPUT_KEY, output)
            tag.putInt(OUTPUT_COUNT_KEY, outputCount.coerceIn(1, 99))
            tag.putBoolean(SUBSTITUTIONS_KEY, allowSubstitutions)
            stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag))
        }
    }
}
