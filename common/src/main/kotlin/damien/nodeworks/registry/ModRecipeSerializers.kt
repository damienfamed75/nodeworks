package damien.nodeworks.registry

import damien.nodeworks.recipe.SoulSandInfusionRecipe
import net.minecraft.core.Registry
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.world.item.crafting.RecipeSerializer

// Registry object for Nodeworks' custom RecipeSerializers.
//
// A RecipeSerializer in 26.1 is a record pairing a MapCodec (used to read
// JSON recipe files) with a StreamCodec (used to sync recipes from server
// to client). No subclassing, you construct an instance directly from the
// recipe class's own codecs.
//
// Must register AFTER ModRecipeTypes since the serializer references a
// recipe class that references its type via getType(). Both get bootstrapped
// on the block-registry RegisterEvent.
object ModRecipeSerializers {

    lateinit var SOUL_SAND_INFUSION: RecipeSerializer<SoulSandInfusionRecipe>
        private set

    fun initialize() {
        SOUL_SAND_INFUSION = register(
            "soul_sand_infusion",
            RecipeSerializer(SoulSandInfusionRecipe.CODEC, SoulSandInfusionRecipe.STREAM_CODEC),
        )
    }

    private fun <T : net.minecraft.world.item.crafting.Recipe<*>> register(
        id: String,
        serializer: RecipeSerializer<T>,
    ): RecipeSerializer<T> {
        val identifier = Identifier.fromNamespaceAndPath("nodeworks", id)
        return Registry.register(BuiltInRegistries.RECIPE_SERIALIZER, identifier, serializer)
    }
}
