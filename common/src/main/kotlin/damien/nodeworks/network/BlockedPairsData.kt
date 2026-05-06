package damien.nodeworks.network

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.resources.Identifier
import net.minecraft.util.datafix.DataFixTypes
import net.minecraft.world.level.saveddata.SavedData
import net.minecraft.world.level.saveddata.SavedDataType

/**
 * Per-dimension persisted set of LOS-blocked Focus Node link pairs, keyed
 * by [NodeConnectionHelper.pairKey]. Persisting it lets propagate skip the
 * raycast on most ticks, only re-checking when the block-change mixin or
 * the lazy gate fires.
 */
class BlockedPairsData : SavedData {
    val pairs: MutableSet<Long> = HashSet()

    constructor() : super()

    constructor(initial: List<Long>) : super() {
        pairs.addAll(initial)
    }

    companion object {
        val CODEC: Codec<BlockedPairsData> = RecordCodecBuilder.create { inst ->
            inst.group(
                Codec.LONG.listOf().fieldOf("pairs").forGetter { it.pairs.toList() }
            ).apply(inst) { BlockedPairsData(it) }
        }

        val TYPE: SavedDataType<BlockedPairsData> = SavedDataType(
            Identifier.fromNamespaceAndPath("nodeworks", "blocked_pairs"),
            { BlockedPairsData() },
            CODEC,
            DataFixTypes.SAVED_DATA_COMMAND_STORAGE
        )
    }
}
