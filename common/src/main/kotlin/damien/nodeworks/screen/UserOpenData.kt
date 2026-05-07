package damien.nodeworks.screen

import net.minecraft.core.BlockPos
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec

data class UserOpenData(
    val pos: BlockPos,
    val deviceName: String,
    val channelId: Int,
    val filterRule: String,
    val redstoneMode: Int,
    val modeOrdinal: Int,
    val previewArea: Boolean,
) {
    companion object {
        const val MAX_FILTER_LENGTH = 256

        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, UserOpenData> =
            object : StreamCodec<FriendlyByteBuf, UserOpenData> {
                override fun decode(buf: FriendlyByteBuf): UserOpenData = UserOpenData(
                    buf.readBlockPos(),
                    buf.readUtf(32),
                    buf.readVarInt(),
                    buf.readUtf(MAX_FILTER_LENGTH),
                    buf.readVarInt(),
                    buf.readVarInt(),
                    buf.readBoolean(),
                )

                override fun encode(buf: FriendlyByteBuf, data: UserOpenData) {
                    buf.writeBlockPos(data.pos)
                    buf.writeUtf(data.deviceName, 32)
                    buf.writeVarInt(data.channelId)
                    buf.writeUtf(data.filterRule, MAX_FILTER_LENGTH)
                    buf.writeVarInt(data.redstoneMode)
                    buf.writeVarInt(data.modeOrdinal)
                    buf.writeBoolean(data.previewArea)
                }
            }
    }
}
