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
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResultHolder
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag
import net.minecraft.world.item.component.CustomData
import net.minecraft.world.level.Level

/**
 * Instruction Set — stores a 3x3 crafting grid template.
 * Right-click while holding to open the recipe editor.
 * Not a NodeCard — cannot be placed in node slots.
 */
class InstructionSet(properties: Properties) : Item(properties) {

    override fun use(level: Level, player: Player, hand: InteractionHand): InteractionResultHolder<ItemStack> {
        val stack = player.getItemInHand(hand)
        if (level.isClientSide) return InteractionResultHolder.success(stack)

        val recipe = getRecipe(stack)
        val serverPlayer = player as ServerPlayer

        PlatformServices.menu.openExtendedMenu(
            serverPlayer,
            Component.translatable("container.nodeworks.instruction_set"),
            InstructionSetOpenData(BlockPos.ZERO, -1, -1, recipe),
            InstructionSetOpenData.STREAM_CODEC,
            { syncId, inv, p -> InstructionSetScreenHandler.createHandheld(syncId, inv, hand, stack) }
        )

        return InteractionResultHolder.consume(stack)
    }

    override fun appendHoverText(stack: ItemStack, context: Item.TooltipContext, tooltip: MutableList<Component>, flag: TooltipFlag) {
        super.appendHoverText(stack, context, tooltip, flag)
        val recipe = getRecipe(stack)
        val ingredients = recipe.filter { it.isNotEmpty() }.mapNotNull { id ->
            val identifier = ResourceLocation.tryParse(id) ?: return@mapNotNull null
            BuiltInRegistries.ITEM.get(identifier)
        }.distinct()

        if (ingredients.isNotEmpty()) {
            tooltip.add(Component.translatable("tooltip.nodeworks.instruction_set.input")
                .withStyle(ChatFormatting.GRAY))
            for (item in ingredients) {
                tooltip.add(Component.literal("  ").append(item.description).withStyle(ChatFormatting.DARK_GRAY))
            }

            val outputId = getOutput(stack)
            if (outputId.isNotEmpty()) {
                val outputIdentifier = ResourceLocation.tryParse(outputId)
                if (outputIdentifier != null) {
                    val outputItem = BuiltInRegistries.ITEM.get(outputIdentifier)
                    if (outputItem != null) {
                        tooltip.add(Component.translatable("tooltip.nodeworks.instruction_set.output")
                            .withStyle(ChatFormatting.GRAY))
                        tooltip.add(Component.literal("  ").append(outputItem.description).withStyle(ChatFormatting.DARK_GRAY))
                    }
                }
            }
        }
    }

    companion object {
        private const val RECIPE_KEY = "recipe"
        private const val OUTPUT_KEY = "output"

        fun getRecipe(stack: ItemStack): List<String> {
            val customData = stack.get(DataComponents.CUSTOM_DATA) ?: return List(9) { "" }
            val tag = customData.copyTag()
            if (!tag.contains(RECIPE_KEY)) return List(9) { "" }
            val list = tag.getList(RECIPE_KEY, 8) // 8 = StringTag type
            if (list.size != 9) return List(9) { "" }
            return (0 until 9).map { list.getString(it) }
        }

        fun getOutput(stack: ItemStack): String {
            val customData = stack.get(DataComponents.CUSTOM_DATA) ?: return ""
            val tag = customData.copyTag()
            return if (tag.contains(OUTPUT_KEY)) tag.getString(OUTPUT_KEY) else ""
        }

        fun setRecipe(stack: ItemStack, recipe: List<String>, output: String = "") {
            require(recipe.size == 9)
            val tag = CompoundTag()
            val list = ListTag()
            for (itemId in recipe) {
                list.add(StringTag.valueOf(itemId))
            }
            tag.put(RECIPE_KEY, list)
            tag.putString(OUTPUT_KEY, output)
            stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag))
        }
    }
}
