package damien.nodeworks.block.entity

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.level.Level

/**
 * Marker interface for block entities that are part of a Crafting CPU multiblock.
 *
 * The [CraftingCoreBlockEntity] discovers components via free-form adjacency BFS,
 * any block entity implementing this interface will be traversed as part of the CPU
 * structure. Specific contributions (buffer capacity, parallel job slots, etc.) are
 * accessed via type-specific checks in the Core's recalculation logic.
 *
 * Example: Buffer, Co-Processor, and any future CPU components should implement this.
 */
interface CpuComponentBlockEntity {
    companion object {
        /**
         * BFS from [startPos] through connected CPU components, returns all
         * [CraftingCoreBlockEntity]s reachable via a chain of adjacent components.
         *
         * Used to notify every affected Core when a component is placed/removed/retiered.
         */
        fun findConnectedCores(level: Level, startPos: BlockPos): Set<CraftingCoreBlockEntity> {
            val cores = mutableSetOf<CraftingCoreBlockEntity>()
            val visited = mutableSetOf(startPos)
            val queue = ArrayDeque<BlockPos>()
            queue.add(startPos)

            while (queue.isNotEmpty()) {
                val cur = queue.removeFirst()
                for (dir in Direction.entries) {
                    val n = cur.relative(dir)
                    if (n in visited) continue
                    visited.add(n)
                    when (val entity = level.getBlockEntity(n)) {
                        is CraftingCoreBlockEntity -> cores.add(entity)
                        is CpuComponentBlockEntity -> queue.add(n)
                        else -> Unit
                    }
                }
            }
            return cores
        }
    }
}
