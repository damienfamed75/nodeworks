package damien.nodeworks.registry

import damien.nodeworks.entity.MilkySoulBallEntity
import net.minecraft.core.Registry
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.MobCategory

object ModEntityTypes {

    private val MILKY_SOUL_BALL_KEY: ResourceKey<EntityType<*>> =
        ResourceKey.create(Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath("nodeworks", "milky_soul_ball"))

    val MILKY_SOUL_BALL: EntityType<MilkySoulBallEntity> = Registry.register(
        BuiltInRegistries.ENTITY_TYPE,
        MILKY_SOUL_BALL_KEY.identifier(),
        EntityType.Builder.of(::MilkySoulBallEntity, MobCategory.MISC)
            .sized(0.25f, 0.25f)
            .clientTrackingRange(4)
            .updateInterval(10)
            .build(MILKY_SOUL_BALL_KEY)
    )

    fun initialize() {
        // Triggers class loading
    }
}
