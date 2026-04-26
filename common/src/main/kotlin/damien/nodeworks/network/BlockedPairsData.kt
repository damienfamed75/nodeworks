package damien.nodeworks.network

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.resources.Identifier
import net.minecraft.util.datafix.DataFixTypes
import net.minecraft.world.level.saveddata.SavedData
import net.minecraft.world.level.saveddata.SavedDataType

/**
 * Persisted set of LOS-blocked connection pairs, keyed by [NodeConnectionHelper.pairKey].
 *
 * Saved per dimension via the vanilla SavedData mechanism, so
 * [NodeConnectionHelper] can trust the cache after a world reload without
 * having to re-raycast every connection. See the design notes on
 * [NodeConnectionHelper.propagateNetworkId] for why persistence is the
 * difference between "correct" and "correct + performant": without it,
 * propagate would need a live raycast per edge on every call to self-heal,
 * which scales poorly to thousand-node networks.
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
            // DataFixTypes only matters when the on-disk format changes between versions.
            // We store a LongArray, no migrations expected. Reusing a vanilla enum entry
            // is a deliberate no-op, vanilla's data fixer won't touch our namespace.
            DataFixTypes.SAVED_DATA_COMMAND_STORAGE
        )
    }
}
