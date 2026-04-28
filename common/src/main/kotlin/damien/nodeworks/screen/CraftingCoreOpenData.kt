package damien.nodeworks.screen

import net.minecraft.core.BlockPos
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec

/**
 * Initial CPU state sent to the client on menu open.
 * Counts are Long-safe, type counts are Int (small).
 * Live updates after open are pushed via BufferSyncPayload.
 */
data class CraftingCoreOpenData(
    val pos: BlockPos,
    val bufferUsed: Long,
    val bufferCapacity: Long,
    val bufferTypesUsed: Int,
    val bufferTypesCapacity: Int,
    val isFormed: Boolean,
    val isCrafting: Boolean,
    val lastFailureReason: String
) {
    companion object {
        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, CraftingCoreOpenData> = object : StreamCodec<FriendlyByteBuf, CraftingCoreOpenData> {
            override fun decode(buf: FriendlyByteBuf): CraftingCoreOpenData {
                return CraftingCoreOpenData(
                    buf.readBlockPos(),
                    buf.readVarLong(),
                    buf.readVarLong(),
                    buf.readVarInt(),
                    buf.readVarInt(),
                    buf.readBoolean(),
                    buf.readBoolean(),
                    buf.readUtf(256)
                )
            }
            override fun encode(buf: FriendlyByteBuf, data: CraftingCoreOpenData) {
                buf.writeBlockPos(data.pos)
                buf.writeVarLong(data.bufferUsed)
                buf.writeVarLong(data.bufferCapacity)
                buf.writeVarInt(data.bufferTypesUsed)
                buf.writeVarInt(data.bufferTypesCapacity)
                buf.writeBoolean(data.isFormed)
                buf.writeBoolean(data.isCrafting)
                buf.writeUtf(data.lastFailureReason, 256)
            }
        }
    }
}
