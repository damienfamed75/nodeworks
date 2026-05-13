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
) {
    val itemId: String get() = BuiltInRegistries.ITEM.getKey(stack.item).toString()

    val componentsHash: String get() = BufferKey.componentsHash(stack)

    /** Canonical key for hashing into a [RecipeId]. Includes count so a recipe
     *  consuming 8 iron differs from one consuming 1 iron, and includes the
     *  components hash so a strength potion differs from a healing potion. */
    val canonicalKey: String get() = "$itemId@$count#$componentsHash"

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

    /** Per-session hash of a components patch. Stable WITHIN one server
     *  session (the [DataComponentPatch.hashCode] backing map is
     *  deterministic on identical contents), but NOT guaranteed stable
     *  across world reloads because [Holder.Reference] uses object identity
     *  in its hashCode. Use [stableComponentsHash] when the hash will be
     *  persisted to NBT and compared after a reload (recipe identity,
     *  bound-handler names, etc).
     *
     *  Empty patch returns "" so plain items behave exactly like the old
     *  itemId-only code paths in maps and storage queries. */
    fun componentsHash(patch: net.minecraft.core.component.DataComponentPatch): String {
        if (patch.size() == 0) return ""
        return "%08x".format(patch.hashCode())
    }

    fun componentsHash(stack: ItemStack): String = componentsHash(stack.componentsPatch)

    /** Cross-session-stable hash of a components patch. Encodes the patch via
     *  [DataComponentPatch.CODEC] under [RegistryOps] so registry-backed
     *  values (Holder<Potion>, Holder<Enchantment>, banner pattern holders)
     *  serialize by their REGISTRY ID rather than their object identity. The
     *  encoded NBT's string representation is canonical for equal patches
     *  (vanilla codecs produce identical NBT for identical data), so the
     *  resulting SHA-256 truncation is stable across world reloads.
     *
     *  Used by [RecipeId.of] so persisted recipe identities (the
     *  `recipe_<hash>` strings stored on Processing Handlers and on
     *  ProcessingSet cards' canonical names) round-trip cleanly across
     *  save/load. */
    fun stableComponentsHash(
        patch: net.minecraft.core.component.DataComponentPatch,
        registries: net.minecraft.core.HolderLookup.Provider,
    ): String {
        if (patch.size() == 0) return ""
        val ops = net.minecraft.resources.RegistryOps.create(net.minecraft.nbt.NbtOps.INSTANCE, registries)
        val tag = net.minecraft.core.component.DataComponentPatch.CODEC
            .encodeStart(ops, patch).result().orElse(null)
            ?: return componentsHash(patch)  // fallback: in-session hash if codec fails
        val bytes = tag.toString().toByteArray(Charsets.UTF_8)
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
        return buildString(8) {
            for (i in 0 until 4) append("%02x".format(digest[i]))
        }
    }

    fun stableComponentsHash(
        stack: ItemStack,
        registries: net.minecraft.core.HolderLookup.Provider,
    ): String = stableComponentsHash(stack.componentsPatch, registries)

    fun of(stack: ItemStack): Key {
        val itemId = BuiltInRegistries.ITEM.getKey(stack.item).toString()
        return Key(itemId, componentsHash(stack))
    }
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
        val canonical = canonicalString(inputs, outputs, fuzzy) { it.canonicalKey }
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
            "${ingr.itemId}@${ingr.count}#${BufferKey.stableComponentsHash(ingr.stack, registries)}"
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
