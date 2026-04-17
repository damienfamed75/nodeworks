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

    fun saveToNBT(tag: CompoundTag) {
        tag.putString("rootItemId", rootItemId)
        tag.putLong("rootCount", rootCount)
        val opsTag = ListTag()
        for (op in ops) {
            val o = CompoundTag()
            op.saveToNBT(o)
            opsTag.add(o)
        }
        tag.put("ops", opsTag)
        val terms = IntArray(terminalOpIds.size)
        var i = 0
        for (t in terminalOpIds) terms[i++] = t
        tag.putIntArray("terminals", terms)
        submitterUuid?.let { tag.putUUID("submitter", it) }
    }

    companion object {
        fun loadFromNBT(tag: CompoundTag): CraftPlan? {
            val rootItemId = tag.getString("rootItemId")
            val rootCount = tag.getLong("rootCount")
            if (rootItemId.isEmpty()) return null
            val opsList = tag.getList("ops", Tag.TAG_COMPOUND.toInt())
            val ops = (0 until opsList.size).mapNotNull { Operation.loadFromNBT(opsList.getCompound(it)) }
            val terms = (tag.getIntArray("terminals") ?: IntArray(0)).toSet()
            val submitter = if (tag.hasUUID("submitter")) tag.getUUID("submitter") else null
            return CraftPlan(rootItemId, rootCount, ops, terms, submitter)
        }
    }
}
