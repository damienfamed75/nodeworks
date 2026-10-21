package damien.nodeworks.screen.widget

import damien.nodeworks.card.ItemTagLookup
import damien.nodeworks.platform.ResourceKind
import damien.nodeworks.script.FilterRule
import net.minecraft.core.HolderLookup
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.util.Util
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack

/**
 * An item to draw beside a filter rule so a rule list reads at a glance. A plain id shows
 * that item, a component rule shows the item with those components, and a tag, regex or
 * namespace rule cycles through the items it matches, one every [CYCLE_PERIOD_MS]. A
 * fluid rule shows the fluid's bucket. Rules that match nothing, or everything, show no
 * icon.
 *
 * Client side. The regex and namespace matches walk the item registry once per rule and
 * are kept, bounded, so a long rule list costs a scan per distinct rule, not per frame.
 */
object FilterRuleIcons {
    private const val CYCLE_PERIOD_MS = 1200L
    private const val MAX_EXAMPLES = 64
    private const val MAX_CACHED_RULES = 256

    private val examples = object : LinkedHashMap<String, List<ItemStack>>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<ItemStack>>): Boolean =
            size > MAX_CACHED_RULES
    }

    /** The stack to show for [rule] right now, or empty when there is nothing to show. */
    fun exampleStack(rule: String, registries: HolderLookup.Provider): ItemStack {
        val list = examplesFor(rule, registries)
        if (list.isEmpty()) return ItemStack.EMPTY
        if (list.size == 1) return list[0]
        return list[((Util.getMillis() / CYCLE_PERIOD_MS) % list.size).toInt()]
    }

    private fun examplesFor(rule: String, registries: HolderLookup.Provider): List<ItemStack> {
        examples[rule]?.let { return it }
        val built = try {
            examplesOf(FilterRule.parse(rule, registries), ResourceKind.ITEM)
        } catch (_: Exception) {
            emptyList()
        }
        examples[rule] = built
        return built
    }

    private fun examplesOf(rule: FilterRule, kind: ResourceKind): List<ItemStack> = when (rule) {
        is FilterRule.Any, is FilterRule.Invalid -> emptyList()
        is FilterRule.Kinded -> examplesOf(rule.inner, rule.kind)
        is FilterRule.Item -> {
            if (kind == ResourceKind.FLUID) bucketFor(rule.itemId)
            else {
                val item = Identifier.tryParse(rule.itemId)?.let { BuiltInRegistries.ITEM.getValue(it) }
                if (item == null) emptyList()
                else listOf(ItemStack(item).apply { rule.componentsPatch?.let { applyComponents(it) } })
            }
        }
        is FilterRule.Tag -> {
            if (kind == ResourceKind.FLUID) emptyList()
            else ItemTagLookup.membersOf(rule.tagId).take(MAX_EXAMPLES).map { ItemStack(it) }
        }
        is FilterRule.Regex -> {
            if (kind == ResourceKind.FLUID) emptyList()
            else scanItems { rule.pattern.matcher(it.toString()).matches() }
        }
        is FilterRule.Namespace -> {
            if (kind == ResourceKind.FLUID) emptyList()
            else scanItems { it.namespace == rule.namespace }
        }
    }

    private fun bucketFor(fluidId: String): List<ItemStack> {
        val fluid = Identifier.tryParse(fluidId)?.let { BuiltInRegistries.FLUID.getValue(it) } ?: return emptyList()
        val bucket: Item = fluid.bucket
        return if (bucket === net.minecraft.world.item.Items.AIR) emptyList() else listOf(ItemStack(bucket))
    }

    /** The first [MAX_EXAMPLES] registered items whose id passes [test]. */
    private fun scanItems(test: (Identifier) -> Boolean): List<ItemStack> {
        val out = ArrayList<ItemStack>()
        for (id in BuiltInRegistries.ITEM.keySet()) {
            if (!test(id)) continue
            val item = BuiltInRegistries.ITEM.getValue(id) ?: continue
            if (item === net.minecraft.world.item.Items.AIR) continue
            out.add(ItemStack(item))
            if (out.size >= MAX_EXAMPLES) break
        }
        return out
    }
}
