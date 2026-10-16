package damien.nodeworks.script

import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue

/**
 * Converts saved block entity data into Lua values for `ObserverCard:data()`.
 *
 * Compounds become tables keyed by name, lists and arrays become 1-based tables,
 * numbers and strings map directly. NBT has no boolean, so flags come through as
 * 0 or 1. Conversion is bounded in both breadth and depth, so a block entity with a
 * huge inventory can't hand a script a table the server chokes on. Dropped content
 * is flagged with `truncated = true` on the root table.
 */
object NbtToLua {
    const val MAX_ENTRIES = 8192
    const val MAX_DEPTH = 16

    fun convert(root: CompoundTag): LuaTable {
        val budget = IntArray(1) { MAX_ENTRIES }
        val table = compound(root, 0, budget)
        if (budget[0] <= 0) table.set("truncated", LuaValue.TRUE)
        return table
    }

    private fun compound(tag: CompoundTag, depth: Int, budget: IntArray): LuaTable {
        val table = LuaTable()
        for (key in tag.keySet()) {
            if (budget[0] <= 0) break
            val value = tag.get(key) ?: continue
            budget[0]--
            table.set(key, value(value, depth + 1, budget))
        }
        return table
    }

    private fun list(tag: ListTag, depth: Int, budget: IntArray): LuaTable {
        val table = LuaTable()
        for (i in 0 until tag.size) {
            if (budget[0] <= 0) break
            budget[0]--
            table.set(i + 1, value(tag.get(i), depth + 1, budget))
        }
        return table
    }

    private fun value(tag: Tag, depth: Int, budget: IntArray): LuaValue {
        if (depth > MAX_DEPTH) return LuaValue.NIL
        tag.asCompound().orElse(null)?.let { return compound(it, depth, budget) }
        tag.asList().orElse(null)?.let { return list(it, depth, budget) }
        tag.asIntArray().orElse(null)?.let { return numbers(it.map { n -> n.toLong() }, budget) }
        tag.asLongArray().orElse(null)?.let { return numbers(it.toList(), budget) }
        tag.asByteArray().orElse(null)?.let { return numbers(it.map { n -> n.toLong() }, budget) }
        tag.asNumber().orElse(null)?.let { return number(it) }
        tag.asString().orElse(null)?.let { return LuaValue.valueOf(it) }
        return LuaValue.NIL
    }

    private fun numbers(values: List<Long>, budget: IntArray): LuaTable {
        val table = LuaTable()
        for ((i, n) in values.withIndex()) {
            if (budget[0] <= 0) break
            budget[0]--
            table.set(i + 1, number(n))
        }
        return table
    }

    private fun number(n: Number): LuaValue = when (n) {
        is Float, is Double -> LuaValue.valueOf(n.toDouble())
        else -> {
            val l = n.toLong()
            if (l in Int.MIN_VALUE..Int.MAX_VALUE) LuaValue.valueOf(l.toInt()) else LuaValue.valueOf(l.toDouble())
        }
    }
}
