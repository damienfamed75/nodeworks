package damien.nodeworks.script

import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.item.ItemStack
import java.security.MessageDigest

/**
 * Recipe identity primitives for the component-aware Processing Set rewrite.
 *
 * Three concerns:
 *
 *  - [RecipeIngredient] groups an [ItemStack] with an explicit count. The stack
 *    carries item + components, the count is a separate Int because recipe
 *    counts routinely exceed `ItemStack.MAX_STACK_SIZE` (e.g. `64 iron`) and
 *    reusing the stack's own `count` field would silently truncate.
 *  - [BufferKey] is the keying primitive for any structure that wants to
 *    distinguish item variants by their DataComponents (CPU buffer, storage
 *    aggregation, per-input channel maps on Processing Handlers, planner
 *    ingredient counts).
 *  - [RecipeId] hashes a full recipe (inputs + outputs + fuzzy flag) into a
 *    stable `recipe_<12hex>` identifier. The hash is order-independent on each
 *    side (same items in different slots collapse) but order-sensitive across
 *    sides (inputs vs outputs are kept distinct). Fuzzy and exact variants of
 *    the same recipe get distinct ids.
 *
 * Per-stack [componentsHash] uses [DataComponentPatch.hashCode] under the hood,
 * which delegates to the patch's `ImmutableMap` of component-type → value pairs.
 * Each MC component type ships proper equals/hashCode, so two ItemStacks with
 * identical components hash to the same int across sessions. Empty patches hash
 * to the empty string so plain-item code paths keep working unchanged.
 */

data class RecipeIngredient(
    val stack: ItemStack,
    val count: Int,
    /** Item tag (no `#`) this input accepts any member of while its Processing Set has
     *  substitutions on. [stack] is then just the example shown in the editor. Null for
     *  exact inputs and for outputs. */
    val tag: String? = null,
    /** An output the machine only sometimes makes. A job never waits for it, takes it
     *  when it turns up, and the planner never counts on it. Inputs are never optional. */
    val optional: Boolean = false,
) {
    val itemId: String get() = BuiltInRegistries.ITEM.getKey(stack.item).toString()

    val componentsHash: String get() = BufferKey.componentsHash(stack)

    /** Canonical key for hashing into a [RecipeId]. Includes count so a recipe
     *  consuming 8 iron differs from one consuming 1 iron, and includes the
     *  components hash so a strength potion differs from a healing potion. */
    val canonicalKey: String get() = "$itemId@$count#$componentsHash" + (if (optional) "~" else "")

    /** Key for a set with substitutions on: a tag input keys on its tag, so sets built
     *  from the same tag recipe share an id whatever example item each shows. */
    val substitutionKey: String get() = if (tag != null) "#$tag@$count" else canonicalKey

    fun bufferKey(): BufferKey.Key = BufferKey.Key(itemId, componentsHash)
}

object BufferKey {
    /** Identity of an item in the CPU buffer / storage-aggregation maps. Two
     *  variants of the same itemId (e.g. strength vs healing potion) get
     *  separate keys; plain items (no components) share a key with all other
     *  plain instances of the same itemId. */
    data class Key(val itemId: String, val componentsHash: String) {
        val isPlain: Boolean get() = componentsHash.isEmpty()
    }

    /** Where the hash gets a registry access for the component codecs. The loader installs
     *  the server's, and on the client the level's, so a client on a dedicated server
     *  encodes exactly as the server does and the two agree on every key that crosses
     *  the wire. Without either the hash falls back to the in-session value, unmemoized. */
    @Volatile
    var registryProvider: () -> net.minecraft.core.HolderLookup.Provider? = { null }

    @Volatile
    var clientRegistryProvider: () -> net.minecraft.core.HolderLookup.Provider? = { null }

    /** Entries past which a sweep may run. Below it the memo is never touched by one. */
    private const val SWEEP_ABOVE = 65_536
    /** Entries past which, even after a sweep, the memo starts over. A safety stop only. */
    private const val HARD_CAP = 4_000_000
    /** An entry not asked for this long is swept. Every poll cycle asks for every live
     *  variant, so a live one is never swept however many there are. */
    private const val STALE_SECONDS = 600
    private const val SWEEP_EVERY_SECONDS = 30

    private class Memo(val patch: net.minecraft.core.component.DataComponentPatch, val hash: String) {
        @Volatile var lastUsed: Int = 0
    }

