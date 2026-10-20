package damien.nodeworks.screen

import net.minecraft.core.BlockPos
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec

/**
 * Open-payload for the Multi-Processing Handler GUI: every Processing Set on the
 * parent network with who else handles it, the names this Handler already owns, the
 * cap, and the two channels. Sent at open time and again after every change through
 * [damien.nodeworks.network.MultiProcessingHandlerStateSyncPayload].
 */
data class MultiProcessingHandlerOpenData(
    val pos: BlockPos,
    val maxSets: Int,
    val bound: List<String>,
    val inputChannelId: Int,
    val outputChannelId: Int,
    val sets: List<SetEntry>,
) {
    /** One Processing Set on the parent network. [claimedBy] names what else handles
     *  it, a script or another handler block, and is empty when it is free. */
    data class SetEntry(val set: ProcessingHandlerOpenData.AvailableSet, val claimedBy: String)

    companion object {
        /** Sets one open packet carries, each with up to twelve item stacks. A network
         *  with more shows the first this many. */
        const val MAX_SETS_ON_WIRE = 256
        const val MAX_CLAIM = 64

        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, MultiProcessingHandlerOpenData> =
            object : StreamCodec<FriendlyByteBuf, MultiProcessingHandlerOpenData> {
                override fun decode(buf: FriendlyByteBuf): MultiProcessingHandlerOpenData {
                    val pos = buf.readBlockPos()
                    val maxSets = buf.readVarInt()
                    val boundCount = buf.readVarInt().coerceIn(0, MAX_SETS_ON_WIRE)
                    val bound = ArrayList<String>(boundCount)
                    repeat(boundCount) { bound.add(buf.readUtf(ProcessingHandlerOpenData.MAX_API_NAME)) }
                    val inputChannel = buf.readVarInt()
                    val outputChannel = buf.readVarInt()
                    val setCount = buf.readVarInt().coerceIn(0, MAX_SETS_ON_WIRE)
                    val sets = ArrayList<SetEntry>(setCount)
                    repeat(setCount) {
                        val set = ProcessingHandlerOpenData.decodeSet(buf)
                        val claimedBy = buf.readUtf(MAX_CLAIM)
                        sets.add(SetEntry(set, claimedBy))
                    }
                    return MultiProcessingHandlerOpenData(pos, maxSets, bound, inputChannel, outputChannel, sets)
                }

                override fun encode(buf: FriendlyByteBuf, data: MultiProcessingHandlerOpenData) {
                    buf.writeBlockPos(data.pos)
                    buf.writeVarInt(data.maxSets)
                    buf.writeVarInt(data.bound.size.coerceAtMost(MAX_SETS_ON_WIRE))
                    for (name in data.bound.take(MAX_SETS_ON_WIRE)) buf.writeUtf(name, ProcessingHandlerOpenData.MAX_API_NAME)
                    buf.writeVarInt(data.inputChannelId)
                    buf.writeVarInt(data.outputChannelId)
                    buf.writeVarInt(data.sets.size.coerceAtMost(MAX_SETS_ON_WIRE))
                    for (entry in data.sets.take(MAX_SETS_ON_WIRE)) {
                        ProcessingHandlerOpenData.encodeSet(buf, entry.set)
                        buf.writeUtf(entry.claimedBy, MAX_CLAIM)
                    }
                }
            }
    }
}
