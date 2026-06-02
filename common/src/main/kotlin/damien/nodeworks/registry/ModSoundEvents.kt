package damien.nodeworks.registry

import net.minecraft.core.Registry
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.sounds.SoundEvent

/**
 * Registers Nodeworks SoundEvents. Mirrors [ModItems]'s direct-Registry
 * style rather than NeoForge's DeferredRegister so initialisation order
 * stays consistent across modules.
 *
 * Each event uses [SoundEvent.createVariableRangeEvent] so the
 * `attenuation_distance` field in `sounds.json` is honoured (the
 * alternative fixed-range variant hard-codes a 16-block falloff).
 */
object ModSoundEvents {

    val GRAPPLE_BEAM_ACTIVATE: SoundEvent =
        register("item.grapple_beam.activate")

    val GRAPPLE_BEAM_DEACTIVATE: SoundEvent =
        register("item.grapple_beam.deactivate")

    val GRAPPLE_BEAM_IDLE: SoundEvent =
        register("item.grapple_beam.idle")

    private fun register(path: String): SoundEvent {
        val id = Identifier.fromNamespaceAndPath("nodeworks", path)
        return Registry.register(
            BuiltInRegistries.SOUND_EVENT,
            id,
            SoundEvent.createVariableRangeEvent(id),
        )
    }

    fun initialize() {
        // Triggers class loading so the val initialisers above run.
    }
}
