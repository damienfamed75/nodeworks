package damien.nodeworks.script.cpu

import damien.nodeworks.script.CraftingHelper
import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.Level

/**
 * Which Process op is using a handler block that takes one job at a time. A
 * Multi-Processing Handler owns many sets through one machine bank, so two ops of one
 * craft tree, or two CPUs, dispatching into it together would only fight over the
 * same slots and tie up co-processor threads waiting. The executor asks here before it
 * routes anything, and an op that finds the handler taken steps aside for a few ticks
 * so its thread can run something else.
 *
 * A reservation ends on its own when the job's poll completes, times out or is
 * cancelled, and is dropped early when the owning plan ends. In-memory only, a reload
 * starts clean, so a resumed poll is not reserved against. Server thread only, the
 * lock is for the odd read from elsewhere.
 */
object HandlerReservations {

    class Holder(val cpuPos: BlockPos, val opId: Int, val pending: CraftingHelper.PendingHandlerJob)

    private val byHandler = HashMap<Pair<ResourceKey<Level>, BlockPos>, Holder>()

    /** The op holding [pos], or null when it is free or its job has already finished. */
    @Synchronized
    fun holder(dimension: ResourceKey<Level>, pos: BlockPos): Holder? {
        val key = dimension to pos
        val holder = byHandler[key] ?: return null
        if (holder.pending.isComplete) {
            byHandler.remove(key)
            return null
        }
        return holder
    }

    @Synchronized
    fun reserve(dimension: ResourceKey<Level>, pos: BlockPos, holder: Holder) {
        byHandler[dimension to pos] = holder
    }

    /** Drops every reservation held by ops of the CPU at [cpuPos]. */
    @Synchronized
    fun releaseAll(cpuPos: BlockPos) {
        byHandler.values.removeIf { it.cpuPos == cpuPos }
    }

    @Synchronized
    fun release(cpuPos: BlockPos, opId: Int) {
        byHandler.values.removeIf { it.cpuPos == cpuPos && it.opId == opId }
    }

    @Synchronized
    fun reset() {
        byHandler.clear()
    }
}
