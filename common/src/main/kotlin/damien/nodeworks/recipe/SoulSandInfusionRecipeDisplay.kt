package damien.nodeworks.recipe

import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import damien.nodeworks.registry.ModRecipeDisplayTypes
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.world.item.crafting.display.RecipeDisplay
import net.minecraft.world.item.crafting.display.SlotDisplay

/**
 * Client-side display payload for a [SoulSandInfusionRecipe].
 *
 * Implementing [RecipeDisplay] (and registering this implementation's [Type]
 * under [net.minecraft.core.registries.Registries.RECIPE_DISPLAY]) is what
 * makes the recipe visible to things that filter by result — in particular
 * GuideME's `<RecipeFor id="...">` tag, whose compiler scans
 * `recipe.display()` and picks up any display whose `result()` item matches
 * the requested ID.
 *
 * Field names are suffixed with `Slot` to avoid Kotlin-level name collisions
 * with the [RecipeDisplay] interface's `result()` / `craftingStation()`
 * methods — a data-class property named `result` would clash with the
 * overridden `result()` method when we reference it with `::` in codecs.
 */
@JvmRecord
data class SoulSandInfusionRecipeDisplay(
    val heldSlot: SlotDisplay,
    val resultSlot: SlotDisplay,
    val stationSlot: SlotDisplay,
) : RecipeDisplay {

    override fun result(): SlotDisplay = resultSlot

    override fun craftingStation(): SlotDisplay = stationSlot

    override fun type(): RecipeDisplay.Type<out RecipeDisplay> = ModRecipeDisplayTypes.SOUL_SAND_INFUSION

    companion object {
        val MAP_CODEC: MapCodec<SoulSandInfusionRecipeDisplay> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                SlotDisplay.CODEC.fieldOf("held").forGetter(SoulSandInfusionRecipeDisplay::heldSlot),
                SlotDisplay.CODEC.fieldOf("result").forGetter(SoulSandInfusionRecipeDisplay::resultSlot),
                SlotDisplay.CODEC.fieldOf("station").forGetter(SoulSandInfusionRecipeDisplay::stationSlot),
            ).apply(instance, ::SoulSandInfusionRecipeDisplay)
        }

        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, SoulSandInfusionRecipeDisplay> =
            StreamCodec.composite(
                SlotDisplay.STREAM_CODEC, SoulSandInfusionRecipeDisplay::heldSlot,
                SlotDisplay.STREAM_CODEC, SoulSandInfusionRecipeDisplay::resultSlot,
                SlotDisplay.STREAM_CODEC, SoulSandInfusionRecipeDisplay::stationSlot,
                ::SoulSandInfusionRecipeDisplay,
            )
    }
}
