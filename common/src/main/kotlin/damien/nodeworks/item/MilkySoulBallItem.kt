package damien.nodeworks.item

import damien.nodeworks.entity.MilkySoulBallEntity
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.stats.Stats
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResultHolder
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level

/**
 * Milky Soul Ball — throwable projectile that applies Wither effect on hit.
 */
class MilkySoulBallItem(properties: Properties) : Item(properties) {

    override fun use(level: Level, player: Player, hand: InteractionHand): InteractionResultHolder<ItemStack> {
        val stack = player.getItemInHand(hand)

        level.playSound(null, player.x, player.y, player.z,
            SoundEvents.SNOWBALL_THROW, SoundSource.NEUTRAL, 0.5f,
            0.4f / (level.getRandom().nextFloat() * 0.4f + 0.8f))

        if (!level.isClientSide) {
            val projectile = MilkySoulBallEntity(level, player)
            projectile.setItem(stack)
            projectile.shootFromRotation(player, player.xRot, player.yRot, 0.0f, 1.5f, 1.0f)
            level.addFreshEntity(projectile)
        }

        player.awardStat(Stats.ITEM_USED.get(this))
        if (!player.abilities.instabuild) {
            stack.shrink(1)
        }

        player.cooldowns.addCooldown(this, 4)
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide)
    }
}
