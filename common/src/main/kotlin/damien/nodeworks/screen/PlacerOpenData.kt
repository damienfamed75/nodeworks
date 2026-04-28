package damien.nodeworks.screen

import net.minecraft.core.BlockPos
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec

data class PlacerOpenData(
    val pos: BlockPos,
    val deviceName: String,
    val channelId: Int = 0,
) {
    companion object {
        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, PlacerOpenData> =
            object : StreamCodec<FriendlyByteBuf, PlacerOpenData> {
                override fun decode(buf: FriendlyByteBuf): PlacerOpenData = PlacerOpenData(
                    buf.readBlockPos(),
                    buf.readUtf(32),
                    buf.readVarInt(),
                )
                override fun encode(buf: FriendlyByteBuf, data: PlacerOpenData) {
                    buf.writeBlockPos(data.pos)
                    buf.writeUtf(data.deviceName, 32)
                    buf.writeVarInt(data.channelId)
                }
            }
    }
}
