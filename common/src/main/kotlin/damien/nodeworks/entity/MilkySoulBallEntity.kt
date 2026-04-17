package damien.nodeworks.entity

import damien.nodeworks.registry.ModEntityTypes
import damien.nodeworks.registry.ModItems
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrowableItemProjectile
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.phys.EntityHitResult
import net.minecraft.world.phys.HitResult

class MilkySoulBallEntity : ThrowableItemProjectile {

    constructor(type: EntityType<out MilkySoulBallEntity>, level: Level) : super(type, level)

    constructor(level: Level, shooter: LivingEntity) : super(
        ModEntityTypes.MILKY_SOUL_BALL, shooter, level, ItemStack(ModItems.MILKY_SOUL_BALL)
    )

    override fun getDefaultItem(): Item = ModItems.MILKY_SOUL_BALL

    override fun onHitEntity(result: EntityHitResult) {
        super.onHitEntity(result)
        val entity = result.entity
        if (entity is LivingEntity) {
            entity.addEffect(MobEffectInstance(MobEffects.WITHER, 200, 0)) // 10 seconds, level 1
        }
    }

    override fun onHit(result: HitResult) {
        super.onHit(result)
        if (!level().isClientSide) {
            discard()
        }
    }
}
