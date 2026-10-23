package damien.nodeworks.screen

import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec

/**
 * Open-time payload for the Storage Card menu. Carries the held-hand ordinal
 * (so the client can recover the held card) plus the snapshot of the card's
 * state at open time. Filter rules are sent verbatim, the rule list is
 * small in practice (handful of expressions) so a full-list payload stays well
 * under the packet size ceiling.
 */
data class StorageCardOpenData(
    val handOrdinal: Int,
    /** Ordinals so the codec stays primitive-only:
     *  - filterMode: 0 = ALLOW, 1 = DENY
     *  - stackability: 0 = ANY, 1 = STACKABLE, 2 = NON_STACKABLE
     *  - nbtFilter: 0 = ANY, 1 = HAS_DATA, 2 = NO_DATA
     *  These map to the [damien.nodeworks.card.StorageCard.Companion] enum
     *  ordinals, so adding a new enum value only requires bumping this list. */
    val filterMode: Int,
    val stackability: Int,
    val nbtFilter: Int,
    val filterRules: List<String>,
    val cardName: String,
    /** Player-chosen side override. -1 = no override (use default face),
     *  0..5 = [damien.nodeworks.screen.widget.RelDir] ordinal. */
    val customSideOrdinal: Int = -1,
    /** [damien.nodeworks.card.StorageCard.Companion.Mode] ordinal. */
    val mode: Int = 0,
    val tickInterval: Int = damien.nodeworks.card.StorageCard.DEFAULT_TICK_INTERVAL,
    val roundRobin: Boolean = false,
    val batch: Int = 0,
    val keep: Int = 0,
    val tags: List<String> = emptyList(),
    /** Tags the server has seen on cards, for the GUI to offer. */
    val knownTags: List<String> = emptyList(),
) {
    companion object {
        /** Per-rule string cap. The filter syntax (`*`, `#tag`, `/regex/`,
         *  exact id) tops out around 64 chars in real use, 256 leaves
         *  headroom without inviting abuse. */
        const val MAX_RULE_LENGTH = 256

        /** Per-card rule count cap. The GUI's add-row affordance tops out at
         *  this number so the network packet stays small and the UI doesn't
         *  scroll forever. */
        const val MAX_RULES = 32

        /** Cap matches the in-game anvil cap so the rename UX feels familiar. */
        const val MAX_NAME_LENGTH = 50

        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, StorageCardOpenData> =
            object : StreamCodec<FriendlyByteBuf, StorageCardOpenData> {
                override fun decode(buf: FriendlyByteBuf): StorageCardOpenData {
                    val hand = buf.readVarInt()
                    val mode = buf.readVarInt()
                    val stack = buf.readVarInt()
                    val nbt = buf.readVarInt()
                    val count = buf.readVarInt().coerceIn(0, MAX_RULES)
                    val rules = ArrayList<String>(count)
                    for (i in 0 until count) rules.add(buf.readUtf(MAX_RULE_LENGTH))
                    val name = buf.readUtf(MAX_NAME_LENGTH)
                    val side = buf.readVarInt()
                    val cardMode = buf.readVarInt()
                    val interval = buf.readVarInt()
                    val roundRobin = buf.readBoolean()
                    val batch = buf.readVarInt()
                    val keep = buf.readVarInt()
                    val tags = readTags(buf, damien.nodeworks.card.CardTags.MAX_TAGS)
                    val known = readTags(buf, damien.nodeworks.card.KnownCardTags.MAX_OFFERED)
                    return StorageCardOpenData(hand, mode, stack, nbt, rules, name, side, cardMode, interval, roundRobin, batch, keep, tags, known)
                }

                override fun encode(buf: FriendlyByteBuf, data: StorageCardOpenData) {
                    buf.writeVarInt(data.handOrdinal)
                    buf.writeVarInt(data.filterMode)
                    buf.writeVarInt(data.stackability)
                    buf.writeVarInt(data.nbtFilter)
                    val cropped = data.filterRules.take(MAX_RULES)
                    buf.writeVarInt(cropped.size)
                    for (rule in cropped) buf.writeUtf(rule.take(MAX_RULE_LENGTH), MAX_RULE_LENGTH)
                    buf.writeUtf(data.cardName.take(MAX_NAME_LENGTH), MAX_NAME_LENGTH)
                    buf.writeVarInt(data.customSideOrdinal)
                    buf.writeVarInt(data.mode)
                    buf.writeVarInt(data.tickInterval)
                    buf.writeBoolean(data.roundRobin)
                    buf.writeVarInt(data.batch)
                    buf.writeVarInt(data.keep)
                    writeTags(buf, data.tags, damien.nodeworks.card.CardTags.MAX_TAGS)
                    writeTags(buf, data.knownTags, damien.nodeworks.card.KnownCardTags.MAX_OFFERED)
                }
            }

        fun writeTags(buf: FriendlyByteBuf, tags: List<String>, max: Int) {
            val cropped = tags.take(max)
            buf.writeVarInt(cropped.size)
            for (t in cropped) buf.writeUtf(t.take(damien.nodeworks.card.CardTags.MAX_TAG_LENGTH), damien.nodeworks.card.CardTags.MAX_TAG_LENGTH)
        }

        fun readTags(buf: FriendlyByteBuf, max: Int): List<String> {
            val n = buf.readVarInt().coerceIn(0, max)
            return List(n) { buf.readUtf(damien.nodeworks.card.CardTags.MAX_TAG_LENGTH) }
        }
    }
}
