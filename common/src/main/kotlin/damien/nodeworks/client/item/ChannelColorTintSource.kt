package damien.nodeworks.client.item

import com.mojang.serialization.MapCodec
import damien.nodeworks.card.CardChannel
import net.minecraft.client.color.item.ItemTintSource
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.item.DyeColor
import net.minecraft.world.item.ItemStack

/**
 * Item tint source for the channel-indicator overlay layer on cards. Returns the
 * channel's dye color as ARGB when the card has been dyed (any non-white channel),
 * and a fully-transparent color when the channel is white so the overlay layer
 * disappears for default cards.
 *
 * Hooks into the per-card item model via `tints[1]` — `tints[0]` stays at constant
 * `-1` to leave the base card texture untinted. The same source serves every card
 * type (IO / Storage / Redstone / Observer); each card's item-model JSON references
 * `nodeworks:channel_color` and the read of [CardChannel.get] is what differentiates
 * the displayed colour at runtime.
 *
 * Registered in [damien.nodeworks.client.NeoForgeClientSetup.onRegisterItemTintSources].
 */
@JvmRecord
data class ChannelColorTintSource(private val unit: Unit = Unit) : ItemTintSource {
    override fun calculate(stack: ItemStack, level: ClientLevel?, entity: LivingEntity?): Int {
        val color = CardChannel.get(stack)
        // White hides the indicator outright. Transparency on the tint propagates to
        // the layer's pixels (Minecraft multiplies tint × texel, so alpha=0 zeroes
        // the entire layer's contribution to the final image).
        if (color == DyeColor.WHITE) return 0x00000000
        // Force alpha to 0xFF so the indicator shows at full opacity. The dye's
        // textureDiffuseColor is RGB-only; we OR the alpha back in to get a clean
        // opaque tint.
        return 0xFF000000.toInt() or (color.textureDiffuseColor and 0xFFFFFF)
    }

    override fun type(): MapCodec<out ItemTintSource> = MAP_CODEC

    companion object {
        val MAP_CODEC: MapCodec<ChannelColorTintSource> =
            MapCodec.unit(ChannelColorTintSource())
    }
}
