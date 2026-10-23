package damien.nodeworks.screen

import net.minecraft.core.BlockPos
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec

/** Open-time payload for the Interface menu: only the block it is bound to. */
data class InterfaceOpenData(val pos: BlockPos) {
    companion object {
        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, InterfaceOpenData> =
            object : StreamCodec<FriendlyByteBuf, InterfaceOpenData> {
                override fun decode(buf: FriendlyByteBuf): InterfaceOpenData = InterfaceOpenData(buf.readBlockPos())
                override fun encode(buf: FriendlyByteBuf, data: InterfaceOpenData) { buf.writeBlockPos(data.pos) }
            }
    }
}
