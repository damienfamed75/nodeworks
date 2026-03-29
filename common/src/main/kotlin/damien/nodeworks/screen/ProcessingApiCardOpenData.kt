package damien.nodeworks.screen

import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec

data class ProcessingApiCardOpenData(
    val inputs: List<Pair<String, Int>>,
    val outputs: List<Pair<String, Int>>,
    val timeout: Int
) {
    companion object {
        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, ProcessingApiCardOpenData> = object : StreamCodec<FriendlyByteBuf, ProcessingApiCardOpenData> {
            override fun decode(buf: FriendlyByteBuf): ProcessingApiCardOpenData {
                val inputCount = buf.readVarInt()
                val inputs = (0 until inputCount).map {
                    buf.readUtf(256) to buf.readVarInt()
                }
                val outputCount = buf.readVarInt()
                val outputs = (0 until outputCount).map {
                    buf.readUtf(256) to buf.readVarInt()
                }
                val timeout = buf.readVarInt()
                return ProcessingApiCardOpenData(inputs, outputs, timeout)
            }

            override fun encode(buf: FriendlyByteBuf, data: ProcessingApiCardOpenData) {
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
            }
        }
    }
}
