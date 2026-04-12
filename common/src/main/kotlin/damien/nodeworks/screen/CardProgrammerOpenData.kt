package damien.nodeworks.screen

import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec

data class CardProgrammerOpenData(val handOrdinal: Int) {
    companion object {
        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, CardProgrammerOpenData> = object : StreamCodec<FriendlyByteBuf, CardProgrammerOpenData> {
            override fun decode(buf: FriendlyByteBuf): CardProgrammerOpenData {
                return CardProgrammerOpenData(buf.readVarInt())
            }
            override fun encode(buf: FriendlyByteBuf, data: CardProgrammerOpenData) {
                buf.writeVarInt(data.handOrdinal)
            }
        }
    }
}
