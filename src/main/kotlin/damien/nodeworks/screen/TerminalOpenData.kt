package damien.nodeworks.screen

import damien.nodeworks.card.InventorySideCapability
import damien.nodeworks.network.CardSnapshot
import net.minecraft.core.BlockPos
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec

data class TerminalOpenData(
    val terminalPos: BlockPos,
    val scriptText: String,
    val running: Boolean,
    val autoRun: Boolean,
    val cards: List<CardSnapshot>
) {
    companion object {
        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, TerminalOpenData> = object : StreamCodec<FriendlyByteBuf, TerminalOpenData> {
            override fun decode(buf: FriendlyByteBuf): TerminalOpenData {
                val pos = buf.readBlockPos()
                val script = buf.readUtf(32767)
                val running = buf.readBoolean()
                val autoRun = buf.readBoolean()
                val cardCount = buf.readVarInt()
                val cards = (0 until cardCount).map {
                    val alias = buf.readUtf(256).ifEmpty { null }
                    val type = buf.readUtf(64)
                    val adjacentPos = buf.readBlockPos()
                    val slotIndex = buf.readVarInt()
                    CardSnapshot(
                        capability = InventorySideCapability(adjacentPos),
                        alias = alias,
                        slotIndex = slotIndex
                    )
                }
                return TerminalOpenData(pos, script, running, autoRun, cards)
            }

            override fun encode(buf: FriendlyByteBuf, data: TerminalOpenData) {
                buf.writeBlockPos(data.terminalPos)
                buf.writeUtf(data.scriptText, 32767)
                buf.writeBoolean(data.running)
                buf.writeBoolean(data.autoRun)
                buf.writeVarInt(data.cards.size)
                for (card in data.cards) {
                    buf.writeUtf(card.alias ?: "", 256)
                    buf.writeUtf(card.capability.type, 64)
                    buf.writeBlockPos(card.capability.adjacentPos)
                    buf.writeVarInt(card.slotIndex)
                }
            }
        }
    }
}
