package damien.nodeworks.block.entity

import damien.nodeworks.script.ServerPolicy
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.tags.TagKey
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack

/**
 * Resolves the User device's deny list against the active item registry. The
 * raw config is a list of strings: bare ids match a single item, `#namespace:tag`
 * entries match every item in that tag. Tag keys are resolved once per
 * snapshot of [ServerPolicy.current.userDeniedItems] so a `/reload` propagates
 * naturally on the next [isDenied] call without per-call parsing overhead.
 */
object UserDenyList {

    private data class ParsedRules(
        val source: List<String>,
        val itemIds: Set<String>,
        val tagKeys: Set<TagKey<Item>>,
    )

    @Volatile
    private var cache: ParsedRules = parse(emptyList())

    private fun parse(raw: List<String>): ParsedRules {
        val ids = HashSet<String>()
        val tags = HashSet<TagKey<Item>>()
        for (entry in raw) {
            val trimmed = entry.trim()
            if (trimmed.isEmpty()) continue
            if (trimmed.startsWith("#")) {
                val id = Identifier.tryParse(trimmed.removePrefix("#")) ?: continue
                tags.add(TagKey.create(Registries.ITEM, id))
            } else {
                ids.add(trimmed)
            }
        }
        return ParsedRules(raw, ids, tags)
    }

    private fun current(): ParsedRules {
        val raw = ServerPolicy.current.userDeniedItems
        val snapshot = cache
        // Identity-eq on the raw list isn't reliable across snapshots (a fresh
        // snapshot always allocates a new list), so compare contents. Cheap
        // because the list is small and only changes on /reload.
        if (snapshot.source == raw) return snapshot
        val parsed = parse(raw)
        cache = parsed
        return parsed
    }

    /** True if [stack] matches any deny-list entry. Empty stacks are never
     *  denied. Tag matching uses the live registry via [ItemStack.is], so
     *  custom modded tags resolve correctly without extra plumbing. */
    fun isDenied(stack: ItemStack, level: ServerLevel): Boolean {
        if (stack.isEmpty) return false
        val rules = current()
        if (rules.itemIds.isEmpty() && rules.tagKeys.isEmpty()) return false
        val itemId = BuiltInRegistries.ITEM.getKey(stack.item)?.toString()
        if (itemId != null && itemId in rules.itemIds) return true
        for (tagKey in rules.tagKeys) {
            if (stack.`is`(tagKey)) return true
        }
        return false
    }
}
