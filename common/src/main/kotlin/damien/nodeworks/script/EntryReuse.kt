package damien.nodeworks.script

/**
 * Reuse of built entries across polls of one storage, keyed by [K]. Building
 * an entry (id string, display name, components hash) costs far more than
 * looking one up, so a storage whose contents barely change between polls
 * should build each entry once and reuse it.
 *
 * Each poll is a [Pass]. A pass hands back the entry the last completed pass
 * built for a key, or builds a new one, and collects every entry it touched.
 * [Pass.complete] makes that collection the reuse set, so entries for keys
 * that left the storage are dropped rather than kept forever. A pass that is
 * never completed (abandoned partway) leaves the previous reuse set in place.
 */
class EntryReuse<K, E> {
    private var settled = HashMap<K, E>()

    /** Entries the last completed pass collected. Inspection only. */
    val size: Int get() = settled.size

    fun startPass(expectedEntries: Int): Pass = Pass(HashMap(maxOf(16, expectedEntries * 2)))

    inner class Pass internal constructor(private val next: HashMap<K, E>) {
        fun get(key: K, build: () -> E): E {
            val entry = settled[key] ?: build()
            next[key] = entry
            return entry
        }

        fun complete() {
            settled = next
        }
    }
}
