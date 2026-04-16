package damien.nodeworks.screen

import net.minecraft.core.BlockPos
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec

data class InstructionStorageOpenData(val pos: BlockPos) {
    companion object {
        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, InstructionStorageOpenData> = object : StreamCodec<FriendlyByteBuf, InstructionStorageOpenData> {
            override fun decode(buf: FriendlyByteBuf): InstructionStorageOpenData {
                return InstructionStorageOpenData(buf.readBlockPos())
            }

            override fun encode(buf: FriendlyByteBuf, data: InstructionStorageOpenData) {
                buf.writeBlockPos(data.pos)
            }
        }
    }
}
