package damien.nodeworks.client.sound

import damien.nodeworks.registry.ModSoundEvents
import net.minecraft.client.player.LocalPlayer
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance
import net.minecraft.sounds.SoundSource
import net.minecraft.util.RandomSource

/**
 * Looping idle hum for the Grapple Beam. One instance per player
 * session, anchored to the local player by overriding the position
 * accessors, gated externally by [setVolume] from the input tick. The
 * instance never self-stops, callers slam volume to 0 when grappling
 * ends and the source stays resident at silence. Avoids re-allocating
 * a voice every attach and gives the audio engine a stable target.
 */
class GrappleBeamIdleSoundInstance(
    private val player: LocalPlayer,
) : AbstractTickableSoundInstance(
    ModSoundEvents.GRAPPLE_BEAM_IDLE,
    SoundSource.PLAYERS,
    RandomSource.create(),
) {

    companion object {
        const val MAX_VOLUME: Float = 1.0f
    }

    init {
        // Non-zero so the audio engine commits a voice on play().
        this.volume = 0.01f
        // MC 26.x's AbstractTickableSoundInstance no longer sets this
        // by default; without it the buffer plays once and the engine
        // evicts the instance.
        this.looping = true
    }

    /** Public mutator since the inherited `volume` field is protected. */
    fun setVolume(v: Float) {
        this.volume = v
    }

    /** Track the player live so attenuation follows them. */
    override fun getX(): Double = player.position().x
    override fun getY(): Double = player.position().y
    override fun getZ(): Double = player.position().z

    override fun tick() {
    }
}
