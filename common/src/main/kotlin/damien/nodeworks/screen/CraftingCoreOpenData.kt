package damien.nodeworks.screen

import net.minecraft.core.BlockPos
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec

data class CraftingCoreOpenData(
    val pos: BlockPos,
    val bufferUsed: Int,
    val bufferCapacity: Int,
    val isFormed: Boolean,
    val isCrafting: Boolean
) {
    companion object {
        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, CraftingCoreOpenData> = object : StreamCodec<FriendlyByteBuf, CraftingCoreOpenData> {
            override fun decode(buf: FriendlyByteBuf): CraftingCoreOpenData {
                return CraftingCoreOpenData(buf.readBlockPos(), buf.readVarInt(), buf.readVarInt(), buf.readBoolean(), buf.readBoolean())
            }
            override fun encode(buf: FriendlyByteBuf, data: CraftingCoreOpenData) {
                buf.writeBlockPos(data.pos)
                buf.writeVarInt(data.bufferUsed)
                buf.writeVarInt(data.bufferCapacity)
                buf.writeBoolean(data.isFormed)
                buf.writeBoolean(data.isCrafting)
            }
        }
    }
}
