package damien.nodeworks.screen

import net.minecraft.core.BlockPos
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec

/**
 * Open-payload for the Display Settings GUI. [pos] is the clicked block; the
 * screen seeds its name field from [displayName]. For a multiblock cluster
 * every member opens the same screen with the cluster's shared name (resolved
 * server-side from the cluster anchor), and name commits go back through
 * [damien.nodeworks.network.DeviceSettingsPayload], last-write-wins.
 */
data class DisplayOpenData(
    val pos: BlockPos,
    val displayName: String,
) {
    companion object {
        const val MAX_NAME_LENGTH = 32

        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, DisplayOpenData> =
            object : StreamCodec<FriendlyByteBuf, DisplayOpenData> {
                override fun decode(buf: FriendlyByteBuf): DisplayOpenData = DisplayOpenData(
                    buf.readBlockPos(),
                    buf.readUtf(MAX_NAME_LENGTH),
                )

                override fun encode(buf: FriendlyByteBuf, data: DisplayOpenData) {
                    buf.writeBlockPos(data.pos)
                    buf.writeUtf(data.displayName, MAX_NAME_LENGTH)
                }
            }
    }
}
