package damien.nodeworks.screen

import damien.nodeworks.script.RecipeIngredient
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.world.item.ItemStack

/**
 * Wire format for opening the Processing Set screen. The slot-position arrays
 * ([inputSlots], [outputSlots]) run parallel to the item lists so the client
 * can place ghost items at the exact grid positions the player left them in.
 *
 * For legacy cards without stored positions, the server-side getter supplies
 * sequential fallbacks, so [inputSlots] always aligns with [inputs].
 *
 * Each ingredient ships as a full ItemStack via [ItemStack.STREAM_CODEC]
 * (preserves DataComponents) with an explicit Int count beside it. The
 * stack's own count field isn't used. Recipe counts can exceed stack-size
 * and the editor treats it as a separate axis.
 */
data class ProcessingSetOpenData(
    val name: String,
    val inputs: List<RecipeIngredient>,
    val inputSlots: IntArray,
    val outputs: List<RecipeIngredient>,
    val outputSlots: IntArray,
    val timeout: Int,
    val fuzzy: Boolean,
    val serial: Boolean,
) {
    companion object {
        // Display name (cosmetic), no longer the handler key. 1024 chars is
        // already excessive but matches the pre-component cap.
        private const val MAX_NAME_LEN = 1024
        const val MAX_TAG_LEN = 256
        /** The encoder writes at most this many entries per side and the decoder refuses
         *  more, so the two can never disagree on how many stacks follow. A card whose
         *  NBT holds more, by command or a later build, opens with the first ones. */
        private const val MAX_INPUTS = damien.nodeworks.card.ProcessingSet.INPUT_GRID_SIZE
        private const val MAX_OUTPUTS = damien.nodeworks.card.ProcessingSet.MAX_OUTPUTS

        /** A list length outside what the encoder can write means a stream this build does
         *  not understand. Failing here is a clean decode error rather than a misread. */
        private fun readCount(buf: FriendlyByteBuf, max: Int): Int {
            val n = buf.readVarInt()
            if (n < 0 || n > max) throw io.netty.handler.codec.DecoderException("Processing Set open data lists $n entries, at most $max allowed")
            return n
        }

        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, ProcessingSetOpenData> = object : StreamCodec<FriendlyByteBuf, ProcessingSetOpenData> {
            override fun decode(buf: FriendlyByteBuf): ProcessingSetOpenData {
                val regBuf = buf as RegistryFriendlyByteBuf
                val name = buf.readUtf(MAX_NAME_LEN)
                val inputCount = readCount(buf, MAX_INPUTS)
                val inputs = (0 until inputCount).map {
                    val stack = ItemStack.OPTIONAL_STREAM_CODEC.decode(regBuf)
                    val cnt = buf.readVarInt()
                    val tag = buf.readUtf(MAX_TAG_LEN).takeIf { it.isNotEmpty() }
                    RecipeIngredient(stack, cnt, tag)
                }
                val inputSlots = IntArray(inputCount) { buf.readVarInt() }
                val outputCount = readCount(buf, MAX_OUTPUTS)
                val outputs = (0 until outputCount).map {
                    val stack = ItemStack.OPTIONAL_STREAM_CODEC.decode(regBuf)
                    val cnt = buf.readVarInt()
                    val optional = buf.readBoolean()
                    RecipeIngredient(stack, cnt, optional = optional)
                }
                val outputSlots = IntArray(outputCount) { buf.readVarInt() }
                val timeout = buf.readVarInt()
                val fuzzy = buf.readBoolean()
                val serial = buf.readBoolean()
                return ProcessingSetOpenData(name, inputs, inputSlots, outputs, outputSlots, timeout, fuzzy, serial)
            }

            override fun encode(buf: FriendlyByteBuf, data: ProcessingSetOpenData) {
                val regBuf = buf as RegistryFriendlyByteBuf
                buf.writeUtf(data.name, MAX_NAME_LEN)
                val inputs = data.inputs.take(MAX_INPUTS)
                buf.writeVarInt(inputs.size)
                for (ingr in inputs) {
                    ItemStack.OPTIONAL_STREAM_CODEC.encode(regBuf, ingr.stack)
                    buf.writeVarInt(ingr.count)
                    buf.writeUtf(ingr.tag ?: "", MAX_TAG_LEN)
                }
                for (i in inputs.indices) {
                    buf.writeVarInt(data.inputSlots.getOrElse(i) { i })
                }
                val outputs = data.outputs.take(MAX_OUTPUTS)
                buf.writeVarInt(outputs.size)
                for (ingr in outputs) {
                    ItemStack.OPTIONAL_STREAM_CODEC.encode(regBuf, ingr.stack)
                    buf.writeVarInt(ingr.count)
                    buf.writeBoolean(ingr.optional)
                }
                for (i in outputs.indices) {
                    buf.writeVarInt(data.outputSlots.getOrElse(i) { i })
                }
                buf.writeVarInt(data.timeout)
                buf.writeBoolean(data.fuzzy)
                buf.writeBoolean(data.serial)
            }
        }
    }
}
