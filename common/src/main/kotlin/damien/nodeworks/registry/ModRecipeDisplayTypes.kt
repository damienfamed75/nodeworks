package damien.nodeworks.registry

import damien.nodeworks.recipe.SoulSandInfusionRecipeDisplay
import net.minecraft.core.Registry
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.world.item.crafting.display.RecipeDisplay

// Registry object for Nodeworks' custom RecipeDisplay types.
//
// Every concrete RecipeDisplay implementation needs a RecipeDisplay.Type
// registered in Registries.RECIPE_DISPLAY so the vanilla dispatch codec
// (Recipe/RecipeDisplay serialization) can find its MapCodec + StreamCodec
// by registry name.
//
// Registration runs alongside ModRecipeTypes + ModRecipeSerializers during
// the block-registry RegisterEvent window.
object ModRecipeDisplayTypes {

    lateinit var SOUL_SAND_INFUSION: RecipeDisplay.Type<SoulSandInfusionRecipeDisplay>
        private set

    fun initialize() {
        SOUL_SAND_INFUSION = register(
            "soul_sand_infusion",
            RecipeDisplay.Type(
                SoulSandInfusionRecipeDisplay.MAP_CODEC,
                SoulSandInfusionRecipeDisplay.STREAM_CODEC,
            ),
        )
    }

    private fun <T : RecipeDisplay> register(
        id: String,
        type: RecipeDisplay.Type<T>,
    ): RecipeDisplay.Type<T> {
        val identifier = Identifier.fromNamespaceAndPath("nodeworks", id)
        return Registry.register(BuiltInRegistries.RECIPE_DISPLAY, identifier, type)
    }
}
