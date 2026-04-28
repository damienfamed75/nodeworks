package damien.nodeworks.screen

import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec

/**
 * Wire format for opening the Processing Set screen. The slot-position arrays
 * ([inputSlots], [outputSlots]) run parallel to the item lists so the client
 * can place ghost items at the exact grid positions the player left them in.
 *
 * For legacy cards without stored positions, the server-side getter supplies
 * sequential fallbacks, so [inputSlots] always aligns with [inputs].
 */
data class ProcessingSetOpenData(
    val name: String,
    val inputs: List<Pair<String, Int>>,
    val inputSlots: IntArray,
    val outputs: List<Pair<String, Int>>,
    val outputSlots: IntArray,
    val timeout: Int,
    val serial: Boolean
) {
    companion object {
        // Canonical-id names can be long (9 inputs + 3 outputs × ~30 chars/slot + separators
        // ≈ 400+ chars with modded namespaces). Use 1024, well under FriendlyByteBuf's
        // 32767 default cap, but big enough for any realistic recipe.
        private const val MAX_NAME_LEN = 1024

        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, ProcessingSetOpenData> = object : StreamCodec<FriendlyByteBuf, ProcessingSetOpenData> {
            override fun decode(buf: FriendlyByteBuf): ProcessingSetOpenData {
                val name = buf.readUtf(MAX_NAME_LEN)
                val inputCount = buf.readVarInt().coerceAtMost(9)
                val inputs = (0 until inputCount).map {
                    buf.readUtf(256) to buf.readVarInt()
                }
                val inputSlots = IntArray(inputCount) { buf.readVarInt() }
                val outputCount = buf.readVarInt().coerceAtMost(3)
                val outputs = (0 until outputCount).map {
                    buf.readUtf(256) to buf.readVarInt()
                }
                val outputSlots = IntArray(outputCount) { buf.readVarInt() }
                val timeout = buf.readVarInt()
                val serial = buf.readBoolean()
                return ProcessingSetOpenData(name, inputs, inputSlots, outputs, outputSlots, timeout, serial)
            }

            override fun encode(buf: FriendlyByteBuf, data: ProcessingSetOpenData) {
                buf.writeUtf(data.name, MAX_NAME_LEN)
                buf.writeVarInt(data.inputs.size)
                for ((id, count) in data.inputs) {
                    buf.writeUtf(id, 256)
                    buf.writeVarInt(count)
                }
                for (i in data.inputs.indices) {
                    buf.writeVarInt(data.inputSlots.getOrElse(i) { i })
                }
                buf.writeVarInt(data.outputs.size)
                for ((id, count) in data.outputs) {
                    buf.writeUtf(id, 256)
                    buf.writeVarInt(count)
                }
                for (i in data.outputs.indices) {
                    buf.writeVarInt(data.outputSlots.getOrElse(i) { i })
                }
                buf.writeVarInt(data.timeout)
                buf.writeBoolean(data.serial)
            }
        }
    }
}
