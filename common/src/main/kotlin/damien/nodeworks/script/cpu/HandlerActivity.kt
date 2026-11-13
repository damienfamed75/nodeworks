package damien.nodeworks.script.cpu

import damien.nodeworks.script.CraftingHelper

/**
 * Which handlers have a job in flight, and when each last finished one. The retry cap on
 * a Process op is meant to catch a machine that can never take the inputs: no output
 * cards, a hopper feeding it foreign items, a filter that lets nothing through. A machine
 * that is merely busy with a job from another op, another CPU or an earlier chunk is not
 * that, and waiting on it will resolve, so those waits must not use up the retries.
 *
 * Keys are whatever identifies one machine bank: a handler block's dimension and position,
 * or an engine's scheduler paired with a recipe name for a Lua handler. Jobs are forgotten
 * once complete. In-memory only, cleared with the server.
 */
object HandlerActivity {

    private class Entry {
        val jobs = ArrayList<CraftingHelper.PendingHandlerJob>(2)
        var lastProgressTick = Long.MIN_VALUE
    }

    private val entries = HashMap<Any, Entry>()

    /** Remember that [pending] is a job inside the handler [key] identifies. */
    @Synchronized
    fun track(key: Any, pending: CraftingHelper.PendingHandlerJob, now: Long) {
        if (pending.isComplete) return
        val entry = entries.getOrPut(key) { Entry() }
        prune(entry, now)
        if (entry.jobs.none { it === pending }) entry.jobs.add(pending)
    }

    /** True while any tracked job on the handler is still running. */
    @Synchronized
    fun busy(key: Any, now: Long): Boolean {
        val entry = entries[key] ?: return false
        prune(entry, now)
        return entry.jobs.isNotEmpty()
    }

    /** Tick at which a job on the handler last finished with success, or [Long.MIN_VALUE]. */
    @Synchronized
    fun lastProgressTick(key: Any, now: Long): Long {
        val entry = entries[key] ?: return Long.MIN_VALUE
        prune(entry, now)
        return entry.lastProgressTick
    }

    @Synchronized
    fun reset() {
        entries.clear()
    }

    /** Drops finished jobs. A finished job that succeeded counts as progress at [now], the
     *  first tick anyone looked, which is close enough for deciding whether a wait is alive. */
    private fun prune(entry: Entry, now: Long) {
        val it = entry.jobs.iterator()
        while (it.hasNext()) {
            val job = it.next()
            if (!job.isComplete) continue
            if (job.success) entry.lastProgressTick = maxOf(entry.lastProgressTick, now)
            it.remove()
        }
    }
}
