package damien.nodeworks.integration.jei

import net.minecraft.core.Holder
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.ItemStackTemplate
import net.minecraft.world.item.crafting.RecipeHolder
import java.util.Optional

/**
 * Works out which outputs of a JEI recipe are chance-based, from the mod's own recipe
 * object. JEI has no chance API, but most machine mods keep a list of output entries on
 * the recipe, each with a public `chance()` (or `getChance()`, `probability()`) and the
 * item it makes. This reads that shape through public accessors only, so it needs no
 * dependency on any mod and can't trip module access checks. Anything it doesn't
 * recognise simply detects nothing.
 */
object ChanceOutputs {

    private data class Entry(val itemId: String, val chance: Float)

    /** For each JEI output slot in [shown], true when the recipe makes it only by chance.
     *  Entries are matched to slots by item, in order, so two slots of one item (a sure
     *  one and a bonus one) pair with the recipe's two entries for that item. */
    fun detect(recipe: Any?, shown: List<ItemStack>): BooleanArray {
        val result = BooleanArray(shown.size)
        val entries = try { chanceEntries(unwrap(recipe)) } catch (_: Throwable) { emptyList() }
        if (entries.isEmpty()) return result
        val used = BooleanArray(entries.size)
        for ((i, stack) in shown.withIndex()) {
            if (stack.isEmpty) continue
            val id = BuiltInRegistries.ITEM.getKey(stack.item).toString()
            val k = entries.indices.firstOrNull { !used[it] && entries[it].itemId == id } ?: continue
            used[k] = true
            result[i] = entries[k].chance < 1f
        }
        return result
    }

    private fun unwrap(recipe: Any?): Any? = if (recipe is RecipeHolder<*>) recipe.value() else recipe

    /** The first public collection on the recipe whose every element has a chance and an item. */
    private fun chanceEntries(recipe: Any?): List<Entry> {
        if (recipe == null) return emptyList()
        for (m in recipe.javaClass.methods) {
            if (m.parameterCount != 0 || !Collection::class.java.isAssignableFrom(m.returnType)) continue
            if (m.declaringClass == Any::class.java) continue
            val list = try { m.invoke(recipe) as? Collection<*> } catch (_: Throwable) { null } ?: continue
            if (list.isEmpty()) continue
            val entries = list.map { entryOf(it) ?: return@map null }
            if (entries.all { it != null }) return entries.filterNotNull()
        }
        return emptyList()
    }

    private fun entryOf(e: Any?): Entry? {
        if (e == null) return null
        val chance = numberOf(e, "chance", "getChance", "probability", "getProbability") ?: return null
        val item = itemOf(e) ?: return null
        return Entry(item, chance)
    }

    private fun numberOf(e: Any, vararg names: String): Float? {
        for (name in names) {
            val v = call(e, name) ?: continue
            if (v is Number) return v.toFloat()
        }
        return null
    }

    private fun itemOf(e: Any): String? {
        for (name in listOf("getItemStackTemplate", "stack", "getStack", "item", "getItem", "output", "getOutput", "result", "getResult", "template")) {
            val id = itemIdOf(call(e, name) ?: continue)
            if (id != null) return id
        }
        return null
    }

    /** Public no-arg method [name] on [e], or null when absent or it throws. */
    private fun call(e: Any, name: String): Any? = try {
        e.javaClass.getMethod(name).invoke(e)
    } catch (_: Throwable) { null }

    private fun itemIdOf(v: Any?): String? = when (v) {
        null -> null
        is ItemStack -> if (v.isEmpty) null else BuiltInRegistries.ITEM.getKey(v.item).toString()
        is ItemStackTemplate -> itemIdOf(v.create())
        is Item -> BuiltInRegistries.ITEM.getKey(v).toString()
        is Holder<*> -> (v.value() as? Item)?.let { BuiltInRegistries.ITEM.getKey(it).toString() }
        is Optional<*> -> itemIdOf(v.orElse(null))
        is com.mojang.datafixers.util.Either<*, *> -> itemIdOf(v.left().orElse(null)) ?: itemIdOf(v.right().orElse(null))
        else -> null
    }
}
