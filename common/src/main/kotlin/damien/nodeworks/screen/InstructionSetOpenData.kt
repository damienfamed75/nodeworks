package damien.nodeworks.screen

import net.minecraft.core.BlockPos
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec

data class InstructionSetOpenData(
    val nodePos: BlockPos,
    val sideOrdinal: Int,
    val slotIndex: Int,
    val recipe: List<String> // 9 item IDs
) {
    companion object {
        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, InstructionSetOpenData> = object : StreamCodec<FriendlyByteBuf, InstructionSetOpenData> {
            override fun decode(buf: FriendlyByteBuf): InstructionSetOpenData {
                val nodePos = buf.readBlockPos()
                val sideOrdinal = buf.readVarInt()
                val slotIndex = buf.readVarInt()
                val recipe = (0 until 9).map { buf.readUtf(256) }
                return InstructionSetOpenData(nodePos, sideOrdinal, slotIndex, recipe)
            }

            override fun encode(buf: FriendlyByteBuf, data: InstructionSetOpenData) {
                buf.writeBlockPos(data.nodePos)
                buf.writeVarInt(data.sideOrdinal)
                buf.writeVarInt(data.slotIndex)
                for (itemId in data.recipe) {
                    buf.writeUtf(itemId, 256)
                }
            }
        }
    }
}
