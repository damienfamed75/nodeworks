package damien.nodeworks.screen

import damien.nodeworks.script.RecipeIngredient
import net.minecraft.core.BlockPos
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.world.item.ItemStack

/**
 * Open-payload for the Processing Handler GUI. Sends the full picture to the
 * client at open time:
 *
 *  - Where this Handler lives (`pos`).
 *  - Its current binding (`processingApiName`, plus per-input + output channels)
 *    so the config view can render the bound state.
 *  - A snapshot of [AvailableSet]s on the parent network, used by the picker
 *    popup. Sets already claimed by another handler (Lua or block) are
 *    excluded server-side so the picker only shows actionable options.
 *  - `boundSetMissing`: true when the Handler's stored binding refers to a
 *    Set that's no longer on the network. The screen surfaces this as a
 *    warning and the player can either pick a new Set or wait for the missing
 *    one to come back.
 *
 * The [AvailableSet]'s inputs and outputs are component-aware
 * [RecipeIngredient]s rather than `(itemId, count)` pairs, so the picker and
 * the recipe-strip render component-bearing items (potions etc.) with their
 * proper hover names.
 */
data class ProcessingHandlerOpenData(
    val pos: BlockPos,
    val processingApiName: String,
    val boundSetMissing: Boolean,
    val inputChannels: List<InputChannelEntry>,
    val outputChannelId: Int,
    /** The bound set's full recipe (inputs + outputs with components), null
     *  when nothing is bound or when the bound set isn't on the parent network
     *  anymore. */
    val boundSet: AvailableSet?,
    val available: List<AvailableSet>,
) {
    /** One row in the bound-state input channel list. Keyed on the full
     *  [BufferKey.Key] (itemId + componentsHash) so a recipe with two
     *  variants of the same itemId (e.g. fire + swiftness potion inputs)
     *  shows two distinct rows that can bind independent channels. Plain
     *  inputs use `componentsHash = ""`. */
    data class InputChannelEntry(val itemId: String, val componentsHash: String, val channelId: Int) {
        val bufferKey: damien.nodeworks.script.BufferKey.Key
            get() = damien.nodeworks.script.BufferKey.Key(itemId, componentsHash)
    }

    /** One picker entry. The picker shows the recipe icons and routes by [name]
     *  back to the server. [inputs] and [outputs] carry full component-bearing
     *  ingredient stacks so the picker can render the actual variant (a
     *  strength potion, not "Uncraftable Potion"). */
    data class AvailableSet(
        val name: String,
        val inputs: List<RecipeIngredient>,
        val outputs: List<RecipeIngredient>,
    )

    companion object {
        /** Recipe id is a `recipe_<hash>` literal now, well under any
         *  reasonable cap. 4096 stays well above the largest legacy
         *  canonical-id string that pre-Phase-2 worlds might still carry. */
        const val MAX_API_NAME = 4096
        const val MAX_ITEM_ID = 256
        const val MAX_INPUTS = 32
        const val MAX_OUTPUTS = 32
        const val MAX_AVAILABLE = 64

        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, ProcessingHandlerOpenData> =
            object : StreamCodec<FriendlyByteBuf, ProcessingHandlerOpenData> {
                private fun decodeSet(buf: FriendlyByteBuf): AvailableSet {
                    val regBuf = buf as RegistryFriendlyByteBuf
                    val sn = buf.readUtf(MAX_API_NAME)
                    val ic = buf.readVarInt().coerceIn(0, MAX_INPUTS)
                    val ins = ArrayList<RecipeIngredient>(ic)
                    repeat(ic) {
                        val stack = ItemStack.OPTIONAL_STREAM_CODEC.decode(regBuf)
                        val ct = buf.readVarInt()
                        ins.add(RecipeIngredient(stack, ct))
                    }
                    val oc = buf.readVarInt().coerceIn(0, MAX_OUTPUTS)
                    val outs = ArrayList<RecipeIngredient>(oc)
                    repeat(oc) {
                        val stack = ItemStack.OPTIONAL_STREAM_CODEC.decode(regBuf)
                        val ct = buf.readVarInt()
                        outs.add(RecipeIngredient(stack, ct))
                    }
                    return AvailableSet(sn, ins, outs)
                }

                private fun encodeSet(buf: FriendlyByteBuf, set: AvailableSet) {
                    val regBuf = buf as RegistryFriendlyByteBuf
                    buf.writeUtf(set.name, MAX_API_NAME)
                    buf.writeVarInt(set.inputs.size.coerceAtMost(MAX_INPUTS))
                    for (ingr in set.inputs.take(MAX_INPUTS)) {
                        ItemStack.OPTIONAL_STREAM_CODEC.encode(regBuf, ingr.stack)
                        buf.writeVarInt(ingr.count)
                    }
                    buf.writeVarInt(set.outputs.size.coerceAtMost(MAX_OUTPUTS))
                    for (ingr in set.outputs.take(MAX_OUTPUTS)) {
                        ItemStack.OPTIONAL_STREAM_CODEC.encode(regBuf, ingr.stack)
                        buf.writeVarInt(ingr.count)
                    }
                }

                override fun decode(buf: FriendlyByteBuf): ProcessingHandlerOpenData {
                    val pos = buf.readBlockPos()
                    val name = buf.readUtf(MAX_API_NAME)
                    val missing = buf.readBoolean()
                    val inputCount = buf.readVarInt().coerceIn(0, MAX_INPUTS)
                    val inputs = ArrayList<InputChannelEntry>(inputCount)
                    repeat(inputCount) {
                        val itemId = buf.readUtf(MAX_ITEM_ID)
                        val hash = buf.readUtf(32)
                        val ch = buf.readVarInt()
                        inputs.add(InputChannelEntry(itemId, hash, ch))
                    }
                    val outputCh = buf.readVarInt()
                    val boundSet = if (buf.readBoolean()) decodeSet(buf) else null
                    val availCount = buf.readVarInt().coerceIn(0, MAX_AVAILABLE)
                    val avail = ArrayList<AvailableSet>(availCount)
                    repeat(availCount) { avail.add(decodeSet(buf)) }
                    return ProcessingHandlerOpenData(pos, name, missing, inputs, outputCh, boundSet, avail)
                }

                override fun encode(buf: FriendlyByteBuf, data: ProcessingHandlerOpenData) {
                    buf.writeBlockPos(data.pos)
                    buf.writeUtf(data.processingApiName, MAX_API_NAME)
                    buf.writeBoolean(data.boundSetMissing)
                    buf.writeVarInt(data.inputChannels.size.coerceAtMost(MAX_INPUTS))
                    for (entry in data.inputChannels.take(MAX_INPUTS)) {
                        buf.writeUtf(entry.itemId, MAX_ITEM_ID)
                        buf.writeUtf(entry.componentsHash, 32)
                        buf.writeVarInt(entry.channelId)
                    }
                    buf.writeVarInt(data.outputChannelId)
                    if (data.boundSet != null) {
                        buf.writeBoolean(true)
                        encodeSet(buf, data.boundSet)
                    } else {
                        buf.writeBoolean(false)
                    }
                    buf.writeVarInt(data.available.size.coerceAtMost(MAX_AVAILABLE))
                    for (set in data.available.take(MAX_AVAILABLE)) encodeSet(buf, set)
                }
            }
    }
}
