package damien.nodeworks.item

import damien.nodeworks.registry.ModItems
import net.minecraft.core.BlockPos
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks

/**
 * Handles right-clicking soul sand with a milk bucket to create a Milky Soul Ball.
 * Called from platform-specific event handlers.
 */
object SoulSandInteraction {

    fun onUseItemOnBlock(player: Player, level: Level, pos: BlockPos, stack: ItemStack): InteractionResult {
        if (stack.`is`(Items.MILK_BUCKET) && level.getBlockState(pos).`is`(Blocks.SOUL_SAND)) {
            if (level.isClientSide) return InteractionResult.SUCCESS

            // Destroy the soul sand block
            level.destroyBlock(pos, false)

            // Consume the milk bucket, give back empty bucket
            if (!player.abilities.instabuild) {
                stack.shrink(1)
                val emptyBucket = ItemStack(Items.BUCKET)
                if (!player.inventory.add(emptyBucket)) {
                    player.drop(emptyBucket, false)
                }
            }

            // Drop the Milky Soul Ball
            val soulBall = ItemStack(ModItems.MILKY_SOUL_BALL)
            if (!player.inventory.add(soulBall)) {
                player.drop(soulBall, false)
            }

            return InteractionResult.SUCCESS
        }
        return InteractionResult.PASS
    }
}
