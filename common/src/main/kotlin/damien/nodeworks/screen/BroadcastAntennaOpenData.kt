package damien.nodeworks.screen

import net.minecraft.core.BlockPos
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec

data class BroadcastAntennaOpenData(val pos: BlockPos) {
    companion object {
        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, BroadcastAntennaOpenData> = object : StreamCodec<FriendlyByteBuf, BroadcastAntennaOpenData> {
            override fun decode(buf: FriendlyByteBuf) = BroadcastAntennaOpenData(buf.readBlockPos())
            override fun encode(buf: FriendlyByteBuf, data: BroadcastAntennaOpenData) { buf.writeBlockPos(data.pos) }
        }
    }
}
