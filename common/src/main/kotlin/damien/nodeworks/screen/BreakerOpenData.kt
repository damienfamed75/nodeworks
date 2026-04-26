package damien.nodeworks.screen

import net.minecraft.core.BlockPos
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec

/**
 * Open-payload for the Breaker Settings GUI. Mirrors [VariableOpenData] —
 * carries the device's position so the menu can address the BlockEntity, plus
 * the current name and channel for the screen to render at open time. Updates
 * after open flow back via the existing settings payload pipeline (same
 * mechanism Variables use).
 */
data class BreakerOpenData(
    val pos: BlockPos,
    val deviceName: String,
    val channelId: Int = 0,
) {
    companion object {
        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, BreakerOpenData> =
            object : StreamCodec<FriendlyByteBuf, BreakerOpenData> {
                override fun decode(buf: FriendlyByteBuf): BreakerOpenData = BreakerOpenData(
                    buf.readBlockPos(),
                    buf.readUtf(32),
                    buf.readVarInt(),
                )
                override fun encode(buf: FriendlyByteBuf, data: BreakerOpenData) {
                    buf.writeBlockPos(data.pos)
                    buf.writeUtf(data.deviceName, 32)
                    buf.writeVarInt(data.channelId)
                }
            }
    }
}
