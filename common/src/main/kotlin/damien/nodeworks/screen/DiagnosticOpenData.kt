package damien.nodeworks.screen

import net.minecraft.core.BlockPos
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec

data class DiagnosticOpenData(
    val blocks: List<NetworkBlock>,
    val networkName: String,
    val networkColor: Int,
    val networkPos: BlockPos,
    val craftableItems: List<String> = emptyList(),
    val cpuInfos: List<CpuInfo> = emptyList(),
    val terminalInfos: List<TerminalInfo> = emptyList(),
    val recentErrors: List<ErrorEntry> = emptyList()
) {
    data class ErrorEntry(
        val terminalPos: BlockPos,
        val message: String,
        val tickAge: Int  // how many ticks ago this error occurred
    )

    data class CpuInfo(
        val pos: BlockPos,
        val bufferUsed: Int,
        val bufferCapacity: Int,
        val isCrafting: Boolean,
        val currentCraftItem: String,
        val isFormed: Boolean
    )

    data class TerminalInfo(
        val pos: BlockPos,
        val isRunning: Boolean,
        val scriptNames: List<String>,
        val handlers: List<String>,  // registered processing handler names
        val autoRun: Boolean
    )

    data class NetworkBlock(
        val pos: BlockPos,
        val type: String,
        val connections: List<BlockPos>,
        val cards: List<CardInfo>,
        val details: List<String>  // free-form detail lines for the inspector panel
    )

    data class CardInfo(
        val side: Int,
        val cardType: String,
        val alias: String,
        val adjacentBlockId: String  // e.g. "minecraft:furnace", empty if air
    )

    companion object {
        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, DiagnosticOpenData> = object : StreamCodec<FriendlyByteBuf, DiagnosticOpenData> {
            override fun decode(buf: FriendlyByteBuf): DiagnosticOpenData {
                val blockCount = buf.readVarInt()
                val blocks = (0 until blockCount).map {
                    val pos = buf.readBlockPos()
                    val type = buf.readUtf(64)
                    val connCount = buf.readVarInt()
                    val connections = (0 until connCount).map { buf.readBlockPos() }
                    val cardCount = buf.readVarInt()
                    val cards = (0 until cardCount).map {
                        CardInfo(buf.readVarInt(), buf.readUtf(32), buf.readUtf(64), buf.readUtf(128))
                    }
                    val detailCount = buf.readVarInt()
                    val details = (0 until detailCount).map { buf.readUtf(256) }
                    NetworkBlock(pos, type, connections, cards, details)
                }
                val networkName = buf.readUtf(64)
                val networkColor = buf.readVarInt()
                val networkPos = buf.readBlockPos()
                val craftableCount = buf.readVarInt()
                val craftableItems = (0 until craftableCount).map { buf.readUtf(256) }
                val cpuCount = buf.readVarInt()
                val cpuInfos = (0 until cpuCount).map {
                    CpuInfo(buf.readBlockPos(), buf.readVarInt(), buf.readVarInt(), buf.readBoolean(), buf.readUtf(64), buf.readBoolean())
                }
                val termCount = buf.readVarInt()
                val terminalInfos = (0 until termCount).map {
                    val tPos = buf.readBlockPos()
                    val running = buf.readBoolean()
                    val sCount = buf.readVarInt()
                    val sNames = (0 until sCount).map { buf.readUtf(64) }
                    val hCount = buf.readVarInt()
                    val handlers = (0 until hCount).map { buf.readUtf(64) }
                    val autoRun = buf.readBoolean()
                    TerminalInfo(tPos, running, sNames, handlers, autoRun)
                }
                val errCount = buf.readVarInt()
                val recentErrors = (0 until errCount).map {
                    ErrorEntry(buf.readBlockPos(), buf.readUtf(512), buf.readVarInt())
                }
                return DiagnosticOpenData(blocks, networkName, networkColor, networkPos, craftableItems, cpuInfos, terminalInfos, recentErrors)
            }

            override fun encode(buf: FriendlyByteBuf, data: DiagnosticOpenData) {
                buf.writeVarInt(data.blocks.size)
                for (block in data.blocks) {
                    buf.writeBlockPos(block.pos)
                    buf.writeUtf(block.type, 64)
                    buf.writeVarInt(block.connections.size)
                    for (conn in block.connections) {
                        buf.writeBlockPos(conn)
                    }
                    buf.writeVarInt(block.cards.size)
                    for (card in block.cards) {
                        buf.writeVarInt(card.side)
                        buf.writeUtf(card.cardType, 32)
                        buf.writeUtf(card.alias, 64)
                        buf.writeUtf(card.adjacentBlockId, 128)
                    }
                    buf.writeVarInt(block.details.size)
                    for (detail in block.details) {
                        buf.writeUtf(detail, 256)
                    }
                }
                buf.writeUtf(data.networkName, 64)
                buf.writeVarInt(data.networkColor)
                buf.writeBlockPos(data.networkPos)
                buf.writeVarInt(data.craftableItems.size)
                for (item in data.craftableItems) buf.writeUtf(item, 256)
                buf.writeVarInt(data.cpuInfos.size)
                for (cpu in data.cpuInfos) {
                    buf.writeBlockPos(cpu.pos)
                    buf.writeVarInt(cpu.bufferUsed)
                    buf.writeVarInt(cpu.bufferCapacity)
                    buf.writeBoolean(cpu.isCrafting)
                    buf.writeUtf(cpu.currentCraftItem, 64)
                    buf.writeBoolean(cpu.isFormed)
                }
                buf.writeVarInt(data.terminalInfos.size)
                for (term in data.terminalInfos) {
                    buf.writeBlockPos(term.pos)
                    buf.writeBoolean(term.isRunning)
                    buf.writeVarInt(term.scriptNames.size)
                    for (s in term.scriptNames) buf.writeUtf(s, 64)
                    buf.writeVarInt(term.handlers.size)
                    for (h in term.handlers) buf.writeUtf(h, 64)
                    buf.writeBoolean(term.autoRun)
                }
                buf.writeVarInt(data.recentErrors.size)
                for (err in data.recentErrors) {
                    buf.writeBlockPos(err.terminalPos)
                    buf.writeUtf(err.message, 512)
                    buf.writeVarInt(err.tickAge)
                }
            }
        }
    }
}
