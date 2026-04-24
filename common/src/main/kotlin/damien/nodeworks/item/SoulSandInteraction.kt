package damien.nodeworks.item

import damien.nodeworks.registry.ModRecipeTypes
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.Containers
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.crafting.SingleRecipeInput
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks

/**
 * Dispatches "right-click a soul sand block with an item" interactions
 * against the [ModRecipeTypes.SOUL_SAND_INFUSION] recipe set. Called from
 * platform-specific right-click-block hooks.
 *
 * Data-driven: the held-item trigger and the dropped result both come from
 * the matching recipe. Adding a new interaction is a recipe JSON — no code
 * changes. The held item's consumption behavior piggybacks on
 * [ItemStack.getCraftingRemainingItem] so milk / lava / water buckets return
 * an empty bucket without this method knowing about it.
 */
object SoulSandInteraction {

    fun onUseItemOnBlock(player: Player, level: Level, pos: BlockPos, stack: ItemStack): InteractionResult {
        // Fast path: bail before we touch the recipe manager unless the block
        // under the cursor is actually soul sand. The recipe type is scoped to
        // soul-sand-only, so a non-matching block can never succeed.
        if (!level.getBlockState(pos).`is`(Blocks.SOUL_SAND)) return InteractionResult.PASS
        if (stack.isEmpty) return InteractionResult.PASS

        // Client-side short-circuit — the server does the real work. Returning
        // SUCCESS on the client suppresses the swing-arm animation from firing
        // for a PASS and gives the usual "interaction happened" feel while we
        // wait for the server's authoritative response.
        if (level.isClientSide) return InteractionResult.SUCCESS
        val serverLevel = level as? ServerLevel ?: return InteractionResult.PASS

        // Look up the recipe by held stack. RecipeManager.getRecipeFor filters
        // to entries registered under SOUL_SAND_INFUSION and tests each one's
        // Ingredient against the held stack — cheap because our entry set is
        // tiny. Returns Optional.empty if nothing matches (e.g. holding stone
        // or a random bucket that isn't registered).
        val recipeHolder = serverLevel.recipeAccess()
            .getRecipeFor(ModRecipeTypes.SOUL_SAND_INFUSION, SingleRecipeInput(stack), serverLevel)
            .orElse(null) ?: return InteractionResult.PASS
        val recipe = recipeHolder.value()

        // Destroy the soul sand block (no drops — the held item's transformation
        // is the payoff, not the block itself).
        level.destroyBlock(pos, false)

        // Consume the held item + deliver its crafting remainder (empty bucket
        // for milk/water/lava buckets, nothing for items without one). Creative
        // players keep their held stack and skip the remainder return.
        //
        // `Item.getCraftingRemainder()` returns an `ItemStackTemplate` in 26.1
        // — a data-only wrapper. `.create()` instantiates a fresh ItemStack we
        // can inspect with `isEmpty` and hand to inventory/drop.
        if (!player.abilities.instabuild) {
            val remainderStack = stack.item.craftingRemainder?.create() ?: ItemStack.EMPTY
            stack.shrink(1)
            if (!remainderStack.isEmpty) {
                if (!player.inventory.add(remainderStack)) {
                    player.drop(remainderStack, false)
                }
            }
        }

        // Drop the result at the block's center. `recipe.result` is an
        // ItemStackTemplate (lenient, component-deferred form); `.create()`
        // materializes a fresh ItemStack each call so downstream code can
        // mutate the drop without affecting the recipe's template.
        Containers.dropItemStack(
            level,
            pos.x + 0.5, pos.y + 0.5, pos.z + 0.5,
            recipe.result.create(),
        )

        return InteractionResult.SUCCESS
    }
}
