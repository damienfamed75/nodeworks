package damien.nodeworks.script

/**
 * Holds the ranges of a storage that is being read across several ticks, so
 * the inventory cache only ever counts a storage once it has been read in
 * full. A storage that goes away partway through (card pulled, block broken,
 * chunk unloaded) is [abandon]ed: what was read is discarded and its later
 * ranges are skipped, so it drops out cleanly instead of showing a partial
 * count. Storages read in one go never touch this and go straight to the
 * front buffer.
 *
 * Keyed by [P] (the storage position), entries by [K], merged with [merge]
 * when the same key shows up in several ranges. Scoped to one poll cycle,
 * [clear] at the start of each.
 */
class SplitScanStaging<P, K, V>(private val merge: (V, V) -> V) {
    private val pending = HashMap<P, LinkedHashMap<K, V>>()
    private val abandoned = HashSet<P>()

    /** True while some storage has ranges read but not yet committed. */
    val hasPending: Boolean get() = pending.isNotEmpty()

    fun isAbandoned(pos: P): Boolean = pos in abandoned

    fun stage(pos: P, key: K, value: V) {
        if (pos in abandoned) return
        val entries = pending.getOrPut(pos) { LinkedHashMap() }
        val existing = entries[key]
        entries[key] = if (existing == null) value else merge(existing, value)
    }

    /** The storage was read to its end: merge its staged entries into [into]. */
    fun commit(pos: P, into: MutableMap<K, V>) {
        val entries = pending.remove(pos) ?: return
        for ((key, value) in entries) {
            val existing = into[key]
            into[key] = if (existing == null) value else merge(existing, value)
        }
    }

    /** Discard what was read for [pos] and ignore it for the rest of the cycle. */
    fun abandon(pos: P) {
        pending.remove(pos)
        abandoned.add(pos)
    }

    fun clear() {
        pending.clear()
        abandoned.clear()
    }
}
