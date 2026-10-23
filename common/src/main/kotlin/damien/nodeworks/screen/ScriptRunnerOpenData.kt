package damien.nodeworks.screen

import damien.nodeworks.block.entity.TerminalBlockEntity
import net.minecraft.core.BlockPos
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec

/** Open-time payload for the Script Runner: the block, and each slot's recorded fault. */
data class ScriptRunnerOpenData(val pos: BlockPos, val errors: List<String>) {
    companion object {
        private const val MAX_ERROR_LENGTH = 512

        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, ScriptRunnerOpenData> =
            object : StreamCodec<FriendlyByteBuf, ScriptRunnerOpenData> {
                override fun decode(buf: FriendlyByteBuf): ScriptRunnerOpenData {
                    val pos = buf.readBlockPos()
                    val errors = List(TerminalBlockEntity.SLOT_COUNT) { buf.readUtf(MAX_ERROR_LENGTH) }
                    return ScriptRunnerOpenData(pos, errors)
                }

                override fun encode(buf: FriendlyByteBuf, data: ScriptRunnerOpenData) {
                    buf.writeBlockPos(data.pos)
                    for (i in 0 until TerminalBlockEntity.SLOT_COUNT) {
                        buf.writeUtf(data.errors.getOrElse(i) { "" }.take(MAX_ERROR_LENGTH), MAX_ERROR_LENGTH)
                    }
                }
            }
    }
}
