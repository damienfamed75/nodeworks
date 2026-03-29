package damien.nodeworks.screen

import net.minecraft.core.BlockPos
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec

data class ApiStorageOpenData(
    val pos: BlockPos,
    val upgradeLevel: Int
) {
    companion object {
        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, ApiStorageOpenData> = object : StreamCodec<FriendlyByteBuf, ApiStorageOpenData> {
            override fun decode(buf: FriendlyByteBuf): ApiStorageOpenData {
                val pos = buf.readBlockPos()
                val upgradeLevel = buf.readVarInt()
                return ApiStorageOpenData(pos, upgradeLevel)
            }

            override fun encode(buf: FriendlyByteBuf, data: ApiStorageOpenData) {
                buf.writeBlockPos(data.pos)
                buf.writeVarInt(data.upgradeLevel)
            }
        }
    }
}
