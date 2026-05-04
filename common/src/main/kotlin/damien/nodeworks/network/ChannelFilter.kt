package damien.nodeworks.network

import net.minecraft.world.item.DyeColor

/**
 * Selector that scopes a network operation to one channel or to all channels.
 * Used by Importer / Exporter chests and by the `Channel:*` script API to filter
 * which Storage Cards (and other channel-tagged members) participate.
 *
 * Two cases:
 *  * [All] no channel filter, every card on the network qualifies.
 *  * [Color] only cards whose [damien.nodeworks.network.CardSnapshot.channel]
 *    equals the wrapped [DyeColor].
 *
 * NBT round-trips through a single int for compactness: `-1` ⇒ [All],
 * `0..15` ⇒ the matching [DyeColor.id].
 */
sealed class ChannelFilter {
    object All : ChannelFilter()
    data class Color(val color: DyeColor) : ChannelFilter()

    /** True when [channel] satisfies this filter. */
    fun matches(channel: DyeColor): Boolean = when (this) {
        All -> true
        is Color -> channel == color
    }

    /** Compact NBT representation. See class kdoc for the encoding. */
    fun toNbtInt(): Int = when (this) {
        All -> -1
        is Color -> color.id
    }

    companion object {
        /** Inverse of [toNbtInt]. Out-of-range values fall back to [All] so a
         *  malformed save (or a value from a future version) doesn't crash. */
        fun fromNbtInt(value: Int): ChannelFilter {
            if (value < 0) return All
            return runCatching { Color(DyeColor.byId(value)) }.getOrDefault(All)
        }
    }
}
