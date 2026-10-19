package damien.nodeworks.screen

import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec

/**
 * Open-time payload for the Transfer Card menu. Carries the held-hand ordinal plus
 * a snapshot of the card's settings, the same shape as [StorageCardOpenData] minus
 * the storage-only filter gates and plus the card's mode, round robin and interval.
 */
data class TransferCardOpenData(
    val handOrdinal: Int,
    /** [damien.nodeworks.card.TransferCard.Companion.Mode] ordinal, 0 = IMPORT, 1 = EXPORT. */
    val mode: Int,
    /** [damien.nodeworks.network.ChannelFilter.toNbtInt], -1 = any Storage Card. */
    val channelNbt: Int,
    val roundRobin: Boolean,
    val tickInterval: Int,
    val filterRules: List<String>,
    val cardName: String,
    /** -1 = no override, 0..5 = [damien.nodeworks.screen.widget.RelDir] ordinal. */
    val customSideOrdinal: Int = -1,
) {
    companion object {
        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, TransferCardOpenData> =
            object : StreamCodec<FriendlyByteBuf, TransferCardOpenData> {
                override fun decode(buf: FriendlyByteBuf): TransferCardOpenData {
                    val hand = buf.readVarInt()
                    val mode = buf.readVarInt()
                    val channelNbt = buf.readVarInt()
                    val roundRobin = buf.readBoolean()
                    val interval = buf.readVarInt()
                    val count = buf.readVarInt().coerceIn(0, StorageCardOpenData.MAX_RULES)
                    val rules = ArrayList<String>(count)
                    for (i in 0 until count) rules.add(buf.readUtf(StorageCardOpenData.MAX_RULE_LENGTH))
                    val name = buf.readUtf(StorageCardOpenData.MAX_NAME_LENGTH)
                    val side = buf.readVarInt()
                    return TransferCardOpenData(hand, mode, channelNbt, roundRobin, interval, rules, name, side)
                }

                override fun encode(buf: FriendlyByteBuf, data: TransferCardOpenData) {
                    buf.writeVarInt(data.handOrdinal)
                    buf.writeVarInt(data.mode)
                    buf.writeVarInt(data.channelNbt)
                    buf.writeBoolean(data.roundRobin)
                    buf.writeVarInt(data.tickInterval)
                    val cropped = data.filterRules.take(StorageCardOpenData.MAX_RULES)
                    buf.writeVarInt(cropped.size)
                    for (rule in cropped) {
                        buf.writeUtf(rule.take(StorageCardOpenData.MAX_RULE_LENGTH), StorageCardOpenData.MAX_RULE_LENGTH)
                    }
                    buf.writeUtf(data.cardName.take(StorageCardOpenData.MAX_NAME_LENGTH), StorageCardOpenData.MAX_NAME_LENGTH)
                    buf.writeVarInt(data.customSideOrdinal)
                }
            }
    }
}
