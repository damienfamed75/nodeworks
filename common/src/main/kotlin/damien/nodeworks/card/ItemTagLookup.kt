package damien.nodeworks.card

import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.world.item.Item

/** Item tag lookups shared by the Processing Set editor, its JEI transfer and the
 *  planner. Tag ids are plain `namespace:path`, no `#`. */
object ItemTagLookup {

    /** Every item in [tagId], empty when the tag is unknown. */
    fun membersOf(tagId: String): List<Item> {
        val ident = Identifier.tryParse(tagId) ?: return emptyList()
        val key = net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.ITEM, ident)
        val set = BuiltInRegistries.ITEM.get(key).orElse(null) ?: return emptyList()
        return set.stream().map { it.value() }.toList()
    }

    /** Every tag [item] belongs to. */
    fun tagsOf(item: Item): List<String> =
        item.builtInRegistryHolder().tags().map { it.location().toString() }.toList()

    fun isMember(item: Item, tagId: String): Boolean {
        val ident = Identifier.tryParse(tagId) ?: return false
        return item.builtInRegistryHolder().tags().anyMatch { it.location() == ident }
    }
}
