package damien.nodeworks.screen

import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec

/**
 * Open-payload for the shared "Card Settings" GUI. Mirrors [StorageCardOpenData],
 * we just need to know which hand is holding the card so the menu can read/write
 * the right ItemStack.
 */
data class CardSettingsOpenData(
    val handOrdinal: Int,
    val cardName: String,
    val tags: List<String> = emptyList(),
    val knownTags: List<String> = emptyList(),
) {
    companion object {
        /** Cap matches the in-game anvil cap so the rename UX feels familiar. */
        const val MAX_NAME_LENGTH = 50

        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, CardSettingsOpenData> =
            object : StreamCodec<FriendlyByteBuf, CardSettingsOpenData> {
                override fun decode(buf: FriendlyByteBuf): CardSettingsOpenData {
                    val hand = buf.readVarInt()
                    val name = buf.readUtf(MAX_NAME_LENGTH)
                    val tags = StorageCardOpenData.readTags(buf, damien.nodeworks.card.CardTags.MAX_TAGS)
                    val known = StorageCardOpenData.readTags(buf, damien.nodeworks.card.KnownCardTags.MAX_OFFERED)
                    return CardSettingsOpenData(hand, name, tags, known)
                }
                override fun encode(buf: FriendlyByteBuf, data: CardSettingsOpenData) {
                    buf.writeVarInt(data.handOrdinal)
                    buf.writeUtf(data.cardName.take(MAX_NAME_LENGTH), MAX_NAME_LENGTH)
                    StorageCardOpenData.writeTags(buf, data.tags, damien.nodeworks.card.CardTags.MAX_TAGS)
                    StorageCardOpenData.writeTags(buf, data.knownTags, damien.nodeworks.card.KnownCardTags.MAX_OFFERED)
                }
            }
    }
}
