package damien.nodeworks.network

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.resources.Identifier
import net.minecraft.util.datafix.DataFixTypes
import net.minecraft.world.level.saveddata.SavedData
import net.minecraft.world.level.saveddata.SavedDataType

/**
 * Per-dimension persisted set of LOS-blocked Advanced Node link pairs, keyed
 * by [NodeConnectionHelper.pairKey]. Saved via the vanilla SavedData mechanism
 * so the cache survives a world reload and propagate doesn't have to re-raycast
 * every link to self-heal — that scales poorly to networks with many
 * Advanced Nodes.
 *
 * The pipe-network refactor narrowed the scope: only Advanced-Node-to-Advanced-Node
 * laser links live here. Pipe and Node adjacency carries no LOS check, so the
 * pair set's size is bounded by the count of explicit wrench-linked pairs, not
 * by total network size.
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
