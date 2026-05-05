package damien.nodeworks.screen

import damien.nodeworks.network.SetExportChestFilterRulesPayload
import net.minecraft.core.BlockPos
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec

/**
 * Initial settings shipped to the client when an Export Chest GUI opens.
 * The menu's [ContainerData] keeps the int fields in sync after open. The
 * rule list is shipped here once at open and re-pushed via
 * [SetExportChestFilterRulesPayload] when the player edits it.
 */
data class ExportChestOpenData(
    val pos: BlockPos,
    val filterRules: List<String>,
    val channelNbtInt: Int,
    val pushFaceOrdinal: Int,
    val redstoneMode: Int,
    val tickInterval: Int,
) {
    companion object {
        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, ExportChestOpenData> =
            object : StreamCodec<FriendlyByteBuf, ExportChestOpenData> {
                override fun decode(buf: FriendlyByteBuf): ExportChestOpenData {
                    val pos = buf.readBlockPos()
                    val ruleCount = buf.readVarInt().coerceIn(0, SetExportChestFilterRulesPayload.MAX_RULES)
                    val rules = (0 until ruleCount).map { buf.readUtf(SetExportChestFilterRulesPayload.MAX_RULE_LENGTH) }
                    return ExportChestOpenData(
                        pos = pos,
                        filterRules = rules,
                        channelNbtInt = buf.readVarInt(),
                        pushFaceOrdinal = buf.readVarInt(),
                        redstoneMode = buf.readVarInt(),
                        tickInterval = buf.readVarInt(),
                    )
                }

                override fun encode(buf: FriendlyByteBuf, data: ExportChestOpenData) {
                    buf.writeBlockPos(data.pos)
                    val cropped = data.filterRules.take(SetExportChestFilterRulesPayload.MAX_RULES)
                    buf.writeVarInt(cropped.size)
                    for (rule in cropped) buf.writeUtf(
                        rule.take(SetExportChestFilterRulesPayload.MAX_RULE_LENGTH),
                        SetExportChestFilterRulesPayload.MAX_RULE_LENGTH,
                    )
                    buf.writeVarInt(data.channelNbtInt)
                    buf.writeVarInt(data.pushFaceOrdinal)
                    buf.writeVarInt(data.redstoneMode)
                    buf.writeVarInt(data.tickInterval)
                }
            }
    }
}
