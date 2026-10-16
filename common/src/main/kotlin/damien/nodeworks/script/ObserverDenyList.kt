package damien.nodeworks.script

import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.tags.TagKey
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState

/**
 * Resolves [ServerSafetySettings.observerDeniedBlocks], the blocks whose data
 * `ObserverCard:data()` won't read. Bare ids match a single block,
 * `#namespace:tag` entries match every block in that tag. Parsed once per
 * snapshot of the setting, so a `/reload` applies on the next [isDenied] call.
 */
object ObserverDenyList {

    private data class ParsedRules(
        val source: List<String>,
        val blockIds: Set<String>,
        val tagKeys: Set<TagKey<Block>>,
    )

    @Volatile
    private var cache: ParsedRules = parse(emptyList())

    private fun parse(raw: List<String>): ParsedRules {
        val ids = HashSet<String>()
        val tags = HashSet<TagKey<Block>>()
        for (entry in raw) {
            val trimmed = entry.trim()
            if (trimmed.isEmpty()) continue
            if (trimmed.startsWith("#")) {
                val id = Identifier.tryParse(trimmed.removePrefix("#")) ?: continue
                tags.add(TagKey.create(Registries.BLOCK, id))
            } else {
                ids.add(trimmed)
            }
        }
        return ParsedRules(raw, ids, tags)
    }

    private fun current(): ParsedRules {
        val raw = ServerPolicy.current.observerDeniedBlocks
        val snapshot = cache
        // Contents, not identity: every config snapshot allocates a fresh list.
        if (snapshot.source == raw) return snapshot
        val parsed = parse(raw)
        cache = parsed
        return parsed
    }

    /** True if [state]'s block matches any entry. */
    fun isDenied(state: BlockState): Boolean {
        val rules = current()
        if (rules.blockIds.isEmpty() && rules.tagKeys.isEmpty()) return false
        if (BuiltInRegistries.BLOCK.getKey(state.block).toString() in rules.blockIds) return true
        for (tagKey in rules.tagKeys) {
            if (state.`is`(tagKey)) return true
        }
        return false
    }
}
