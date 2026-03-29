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
    val scripts: Map<String, String>,
    val running: Boolean,
    val autoRun: Boolean,
    val layoutIndex: Int,
    val cards: List<CardSnapshot>,
    val itemTags: List<String>,
    val variables: List<Pair<String, Int>> = emptyList(), // name to type ordinal
    val processingOutputs: List<String> = emptyList() // output item IDs from Processing API Cards
) {
    companion object {
        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, TerminalOpenData> = object : StreamCodec<FriendlyByteBuf, TerminalOpenData> {
            override fun decode(buf: FriendlyByteBuf): TerminalOpenData {
                val pos = buf.readBlockPos()
                val scriptCount = buf.readVarInt()
                val scripts = linkedMapOf<String, String>()
                for (i in 0 until scriptCount) {
                    val name = buf.readUtf(64)
                    val text = buf.readUtf(32767)
                    scripts[name] = text
                }
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
                val varCount = buf.readVarInt()
                val variables = (0 until varCount).map { buf.readUtf(32) to buf.readVarInt() }
                val procCount = buf.readVarInt()
                val processingOutputs = (0 until procCount).map { buf.readUtf(256) }
                return TerminalOpenData(pos, scripts, running, autoRun, layoutIndex, cards, itemTags, variables, processingOutputs)
            }

            override fun encode(buf: FriendlyByteBuf, data: TerminalOpenData) {
                buf.writeBlockPos(data.terminalPos)
                buf.writeVarInt(data.scripts.size)
                for ((name, text) in data.scripts) {
                    buf.writeUtf(name, 64)
                    buf.writeUtf(text, 32767)
                }
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
                buf.writeVarInt(data.variables.size)
                for ((name, type) in data.variables) {
                    buf.writeUtf(name, 32)
                    buf.writeVarInt(type)
                }
                buf.writeVarInt(data.processingOutputs.size)
                for (output in data.processingOutputs) {
                    buf.writeUtf(output, 256)
                }
            }
        }
    }
}