    /** Patches seen, by their Java hash, each with the patches sharing it and their content
     *  hashes, resolved by equality so two different patches with one Java hash never
     *  share a key. Reads take no lock. Size follows the live variants, see the sweep. */
    private val memo = java.util.concurrent.ConcurrentHashMap<Int, Array<Memo>>()
    private val lastSweep = java.util.concurrent.atomic.AtomicInteger(0)

    /** One sweep pass at a time, carried across slices. [sweepCursor] and
     *  [sweepStaleBefore] are only touched by the thread holding [sweeping]. */
    private val sweeping = java.util.concurrent.atomic.AtomicBoolean(false)
    private var sweepCursor: Iterator<Int>? = null
    private var sweepStaleBefore = 0

    /** Keys one slice visits, a few hundred microseconds, so no thread ever stalls on it. */
    private const val SWEEP_SLICE_KEYS = 2_048

    /** Item ids by item, so a scan of a million slots never builds a million strings. */
    private val itemIds = java.util.concurrent.ConcurrentHashMap<net.minecraft.world.item.Item, String>()

    private val HEX = "0123456789abcdef".toCharArray()
    private val START_NANOS = System.nanoTime()

    private fun nowSeconds(): Int = ((System.nanoTime() - START_NANOS) / 1_000_000_000L).toInt()

    fun itemId(item: net.minecraft.world.item.Item): String =
        itemIds[item] ?: itemIds.computeIfAbsent(item) { BuiltInRegistries.ITEM.getKey(it).toString() }

    /**
     * Identity of a variant: 16 hex digits of a 64-bit hash over the patch encoded through
     * the component codecs, the encoding the world saves, so registry-backed values hash
     * by their ids and two variants that differ never share a key. The in-session Java
     * hash is only a bucket, a patch is the same variant as a memoized one only when equal,
     * which also covers a component type whose hashCode is weaker than its equals.
     *
     * Computed once per distinct patch. A hit is the Java hash, one lock-free map read and
     * one equals, no allocation beyond the caller's patch. Empty patch returns "" so plain
     * items behave exactly like the old itemId-only code paths in maps and storage queries.
     */
    fun componentsHash(patch: net.minecraft.core.component.DataComponentPatch): String {
        if (patch.size() == 0) return ""
        return hashOf(patch)
    }

    fun componentsHash(stack: ItemStack): String = componentsHash(stack.componentsPatch)

    private fun hashOf(patch: net.minecraft.core.component.DataComponentPatch): String {
        val javaHash = patch.hashCode()
        val now = nowSeconds()
        memo[javaHash]?.let { bucket ->
            for (m in bucket) if (m.patch == patch) {
                m.lastUsed = now
                return m.hash
            }
        }
        // No registry yet, before the server is up or outside a world on the client. The
        // in-session value serves, and is not kept, so the content hash takes over as soon
        // as a registry is there rather than this patch staying on the old value.
        val registries = registryProvider() ?: clientRegistryProvider() ?: return hex8(javaHash)
        // A codec that refuses will refuse again, that fallback is kept.
        val hash = encode(patch, registries)?.let { hex16(TagHash.of(it)) } ?: hex8(javaHash)
        val entry = Memo(patch, hash).also { it.lastUsed = now }
        memo.compute(javaHash) { _, old ->
            when {
                old == null -> arrayOf(entry)
                old.any { it.patch == patch } -> old
                else -> old + entry
            }
        }
        sweepSlice()
        return hash
    }

