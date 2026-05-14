package damien.nodeworks.recipe

import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import damien.nodeworks.registry.ModRecipeSerializers
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.crafting.CraftingBookCategory
import net.minecraft.world.item.crafting.CraftingInput
import net.minecraft.world.item.crafting.CustomRecipe
import net.minecraft.world.item.crafting.RecipeSerializer
import net.minecraft.world.level.Level

/**
 * Shapeless 1-input crafting recipe that strips configuration data off a
 * Nodeworks item. Each JSON instance declares the target [item] (a card,
 * set, or the Scripting Terminal); the recipe matches a single stack of
 * that item carrying either [DataComponents.CUSTOM_DATA] (cards / sets
 * with saved config) or [DataComponents.BLOCK_ENTITY_DATA] (Scripting
 * Terminal with saved scripts) and produces a bare copy with both stripped.
 *
 * Only matches when there's something to wipe so a blank item placed in
 * the grid doesn't show a phantom self-recipe in JEI.
 */
class WipeConfigRecipe(
    private val craftingCategory: CraftingBookCategory,
    private val item: Item,
) : CustomRecipe() {

    override fun matches(input: CraftingInput, level: Level): Boolean {
        var found = false
        for (i in 0 until input.size()) {
            val stack = input.getItem(i)
            if (stack.isEmpty) continue
            if (found) return false
            if (stack.item !== item) return false
            val hasCustomData = stack.get(DataComponents.CUSTOM_DATA) != null
            val hasBlockEntityData = stack.get(DataComponents.BLOCK_ENTITY_DATA) != null
            if (!hasCustomData && !hasBlockEntityData) return false
            found = true
        }
        return found
    }

    override fun assemble(input: CraftingInput): ItemStack = ItemStack(item)

    override fun category(): CraftingBookCategory = craftingCategory

    override fun getSerializer(): RecipeSerializer<out CustomRecipe> =
        ModRecipeSerializers.WIPE_CONFIG

    companion object {
        private val ITEM_CODEC = BuiltInRegistries.ITEM.byNameCodec()
        private val ITEM_STREAM_CODEC = ByteBufCodecs.registry(net.minecraft.core.registries.Registries.ITEM)

        val CODEC: MapCodec<WipeConfigRecipe> = RecordCodecBuilder.mapCodec { builder ->
            builder.group(
                CraftingBookCategory.CODEC.fieldOf("category")
                    .forGetter(WipeConfigRecipe::craftingCategory),
                ITEM_CODEC.fieldOf("item").forGetter(WipeConfigRecipe::item),
            ).apply(builder, ::WipeConfigRecipe)
        }

        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, WipeConfigRecipe> =
            StreamCodec.composite(
                CraftingBookCategory.STREAM_CODEC, WipeConfigRecipe::craftingCategory,
                ITEM_STREAM_CODEC, WipeConfigRecipe::item,
                ::WipeConfigRecipe,
            )
    }
}
