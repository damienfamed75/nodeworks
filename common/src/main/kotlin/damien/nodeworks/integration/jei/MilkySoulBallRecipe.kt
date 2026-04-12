package damien.nodeworks.integration.jei

import net.minecraft.world.item.ItemStack

/** A single entry in the Soul Sand Infusion JEI category. */
data class MilkySoulBallRecipe(
    val milk: ItemStack,
    val soulSand: ItemStack,
    val result: ItemStack
)
