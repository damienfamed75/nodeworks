package damien.nodeworks.screen

import net.minecraft.core.BlockPos
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec

/**
 * Open-payload for the Placer Settings GUI. Carries the device's position so
 * the menu can address the BlockEntity, plus the snapshot the screen needs at
 * open time. Live-syncing fields (channel, redstoneMode, previewArea) ride on
 * [PlacerMenu]'s [ContainerData] so external mutations reflect in-screen;
 * non-syncing fields (deviceName, filterRule) seed from this payload and
 * commit through [DeviceSettingsPayload], last-write-wins.
 */
data class PlacerOpenData(
    val pos: BlockPos,
    val deviceName: String,
    val channelId: Int,
    val filterRule: String,
    val redstoneMode: Int,
    val previewArea: Boolean,
) {
    companion object {
        const val MAX_FILTER_LENGTH = 256

        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, PlacerOpenData> =
            object : StreamCodec<FriendlyByteBuf, PlacerOpenData> {
                override fun decode(buf: FriendlyByteBuf): PlacerOpenData = PlacerOpenData(
                    buf.readBlockPos(),
                    buf.readUtf(32),
                    buf.readVarInt(),
                    buf.readUtf(MAX_FILTER_LENGTH),
                    buf.readVarInt(),
                    buf.readBoolean(),
                )

                override fun encode(buf: FriendlyByteBuf, data: PlacerOpenData) {
                    buf.writeBlockPos(data.pos)
                    buf.writeUtf(data.deviceName, 32)
                    buf.writeVarInt(data.channelId)
                    buf.writeUtf(data.filterRule, MAX_FILTER_LENGTH)
                    buf.writeVarInt(data.redstoneMode)
                    buf.writeBoolean(data.previewArea)
                }
            }
    }
}
