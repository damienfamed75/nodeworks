package damien.nodeworks.screen

import net.minecraft.core.BlockPos
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec

/**
 * Extended-menu open payload for the Widget block GUI. Carries the full
 * server-side configuration so the screen can seed every field on open.
 * [options] is the radio option list joined with newlines.
 */
data class WidgetOpenData(
    val pos: BlockPos,
    val widgetName: String,
    val widgetType: Int,
    val channelId: Int,
    val sliderMin: Float,
    val sliderMax: Float,
    val sliderStep: Float,
    val options: String,
    val buttonHoldTicks: Int,
) {
    companion object {
        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, WidgetOpenData> = object : StreamCodec<FriendlyByteBuf, WidgetOpenData> {
            override fun decode(buf: FriendlyByteBuf): WidgetOpenData {
                return WidgetOpenData(
                    buf.readBlockPos(),
                    buf.readUtf(32),
                    buf.readVarInt(),
                    buf.readVarInt(),
                    buf.readFloat(),
                    buf.readFloat(),
                    buf.readFloat(),
                    buf.readUtf(512),
                    buf.readVarInt(),
                )
            }
            override fun encode(buf: FriendlyByteBuf, data: WidgetOpenData) {
                buf.writeBlockPos(data.pos)
                buf.writeUtf(data.widgetName, 32)
                buf.writeVarInt(data.widgetType)
                buf.writeVarInt(data.channelId)
                buf.writeFloat(data.sliderMin)
                buf.writeFloat(data.sliderMax)
                buf.writeFloat(data.sliderStep)
                buf.writeUtf(data.options, 512)
                buf.writeVarInt(data.buttonHoldTicks)
            }
        }
    }
}
