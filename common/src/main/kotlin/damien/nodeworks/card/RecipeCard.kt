package damien.nodeworks.card

import damien.nodeworks.screen.RecipeCardOpenData
import damien.nodeworks.screen.RecipeCardScreenHandler
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
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag
import net.minecraft.world.item.component.CustomData
import net.minecraft.world.item.component.TooltipDisplay
import net.minecraft.world.level.Level
import java.util.function.Consumer

/**
 * Recipe Card — stores a 3x3 crafting grid template.
 * Right-click while holding to open the recipe editor.
 */
class RecipeCard(properties: Properties) : NodeCard(properties) {
    override val cardType: String = "recipe"

    override fun use(level: Level, player: Player, hand: InteractionHand): InteractionResult {
        if (level.isClientSide) return InteractionResult.SUCCESS

        val stack = player.getItemInHand(hand)
        val recipe = getRecipe(stack)
        val serverPlayer = player as ServerPlayer

        PlatformServices.menu.openExtendedMenu(
            serverPlayer,
            Component.translatable("container.nodeworks.recipe_card"),
            RecipeCardOpenData(BlockPos.ZERO, -1, -1, recipe),
            RecipeCardOpenData.STREAM_CODEC,
            { syncId, inv, p -> RecipeCardScreenHandler.createHandheld(syncId, inv, hand, stack) }
        )

        return InteractionResult.SUCCESS
    }

    override fun appendHoverText(stack: ItemStack, context: Item.TooltipContext, display: TooltipDisplay, adder: Consumer<Component>, flag: TooltipFlag) {
        super.appendHoverText(stack, context, display, adder, flag)
        val recipe = getRecipe(stack)
        val ingredients = recipe.filter { it.isNotEmpty() }.mapNotNull { id ->
            val identifier = Identifier.tryParse(id) ?: return@mapNotNull null
            BuiltInRegistries.ITEM.getValue(identifier)
        }.distinct()

        if (ingredients.isNotEmpty()) {
            adder.accept(Component.translatable("tooltip.nodeworks.recipe_card.input")
                .withStyle(ChatFormatting.GRAY))
            for (item in ingredients) {
                adder.accept(Component.literal("  ").append(item.getName(ItemStack(item))).withStyle(ChatFormatting.DARK_GRAY))
            }

            val outputId = getOutput(stack)
            if (outputId.isNotEmpty()) {
                val outputIdentifier = Identifier.tryParse(outputId)
                if (outputIdentifier != null) {
                    val outputItem = BuiltInRegistries.ITEM.getValue(outputIdentifier)
                    if (outputItem != null) {
                        adder.accept(Component.translatable("tooltip.nodeworks.recipe_card.output")
                            .withStyle(ChatFormatting.GRAY))
                        adder.accept(Component.literal("  ").append(outputItem.getName(ItemStack(outputItem))).withStyle(ChatFormatting.DARK_GRAY))
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
            val listOpt = tag.getList(RECIPE_KEY)
            if (listOpt.isEmpty) return List(9) { "" }
            val list = listOpt.get()
            if (list.size != 9) return List(9) { "" }
            return (0 until 9).map { list.get(it).asString().orElse("") }
        }

        fun getOutput(stack: ItemStack): String {
            val customData = stack.get(DataComponents.CUSTOM_DATA) ?: return ""
            val tag = customData.copyTag()
            return tag.getString(OUTPUT_KEY).orElse("")
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
