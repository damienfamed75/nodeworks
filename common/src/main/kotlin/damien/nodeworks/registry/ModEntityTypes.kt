package damien.nodeworks.registry

import damien.nodeworks.entity.GrappleBeamHookEntity
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

    private val GRAPPLE_BEAM_HOOK_KEY: ResourceKey<EntityType<*>> =
        ResourceKey.create(Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath("nodeworks", "grapple_beam_hook"))

    val GRAPPLE_BEAM_HOOK: EntityType<GrappleBeamHookEntity> = Registry.register(
        BuiltInRegistries.ENTITY_TYPE,
        GRAPPLE_BEAM_HOOK_KEY.identifier(),
        EntityType.Builder.of(::GrappleBeamHookEntity, MobCategory.MISC)
            .sized(0.2f, 0.2f)
            // Big tracking range so the beam stays drawn for distant
            // viewers of the player who's grappling.
            .clientTrackingRange(8)
            .updateInterval(1)
            .build(GRAPPLE_BEAM_HOOK_KEY)
    )

    fun initialize() {
        // Triggers class loading
    }
}
