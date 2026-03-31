package damien.nodeworks.screen

import net.minecraft.core.BlockPos
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec

data class DiagnosticOpenData(
    val blocks: List<NetworkBlock>,
    val networkName: String,
    val networkColor: Int
) {
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
        val alias: String
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
                        CardInfo(buf.readVarInt(), buf.readUtf(32), buf.readUtf(64))
                    }
                    val detailCount = buf.readVarInt()
                    val details = (0 until detailCount).map { buf.readUtf(256) }
                    NetworkBlock(pos, type, connections, cards, details)
                }
                val networkName = buf.readUtf(64)
                val networkColor = buf.readVarInt()
                return DiagnosticOpenData(blocks, networkName, networkColor)
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
                    }
                    buf.writeVarInt(block.details.size)
                    for (detail in block.details) {
                        buf.writeUtf(detail, 256)
                    }
                }
                buf.writeUtf(data.networkName, 64)
                buf.writeVarInt(data.networkColor)
            }
        }
    }
}
