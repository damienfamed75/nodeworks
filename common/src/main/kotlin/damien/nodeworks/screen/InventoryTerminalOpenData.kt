package damien.nodeworks.screen

import net.minecraft.core.BlockPos
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec

data class InventoryTerminalOpenData(
    val terminalPos: BlockPos,
    val nodePos: BlockPos
) {
    companion object {
        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, InventoryTerminalOpenData> = object : StreamCodec<FriendlyByteBuf, InventoryTerminalOpenData> {
            override fun decode(buf: FriendlyByteBuf): InventoryTerminalOpenData {
                return InventoryTerminalOpenData(buf.readBlockPos(), buf.readBlockPos())
            }
            override fun encode(buf: FriendlyByteBuf, data: InventoryTerminalOpenData) {
                buf.writeBlockPos(data.terminalPos)
                buf.writeBlockPos(data.nodePos)
            }
        }
    }
}
