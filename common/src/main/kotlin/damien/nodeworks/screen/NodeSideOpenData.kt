package damien.nodeworks.screen

import net.minecraft.core.BlockPos
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec

data class NodeSideOpenData(val nodePos: BlockPos, val sideOrdinal: Int) {
    companion object {
        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, NodeSideOpenData> = object : StreamCodec<FriendlyByteBuf, NodeSideOpenData> {
            override fun decode(buf: FriendlyByteBuf): NodeSideOpenData {
                return NodeSideOpenData(buf.readBlockPos(), buf.readVarInt())
            }
            override fun encode(buf: FriendlyByteBuf, data: NodeSideOpenData) {
                buf.writeBlockPos(data.nodePos)
                buf.writeVarInt(data.sideOrdinal)
            }
        }
    }
}
