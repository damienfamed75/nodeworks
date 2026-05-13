package damien.nodeworks.script.cpu

import damien.nodeworks.script.BufferKey
import net.minecraft.core.component.DataComponentPatch
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtOps
import net.minecraft.nbt.Tag
import net.minecraft.resources.Identifier
import net.minecraft.resources.RegistryOps
import net.minecraft.world.item.ItemStack

/**
 * The Crafting CPU's in-flight item buffer. Tracks both:
 *
 *   - **count**, total items across every stored item type (Long-typed to remain
 *                  safe against networks with billions of items)
 *   - **types**, number of distinct item types currently stored (Int, bounded by
 *                  [typesCapacity], which is a small number)
 *
 * Variants of the same item id that differ in DataComponents (strength vs.
 * healing potion, damaged vs. pristine tool) live in independent buckets and
 * each count as a distinct **type** for the types-capacity limit. Plain items
 * (empty components patch) share one bucket per item id, identical behaviour
 * to the pre-component buffer.
 *
 * All numeric tunables (capacities, defaults) live in [CpuRules]. This class
 * only tracks state and enforces the rules declared there.
 *
 * Serialization handles three formats: the current component-aware list under
 * `entries`, the prior LongMap shape under `items`, and the legacy top-level
 * itemId keys saved before the `items` subtag existed.
 */
class BufferState {

    /** Per-bucket entry. Count is independent of the stack's `count` field, which
     *  isn't authoritative here. The stack is just the components-bearing
     *  template used to rebuild extracts. */
    data class Entry(
        val template: ItemStack,
        val count: Long,
    )

    private val items: MutableMap<BufferKey.Key, Entry> = mutableMapOf()

    /** Total count capacity (sum of all stored items cannot exceed this). */
    var countCapacity: Long = CpuRules.CORE_BASE_COUNT
        private set

    /** Unique item-type capacity ([types] cannot exceed this). */
    var typesCapacity: Int = CpuRules.CORE_BASE_TYPES
        private set

    /** Total items held across every type. */
    val count: Long get() {
        var total = 0L
        for (v in items.values) total += v.count
        return total
    }

    /** Number of distinct item types held. Counts each component variant as
     *  its own type, so two strength potions and a healing potion count as 2. */
    val types: Int get() = items.size

    // =====================================================================
    // Capacity control, called by the Core during recalculateCapacity()
    // =====================================================================

    fun setCapacities(countCap: Long, typesCap: Int) {
        countCapacity = countCap.coerceAtLeast(0L)
        typesCapacity = typesCap.coerceAtLeast(0)
    }

    // =====================================================================
    // Query
    // =====================================================================

    fun get(key: BufferKey.Key): Long = items[key]?.count ?: 0L

    /** Backward-compat itemId-only query: sums across every component variant
     *  of [itemId]. Mirrors the old API's "all-of-this-item" semantics. */
    fun get(itemId: String): Long {
        var total = 0L
        for ((k, e) in items) if (k.itemId == itemId) total += e.count
        return total
    }

    /** Representative stack for [key], or null when nothing's stored. Used by
     *  [BufferSource] to rebuild a real [ItemStack] (with components) on extract
     *  instead of stripping them via `ItemStack(item, count)`. */
    fun template(key: BufferKey.Key): ItemStack? = items[key]?.template

    /** Snapshot of current contents grouped by [BufferKey.Key]. Returned map is
     *  a copy. Order is stable (LinkedHashMap-style insertion order). */
    fun contents(): Map<BufferKey.Key, Entry> = items.toMap()

    /** Backward-compat flattening to the old itemId → count shape. Sums across
     *  components variants. Used by save/load paths and any caller that doesn't
     *  yet distinguish variants. */
    fun contentsByItemId(): Map<String, Long> {
        val flat = LinkedHashMap<String, Long>()
        for ((k, e) in items) flat.merge(k.itemId, e.count) { a, b -> a + b }
        return flat
    }

    /** True iff inserting [amount] under [key] would succeed under both
     *  capacity axes. A new key consumes a types slot. */
    fun canAccept(key: BufferKey.Key, amount: Long): Boolean {
        if (amount <= 0) return true
        val wouldCount = count + amount
        if (wouldCount > countCapacity || wouldCount < 0) return false   // overflow guard
        if (key !in items && types >= typesCapacity) return false
        return true
    }

    /** Backward-compat itemId-only acceptance test. Treats the request as plain
     *  (empty components). Existing callers that don't yet thread components
     *  through keep working, they just can't insert variants. */
    fun canAccept(itemId: String, amount: Long): Boolean =
        canAccept(BufferKey.Key(itemId, ""), amount)

    // =====================================================================
    // Mutation
    // =====================================================================

    /** Insert [amount] of [stack]'s identity (item + components). Returns true
     *  on success, false on failure. Failures are atomic. */
    fun insert(stack: ItemStack, amount: Long): Boolean {
        if (amount <= 0) return true
        val key = BufferKey.of(stack)
        if (!canAccept(key, amount)) return false
        val existing = items[key]
        if (existing == null) {
            // Template is a single-count copy so future ItemStack rebuilds get
            // a clean count to overwrite without surprising the buffer's bookkeeping.
            items[key] = Entry(stack.copyWithCount(1), amount)
        } else {
            items[key] = existing.copy(count = existing.count + amount)
        }
        return true
    }

    /** Backward-compat itemId-only insertion. Builds a plain (empty-components)
     *  template stack so the bucket is keyed under empty-hash. */
    fun insert(itemId: String, amount: Long): Boolean {
        if (amount <= 0) return true
        val identifier = Identifier.tryParse(itemId) ?: return false
        val item = BuiltInRegistries.ITEM.getValue(identifier) ?: return false
        return insert(ItemStack(item), amount)
    }

