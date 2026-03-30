package damien.nodeworks.screen

import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec

data class ProcessingSetOpenData(
    val name: String,
    val inputs: List<Pair<String, Int>>,
    val outputs: List<Pair<String, Int>>,
    val timeout: Int,
    val serial: Boolean
) {
    companion object {
        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, ProcessingSetOpenData> = object : StreamCodec<FriendlyByteBuf, ProcessingSetOpenData> {
            override fun decode(buf: FriendlyByteBuf): ProcessingSetOpenData {
                val name = buf.readUtf(64)
                val inputCount = buf.readVarInt().coerceAtMost(9)
                val inputs = (0 until inputCount).map {
                    buf.readUtf(256) to buf.readVarInt()
                }
                val outputCount = buf.readVarInt().coerceAtMost(3)
                val outputs = (0 until outputCount).map {
                    buf.readUtf(256) to buf.readVarInt()
                }
                val timeout = buf.readVarInt()
                val serial = buf.readBoolean()
                return ProcessingSetOpenData(name, inputs, outputs, timeout, serial)
            }

            override fun encode(buf: FriendlyByteBuf, data: ProcessingSetOpenData) {
                buf.writeUtf(data.name, 64)
                buf.writeVarInt(data.inputs.size)
                for ((id, count) in data.inputs) {
                    buf.writeUtf(id, 256)
                    buf.writeVarInt(count)
                }
                buf.writeVarInt(data.outputs.size)
                for ((id, count) in data.outputs) {
                    buf.writeUtf(id, 256)
                    buf.writeVarInt(count)
                }
                buf.writeVarInt(data.timeout)
                buf.writeBoolean(data.serial)
            }
        }
    }
}
