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
 * Tree shape discovery for the Breaker's tree-felling path. Tag-driven via
 * `BlockTags.LOGS` and the vanilla leaf `distance` property, so any mod
 * following the vanilla convention works without a direct dependency.
 */
object TreeCutter {

    /** Hard cap on combined logs + leaves. Above this the scan aborts and the
     *  caller falls back to a single-block break, bounds the worst-case server
     *  tick cost for modded giant trees. */
    const val MAX_BLOCKS = 512

    data class TreeShape(val logs: List<BlockPos>, val leaves: List<BlockPos>)

    /** Scan from [cutPos], assumed already broken (air in [reader]). Returns
     *  null when [brokenState] wasn't a log, the canopy is still supported by
     *  another trunk, or the cap is exceeded. */
    fun findTree(reader: BlockGetter, cutPos: BlockPos, brokenState: BlockState): TreeShape? {
        if (!isLog(brokenState)) return null
        if (!validateCut(reader, cutPos)) return null

        val logs = ArrayList<BlockPos>()
        val visited = HashSet<BlockPos>()
        val frontier = ArrayDeque<BlockPos>()

        visited.add(cutPos)
        forEachNeighbour(cutPos, 0..1) { frontier.add(it) }

        while (frontier.isNotEmpty()) {
            val pos = frontier.removeFirst()
            if (!visited.add(pos)) continue
            if (logs.size >= MAX_BLOCKS) return null
            val state = reader.getBlockState(pos)
            if (!isLog(state)) continue
            logs.add(pos)
            forEachNeighbour(pos, 0..1) {
                if (it !in visited) frontier.add(it)
            }
        }

        if (logs.isEmpty()) return null

        val leaves = ArrayList<BlockPos>()
        visited.clear()
        visited.addAll(logs)
        visited.add(cutPos)
        for (log in logs) frontier.add(log)

        while (frontier.isNotEmpty()) {
            val prev = frontier.removeFirst()
            if (logs.size + leaves.size >= MAX_BLOCKS) return null
            val prevState = reader.getBlockState(prev)
            val prevDistance = if (isLeaf(prevState)) leafDistance(prevState) else 0

            forEachNeighbour(prev, -1..1) { candidate ->
                if (candidate in visited) return@forEachNeighbour
                val state = reader.getBlockState(candidate)
                if (isLeaf(state) && leafDistance(state) > prevDistance) {
                    if (visited.add(candidate)) {
                        leaves.add(candidate)
                        frontier.add(candidate)
                    }
                }
            }
        }

        return TreeShape(logs, leaves)
    }

    fun isLog(state: BlockState): Boolean = state.`is`(BlockTags.LOGS)

    /** True when [cutPos] is the last log connecting the canopy to the ground.
     *  Rejects if any log above the cut has another log directly beneath it
     *  (a sibling trunk still rooted). The cut pos itself is excluded so
     *  cutting the bottom of a single trunk counts as a disconnect. */
    private fun validateCut(reader: BlockGetter, cutPos: BlockPos): Boolean {
        val visited = HashSet<BlockPos>()
        val frontier = ArrayDeque<BlockPos>()
        frontier.add(cutPos)
        frontier.add(cutPos.above())
        val baseY = cutPos.y

        while (frontier.isNotEmpty()) {
            val pos = frontier.removeFirst()
            if (!visited.add(pos)) continue
            val state = reader.getBlockState(pos)
            if (!isLog(state)) continue

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

    private inline fun forEachNeighbour(
        pos: BlockPos,
        yRange: IntRange,
        sink: (BlockPos) -> Unit,
    ) {
        for (dx in -1..1) for (dy in yRange) for (dz in -1..1) {
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
