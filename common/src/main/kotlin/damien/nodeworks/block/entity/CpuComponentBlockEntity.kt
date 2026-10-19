package damien.nodeworks.block.entity

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.state.BlockState

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
         * blocks, the same bound the Core's own scan has. Connecting coolant (see
         * [connectsThrough]) is walked like a component.
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
                        else -> if (connectsThrough(level, n)) {
                            queue.add(n)
                            discovered++
                        }
                    }
                }
            }
            return cores
        }

        /** True when [pos] holds a block in the `nodeworks:coolant` data map whose entry
         *  carries the structure connection. Such a block is part of the CPU's shape
         *  without contributing anything itself, so Core - coolant - Buffer forms. An
         *  unloaded position is never walked into, the scan does not load chunks for
         *  foreign blocks. */
        fun connectsThrough(level: Level, pos: BlockPos): Boolean {
            if (!level.isLoaded(pos)) return false
            return damien.nodeworks.platform.PlatformServices.coolantRating(level.getBlockState(pos))?.connects == true
        }

        /** Call from a component block's `onPlace`. A component whose only link to the
         *  structure is a connecting coolant gets no neighbor update from it (a vanilla
         *  block forwards none), so the placed block asks for the rescan itself. Runs
         *  only for a real placement: a formed or overheat flip of an existing component
         *  is the Core's own push and must not start another scan. */
        fun requestRescanOnPlace(level: Level, pos: BlockPos, state: BlockState, oldState: BlockState) {
            if (level.isClientSide) return
            if (oldState.block === state.block) return
            if (CraftingCoreBlockEntity.pushingStates) return
            findConnectedCores(level, pos).forEach { it.requestRecalculate() }
        }

        /** Call from a block's `affectNeighborsAfterRemoval`. Runs for every removal,
         *  not just a player's, so an explosion, a Breaker or a command taking a block
         *  whose only link was a connecting coolant still reaches the Core. The block is
         *  already gone, the walk starts from its neighbors. */
        fun requestRescanOnRemove(level: Level, pos: BlockPos) {
            if (level.isClientSide) return
            findConnectedCores(level, pos).forEach { it.requestRecalculate() }
        }
    }
}
