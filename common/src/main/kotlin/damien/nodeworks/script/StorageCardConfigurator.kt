package damien.nodeworks.script

import damien.nodeworks.block.entity.NodeBlockEntity
import damien.nodeworks.card.StorageCard
import damien.nodeworks.card.StorageSideCapability
import damien.nodeworks.network.NetworkSnapshot
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.ItemStack
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.lib.OneArgFunction
import org.luaj.vm2.lib.TwoArgFunction

/**
 * Builder returned by `network:route(aliasPattern)`. Configures the filter
 * settings on every Storage Card whose effective alias matches the pattern,
 * mutating their NBT directly so the change persists. Each builder method
 * returns the same builder so the call site can chain.
 *
 * The pre-1.1 `network:route(alias, predicate)` runtime-routing API was
 * removed in favor of this configurator, see the v1.1 changelog. Card
 * filters are now the source of truth; `network:route` is a script-side
 * shortcut for setting them across many cards at once.
 *
 * Example:
 * ```lua
 * network:route("cobblestone_*")
 *   :reset()
 *   :rule("#minecraft:cobblestones")
 *   :noNbt()
 *   :allow()
 * ```
 *
 * Mutations are applied immediately on each method call. Caller-driven
 * idempotence: scripts that re-run should `:reset()` first so the resulting
 * state doesn't depend on prior runs.
 */
object StorageCardConfigurator {

    fun createBuilder(level: ServerLevel, snapshot: NetworkSnapshot, pattern: String): LuaValue {
        val builder = LuaTable()
        val regex = wildcardToRegex(pattern)

        // :rule(filterString): append a rule to the rule list.
        builder.set("rule", object : TwoArgFunction() {
            override fun call(selfArg: LuaValue, ruleArg: LuaValue): LuaValue {
                val ruleStr = ruleArg.checkjstring().trim()
                if (ruleStr.isNotEmpty()) {
                    forMatchingCards(level, snapshot, regex) { stack ->
                        val rules = StorageCard.getFilterRules(stack).toMutableList()
                        if (ruleStr !in rules) rules.add(ruleStr)
                        StorageCard.setFilterRules(stack, rules)
                    }
                }
                return builder
            }
        })

        // :clearRules(): drop every existing rule (modes left alone).
        builder.set("clearRules", object : OneArgFunction() {
            override fun call(selfArg: LuaValue): LuaValue {
                forMatchingCards(level, snapshot, regex) { stack ->
                    StorageCard.setFilterRules(stack, emptyList())
                }
                return builder
            }
        })

        // Mode setters.
        builder.set("allow", oneArgSetter(builder, level, snapshot, regex) { stack ->
            StorageCard.setFilterMode(stack, StorageCard.Companion.FilterMode.ALLOW)
        })
        builder.set("deny", oneArgSetter(builder, level, snapshot, regex) { stack ->
            StorageCard.setFilterMode(stack, StorageCard.Companion.FilterMode.DENY)
        })

        // Stackability setters.
        builder.set("stackable", oneArgSetter(builder, level, snapshot, regex) { stack ->
            StorageCard.setStackabilityFilter(stack, StorageCard.Companion.StackabilityFilter.STACKABLE)
        })
        builder.set("nonStackable", oneArgSetter(builder, level, snapshot, regex) { stack ->
            StorageCard.setStackabilityFilter(stack, StorageCard.Companion.StackabilityFilter.NON_STACKABLE)
        })
        builder.set("anyStackable", oneArgSetter(builder, level, snapshot, regex) { stack ->
            StorageCard.setStackabilityFilter(stack, StorageCard.Companion.StackabilityFilter.ANY)
        })

        // NBT setters.
        builder.set("hasNbt", oneArgSetter(builder, level, snapshot, regex) { stack ->
            StorageCard.setNbtFilter(stack, StorageCard.Companion.NbtFilter.HAS_DATA)
        })
        builder.set("noNbt", oneArgSetter(builder, level, snapshot, regex) { stack ->
            StorageCard.setNbtFilter(stack, StorageCard.Companion.NbtFilter.NO_DATA)
        })
        builder.set("anyNbt", oneArgSetter(builder, level, snapshot, regex) { stack ->
            StorageCard.setNbtFilter(stack, StorageCard.Companion.NbtFilter.ANY)
        })

        // :reset(): full default reset, rules cleared + modes back to ALLOW + ANY/ANY.
        builder.set("reset", object : OneArgFunction() {
            override fun call(selfArg: LuaValue): LuaValue {
                forMatchingCards(level, snapshot, regex) { stack ->
                    StorageCard.setFilterRules(stack, emptyList())
                    StorageCard.setFilterMode(stack, StorageCard.Companion.FilterMode.ALLOW)
                    StorageCard.setStackabilityFilter(stack, StorageCard.Companion.StackabilityFilter.ANY)
                    StorageCard.setNbtFilter(stack, StorageCard.Companion.NbtFilter.ANY)
                }
                return builder
            }
        })

        return builder
    }

    /** Wraps a per-stack mutation in a no-arg Lua method that returns the
     *  builder for chaining. Keeps the registration block compact for the
     *  many "set the same way for every matching card" methods. */
    private fun oneArgSetter(
        builder: LuaTable,
        level: ServerLevel,
        snapshot: NetworkSnapshot,
        regex: Regex,
        mutate: (ItemStack) -> Unit,
    ): OneArgFunction = object : OneArgFunction() {
        override fun call(selfArg: LuaValue): LuaValue {
            forMatchingCards(level, snapshot, regex, mutate)
            return builder
        }
    }

    /** Walk every Storage Card on the network whose effective alias matches
     *  [regex] and apply [mutate] to its backing ItemStack. Each touched
     *  node is `setChanged()` so the mutation persists across save/load.
     *
     *  Skips non-Storage cards even if the alias regex would match (a
     *  generic alias like `node_*` could otherwise hit IO/Observer cards
     *  whose stacks aren't [StorageCard]s). */
    private fun forMatchingCards(
        level: ServerLevel,
        snapshot: NetworkSnapshot,
        regex: Regex,
        mutate: (ItemStack) -> Unit,
    ) {
        for (node in snapshot.nodes) {
            val be = level.getBlockEntity(node.pos) as? NodeBlockEntity ?: continue
            var changed = false
            for ((side, cards) in node.sides) {
                for (card in cards) {
                    if (card.capability !is StorageSideCapability) continue
                    if (!regex.matches(card.effectiveAlias)) continue
                    val globalSlot = side.ordinal * NodeBlockEntity.SLOTS_PER_SIDE + card.slotIndex
                    val stack = be.getItem(globalSlot)
                    if (stack.item !is StorageCard) continue
                    mutate(stack)
                    changed = true
                }
            }
            if (changed) be.setChanged()
        }
    }

    /** Glob-to-regex: `*` becomes `.*`, every other char is escaped literally
     *  and the whole pattern is anchored. Mirrors the legacy
     *  [RouteTable.wildcardToRegex] so alias matching stays consistent
     *  between this builder and the storage routing (which still consults
     *  the same alias shapes). */
    private fun wildcardToRegex(pattern: String): Regex {
        val sb = StringBuilder("^")
        for (ch in pattern) {
            if (ch == '*') sb.append(".*") else sb.append(Regex.escape(ch.toString()))
        }
        sb.append("$")
        return Regex(sb.toString())
    }
}
