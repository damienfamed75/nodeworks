package damien.nodeworks.screen

import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec

/**
 * Open-payload for the shared "Card Settings" GUI. Mirrors [StorageCardOpenData] —
 * we just need to know which hand is holding the card so the menu can read/write
 * the right ItemStack.
 */
data class CardSettingsOpenData(val handOrdinal: Int) {
    companion object {
        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, CardSettingsOpenData> =
            object : StreamCodec<FriendlyByteBuf, CardSettingsOpenData> {
                override fun decode(buf: FriendlyByteBuf): CardSettingsOpenData {
                    return CardSettingsOpenData(buf.readVarInt())
                }
                override fun encode(buf: FriendlyByteBuf, data: CardSettingsOpenData) {
                    buf.writeVarInt(data.handOrdinal)
                }
            }
    }
}
