package damien.nodeworks.screen

import net.minecraft.core.BlockPos
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec

data class NetworkControllerOpenData(
    val pos: BlockPos,
    val networkColor: Int,
    val networkName: String,
    val redstoneMode: Int,
    val nodeGlowStyle: Int
) {
    companion object {
        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, NetworkControllerOpenData> = object : StreamCodec<FriendlyByteBuf, NetworkControllerOpenData> {
            override fun decode(buf: FriendlyByteBuf): NetworkControllerOpenData {
                return NetworkControllerOpenData(
                    buf.readBlockPos(),
                    buf.readVarInt(),
                    buf.readUtf(32),
                    buf.readVarInt(),
                    buf.readVarInt()
                )
            }
            override fun encode(buf: FriendlyByteBuf, data: NetworkControllerOpenData) {
                buf.writeBlockPos(data.pos)
                buf.writeVarInt(data.networkColor)
                buf.writeUtf(data.networkName, 32)
                buf.writeVarInt(data.redstoneMode)
                buf.writeVarInt(data.nodeGlowStyle)
            }
        }
    }
}
