package damien.nodeworks.client.item

import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import damien.nodeworks.card.NodeCard
import damien.nodeworks.item.CardProgrammerItem
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.item.properties.select.SelectItemModelProperty
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.item.ItemDisplayContext
import net.minecraft.world.item.ItemStack

/**
 * MC 26.1.2 port of the old `nodeworks:card_type` item-model predicate.
 *
 * Returns the stored template's card sub-type (`"storage"`, `"io"`,
 * `"redstone"`, or `null` when empty). Referenced from
 * `assets/nodeworks/items/card_programmer.json` via a `minecraft:select` model
 * with one `when` branch per card type:
 *
 *   { "type": "nodeworks:card_programmer_card_type",
 *     "cases": [{"when": "storage", "model": {...}}, ...],
 *     "fallback": {...} }
 */
@JvmRecord
data class CardProgrammerTypeProperty(private val unit: Unit = Unit) : SelectItemModelProperty<String> {
    override fun get(
        stack: ItemStack,
        level: ClientLevel?,
        owner: LivingEntity?,
        seed: Int,
        context: ItemDisplayContext
    ): String? {
        val template = CardProgrammerItem.getTemplate(stack)
        if (template.isEmpty) return null
        val card = template.item as? NodeCard ?: return null
        return card.cardType
    }

    override fun valueCodec(): Codec<String> = Codec.STRING

    override fun type(): SelectItemModelProperty.Type<CardProgrammerTypeProperty, String> = TYPE

    companion object {
        val MAP_CODEC: MapCodec<CardProgrammerTypeProperty> = MapCodec.unit(CardProgrammerTypeProperty())
        val TYPE: SelectItemModelProperty.Type<CardProgrammerTypeProperty, String> =
            SelectItemModelProperty.Type.create(MAP_CODEC, Codec.STRING)
    }
}
