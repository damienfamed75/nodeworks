package damien.nodeworks.client.item

import com.mojang.serialization.MapCodec
import damien.nodeworks.item.LinkCrystalItem
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.item.properties.conditional.ConditionalItemModelProperty
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.item.ItemDisplayContext
import net.minecraft.world.item.ItemStack

/**
 * MC 26.1.2 port of the old `nodeworks:linked` item-model predicate.
 *
 * Returns true when the Link Crystal stack has been encoded against a block
 * (see [LinkCrystalItem.isEncoded]). Referenced from
 * `assets/nodeworks/items/link_crystal.json` via:
 *
 *   { "type": "nodeworks:link_crystal_linked" }
 */
@JvmRecord
data class LinkCrystalLinkedProperty(private val unit: Unit = Unit) : ConditionalItemModelProperty {
    override fun get(
        stack: ItemStack,
        level: ClientLevel?,
        owner: LivingEntity?,
        seed: Int,
        context: ItemDisplayContext
    ): Boolean = LinkCrystalItem.isEncoded(stack)

    override fun type(): MapCodec<out ConditionalItemModelProperty> = MAP_CODEC

    companion object {
        val MAP_CODEC: MapCodec<LinkCrystalLinkedProperty> = MapCodec.unit(LinkCrystalLinkedProperty())
    }
}
