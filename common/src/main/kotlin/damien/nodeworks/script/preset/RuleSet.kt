package damien.nodeworks.script.preset

import damien.nodeworks.platform.ResourceKind
import damien.nodeworks.script.CardHandle
import net.minecraft.core.HolderLookup
import net.minecraft.world.item.ItemStack
import org.luaj.vm2.Varargs

/**
 * What a preset moves: an allow list and a deny list of resource filter rules, the
 * same rule language the Storage Card and the chests use. A resource passes when some
 * allow rule matches it, or there are none, and no deny rule matches it. The deny
 * side always wins, whatever order the script added the rules in.
 *
 * Items are matched on the real stack so component rules narrow to the variant.
 * Fluids are matched by id. A rule with a `$item:` or `$fluid:` sigil is skipped for
 * the other kind, a rule without one applies to both.
 */
internal class RuleSet {
    val allow = ArrayList<String>()
    val deny = ArrayList<String>()

    /** Replaces the allow list. Empty means everything, which is also the start state. */
    fun setAllow(rules: List<String>) {
        allow.clear()
        allow.addAll(rules)
    }

    /** Adds to the allow list, or clears it when [rules] is empty, so a script can
     *  reuse a builder. */
    fun filter(rules: List<String>) {
        if (rules.isEmpty()) allow.clear() else allow.addAll(rules)
    }

    /** Adds to the deny list, or clears it when [rules] is empty. */
    fun except(rules: List<String>) {
        if (rules.isEmpty()) deny.clear() else deny.addAll(rules)
    }

    fun matchesStack(stack: ItemStack, registries: HolderLookup.Provider): Boolean =
        (allow.isEmpty() || allow.any { CardHandle.matchesFilter(stack, it, registries) }) &&
            deny.none { CardHandle.matchesFilter(stack, it, registries) }

    fun matchesId(id: String, kind: ResourceKind): Boolean =
        (allow.isEmpty() || allow.any { CardHandle.matchesFilter(id, kind, it) }) &&
            // By id a component rule reads as its bare item, which would deny every
            // variant. Such a rule only ever denies on the stack check.
            deny.none { !hasComponents(it) && CardHandle.matchesFilter(id, kind, it) }

    private fun hasComponents(rule: String): Boolean = rule.indexOf('[') >= 0

    fun stackPredicate(registries: HolderLookup.Provider): (ItemStack) -> Boolean =
        { stack -> matchesStack(stack, registries) }

    /** Id-level check for one kind. For items it is a superset of [matchesStack], good
     *  for narrowing a scan before the stacks are in hand. */
    fun idPredicate(kind: ResourceKind): (String) -> Boolean =
        { id -> matchesId(id, kind) }

    /** False when every allow rule is pinned to the other kind, so a pass over this
     *  kind can be skipped without looking at a single resource. */
    fun mayMatch(kind: ResourceKind): Boolean {
        if (allow.isEmpty()) return true
        val otherSigil = if (kind == ResourceKind.FLUID) "\$item:" else "\$fluid:"
        return allow.any { !it.startsWith(otherSigil) }
    }

    companion object {
        private const val MAX_RULES = 64

        /** Rule strings from Lua call arguments starting at [startIndex]: plain strings,
         *  or tables of strings, in any mix. */
        fun rulesFromArgs(args: Varargs, startIndex: Int): List<String> {
            val out = ArrayList<String>()
            for (i in startIndex..args.narg()) {
                val v = args.arg(i)
                if (v.isnil()) continue
                if (v.istable()) {
                    val table = v.checktable()
                    var k = 1
                    while (true) {
                        val entry = table.get(k)
                        if (entry.isnil()) break
                        out.add(entry.checkjstring())
                        k++
                    }
                } else {
                    out.add(v.checkjstring())
                }
            }
            if (out.size > MAX_RULES) throw org.luaj.vm2.LuaError("at most $MAX_RULES rules per call")
            return out.map { it.trim() }.filter { it.isNotEmpty() }
        }
    }
}