    /** Extract up to [amount] from [key]. Returns the amount actually extracted
     *  (≤ amount, could be 0 if none stored). Removes the bucket entirely when
     *  the last of a variant is pulled out, freeing a types slot. */
    fun extract(key: BufferKey.Key, amount: Long): Long {
        if (amount <= 0) return 0L
        val current = items[key] ?: return 0L
        val extracted = minOf(current.count, amount)
        val remaining = current.count - extracted
        if (remaining == 0L) items.remove(key) else items[key] = current.copy(count = remaining)
        return extracted
    }

    /** Backward-compat itemId-only extract. Extracts from the plain-components
     *  bucket only, variants stay put. Callers that need variant-aware extract
     *  must thread a [BufferKey.Key]. */
    fun extract(itemId: String, amount: Long): Long =
        extract(BufferKey.Key(itemId, ""), amount)

    /** Empty the buffer. Returns a snapshot of what was removed (component-aware)
     *  so the caller can push it back into network storage as real ItemStacks. */
    fun clear(): Map<BufferKey.Key, Entry> {
        val snapshot = items.toMap()
        items.clear()
        return snapshot
    }

    fun isEmpty(): Boolean = items.isEmpty()

    // =====================================================================
    // Serialization
    // =====================================================================

    /** Writes buffer contents and capacity to [tag]. Format v3 is a list of
     *  per-bucket entries each carrying an ItemStack (preserves components)
     *  and a Long count. Legacy formats are still read by [loadFromNBT] for
     *  worlds saved before this version. Needs a [registryAccess] to encode
     *  components-bearing stacks since some component types reference
     *  registry-backed values (e.g. potion effects, enchantments). */
    fun saveToNBT(tag: CompoundTag, registryAccess: net.minecraft.core.HolderLookup.Provider) {
        val ops = RegistryOps.create(NbtOps.INSTANCE, registryAccess)
        val entriesTag = net.minecraft.nbt.ListTag()
        for ((_, entry) in items) {
            if (entry.count <= 0) continue
            val entryTag = CompoundTag()
            // Save the components-bearing template stack with count = 1; the
            // authoritative count lives next to it as a Long so worlds with
            // > Int.MAX_VALUE buffered items still serialize correctly.
            val stackTag = ItemStack.CODEC.encodeStart(ops, entry.template).result().orElse(null) ?: continue
            entryTag.put("stack", stackTag)
            entryTag.putLong("count", entry.count)
            entriesTag.add(entryTag)
        }
        tag.put("entries", entriesTag)
        tag.putLong("countCap", countCapacity)
        tag.putInt("typesCap", typesCapacity)
    }

    /** Restores buffer contents and capacity from [tag]. Three formats:
     *   1. `entries` list (v3, current): component-aware ItemStack entries.
     *   2. `items` compound (v2): itemId to Long count, no components.
     *   3. Top-level itemId keys (v1, very old): itemId to Int|Long count.
     *  Each older format migrates to the empty-components bucket of its itemId. */
    fun loadFromNBT(tag: CompoundTag, registryAccess: net.minecraft.core.HolderLookup.Provider) {
        items.clear()
        val ops = RegistryOps.create(NbtOps.INSTANCE, registryAccess)

        val entriesTag = tag.get("entries") as? net.minecraft.nbt.ListTag
        if (entriesTag != null) {
            for (i in 0 until entriesTag.size) {
                val entryTag = entriesTag.getCompound(i).orElse(null) ?: continue
                val stackTag = entryTag.get("stack") ?: continue
                val stack = ItemStack.CODEC.parse(ops, stackTag).result().orElse(null) ?: continue
                if (stack.isEmpty) continue
                val cnt = entryTag.getLong("count").orElse(0L)
                if (cnt <= 0L) continue
                items[BufferKey.of(stack)] = Entry(stack.copyWithCount(1), cnt)
            }
        } else {
            // Legacy migration: itemId → count, no components. Each entry lands
            // in the empty-components bucket of its item id.
            val itemsTag = tag.getCompound("items").orElse(null)
            if (itemsTag != null) {
                for (key in itemsTag.keySet()) {
                    migrateLegacyEntry(key, readLongOrInt(itemsTag, key))
                }
            } else {
                for (key in tag.keySet()) {
                    if (key in RESERVED_KEYS) continue
                    migrateLegacyEntry(key, readLongOrInt(tag, key))
                }
            }
        }

        countCapacity = tag.getLong("countCap").orElse(CpuRules.CORE_BASE_COUNT).coerceAtLeast(0L)
        typesCapacity = tag.getInt("typesCap").orElse(CpuRules.CORE_BASE_TYPES).coerceAtLeast(0)
    }

    private fun migrateLegacyEntry(itemId: String, count: Long) {
        if (count <= 0) return
        val identifier = Identifier.tryParse(itemId) ?: return
        val item = BuiltInRegistries.ITEM.getValue(identifier) ?: return
        val stack = ItemStack(item)
        items[BufferKey.of(stack)] = Entry(stack.copyWithCount(1), count)
    }

    private fun readLongOrInt(t: CompoundTag, key: String): Long =
        t.getLong(key).orElse(null)
            ?: t.getInt(key).orElse(0).toLong()

    companion object {
        /** NBT keys that aren't item IDs, used by the v1-format legacy loader. */
        private val RESERVED_KEYS = setOf("items", "entries", "countCap", "typesCap")
    }
}
