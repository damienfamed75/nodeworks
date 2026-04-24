package damien.nodeworks.recipe

import damien.nodeworks.registry.ModRecipeTypes
import net.minecraft.world.item.crafting.RecipeHolder
import net.minecraft.world.item.crafting.RecipeMap

/**
 * Client-side cache of [SoulSandInfusionRecipe]s synced from the server.
 *
 * Vanilla 26.1 stopped syncing the full recipe set to clients — the default
 * [net.minecraft.client.multiplayer.ClientRecipeContainer] only tracks display
 * sets for things like smelting and stonecutter. NeoForge keeps the old
 * behavior alive by firing [net.neoforged.neoforge.client.event.RecipesReceivedEvent]
 * with the full [RecipeMap]; we listen for that event and pull our own type's
 * entries into this cache.
 *
 * Readers: [damien.nodeworks.integration.jei.NodeworksJeiPlugin] when JEI
 * rebuilds its recipe indexes. Anything else that needs client-side access to
 * these recipes (a future GuideME fallback, a custom UI, etc.) can call
 * [recipes] without re-subscribing to the event.
 *
 * The cache is cleared on client disconnect so switching worlds/servers
 * doesn't show stale entries.
 */
object SoulSandInfusionClientCache {

    @Volatile
    private var cached: List<RecipeHolder<SoulSandInfusionRecipe>> = emptyList()

    /** Repopulate from a freshly-received [RecipeMap]. Idempotent; safe to
     *  call on every [net.neoforged.neoforge.client.event.RecipesReceivedEvent]. */
    fun refresh(map: RecipeMap) {
        cached = map.byType(ModRecipeTypes.SOUL_SAND_INFUSION).toList()
    }

    /** Drop all cached recipes. Invoked on player disconnect. */
    fun clear() {
        cached = emptyList()
    }

    /** Read-only view of the current recipe set. Callers should not assume
     *  stability across ticks — the list is replaced wholesale on refresh. */
    fun recipes(): List<RecipeHolder<SoulSandInfusionRecipe>> = cached
}