    /**
     * Advances the sweep by one slice. Drops entries nothing has asked for in
     * [STALE_SECONDS], once the memo is large and not more often than [SWEEP_EVERY_SECONDS]
     * per pass. A variant still in storage is asked for every poll and stays, so a network
     * with a million live variants keeps a million entries and never recomputes one. Only
     * variants that left the network go.
     *
     * A pass walks the map a slice at a time, [SWEEP_SLICE_KEYS] keys per call, carried by
     * an iterator across calls. Each miss advances it, and the server advances it once a
     * tick so a quiet network still finishes a pass, a million entries in under half a
     * minute of ticks. No call ever walks the whole map, so no thread stalls on it. The
     * map's iterator is weakly consistent, an entry added mid-pass is seen or not, either
     * is fine. Another thread finding a slice in progress simply moves on.
     */
    fun sweepSlice() {
        if (memo.size < SWEEP_ABOVE) return
        if (!sweeping.compareAndSet(false, true)) return
        try {
            val now = nowSeconds()
            var cursor = sweepCursor
            if (cursor == null) {
                if (now - lastSweep.get() < SWEEP_EVERY_SECONDS && memo.size < HARD_CAP) return
                lastSweep.set(now)
                sweepStaleBefore = now - STALE_SECONDS
                cursor = memo.keys.iterator()
                sweepCursor = cursor
            }
            val stale = sweepStaleBefore
            var visited = 0
            while (visited < SWEEP_SLICE_KEYS && cursor.hasNext()) {
                val key = cursor.next()
                visited++
                memo.computeIfPresent(key) { _, arr ->
                    var kept = 0
                    for (m in arr) if (m.lastUsed >= stale) kept++
                    when (kept) {
                        arr.size -> arr
                        0 -> null
                        else -> arr.filter { it.lastUsed >= stale }.toTypedArray()
                    }
                }
            }
            if (!cursor.hasNext()) {
                sweepCursor = null
                if (memo.size >= HARD_CAP) memo.clear()
            }
        } finally {
            sweeping.set(false)
        }
    }

    private fun encode(
        patch: net.minecraft.core.component.DataComponentPatch,
        registries: net.minecraft.core.HolderLookup.Provider,
    ): net.minecraft.nbt.Tag? {
        val ops = net.minecraft.resources.RegistryOps.create(net.minecraft.nbt.NbtOps.INSTANCE, registries)
        return try {
            net.minecraft.core.component.DataComponentPatch.CODEC.encodeStart(ops, patch).result().orElse(null)
        } catch (_: Exception) {
            null
        }
    }

    private fun hex16(v: Long): String {
        val out = CharArray(16)
        var x = v
        for (i in 15 downTo 0) {
            out[i] = HEX[(x and 0xFL).toInt()]
            x = x ushr 4
        }
        return String(out)
    }

    private fun hex8(v: Int): String {
        val out = CharArray(8)
        var x = v
        for (i in 7 downTo 0) {
            out[i] = HEX[x and 0xF]
            x = x ushr 4
        }
        return String(out)
    }

    /** A 64-bit hash of an NBT tree by content: compound keys in sorted order, every value
     *  by its type and bits, no string form in between. Equal trees hash equal in every
     *  session on every side. */
    private object TagHash {
        private val M1 = 0x9E3779B97F4A7C15uL.toLong()
        private val M2 = 0xBF58476D1CE4E5B9uL.toLong()

        private fun mix(h: Long, v: Long): Long {
            var x = (h xor v) * M1
            x = x xor (x ushr 32)
            x *= M2
            return x xor (x ushr 29)
        }

        private fun mixString(h0: Long, s: String): Long {
            var h = mix(h0, s.length.toLong())
            for (c in s) h = mix(h, c.code.toLong())
            return h
        }

        fun of(tag: net.minecraft.nbt.Tag): Long = hash(0x4E4F4445574F524BL, tag)

        private fun hash(h0: Long, tag: net.minecraft.nbt.Tag): Long {
            var h = mix(h0, tag.id.toLong())
            when (tag) {
                is net.minecraft.nbt.CompoundTag -> {
                    h = mix(h, tag.size().toLong())
                    for (key in tag.keySet().sorted()) {
                        h = mixString(h, key)
                        h = hash(h, tag.get(key) ?: continue)
                    }
                }
                is net.minecraft.nbt.ListTag -> {
                    h = mix(h, tag.size.toLong())
                    for (t in tag) h = hash(h, t)
                }
                is net.minecraft.nbt.StringTag -> h = mixString(h, tag.value())
                is net.minecraft.nbt.FloatTag -> h = mix(h, java.lang.Float.floatToIntBits(tag.value()).toLong())
                is net.minecraft.nbt.DoubleTag -> h = mix(h, java.lang.Double.doubleToLongBits(tag.value()))
                is net.minecraft.nbt.NumericTag -> h = mix(h, tag.longValue())
                is net.minecraft.nbt.ByteArrayTag -> {
                    val a = tag.asByteArray
                    h = mix(h, a.size.toLong())
                    for (b in a) h = mix(h, b.toLong())
                }
                is net.minecraft.nbt.IntArrayTag -> {
                    val a = tag.asIntArray
                    h = mix(h, a.size.toLong())
                    for (i in a) h = mix(h, i.toLong())
                }
                is net.minecraft.nbt.LongArrayTag -> {
                    val a = tag.asLongArray
                    h = mix(h, a.size.toLong())
                    for (l in a) h = mix(h, l)
                }
                else -> {}
            }
            return h
        }
    }

