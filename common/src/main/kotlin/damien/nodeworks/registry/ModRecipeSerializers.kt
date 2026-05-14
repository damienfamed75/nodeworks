package damien.nodeworks.registry

import damien.nodeworks.recipe.CoveredPipeRecipe
import damien.nodeworks.recipe.SoulSandInfusionRecipe
import damien.nodeworks.recipe.WipeConfigRecipe
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

    /** Dynamic shaped recipe for the Covered Vacuum Pipe. 8 Vacuum Pipes
     *  surround any full-block item in a 3×3 grid → 8 Covered Pipes
     *  stamped with the centre block as their camo. Shape is hard-coded
     *  in [CoveredPipeRecipe.matches]; the JSON entry only carries the
     *  recipe-book category. */
    lateinit var COVERED_PIPE_CRAFTING: RecipeSerializer<CoveredPipeRecipe>
        private set

    /** Wipes saved configuration off a card / set / terminal. One serializer,
     *  one JSON per supported item, each JSON declares the target via the
     *  `item` field. Matches only when the stack carries a non-null
     *  CUSTOM_DATA or BLOCK_ENTITY_DATA component, so blank items don't
     *  show a useless self-recipe in JEI. */
    lateinit var WIPE_CONFIG: RecipeSerializer<WipeConfigRecipe>
        private set

    fun initialize() {
        SOUL_SAND_INFUSION = register(
            "soul_sand_infusion",
            RecipeSerializer(SoulSandInfusionRecipe.CODEC, SoulSandInfusionRecipe.STREAM_CODEC),
        )
        COVERED_PIPE_CRAFTING = register(
            "covered_pipe_crafting",
            RecipeSerializer(CoveredPipeRecipe.CODEC, CoveredPipeRecipe.STREAM_CODEC),
        )
        WIPE_CONFIG = register(
            "wipe_config",
            RecipeSerializer(WipeConfigRecipe.CODEC, WipeConfigRecipe.STREAM_CODEC),
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
