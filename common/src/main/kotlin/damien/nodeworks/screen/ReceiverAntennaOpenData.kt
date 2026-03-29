package damien.nodeworks.screen

import net.minecraft.core.BlockPos
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec

data class ReceiverAntennaOpenData(val pos: BlockPos, val statusCode: Int) {
    companion object {
        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, ReceiverAntennaOpenData> = object : StreamCodec<FriendlyByteBuf, ReceiverAntennaOpenData> {
            override fun decode(buf: FriendlyByteBuf) = ReceiverAntennaOpenData(buf.readBlockPos(), buf.readVarInt())
            override fun encode(buf: FriendlyByteBuf, data: ReceiverAntennaOpenData) { buf.writeBlockPos(data.pos); buf.writeVarInt(data.statusCode) }
        }
    }
}
