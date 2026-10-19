package damien.nodeworks.script.cpu

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder

/** One entry of the `nodeworks:coolant` block data map. [connects] lets the block carry
 *  the CPU structure the way a Coolant block does, so Core - coolant - Buffer forms. A
 *  pack maker turns it off for blocks that occur naturally in bulk, such as ice, so a
 *  frozen lake next to a CPU is not walked as part of it. */
data class CoolantRating(val baseCooling: Float, val clusterBonus: Float, val connects: Boolean = true) {
    companion object {
        private val NON_NEGATIVE: Codec<Float> = Codec.floatRange(0f, Float.MAX_VALUE)

        val CODEC: Codec<CoolantRating> = RecordCodecBuilder.create { inst ->
            inst.group(
                NON_NEGATIVE.fieldOf("base_cooling").forGetter(CoolantRating::baseCooling),
                NON_NEGATIVE.optionalFieldOf("cluster_bonus", 0f).forGetter(CoolantRating::clusterBonus),
                Codec.BOOL.optionalFieldOf("connects", true).forGetter(CoolantRating::connects),
            ).apply(inst, ::CoolantRating)
        }
    }
}
