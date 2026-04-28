package damien.nodeworks.client.item

import com.mojang.serialization.MapCodec
import damien.nodeworks.card.CardChannel
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.item.properties.conditional.ConditionalItemModelProperty
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.item.DyeColor
import net.minecraft.world.item.ItemDisplayContext
import net.minecraft.world.item.ItemStack

/**
 * Conditional item-model property, true when the card has been dyed to a non-white
 * channel. Used to switch each card item between a plain model (layer0 only, no
 * indicator) and a layered model that adds the tinted `channel_indicator` overlay.
 *
 * Why a conditional dispatch instead of always rendering layer1 with a transparent
 * tint: a tint of `0x00000000` zeroes the layer's pixel contribution mathematically,
 * but Minecraft's item-render pipeline still pushes that layer's geometry into the
 * scene, which produces the visible "punched-out" transparent pixels reported on
 * white-channel cards. Splitting at the model-selection layer means white-channel
 * cards have no layer1 at all and render exactly as before channels existed.
 *
 * Referenced from each card's `assets/nodeworks/items/<card>_card.json` via:
 *
 *   { "type": "nodeworks:card_channel_set" }
 */
@JvmRecord
data class CardChannelSetProperty(private val unit: Unit = Unit) : ConditionalItemModelProperty {
    override fun get(
        stack: ItemStack,
        level: ClientLevel?,
        owner: LivingEntity?,
        seed: Int,
        context: ItemDisplayContext,
    ): Boolean = CardChannel.get(stack) != DyeColor.WHITE

    override fun type(): MapCodec<out ConditionalItemModelProperty> = MAP_CODEC

    companion object {
        val MAP_CODEC: MapCodec<CardChannelSetProperty> = MapCodec.unit(CardChannelSetProperty())
    }
}
