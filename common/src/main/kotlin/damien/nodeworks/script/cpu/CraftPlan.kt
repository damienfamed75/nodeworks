package damien.nodeworks.script.cpu

import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag

/**
 * A fully-planned craft: the static dependency DAG of [Operation]s that the scheduler
 * will drive to completion. Immutable after construction.
 *
 * - [ops] is indexed-sorted by [Operation.id] for O(1) lookup.
 * - [rootItemId] / [rootCount] identify the user's original request (for display and resume).
 * - [terminalOpIds] are the ops whose completion means the whole craft is done — typically
 *   the final Deliver op(s).
 */
data class CraftPlan(
    val rootItemId: String,
    val rootCount: Long,
    val ops: List<Operation>,
    val terminalOpIds: Set<Int>,
    /** UUID of the player who submitted this craft. Null for programmatic/resume submissions.
     *  Used by [damien.nodeworks.script.cpu.CpuOpExecutor.onPlanFailed] to chat-notify the
     *  submitter when the plan fails. */
    val submitterUuid: java.util.UUID? = null
) {
    /** O(1) lookup by op id. Built once. */
    private val byId: Map<Int, Operation> = ops.associateBy { it.id }

    fun op(id: Int): Operation? = byId[id]

    // TODO MC 26.1.2 NBT MIGRATION: rewrite against the new CompoundTag API.
    //  Must preserve: rootItemId, rootCount, ops (ListTag of Operation NBT),
    //  terminalOpIds (IntArray), submitterUuid (putUUID/getUUID).
    //  See git history for pre-migration body.
    fun saveToNBT(tag: CompoundTag) {
    }

    companion object {
        fun loadFromNBT(tag: CompoundTag): CraftPlan? = null
    }
}
