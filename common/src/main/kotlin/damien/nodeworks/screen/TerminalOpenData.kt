package damien.nodeworks.screen

import net.minecraft.core.BlockPos
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec

data class TerminalOpenData(
    val terminalPos: BlockPos,
    val scripts: Map<String, String>,
    val running: Boolean,
    val autoRun: Boolean,
    val layoutIndex: Int
) {
    companion object {
        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, TerminalOpenData> = object : StreamCodec<FriendlyByteBuf, TerminalOpenData> {
            override fun decode(buf: FriendlyByteBuf): TerminalOpenData {
                val pos = buf.readBlockPos()
                val scriptCount = buf.readVarInt()
                val scripts = linkedMapOf<String, String>()
                for (i in 0 until scriptCount) {
                    val name = buf.readUtf(64)
                    val text = buf.readUtf(32767)
                    scripts[name] = text
                }
                val running = buf.readBoolean()
                val autoRun = buf.readBoolean()
                val layoutIndex = buf.readVarInt()
                return TerminalOpenData(pos, scripts, running, autoRun, layoutIndex)
            }

            override fun encode(buf: FriendlyByteBuf, data: TerminalOpenData) {
                buf.writeBlockPos(data.terminalPos)
                buf.writeVarInt(data.scripts.size)
                for ((name, text) in data.scripts) {
                    buf.writeUtf(name, 64)
                    buf.writeUtf(text, 32767)
                }
                buf.writeBoolean(data.running)
                buf.writeBoolean(data.autoRun)
                buf.writeVarInt(data.layoutIndex)
            }
        }
    }
}
