package damien.nodeworks.screen.widget

import damien.nodeworks.compat.drawString
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.EditBox
import net.minecraft.core.registries.BuiltInRegistries

/**
 * Lightweight filter-string autocomplete dropdown for a single [EditBox].
 *
 * Built specifically for the Storage Card filter rule editor so the rule field
 * gets the same id / tag / sigil suggestion support the script editor's
 * `network:find` / `card:findEach` filter argument has, without dragging
 * `AutocompletePopup`'s editor-coupled token-stream machinery in.
 *
 * Lifecycle:
 * - The host calls [bindTo] when an [EditBox] gains focus and [unbind] when it
 *   loses focus or the host closes.
 * - Each frame the host calls [update] with the field's current text. The
 *   widget re-ranks suggestions (lazy registry-backed item/fluid/tag lists,
 *   FuzzyMatch'd by the partial).
 * - The host renders via [render] and forwards keyboard input through
 *   [keyPressed]. Mouse clicks come through [mouseClicked]. Both return true
 *   when the input was consumed.
 */
class FilterRuleAutocomplete(private val font: Font) {

    companion object {
        const val MAX_VISIBLE = 8

        /** Single row pixel height. Tight on purpose so 8 rows fit in ~88 px. */
        const val ROW_H = 11

        // Registry-backed completion sources, computed once. Kept lazy so a
        // dedicated server with no item registry doesn't crash, the widget
        // is client-side anyway but the lazy avoids paying for registry walk
        // until the first card GUI opens.
        private val itemIds: List<String> by lazy {
            BuiltInRegistries.ITEM.keySet().map { it.toString() }.sorted()
        }

        private val fluidIds: List<String> by lazy {
            BuiltInRegistries.FLUID.keySet()
                .map { it.toString() }
                .filter { !it.endsWith(":empty") && !it.contains(":flowing_") }
                .sorted()
        }

        private val itemTags: List<String> by lazy {
            BuiltInRegistries.ITEM.getTags().map { "#" + it.key().location.toString() }.sorted().toList()
        }

        private val fluidTags: List<String> by lazy {
            BuiltInRegistries.FLUID.getTags().map { "#" + it.key().location.toString() }.sorted().toList()
        }

        /** Sigils + bare wildcard for the empty-partial case. */
        private val sigils: List<String> = listOf("*", "\$item:", "\$fluid:")
    }

    private var anchor: EditBox? = null
    private var suggestions: List<String> = emptyList()
    private var selectedIndex: Int = 0

    val isOpen: Boolean get() = anchor != null && suggestions.isNotEmpty()

    fun bindTo(field: EditBox) {
        anchor = field
        update(field.value)
    }

    fun unbind() {
        anchor = null
        suggestions = emptyList()
        selectedIndex = 0
    }

    /** Recompute suggestions for [partial]. Mirrors the dispatch in
     *  `AutocompletePopup.suggestResourceFilter` so users see the same
     *  ranking behavior they're used to from script editor filter fields. */
    fun update(partial: String) {
        val pool = when {
            partial.startsWith("#") -> itemTags + fluidTags
            partial.startsWith("\$item:") -> {
                val inner = partial.removePrefix("\$item:")
                return rebuild(inner, itemIds.map { "\$item:$it" })
            }
            partial.startsWith("\$fluid:") -> {
                val inner = partial.removePrefix("\$fluid:")
                return rebuild(inner, fluidIds.map { "\$fluid:$it" })
            }
            partial.startsWith("\$") -> listOf("\$item:", "\$fluid:")
            else -> sigils + itemIds + itemTags + fluidIds.map { "\$fluid:$it" } + fluidTags
        }
        rebuild(partial, pool)
    }

    private fun rebuild(query: String, pool: List<String>) {
        suggestions = if (query.isEmpty()) {
            pool.take(MAX_VISIBLE * 4)
        } else {
            val q = query.lowercase()
            pool
                .asSequence()
                .filter { it.lowercase() != q }
                .map { it to scoreFuzzy(q, it.lowercase()) }
                .filter { it.second > 0 }
                .sortedByDescending { it.second }
                .map { it.first }
                .take(MAX_VISIBLE * 4)
                .toList()
        }
        selectedIndex = selectedIndex.coerceIn(0, (suggestions.size - 1).coerceAtLeast(0))
    }

