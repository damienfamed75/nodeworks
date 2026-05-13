package damien.nodeworks.script

import com.mojang.brigadier.StringReader
import com.mojang.brigadier.exceptions.CommandSyntaxException
import damien.nodeworks.platform.ResourceKind
import net.minecraft.commands.arguments.item.ItemParser
import net.minecraft.core.HolderLookup
import net.minecraft.core.component.DataComponentPatch
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.world.item.ItemStack

/**
 * Structured filter representation for Storage Card rule lists, Stocker / IO
 * card target slots, and the `network:find` / `CardHandle:find` /
 * `network:craft` script APIs.
 *
 * Wire format mirrors vanilla `/give` argument syntax so a stack dragged from
 * the player's inventory round-trips cleanly through [format] / [parse]:
 *
 *   - `minecraft:cobblestone`                    plain item id (any variant)
 *   - `minecraft:potion[minecraft:potion_contents={potion:"minecraft:fire_resistance"}]`
 *                                                item id + specific component patch
 *   - `#minecraft:logs`                          item tag (components not supported)
 *   - `/^.*_ore$/`                               regex over the item id
 *   - `mod:*`                                    namespace prefix
 *   - `*`                                        anything
 *   - `$item:<inner>` / `$fluid:<inner>`         kind sigil that wraps any of the above
 *
 * Match semantics: [Item] with [Item.componentsPatch] = null matches any
 * variant of [Item.itemId] (the legacy behaviour). [Item.componentsPatch]
 * non-null further requires the candidate stack's components to hash equal
 * to the patch, so dragging a Strength Potion onto a rule produces a rule
 * that only matches Strength Potions, not Fire Resistance Potions.
 */
sealed class FilterRule {
    /** No rule, matches every resource. */
    object Any : FilterRule()

    /** Exact item-id match. When [componentsPatch] is non-null, also requires
     *  the candidate's components patch to hash equal. */
    data class Item(
        val itemId: String,
        val componentsPatch: DataComponentPatch? = null,
    ) : FilterRule()

    /** Item or fluid tag membership. Components aren't expressible here, MC
     *  tags are registry-id-set-based. */
    data class Tag(val tagId: String) : FilterRule()

    /** Regex over the resource id. */
    data class Regex(val pattern: java.util.regex.Pattern) : FilterRule()

    /** All resources whose id starts with `$namespace:`. */
    data class Namespace(val namespace: String) : FilterRule()

    /** Sigil wrapper that locks the inner rule to one [ResourceKind]. */
    data class Kinded(val kind: ResourceKind, val inner: FilterRule) : FilterRule()

    /** Couldn't parse [raw]. Carrying the original lets the GUI surface it. */
    data class Invalid(val raw: String, val reason: String) : FilterRule()

    companion object {
        private const val MAX_REGEX_LENGTH = 200
        private const val MAX_REGEX_CACHE_SIZE = 64

        private val regexCache = object : LinkedHashMap<String, java.util.regex.Pattern>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, java.util.regex.Pattern>?): Boolean {
                return size > MAX_REGEX_CACHE_SIZE
            }
        }

        /** Parse a filter string. [registries] is required to resolve the
         *  component patch when the string contains `[...]`. Pass a vanilla
         *  registry access from the level (`level.registryAccess()`). */
        fun parse(filter: String, registries: HolderLookup.Provider): FilterRule {
            if (filter.isEmpty()) return Any

            // Kind sigil. Strips and recurses on the inner string.
            when {
                filter.startsWith("\$item:") ->
                    return Kinded(ResourceKind.ITEM, parse(filter.removePrefix("\$item:"), registries))
                filter.startsWith("\$fluid:") ->
                    return Kinded(ResourceKind.FLUID, parse(filter.removePrefix("\$fluid:"), registries))
            }

            if (filter == "*") return Any

            if (filter.startsWith("#")) {
                val tagId = filter.substring(1)
                if (Identifier.tryParse(tagId) == null) return Invalid(filter, "bad tag id")
                return Tag(tagId)
            }

            if (filter.startsWith("/") && filter.endsWith("/") && filter.length > 2) {
                val body = filter.substring(1, filter.length - 1)
                if (body.length > MAX_REGEX_LENGTH) return Invalid(filter, "regex too long")
                // LinkedHashMap with accessOrder isn't thread-safe; the
                // LAN-host case shares static state between client and server
                // threads. Synchronize the whole get-then-put around a
                // compile() that can throw so we don't leave a half-built
                // entry in the LRU. Compilation runs under the lock but the
                // 200-char regex cap bounds worst-case wait time to ~ms.
                val pattern = synchronized(regexCache) {
                    val cached = regexCache[body]
                    if (cached != null) cached
                    else try {
                        val compiled = java.util.regex.Pattern.compile(body)
                        regexCache[body] = compiled
                        compiled
                    } catch (_: Exception) { null }
                } ?: return Invalid(filter, "bad regex")
                return Regex(pattern)
            }

            if (filter.endsWith(":*")) {
                val namespace = filter.removeSuffix(":*")
                return Namespace(namespace)
            }

            // Vanilla item-arg syntax: `id[component=value,...]`.
            if (filter.contains('[')) {
                return parseItemArg(filter, registries)
            }

            // Plain item id, no component constraint.
            return Item(filter, componentsPatch = null)
        }

        /** Parse via vanilla [ItemParser] when the filter carries `[components]`. */
        private fun parseItemArg(filter: String, registries: HolderLookup.Provider): FilterRule {
            val parser = ItemParser(registries)
            return try {
                val result = parser.parse(StringReader(filter))
                val item = result.item().value()
                val itemId = BuiltInRegistries.ITEM.getKey(item)?.toString()
                    ?: return Invalid(filter, "unknown item")
                Item(itemId, result.components())
            } catch (e: CommandSyntaxException) {
                Invalid(filter, e.message ?: "parse error")
            }
        }

        /** Format a stack as a canonical filter string. Empty patches emit the
         *  plain item id, otherwise the bracketed component-args syntax that
         *  [parse] reads back. Used by drag-onto-rule GUI helpers so the stack
         *  becomes the rule that exactly matches it. */
        fun format(stack: ItemStack, registries: HolderLookup.Provider): String {
            val itemId = BuiltInRegistries.ITEM.getKey(stack.item).toString()
            val patch = stack.componentsPatch
            if (patch.size() == 0) return itemId
            val ops = net.minecraft.resources.RegistryOps.create(net.minecraft.nbt.NbtOps.INSTANCE, registries)
            val sb = StringBuilder(itemId).append('[')
            var first = true
            for (entry in patch.entrySet()) {
                if (!first) sb.append(',')
                first = false
                val type = entry.key
                val typeId = BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(type) ?: continue
                val opt = entry.value
                if (!opt.isPresent) {
                    // Removed component, vanilla `!key` syntax.
                    sb.append('!').append(typeId.toString())
                    continue
                }
                @Suppress("UNCHECKED_CAST")
                val codec = type.codecOrThrow() as com.mojang.serialization.Codec<kotlin.Any>
                val tag = codec.encodeStart(ops, opt.get()).result().orElse(null) ?: continue
                sb.append(typeId.toString()).append('=').append(tag.toString())
            }
            sb.append(']')
            return sb.toString()
        }
    }
}

