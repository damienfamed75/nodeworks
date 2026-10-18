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
         * Walks at most [damien.nodeworks.script.cpu.CpuRules.COMPONENTS_PER_CPU_CAP]
         * components, the same bound the Core's own scan has.
         */
        fun findConnectedCores(level: Level, startPos: BlockPos): Set<CraftingCoreBlockEntity> {
            val cores = mutableSetOf<CraftingCoreBlockEntity>()
            val visited = mutableSetOf(startPos)
            val queue = ArrayDeque<BlockPos>()
            queue.add(startPos)
            var discovered = 0

            while (queue.isNotEmpty() && discovered < damien.nodeworks.script.cpu.CpuRules.COMPONENTS_PER_CPU_CAP) {
                val cur = queue.removeFirst()
                for (dir in Direction.entries) {
                    val n = cur.relative(dir)
                    if (n in visited) continue
                    visited.add(n)
                    when (val entity = level.getBlockEntity(n)) {
                        is CraftingCoreBlockEntity -> cores.add(entity)
                        is CpuComponentBlockEntity -> {
                            queue.add(n)
                            discovered++
                        }
                        else -> Unit
                    }
                }
            }
            return cores
        }
    }
}