    /** Cross-session-stable hash of a components patch, eight hex digits, the width every
     *  persisted recipe_ id was built with. Not widened and not moved off the SHA of the
     *  string form, every saved recipe id depends on exactly this. Rare path: recipe ids
     *  are computed when a Processing Set is saved, not per slot. */
    fun stableComponentsHash(
        patch: net.minecraft.core.component.DataComponentPatch,
        registries: net.minecraft.core.HolderLookup.Provider,
    ): String {
        if (patch.size() == 0) return ""
        val tag = encode(patch, registries) ?: return componentsHash(patch)
        val digest = MessageDigest.getInstance("SHA-256").digest(tag.toString().toByteArray(Charsets.UTF_8))
        return buildString(8) {
            for (i in 0 until 4) {
                append(HEX[(digest[i].toInt() ushr 4) and 0xF])
                append(HEX[digest[i].toInt() and 0xF])
            }
        }
    }

    fun stableComponentsHash(
        stack: ItemStack,
        registries: net.minecraft.core.HolderLookup.Provider,
    ): String = stableComponentsHash(stack.componentsPatch, registries)

    fun of(stack: ItemStack): Key = Key(itemId(stack.item), componentsHash(stack))
}

object RecipeId {
    private const val PREFIX = "recipe_"
    private const val HEX_CHARS = 12

    /** Hash a recipe's structure to a stable `recipe_<12hex>` id. Inputs and
     *  outputs are independently sorted by their canonical key before hashing
     *  so two recipes that differ only in slot layout collapse to the same
     *  id. The `fuzzy` flag is part of the hash so a fuzzy recipe and its
     *  exact-match twin have distinct ids and can coexist on one network.
     *
     *  **WITHIN-SESSION variant.** Uses [BufferKey.componentsHash] which
     *  depends on Java hashCode, NOT stable across world reloads. Prefer
     *  the [registries]-aware overload for persisted recipe identity. */
    fun of(inputs: List<RecipeIngredient>, outputs: List<RecipeIngredient>, fuzzy: Boolean): String {
        val canonical = canonicalString(inputs, outputs, fuzzy) { if (fuzzy) it.substitutionKey else it.canonicalKey }
        return digestTo(canonical)
    }

    /** Cross-session-stable variant. Encodes each ingredient's components
     *  via [BufferKey.stableComponentsHash] so the resulting recipe id is
     *  byte-identical across world reloads for equal recipes. */
    fun of(
        inputs: List<RecipeIngredient>,
        outputs: List<RecipeIngredient>,
        fuzzy: Boolean,
        registries: net.minecraft.core.HolderLookup.Provider,
    ): String {
        val canonical = canonicalString(inputs, outputs, fuzzy) { ingr ->
            if (fuzzy && ingr.tag != null) "#${ingr.tag}@${ingr.count}"
            else "${ingr.itemId}@${ingr.count}#${BufferKey.stableComponentsHash(ingr.stack, registries)}" + (if (ingr.optional) "~" else "")
        }
        return digestTo(canonical)
    }

    private inline fun canonicalString(
        inputs: List<RecipeIngredient>,
        outputs: List<RecipeIngredient>,
        fuzzy: Boolean,
        keyOf: (RecipeIngredient) -> String,
    ): String = buildString {
        append("fuzzy:").append(fuzzy).append('|')
        append("in:[")
        inputs.map(keyOf).sorted().joinTo(this, ";")
        append("]|out:[")
        outputs.map(keyOf).sorted().joinTo(this, ";")
        append(']')
    }

    private fun digestTo(canonical: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        val hex = buildString(HEX_CHARS) {
            for (i in 0 until HEX_CHARS / 2) append("%02x".format(digest[i]))
        }
        return PREFIX + hex
    }

    /** True when [id] is in the new hash format. Used during migration to
     *  distinguish legacy canonical-id strings (which start with `minecraft:`
     *  or another mod namespace) from new-format ids. */
    fun isRecipeId(id: String): Boolean = id.startsWith(PREFIX)
}
