package damien.nodeworks.screen

import net.minecraft.core.BlockPos
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec

data class InventoryTerminalOpenData(
    val terminalPos: BlockPos
) {
    // Legacy constructor for compatibility
    constructor(terminalPos: BlockPos, nodePos: BlockPos) : this(terminalPos)
    constructor(terminalPos: BlockPos, nodePos: BlockPos, layoutIndex: Int) : this(terminalPos)

    companion object {
        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, InventoryTerminalOpenData> = object : StreamCodec<FriendlyByteBuf, InventoryTerminalOpenData> {
            override fun decode(buf: FriendlyByteBuf): InventoryTerminalOpenData {
                return InventoryTerminalOpenData(buf.readBlockPos())
            }
            override fun encode(buf: FriendlyByteBuf, data: InventoryTerminalOpenData) {
                buf.writeBlockPos(data.terminalPos)
            }
        }
    }
}
