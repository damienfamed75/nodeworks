package damien.nodeworks.card

import net.minecraft.core.component.DataComponents
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.StringTag
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.CustomData
import java.util.concurrent.ConcurrentHashMap

/**
 * Tags on a card. A tag names a role, "ore", "overflow", "smelter-in", and is a fact
 * about a layout convention rather than about one block, so a script that asks for
 * storage tagged "ore" runs in any network whose owner tagged a chest that way. A card
 * carries up to [MAX_TAGS]. Stored under `tags` in the card's custom data beside its
 * other keys.
 */
object CardTags {
    private const val KEY = "tags"
    const val MAX_TAGS = 8
    const val MAX_TAG_LENGTH = 24

    /** Lowercase, trimmed, inner whitespace turned into dashes, anything else dropped.
     *  Null when nothing usable remains. Typos stay typos, the GUI offers known tags. */
    fun normalize(raw: String): String? {
        val sb = StringBuilder()
        var pendingDash = false
        for (ch in raw.trim().lowercase()) {
            when {
                ch.isWhitespace() -> pendingDash = sb.isNotEmpty()
                ch.isLetterOrDigit() || ch == '_' || ch == '-' || ch == '.' || ch == ':' -> {
                    if (pendingDash) { sb.append('-'); pendingDash = false }
                    sb.append(ch)
                }
            }
            if (sb.length >= MAX_TAG_LENGTH) break
        }
        return sb.toString().trim('-').takeIf { it.isNotEmpty() }
    }

    fun clean(tags: List<String>): List<String> = tags.mapNotNull { normalize(it) }.distinct().take(MAX_TAGS)

    fun get(stack: ItemStack): List<String> {
        val list = stack.get(DataComponents.CUSTOM_DATA)?.copyTag()?.getList(KEY)?.orElse(null) ?: return emptyList()
        return (0 until list.size).map { list.getStringOr(it, "") }.filter { it.isNotEmpty() }
    }

    fun set(stack: ItemStack, tags: List<String>) {
        val cleaned = clean(tags)
        if (get(stack) == cleaned) return
        val tag = stack.get(DataComponents.CUSTOM_DATA)?.copyTag() ?: CompoundTag()
        if (cleaned.isEmpty()) {
            tag.remove(KEY)
        } else {
            val list = ListTag()
            for (t in cleaned) list.add(StringTag.valueOf(t))
            tag.put(KEY, list)
        }
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag))
    }
}

/**
 * Tags the server has seen on cards, so the GUI can offer what already exists rather
 * than have every player invent a spelling. Filled as Nodes load and as cards are
 * tagged, cleared with the server. Recency first, so the tags in use come up first.
 */
object KnownCardTags {
    const val MAX_OFFERED = 64

    private val lastSeen = ConcurrentHashMap<String, Long>()

    fun record(tags: Collection<String>, tick: Long) {
        for (t in tags) lastSeen[t] = tick
    }

    fun all(): List<String> =
        lastSeen.entries.sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key })
            .take(MAX_OFFERED).map { it.key }

    fun clear() {
        lastSeen.clear()
    }
}
