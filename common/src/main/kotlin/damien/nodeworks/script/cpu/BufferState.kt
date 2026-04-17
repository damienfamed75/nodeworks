package damien.nodeworks.script.cpu

import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.Tag

/**
 * The Crafting CPU's in-flight item buffer. Tracks both:
 *
 *   - **count**  — total items across every stored item type (Long-typed to remain
 *                  safe against networks with billions of items)
 *   - **types**  — number of distinct item types currently stored (Int — bounded by
 *                  [typesCapacity], which is a small number)
 *
 * Both limits apply independently. An item insertion fails if either limit would be
 * exceeded. Every public method is safe to call at any count/type combination.
 *
 * All numeric tunables (capacities, defaults) live in [CpuRules]. This class
 * only tracks state and enforces the rules declared there.
 *
 * Serialization is backward-compatible: old NBT data that stored counts as Ints
 * still loads correctly (see [loadFromNBT]).
 */
class BufferState {

    private val items: MutableMap<String, Long> = mutableMapOf()

    /** Total count capacity (sum of all stored items cannot exceed this). */
    var countCapacity: Long = CpuRules.CORE_BASE_COUNT
        private set

    /** Unique item-type capacity ([types] cannot exceed this). */
    var typesCapacity: Int = CpuRules.CORE_BASE_TYPES
        private set

    /** Total items held across every type. */
    val count: Long get() {
        var total = 0L
        for (v in items.values) total += v
        return total
    }

    /** Number of distinct item types held. */
    val types: Int get() = items.size

    // =====================================================================
    // Capacity control — called by the Core during recalculateCapacity()
    // =====================================================================

    fun setCapacities(countCap: Long, typesCap: Int) {
        countCapacity = countCap.coerceAtLeast(0L)
        typesCapacity = typesCap.coerceAtLeast(0)
    }

    // =====================================================================
    // Query
    // =====================================================================

    fun get(itemId: String): Long = items[itemId] ?: 0L

    /**
     * Snapshot of current contents. Returned map is a copy; modifying it does not
     * affect the buffer. Order is stable (LinkedHashMap-style insertion order).
     */
    fun contents(): Map<String, Long> = items.toMap()

    /**
     * Returns true iff an insertion of [amount] [itemId] would succeed right now,
     * under both capacity axes.
     */
    fun canAccept(itemId: String, amount: Long): Boolean {
        if (amount <= 0) return true
        val wouldCount = count + amount
        if (wouldCount > countCapacity || wouldCount < 0) return false   // overflow guard
        if (itemId !in items && types >= typesCapacity) return false
        return true
    }

    // =====================================================================
    // Mutation
    // =====================================================================

    /**
     * Insert [amount] of [itemId]. Returns true on success, false on failure.
     * Failures are atomic — nothing is changed if any capacity is exceeded.
     */
    fun insert(itemId: String, amount: Long): Boolean {
        if (amount <= 0) return true
        if (!canAccept(itemId, amount)) return false
        items.merge(itemId, amount) { a, b -> a + b }
        return true
    }

    /**
     * Extract up to [amount] of [itemId]. Returns the amount actually extracted
     * (≤ amount, could be 0 if none stored). Removes the type entry entirely when
     * the last of an item is pulled out, freeing a types slot.
     */
    fun extract(itemId: String, amount: Long): Long {
        if (amount <= 0) return 0L
        val current = items[itemId] ?: return 0L
        val extracted = minOf(current, amount)
        val remaining = current - extracted
        if (remaining == 0L) items.remove(itemId) else items[itemId] = remaining
        return extracted
    }

    /**
     * Empty the buffer. Returns a snapshot of what was removed, so the caller
     * can push it back into network storage.
     */
    fun clear(): Map<String, Long> {
        val snapshot = items.toMap()
        items.clear()
        return snapshot
    }

    fun isEmpty(): Boolean = items.isEmpty()

    // =====================================================================
    // Serialization
    // =====================================================================

    /**
     * Writes the buffer contents and capacity state to [tag]. Counts are saved
     * as Longs. Capacities are saved so they can be displayed on clients that
     * deserialize the tag before recalculateCapacity() has had a chance to run.
     */
    // TODO MC 26.1.2 NBT MIGRATION: the old body called tag.put/putLong/putInt and
    //  iterated itemsTag.allKeys. Port to the new CompoundTag API (getLong/getInt
    //  now return Optional<T>; use CompoundTag.getLongOr / getIntOr / getCompoundOrEmpty
    //  for default reads). See git history for the full pre-migration body, including
    //  the legacy "Int counts stored as putInt" fallback for pre-Phase-1 worlds.
    fun saveToNBT(tag: CompoundTag) {
    }

    fun loadFromNBT(tag: CompoundTag) {
        items.clear()
        countCapacity = CpuRules.CORE_BASE_COUNT
        typesCapacity = CpuRules.CORE_BASE_TYPES
    }

    // TODO MC 26.1.2 NBT MIGRATION: restore `readLongOrInt` helper + `RESERVED_KEYS`
    //  companion set when saveToNBT / loadFromNBT get rewritten. See git history for
    //  the pre-migration body.
}
