package damien.nodeworks.registry

import damien.nodeworks.recipe.SoulSandInfusionRecipe
import net.minecraft.core.Registry
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.world.item.crafting.RecipeType

/**
 * Registry object for Nodeworks' custom [RecipeType]s.
 *
 * A [RecipeType] is the discriminator used by the recipe manager to index
 * recipes — `recipeManager.getRecipeFor(type, input, level)` only scans the
 * entries registered under the given type. Each distinct crafting mechanic
 * that isn't a standard shaped/shapeless crafting gets its own type here.
 *
 * Registration lives on the block-registry RegisterEvent window alongside the
 * other ModX registries — NeoForge's [net.neoforged.neoforge.registries.RegisterEvent]
 * permits cross-registry writes during any firing of the event, so we chain
 * recipe-type + recipe-serializer registration in with blocks/items.
 */
object ModRecipeTypes {

    /**
     * Right-click-on-soul-sand interaction recipes. Each registered entry
     * declares "hold X, right-click soul sand → drop Y." The gameplay handler
     * in `SoulSandInteraction` queries this type to find the matching recipe
     * for the held stack; JEI and GuideME read the same set.
     */
    lateinit var SOUL_SAND_INFUSION: RecipeType<SoulSandInfusionRecipe>
        private set

    fun initialize() {
        SOUL_SAND_INFUSION = register("soul_sand_infusion")
    }

    /**
     * Create a [RecipeType] keyed by `nodeworks:<id>`. The [RecipeType] class
     * is essentially opaque — it's a marker object whose only job is to be an
     * identity for recipe-manager indexing. Overriding `toString` gives useful
     * debug output (e.g. in a stack trace) without reaching for reflection.
     */
    private fun <T : net.minecraft.world.item.crafting.Recipe<*>> register(id: String): RecipeType<T> {
        val identifier = Identifier.fromNamespaceAndPath("nodeworks", id)
        val type = object : RecipeType<T> {
            override fun toString(): String = identifier.toString()
        }
        return Registry.register(BuiltInRegistries.RECIPE_TYPE, identifier, type)
    }
}
