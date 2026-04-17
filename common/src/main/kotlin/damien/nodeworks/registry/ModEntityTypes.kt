package damien.nodeworks.registry

import damien.nodeworks.entity.MilkySoulBallEntity
import net.minecraft.core.Registry
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.MobCategory

object ModEntityTypes {

    val MILKY_SOUL_BALL: EntityType<MilkySoulBallEntity> = Registry.register(
        BuiltInRegistries.ENTITY_TYPE,
        Identifier.fromNamespaceAndPath("nodeworks", "milky_soul_ball"),
        EntityType.Builder.of(::MilkySoulBallEntity, MobCategory.MISC)
            .sized(0.25f, 0.25f)
            .clientTrackingRange(4)
            .updateInterval(10)
            .build("milky_soul_ball")
    )

    fun initialize() {
        // Triggers class loading
    }
}
