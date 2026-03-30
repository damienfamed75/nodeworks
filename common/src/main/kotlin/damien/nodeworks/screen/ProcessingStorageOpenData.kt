package damien.nodeworks.screen

import net.minecraft.core.BlockPos
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec

data class ProcessingStorageOpenData(
    val pos: BlockPos,
    val upgradeLevel: Int
) {
    companion object {
        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, ProcessingStorageOpenData> = object : StreamCodec<FriendlyByteBuf, ProcessingStorageOpenData> {
            override fun decode(buf: FriendlyByteBuf): ProcessingStorageOpenData {
                val pos = buf.readBlockPos()
                val upgradeLevel = buf.readVarInt()
                return ProcessingStorageOpenData(pos, upgradeLevel)
            }

            override fun encode(buf: FriendlyByteBuf, data: ProcessingStorageOpenData) {
                buf.writeBlockPos(data.pos)
                buf.writeVarInt(data.upgradeLevel)
            }
        }
    }
}
