package damien.nodeworks.screen

import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec

data class StorageCardOpenData(val handOrdinal: Int) {
    companion object {
        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, StorageCardOpenData> = object : StreamCodec<FriendlyByteBuf, StorageCardOpenData> {
            override fun decode(buf: FriendlyByteBuf): StorageCardOpenData {
                return StorageCardOpenData(buf.readVarInt())
            }
            override fun encode(buf: FriendlyByteBuf, data: StorageCardOpenData) {
                buf.writeVarInt(data.handOrdinal)
            }
        }
    }
}
