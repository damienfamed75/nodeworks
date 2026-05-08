package damien.nodeworks.screen

import net.minecraft.core.BlockPos
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec

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
 */
data class ProcessingHandlerOpenData(
    val pos: BlockPos,
    val processingApiName: String,
    val boundSetMissing: Boolean,
    val inputChannels: List<InputChannelEntry>,
    val outputChannelId: Int,
    /** The bound set's full recipe (inputs + outputs with counts), null when
     *  nothing is bound or when the bound set isn't on the parent network
     *  anymore. The screen uses this to render the recipe-strip panel and
     *  the outputs scrollbox; the BE itself only stores input itemIds and a
     *  single output channel, not counts. */
    val boundSet: AvailableSet?,
    val available: List<AvailableSet>,
) {
    /** One row in the bound-state input channel list. */
    data class InputChannelEntry(val itemId: String, val channelId: Int)

    /** One picker entry. The picker shows [name] but routes by [name] back to
     *  the server. [inputs] and [outputs] are full (id, count) lists so the
     *  picker can render item icons + counts, and so the server-side bind
     *  handler can default the per-input channel map without re-walking the
     *  network. */
    data class AvailableSet(
        val name: String,
        val inputs: List<Pair<String, Int>>,
        val outputs: List<Pair<String, Int>>,
    )

    companion object {
        /** Canonical recipe id is the recipe layout joined into a string
         *  (`item@count|...>>item@count|...`). With 9 inputs + 3 outputs of
         *  typical modded item ids it can easily exceed 256 chars; cap at
         *  4096 so the upper bound (~12 entries × ~80 chars + separators)
         *  always fits. Bound by [ProcessingSet.canonicalId]'s output, not
         *  player input. */
        const val MAX_API_NAME = 4096
        const val MAX_ITEM_ID = 256
        const val MAX_INPUTS = 32
        const val MAX_OUTPUTS = 32
        const val MAX_AVAILABLE = 64

        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, ProcessingHandlerOpenData> =
            object : StreamCodec<FriendlyByteBuf, ProcessingHandlerOpenData> {
                private fun decodeSet(buf: FriendlyByteBuf): AvailableSet {
                    val sn = buf.readUtf(MAX_API_NAME)
                    val ic = buf.readVarInt().coerceIn(0, MAX_INPUTS)
                    val ins = ArrayList<Pair<String, Int>>(ic)
                    repeat(ic) { ins.add(buf.readUtf(MAX_ITEM_ID) to buf.readVarInt()) }
                    val oc = buf.readVarInt().coerceIn(0, MAX_OUTPUTS)
                    val outs = ArrayList<Pair<String, Int>>(oc)
                    repeat(oc) { outs.add(buf.readUtf(MAX_ITEM_ID) to buf.readVarInt()) }
                    return AvailableSet(sn, ins, outs)
                }

                private fun encodeSet(buf: FriendlyByteBuf, set: AvailableSet) {
                    buf.writeUtf(set.name, MAX_API_NAME)
                    buf.writeVarInt(set.inputs.size.coerceAtMost(MAX_INPUTS))
                    for ((id, count) in set.inputs.take(MAX_INPUTS)) {
                        buf.writeUtf(id, MAX_ITEM_ID); buf.writeVarInt(count)
                    }
                    buf.writeVarInt(set.outputs.size.coerceAtMost(MAX_OUTPUTS))
                    for ((id, count) in set.outputs.take(MAX_OUTPUTS)) {
                        buf.writeUtf(id, MAX_ITEM_ID); buf.writeVarInt(count)
                    }
                }

                override fun decode(buf: FriendlyByteBuf): ProcessingHandlerOpenData {
                    val pos = buf.readBlockPos()
                    val name = buf.readUtf(MAX_API_NAME)
                    val missing = buf.readBoolean()
                    val inputCount = buf.readVarInt().coerceIn(0, MAX_INPUTS)
                    val inputs = ArrayList<InputChannelEntry>(inputCount)
                    repeat(inputCount) {
                        inputs.add(InputChannelEntry(buf.readUtf(MAX_ITEM_ID), buf.readVarInt()))
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
