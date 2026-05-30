package damien.nodeworks.block.entity

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.tags.BlockTags
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.IntegerProperty
import java.util.ArrayDeque

/**
 * Tree shape discovery for the Breaker. Validates that the cut isn't bypassed
 * by a sibling trunk supporting the canopy directly above, then collects every
 * log reachable upward from the cut and walks leaves outward through the
 * vanilla `distance` gradient.
 *
 * Tag-driven via `BlockTags.LOGS` and the vanilla leaf `distance` property,
 * so any mod following the vanilla convention works without a direct
 * dependency.
 */
object TreeCutter {

    /** Hard cap on combined logs + leaves. Above this the scan aborts and the
     *  caller falls back to a single-block break, bounds the worst-case server
     *  tick cost for modded giant trees. */
    const val MAX_BLOCKS = 512

    data class TreeShape(val logs: List<BlockPos>, val leaves: List<BlockPos>)

    /** Discover the portion of a tree to fell when [cutPos] is broken (already
     *  air in [reader]). Returns null when [brokenState] wasn't a log, a
     *  sibling trunk still holds the canopy up at the layer above the cut, or
     *  the scan exceeds [MAX_BLOCKS]. */
    fun findTree(reader: BlockGetter, cutPos: BlockPos, brokenState: BlockState): TreeShape? {
        if (!isLog(brokenState)) return null
        if (!validateCut(reader, cutPos)) return null

        val logs = ArrayList<BlockPos>()
        val visited = HashSet<BlockPos>()
        val frontier = ArrayDeque<BlockPos>()

        visited.add(cutPos)
        forEachNeighbourUp(cutPos) { frontier.add(it) }

        while (frontier.isNotEmpty()) {
            val pos = frontier.removeFirst()
            if (!visited.add(pos)) continue
            if (logs.size >= MAX_BLOCKS) return null
            if (!isLog(reader.getBlockState(pos))) continue
            logs.add(pos)
            forEachNeighbourUp(pos) {
                if (it !in visited) frontier.add(it)
            }
        }

        if (logs.isEmpty()) return null

        val leaves = collectLeaves(reader, logs, capRemaining = MAX_BLOCKS - logs.size)
        return TreeShape(logs, leaves)
    }

    fun isLog(state: BlockState): Boolean = state.`is`(BlockTags.LOGS)

    /** True when no log at the layer directly above [cutPos] is still
     *  supported by another non-cut log beneath it. A sibling trunk in the
     *  3x3 column above the cut (e.g. dark oak's 2x2 trunk) trips this and
     *  the fell is refused. */
    private fun validateCut(reader: BlockGetter, cutPos: BlockPos): Boolean {
        val visited = HashSet<BlockPos>()
        val frontier = ArrayDeque<BlockPos>()
        frontier.add(cutPos)
        frontier.add(cutPos.above())
        val baseY = cutPos.y

        while (frontier.isNotEmpty()) {
            val pos = frontier.removeFirst()
            if (!visited.add(pos)) continue
            if (!isLog(reader.getBlockState(pos))) continue

            val lowerLayer = pos.y == baseY
            val below = pos.below()
            if (!lowerLayer && below != cutPos && isLog(reader.getBlockState(below))) {
                return false
            }

            for (dir in Direction.entries) {
                if (dir == Direction.DOWN) continue
                if (dir == Direction.UP && !lowerLayer) continue
                val next = pos.relative(dir)
                if (next !in visited) frontier.add(next)
            }
        }
        return true
    }

    /** Walk leaves outward from felled logs along the vanilla `distance`
     *  gradient. Each step accepts leaves with a higher distance than the
     *  previous log/leaf, mirroring how vanilla leaf decay propagates. */
    private fun collectLeaves(
        reader: BlockGetter,
        seeds: List<BlockPos>,
        capRemaining: Int,
    ): List<BlockPos> {
        if (capRemaining <= 0) return emptyList()
        val leaves = ArrayList<BlockPos>()
        val visited = HashSet<BlockPos>(seeds)
        val frontier = ArrayDeque<BlockPos>().apply { addAll(seeds) }
        while (frontier.isNotEmpty()) {
            if (leaves.size >= capRemaining) break
            val prev = frontier.removeFirst()
            val prevState = reader.getBlockState(prev)
            val prevDistance = if (isLeaf(prevState)) leafDistance(prevState) else 0
            forEachNeighbour3D(prev) { candidate ->
                if (candidate in visited) return@forEachNeighbour3D
                val state = reader.getBlockState(candidate)
                if (isLeaf(state) && leafDistance(state) > prevDistance && visited.add(candidate)) {
                    leaves.add(candidate)
                    frontier.add(candidate)
                }
            }
        }
        return leaves
    }

    /** Upward 3x3x2 expansion (cut level and one above), used for log walks. */
    private inline fun forEachNeighbourUp(pos: BlockPos, sink: (BlockPos) -> Unit) {
        for (dx in -1..1) for (dy in 0..1) for (dz in -1..1) {
            if (dx == 0 && dy == 0 && dz == 0) continue
            sink(pos.offset(dx, dy, dz))
        }
    }

    /** Full 3x3x3 expansion, used for leaf walks where leaves can sit below
     *  the log they attach to. */
    private inline fun forEachNeighbour3D(pos: BlockPos, sink: (BlockPos) -> Unit) {
        for (dx in -1..1) for (dy in -1..1) for (dz in -1..1) {
            if (dx == 0 && dy == 0 && dz == 0) continue
            sink(pos.offset(dx, dy, dz))
        }
    }

    /** Vanilla `LeavesBlock` and modded variants carry a `distance` int
     *  property used by leaf decay. Scaffolding's `stability_distance` is
     *  excluded explicitly. */
    private fun isLeaf(state: BlockState): Boolean = leafDistanceProperty(state) != null

    private fun leafDistance(state: BlockState): Int {
        val prop = leafDistanceProperty(state) ?: return 0
        return state.getValue(prop)
    }

    private fun leafDistanceProperty(state: BlockState): IntegerProperty? {
        for (property in state.properties) {
            if (property is IntegerProperty
                && property.name == "distance"
                && property != BlockStateProperties.STABILITY_DISTANCE
            ) return property
        }
        return null
    }
}
