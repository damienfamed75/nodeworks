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

        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, ProcessingSetOpenData> = object : StreamCodec<FriendlyByteBuf, ProcessingSetOpenData> {
            override fun decode(buf: FriendlyByteBuf): ProcessingSetOpenData {
                val regBuf = buf as RegistryFriendlyByteBuf
                val name = buf.readUtf(MAX_NAME_LEN)
                val inputCount = buf.readVarInt().coerceAtMost(9)
                val inputs = (0 until inputCount).map {
                    val stack = ItemStack.OPTIONAL_STREAM_CODEC.decode(regBuf)
                    val cnt = buf.readVarInt()
                    RecipeIngredient(stack, cnt)
                }
                val inputSlots = IntArray(inputCount) { buf.readVarInt() }
                val outputCount = buf.readVarInt().coerceAtMost(3)
                val outputs = (0 until outputCount).map {
                    val stack = ItemStack.OPTIONAL_STREAM_CODEC.decode(regBuf)
                    val cnt = buf.readVarInt()
                    RecipeIngredient(stack, cnt)
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
                buf.writeVarInt(data.inputs.size)
                for (ingr in data.inputs) {
                    ItemStack.OPTIONAL_STREAM_CODEC.encode(regBuf, ingr.stack)
                    buf.writeVarInt(ingr.count)
                }
                for (i in data.inputs.indices) {
                    buf.writeVarInt(data.inputSlots.getOrElse(i) { i })
                }
                buf.writeVarInt(data.outputs.size)
                for (ingr in data.outputs) {
                    ItemStack.OPTIONAL_STREAM_CODEC.encode(regBuf, ingr.stack)
                    buf.writeVarInt(ingr.count)
                }
                for (i in data.outputs.indices) {
                    buf.writeVarInt(data.outputSlots.getOrElse(i) { i })
                }
                buf.writeVarInt(data.timeout)
                buf.writeBoolean(data.fuzzy)
                buf.writeBoolean(data.serial)
            }
        }
    }
}
