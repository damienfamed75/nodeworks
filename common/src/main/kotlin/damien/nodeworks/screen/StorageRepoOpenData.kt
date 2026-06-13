package damien.nodeworks.screen

import net.minecraft.core.BlockPos
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec

/**
 * Open-time payload for the Storage Repo menu. Mirrors [StorageCardOpenData] but
 * targets a block entity by [pos] instead of a held card. Carries the cluster
 * anchor's filter snapshot so the client sees the right initial state without a
 * round-trip read.
 *
 * No `cardName` (Repos are renamed via the block item's anvil), no `customSideOrdinal`
 * (Repos are symmetric and don't have a directional face), no `handOrdinal` (no hand
 * binding).
 */
data class StorageRepoOpenData(
    val pos: BlockPos,
    val filterMode: Int,
    val stackability: Int,
    val nbtFilter: Int,
    val priority: Int,
    val channelId: Int,
    val filterRules: List<String>,
) {
    companion object {
        const val MAX_RULE_LENGTH = StorageCardOpenData.MAX_RULE_LENGTH
        const val MAX_RULES = StorageCardOpenData.MAX_RULES

        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, StorageRepoOpenData> =
            object : StreamCodec<FriendlyByteBuf, StorageRepoOpenData> {
                override fun decode(buf: FriendlyByteBuf): StorageRepoOpenData {
                    val pos = buf.readBlockPos()
                    val mode = buf.readVarInt()
                    val stack = buf.readVarInt()
                    val nbt = buf.readVarInt()
                    val priority = buf.readVarInt()
                    val channel = buf.readVarInt()
                    val count = buf.readVarInt().coerceIn(0, MAX_RULES)
                    val rules = ArrayList<String>(count)
                    for (i in 0 until count) rules.add(buf.readUtf(MAX_RULE_LENGTH))
                    return StorageRepoOpenData(pos, mode, stack, nbt, priority, channel, rules)
                }

                override fun encode(buf: FriendlyByteBuf, data: StorageRepoOpenData) {
                    buf.writeBlockPos(data.pos)
                    buf.writeVarInt(data.filterMode)
                    buf.writeVarInt(data.stackability)
                    buf.writeVarInt(data.nbtFilter)
                    buf.writeVarInt(data.priority)
                    buf.writeVarInt(data.channelId)
                    val cropped = data.filterRules.take(MAX_RULES)
                    buf.writeVarInt(cropped.size)
                    for (rule in cropped) buf.writeUtf(rule.take(MAX_RULE_LENGTH), MAX_RULE_LENGTH)
                }
            }
    }
}