/** Match [stack] against this rule. Item stacks only, fluid checks should
 *  use the [String]-id overload below since fluids don't carry components.
 *
 *  For [FilterRule.Item], a non-null `componentsPatch` requires the stack's
 *  in-session components hash to equal the patch's. Plain rules (null
 *  patch) match any variant of the item. */
fun FilterRule.matches(stack: ItemStack): Boolean {
    if (stack.isEmpty) return false
    val itemId = BuiltInRegistries.ITEM.getKey(stack.item).toString()
    return when (this) {
        is FilterRule.Any -> true
        is FilterRule.Item -> {
            if (itemId != this.itemId) return false
            val expected = this.componentsPatch ?: return true
            BufferKey.componentsHash(stack) == BufferKey.componentsHash(expected)
        }
        is FilterRule.Tag -> matchesIdAsTag(itemId, this.tagId, ResourceKind.ITEM)
        is FilterRule.Regex -> this.pattern.matcher(itemId).matches()
        is FilterRule.Namespace -> itemId.startsWith("${this.namespace}:")
        is FilterRule.Kinded -> this.kind == ResourceKind.ITEM && this.inner.matches(stack)
        is FilterRule.Invalid -> false
    }
}

/** Backwards-compat string-id matcher for the fluid path and any caller
 *  that doesn't have an [ItemStack] in hand. Component-bearing rules
 *  degrade to itemId-only matching here, callers should prefer the stack
 *  overload whenever they have the stack. */
fun FilterRule.matches(resourceId: String, kind: ResourceKind): Boolean {
    return when (this) {
        is FilterRule.Any -> true
        is FilterRule.Item -> resourceId == this.itemId
        is FilterRule.Tag -> matchesIdAsTag(resourceId, this.tagId, kind)
        is FilterRule.Regex -> this.pattern.matcher(resourceId).matches()
        is FilterRule.Namespace -> resourceId.startsWith("${this.namespace}:")
        is FilterRule.Kinded -> this.kind == kind && this.inner.matches(resourceId, kind)
        is FilterRule.Invalid -> false
    }
}

private fun matchesIdAsTag(resourceId: String, tagId: String, kind: ResourceKind): Boolean {
    val tagIdent = Identifier.tryParse(tagId) ?: return false
    val resIdent = Identifier.tryParse(resourceId) ?: return false
    return when (kind) {
        ResourceKind.ITEM -> {
            val tagKey = net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.ITEM, tagIdent)
            val item = BuiltInRegistries.ITEM.getValue(resIdent) ?: return false
            item.builtInRegistryHolder().`is`(tagKey)
        }
        ResourceKind.FLUID -> {
            val tagKey = net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.FLUID, tagIdent)
            val fluid = BuiltInRegistries.FLUID.getValue(resIdent) ?: return false
            fluid.builtInRegistryHolder().`is`(tagKey)
        }
    }
}
