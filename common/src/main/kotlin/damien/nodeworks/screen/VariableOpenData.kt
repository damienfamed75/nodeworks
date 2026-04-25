package damien.nodeworks.screen

import net.minecraft.core.BlockPos
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec

data class VariableOpenData(
    val pos: BlockPos,
    val variableName: String,
    val variableType: Int,
    val variableValue: String,
    val channelId: Int = 0,
) {
    companion object {
        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, VariableOpenData> = object : StreamCodec<FriendlyByteBuf, VariableOpenData> {
            override fun decode(buf: FriendlyByteBuf): VariableOpenData {
                return VariableOpenData(
                    buf.readBlockPos(),
                    buf.readUtf(32),
                    buf.readVarInt(),
                    buf.readUtf(256),
                    buf.readVarInt(),
                )
            }
            override fun encode(buf: FriendlyByteBuf, data: VariableOpenData) {
                buf.writeBlockPos(data.pos)
                buf.writeUtf(data.variableName, 32)
                buf.writeVarInt(data.variableType)
                buf.writeUtf(data.variableValue, 256)
                buf.writeVarInt(data.channelId)
            }
        }
    }
}
