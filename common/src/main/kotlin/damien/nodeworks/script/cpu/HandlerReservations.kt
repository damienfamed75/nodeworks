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

    /** Keyed by a handler block's dimension and position, or by an exclusive
     *  `network:recipes` rule itself, which compares by identity. */
    private val byHandler = HashMap<Any, Holder>()

    /** The op holding [pos], or null when it is free or its job has already finished. */
    fun holder(dimension: ResourceKey<Level>, pos: BlockPos): Holder? = holder(dimension to pos)

    fun reserve(dimension: ResourceKey<Level>, pos: BlockPos, holder: Holder) = reserve(dimension to pos, holder)

    @Synchronized
    fun holder(key: Any): Holder? {
        val holder = byHandler[key] ?: return null
        if (holder.pending.isComplete) {
            byHandler.remove(key)
            return null
        }
        return holder
    }

    /** Takes the key for [holder] unless another op still holds it. False when it does,
     *  so a CPU resuming after a reload cannot take a handler another CPU is using. */
    @Synchronized
    fun reserve(key: Any, holder: Holder): Boolean {
        val current = holder(key)
        if (current != null && (current.cpuPos != holder.cpuPos || current.opId != holder.opId)) return false
        byHandler[key] = holder
        return true
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