    /** Cheap subsequence scoring, matches the spirit of the script editor's
     *  fuzzy matcher without depending on it: each query char must appear in
     *  the candidate in order, contiguous matches score higher, prefix
     *  matches score highest. Good enough for filter strings (~50 chars). */
    private fun scoreFuzzy(query: String, candidate: String): Int {
        if (candidate.startsWith(query)) return 1000 - candidate.length
        var qi = 0
        var streak = 0
        var score = 0
        for (c in candidate) {
            if (qi < query.length && c == query[qi]) {
                qi++
                streak++
                score += 5 + streak * 2
            } else {
                streak = 0
            }
        }
        return if (qi == query.length) score else 0
    }

    /** Renders the dropdown directly below the bound EditBox. Caller must
     *  invoke this after the rest of the screen so the popup layers above. */
    fun render(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val a = anchor ?: return
        if (suggestions.isEmpty()) return

        val visibleCount = suggestions.size.coerceAtMost(MAX_VISIBLE)
        val popupW = (a.width).coerceAtLeast(140)
        val popupH = visibleCount * ROW_H + 2

        val popupX = a.x
        val popupY = a.y + a.height + 1

        // Background + border so the popup reads as a separate surface from
        // the GUI behind it. Two-tone border matches the cycle button style.
        graphics.fill(popupX, popupY, popupX + popupW, popupY + popupH, 0xFF1A1A1A.toInt())
        graphics.fill(popupX - 1, popupY - 1, popupX + popupW + 1, popupY, 0xFF555555.toInt())
        graphics.fill(popupX - 1, popupY + popupH, popupX + popupW + 1, popupY + popupH + 1, 0xFF111111.toInt())
        graphics.fill(popupX - 1, popupY, popupX, popupY + popupH, 0xFF555555.toInt())
        graphics.fill(popupX + popupW, popupY, popupX + popupW + 1, popupY + popupH, 0xFF111111.toInt())

        for (i in 0 until visibleCount) {
            val rowY = popupY + 1 + i * ROW_H
            val rowHovered = mouseX in popupX until popupX + popupW &&
                mouseY in rowY until rowY + ROW_H
            val isSelected = i == selectedIndex
            if (isSelected) {
                graphics.fill(popupX, rowY, popupX + popupW, rowY + ROW_H, 0xFF3A6FB5.toInt())
            } else if (rowHovered) {
                graphics.fill(popupX, rowY, popupX + popupW, rowY + ROW_H, 0xFF2A2A2A.toInt())
            }
            // Truncate long ids visually so the popup width stays bounded.
            // Walk back chars one at a time until the rendered width fits,
            // then suffix with ellipsis. Cheap and avoids the FormattedText
            // path which ships its glyph stream as a `Lambda` toString.
            val raw = suggestions[i]
            val text = if (font.width(raw) <= popupW - 4) {
                raw
            } else {
                var end = raw.length
                while (end > 0 && font.width(raw.substring(0, end) + "...") > popupW - 4) end--
                raw.substring(0, end.coerceAtLeast(0)) + "..."
            }
            graphics.drawString(font, text, popupX + 3, rowY + 2, 0xFFFFFFFF.toInt())
        }
    }

    /** Returns the new value for the field if a suggestion was accepted, else null. */
    fun keyPressed(keyCode: Int): String? {
        if (suggestions.isEmpty() || anchor == null) return null
        return when (keyCode) {
            265 -> { // Up
                selectedIndex = (selectedIndex - 1).coerceAtLeast(0)
                ""
            }
            264 -> { // Down
                selectedIndex = (selectedIndex + 1).coerceAtMost(suggestions.size - 1)
                ""
            }
            257, 335, 258 -> suggestions.getOrNull(selectedIndex) // Enter / KP-Enter / Tab
            256 -> { // Esc, close without accepting
                unbind()
                ""
            }
            else -> null
        }
    }

    /** Returns the new value if a row was clicked, else null. */
    fun mouseClicked(mouseX: Int, mouseY: Int): String? {
        val a = anchor ?: return null
        if (suggestions.isEmpty()) return null
        val popupX = a.x
        val popupY = a.y + a.height + 1
        val visibleCount = suggestions.size.coerceAtMost(MAX_VISIBLE)
        val popupW = (a.width).coerceAtLeast(140)
        for (i in 0 until visibleCount) {
            val rowY = popupY + 1 + i * ROW_H
            if (mouseX in popupX until popupX + popupW && mouseY in rowY until rowY + ROW_H) {
                return suggestions[i]
            }
        }
        return null
    }
}
