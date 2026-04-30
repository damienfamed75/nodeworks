package damien.nodeworks.screen

import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec

/**
 * Open-payload for the shared "Card Settings" GUI. Mirrors [StorageCardOpenData],
 * we just need to know which hand is holding the card so the menu can read/write
 * the right ItemStack.
 */
data class CardSettingsOpenData(val handOrdinal: Int, val cardName: String) {
    companion object {
        /** Cap matches the in-game anvil cap so the rename UX feels familiar. */
        const val MAX_NAME_LENGTH = 50

        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, CardSettingsOpenData> =
            object : StreamCodec<FriendlyByteBuf, CardSettingsOpenData> {
                override fun decode(buf: FriendlyByteBuf): CardSettingsOpenData {
                    return CardSettingsOpenData(buf.readVarInt(), buf.readUtf(MAX_NAME_LENGTH))
                }
                override fun encode(buf: FriendlyByteBuf, data: CardSettingsOpenData) {
                    buf.writeVarInt(data.handOrdinal)
                    buf.writeUtf(data.cardName.take(MAX_NAME_LENGTH), MAX_NAME_LENGTH)
                }
            }
    }
}
