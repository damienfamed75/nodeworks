package damien.nodeworks.screen

import damien.nodeworks.card.IOSideCapability
import damien.nodeworks.card.StorageSideCapability
import damien.nodeworks.network.CardSnapshot
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec

data class TerminalOpenData(
    val terminalPos: BlockPos,
    val scriptText: String,
    val running: Boolean,
    val autoRun: Boolean,
    val layoutIndex: Int,
    val cards: List<CardSnapshot>,
    val itemTags: List<String>
) {
    companion object {
        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, TerminalOpenData> = object : StreamCodec<FriendlyByteBuf, TerminalOpenData> {
            override fun decode(buf: FriendlyByteBuf): TerminalOpenData {
                val pos = buf.readBlockPos()
                val script = buf.readUtf(32767)
                val running = buf.readBoolean()
                val autoRun = buf.readBoolean()
                val layoutIndex = buf.readVarInt()
                val cardCount = buf.readVarInt()
                val cards = (0 until cardCount).map {
                    val alias = buf.readUtf(256).ifEmpty { null }
                    val type = buf.readUtf(64)
                    val adjacentPos = buf.readBlockPos()
                    val defaultFace = Direction.entries[buf.readVarInt()]
                    val slotIndex = buf.readVarInt()
                    val capability = when (type) {
                        "storage" -> StorageSideCapability(adjacentPos, defaultFace)
                        else -> IOSideCapability(adjacentPos, defaultFace)
                    }
                    CardSnapshot(
                        capability = capability,
                        alias = alias,
                        slotIndex = slotIndex
                    )
                }
                val tagCount = buf.readVarInt()
                val itemTags = (0 until tagCount).map { buf.readUtf(256) }
                return TerminalOpenData(pos, script, running, autoRun, layoutIndex, cards, itemTags)
            }

            override fun encode(buf: FriendlyByteBuf, data: TerminalOpenData) {
                buf.writeBlockPos(data.terminalPos)
                buf.writeUtf(data.scriptText, 32767)
                buf.writeBoolean(data.running)
                buf.writeBoolean(data.autoRun)
                buf.writeVarInt(data.layoutIndex)
                buf.writeVarInt(data.cards.size)
                for (card in data.cards) {
                    buf.writeUtf(card.effectiveAlias, 256)
                    buf.writeUtf(card.capability.type, 64)
                    buf.writeBlockPos(card.capability.adjacentPos)
                    val defaultFace = when (val cap = card.capability) {
                        is IOSideCapability -> cap.defaultFace
                        is StorageSideCapability -> cap.defaultFace
                        else -> Direction.UP
                    }
                    buf.writeVarInt(defaultFace.ordinal)
                    buf.writeVarInt(card.slotIndex)
                }
                buf.writeVarInt(data.itemTags.size)
                for (tag in data.itemTags) {
                    buf.writeUtf(tag, 256)
                }
            }
        }
    }
}
