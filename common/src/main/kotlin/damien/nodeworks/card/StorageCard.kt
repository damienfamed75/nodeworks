package damien.nodeworks.card

import damien.nodeworks.platform.PlatformServices
import damien.nodeworks.screen.StorageCardMenu
import damien.nodeworks.screen.StorageCardOpenData
import net.minecraft.ChatFormatting
import net.minecraft.core.component.DataComponents
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.StringTag
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag
import net.minecraft.world.item.component.CustomData
import net.minecraft.world.item.component.TooltipDisplay
import net.minecraft.world.level.Level
import java.util.function.Consumer

/**
 * Storage Card, declares what the inventory a Node faces is to the network.
 *
 * In Storage mode the inventory is network storage: counted, shown in terminals,
 * planned over, filled by priority. In Export mode the network fills the inventory with
 * rule-matched items from its storage. In Import mode the network drains the inventory
 * into its storage. Export and Import inventories are never storage themselves. The
 * card only declares the move, the network runs it on the card's interval.
 *
 * Right-click in air to configure.
 */
class StorageCard(properties: Properties) : NodeCard(properties) {
    override val cardType: String = "storage"

    override fun use(level: Level, player: Player, hand: InteractionHand): InteractionResult {
        tryResetConfig(level, player, hand)?.let { return it }
        if (level.isClientSide) return InteractionResult.SUCCESS

        val serverPlayer = player as ServerPlayer
        val stack = serverPlayer.getItemInHand(hand)
        val cardName = stack.get(DataComponents.CUSTOM_NAME)?.string.orEmpty()
        val openData = StorageCardOpenData(
            handOrdinal = hand.ordinal,
            filterMode = getFilterMode(stack).ordinal,
            stackability = getStackabilityFilter(stack).ordinal,
            nbtFilter = getNbtFilter(stack).ordinal,
            filterRules = getFilterRules(stack),
            cardName = cardName,
            customSideOrdinal = getCustomSide(stack)?.ordinal ?: -1,
            mode = getMode(stack).ordinal,
            tickInterval = getTickInterval(stack),
            roundRobin = getRoundRobin(stack),
            batch = getBatch(stack),
            keep = getKeep(stack),
            tags = CardTags.get(stack),
            knownTags = KnownCardTags.all(),
        )
        PlatformServices.menu.openExtendedMenu(
            serverPlayer,
            Component.translatable("container.nodeworks.storage_card"),
            openData,
            StorageCardOpenData.STREAM_CODEC,
            { syncId, inv, _ -> StorageCardMenu(syncId, inv, hand, initialName = cardName, initialTags = CardTags.get(stack)) }
        )
        return InteractionResult.CONSUME
    }

    override fun appendHoverText(stack: ItemStack, context: TooltipContext, display: TooltipDisplay, tooltip: Consumer<Component>, flag: TooltipFlag) {
        super.appendHoverText(stack, context, display, tooltip, flag)
        val mode = getMode(stack)
        when (mode) {
            Mode.STORAGE -> {
                tooltip.accept(Component.literal("Priority: ${getPriority(stack)}").withStyle(ChatFormatting.GRAY))
            }
            Mode.EXPORT, Mode.IMPORT -> {
                val interval = getTickInterval(stack)
                val every = if (interval == 1) "every tick" else "every $interval ticks"
                tooltip.accept(Component.literal("Mode: ${modeName(mode)}, $every").withStyle(ChatFormatting.GRAY))
                if (mode == Mode.EXPORT && getBatch(stack) > 0) {
                    tooltip.accept(Component.literal("Batch: ${getBatch(stack)}").withStyle(ChatFormatting.GRAY))
                }
                if (mode == Mode.IMPORT) {
                    if (getKeep(stack) > 0) tooltip.accept(Component.literal("Keep: ${getKeep(stack)}").withStyle(ChatFormatting.GRAY))
                    if (getRoundRobin(stack)) tooltip.accept(Component.literal("Round robin").withStyle(ChatFormatting.GRAY))
                }
                val channel = getChannelFilter(stack)
                if (channel is damien.nodeworks.network.ChannelFilter.Color) {
                    tooltip.accept(Component.literal("Channel: ${channel.color.getName().replace('_', ' ')}").withStyle(ChatFormatting.GRAY))
                }
            }
        }

        // Face override is opt-in, omit the line entirely when the player
        // hasn't picked one (so the default-touching-face card stays clean).
        val customSide = getCustomSide(stack)
        if (customSide != null) {
            tooltip.accept(
                Component.literal("Face: ${customSide.displayName}")
                    .withStyle(ChatFormatting.GRAY),
            )
        }

        // Single-line summary of the three filter dimensions so a quick hover
        // reads at a glance whether the card is configured. All three default
        // to "Any" / "Allow", so an unconfigured card reads cleanly too.
        val filterMode = formatEnum(getFilterMode(stack).name)
        val nbt = formatEnum(getNbtFilter(stack).name)
        val stacking = formatEnum(getStackabilityFilter(stack).name)
        tooltip.accept(
            Component.literal("Mode=$filterMode  NBT=$nbt  Stacking=$stacking")
                .withStyle(ChatFormatting.GRAY),
        )

        // Rules: omitted entirely on an unconfigured card. Cap at 3 visible
        // entries with a trailing "..." so a 32-rule card doesn't blow the
        // tooltip off the screen.
        val rules = getFilterRules(stack)
        if (rules.isNotEmpty()) {
            tooltip.accept(Component.literal("Rules:").withStyle(ChatFormatting.GRAY))
            val visible = rules.take(MAX_TOOLTIP_RULES)
            for (rule in visible) {
                tooltip.accept(
                    Component.literal("    $rule").withStyle(ChatFormatting.DARK_GRAY),
                )
            }
            if (rules.size > MAX_TOOLTIP_RULES) {
                tooltip.accept(
                    Component.literal("    ...").withStyle(ChatFormatting.DARK_GRAY),
                )
            }
        }
    }

    /** "ALLOW" → "Allow", "HAS_DATA" → "Has Data". Single helper so the three
     *  filter dimensions render with the same casing convention. */
    private fun formatEnum(name: String): String =
        name.split('_').joinToString(" ") { word ->
            word.lowercase().replaceFirstChar { it.uppercase() }
        }

    companion object {
        /** Cap on rules shown in the item tooltip. Anything beyond this is
         *  collapsed into a trailing "..." so a card with the full
         *  [StorageCardOpenData.MAX_RULES] worth of patterns doesn't push the
         *  tooltip off-screen. */
        private const val MAX_TOOLTIP_RULES = 3

        private const val FILTER_MODE_KEY = "filterMode"
        private const val FILTER_RULES_KEY = "filterRules"
        private const val FILTER_STACK_KEY = "filterStack"
        private const val FILTER_NBT_KEY = "filterNbt"
        /** Optional player-chosen face override. Persisted as the [RelDir.name]
         *  string (e.g. "UP" / "FRONT"). Absent key = use the default face
         *  ([NodeBlockEntity.getSideCapabilities] resolves to the node-side's
         *  opposite, the face touching the node). */
        private const val CUSTOM_SIDE_KEY = "customSide"
        private const val MODE_KEY = "cardMode"
        private const val ROUND_ROBIN_KEY = "roundRobin"
        private const val TICK_INTERVAL_KEY = "tickInterval"
        private const val BATCH_KEY = "batch"
        private const val KEEP_KEY = "keep"

        const val DEFAULT_TICK_INTERVAL = 20
        const val MIN_TICK_INTERVAL = 1
        const val MAX_TICK_INTERVAL = 600

        /** One run moves at most nine full stacks' worth of items, a chest buffer's worth,
         *  however the source is laid out. The scan behind it is not capped: it walks as
         *  many slots as the network's move-time budget allows and resumes from where it
         *  stopped next run, so a large store is covered over several runs. */
        const val MAX_ITEMS_PER_RUN = 9L * 64L

        /** An Export batch can be at most one run's worth, since a run is what carries it. */
        const val MAX_BATCH = MAX_ITEMS_PER_RUN.toInt()

        /** Items an Import leaves behind per item type, at most. */
        const val MAX_KEEP = 999

        const val BUCKET_MB = 1000L

        /** Fluids move alongside items when the faced block has a tank: nine buckets per
         *  run, the fluid counterpart of nine stacks. */
        const val MAX_FLUID_PER_RUN = 9L * BUCKET_MB

        /** A card reuses the network walk for up to a second. Items it moves do not change
         *  the network, and anything that does drops the cached walk at once, so an
         *  interval of one tick costs one walk a second, not one a tick. */
        const val SNAPSHOT_MAX_AGE_TICKS = 20L

        /** Round robin spreads each stack over at most this many Storage Cards per run,
         *  starting at the rotation index, and the window moves on next run. Small setups
         *  fit inside it, large ones are covered a window at a time, and a run never
         *  touches more cards than this however many the network has. */
        const val ROUND_ROBIN_WINDOW = 16

        /** What the faced inventory is to the network. See the class comment. */
        enum class Mode { STORAGE, EXPORT, IMPORT }

        fun modeName(mode: Mode): String = when (mode) {
            Mode.STORAGE -> "Storage"
            Mode.EXPORT -> "Export"
            Mode.IMPORT -> "Import"
        }

        /** Filter mode controls whether the rule list whitelists or blacklists items.
         *  An empty rule list means "accept everything" regardless of mode, so the
         *  default-constructed card behaves the way it always has. */
        enum class FilterMode { ALLOW, DENY }

        /** Stackability gate, AND-combined with the rule list and the NBT gate.
         *  ANY means stackability isn't considered (default), STACKABLE accepts
         *  only items with `maxStackSize > 1`, NON_STACKABLE accepts only items
         *  with `maxStackSize == 1` (tools, armor, etc.). */
        enum class StackabilityFilter { ANY, STACKABLE, NON_STACKABLE }

        /** NBT-presence gate, AND-combined with the rule list and stackability.
         *  ANY ignores NBT, HAS_DATA accepts only items carrying CUSTOM_DATA /
         *  damage / similar non-default state, NO_DATA accepts only items in
         *  their pristine form. Doesn't introspect the NBT contents, that's
         *  what `network:route`'s Lua predicate is for. */
        enum class NbtFilter { ANY, HAS_DATA, NO_DATA }

        private fun tagOf(stack: ItemStack): CompoundTag? = stack.get(DataComponents.CUSTOM_DATA)?.copyTag()

        private fun write(stack: ItemStack, edit: (CompoundTag) -> Unit) {
            val tag = tagOf(stack) ?: CompoundTag()
            edit(tag)
            stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag))
        }

        fun getMode(stack: ItemStack): Mode {
            val raw = tagOf(stack)?.getStringOr(MODE_KEY, Mode.STORAGE.name) ?: return Mode.STORAGE
            return runCatching { Mode.valueOf(raw) }.getOrDefault(Mode.STORAGE)
        }

        /** Storage is the default and leaves no key, so a card never touched stays clean. */
        fun setMode(stack: ItemStack, mode: Mode) {
            if (getMode(stack) == mode) return
            write(stack) { if (mode == Mode.STORAGE) it.remove(MODE_KEY) else it.putString(MODE_KEY, mode.name) }
        }

        fun getPriority(stack: ItemStack): Int = tagOf(stack)?.getIntOr("priority", 0) ?: 0

        fun setPriority(stack: ItemStack, priority: Int) {
            val clamped = priority.coerceIn(0, 999)
            // Skip the write when the value already matches so a clean GUI cycle
            // doesn't dirty CUSTOM_DATA on a previously-pristine stack and drop
            // the mod-name tooltip line.
            if (getPriority(stack) == clamped) return
            write(stack) { it.putInt("priority", clamped) }
        }

        fun getTickInterval(stack: ItemStack): Int =
            (tagOf(stack)?.getIntOr(TICK_INTERVAL_KEY, DEFAULT_TICK_INTERVAL) ?: DEFAULT_TICK_INTERVAL)
                .coerceIn(MIN_TICK_INTERVAL, MAX_TICK_INTERVAL)

        fun setTickInterval(stack: ItemStack, interval: Int) {
            val clamped = interval.coerceIn(MIN_TICK_INTERVAL, MAX_TICK_INTERVAL)
            if (getTickInterval(stack) == clamped) return
            write(stack) { if (clamped == DEFAULT_TICK_INTERVAL) it.remove(TICK_INTERVAL_KEY) else it.putInt(TICK_INTERVAL_KEY, clamped) }
        }

        fun getRoundRobin(stack: ItemStack): Boolean = tagOf(stack)?.getBooleanOr(ROUND_ROBIN_KEY, false) ?: false

        /** Kept whatever the mode, so switching modes loses nothing. */
        fun setRoundRobin(stack: ItemStack, on: Boolean) {
            if (getRoundRobin(stack) == on) return
            write(stack) { if (on) it.putBoolean(ROUND_ROBIN_KEY, true) else it.remove(ROUND_ROBIN_KEY) }
        }

        /** Export only: move when at least this many matching items are in storage, and at
         *  most this many per run. Zero moves whatever exists. */
        fun getBatch(stack: ItemStack): Int = (tagOf(stack)?.getIntOr(BATCH_KEY, 0) ?: 0).coerceIn(0, MAX_BATCH)

        fun setBatch(stack: ItemStack, batch: Int) {
            val clamped = batch.coerceIn(0, MAX_BATCH)
            if (getBatch(stack) == clamped) return
            write(stack) { if (clamped == 0) it.remove(BATCH_KEY) else it.putInt(BATCH_KEY, clamped) }
        }

        /** Import only: items of each type left in the faced inventory. */
        fun getKeep(stack: ItemStack): Int = (tagOf(stack)?.getIntOr(KEEP_KEY, 0) ?: 0).coerceIn(0, MAX_KEEP)

        fun setKeep(stack: ItemStack, keep: Int) {
            val clamped = keep.coerceIn(0, MAX_KEEP)
            if (getKeep(stack) == clamped) return
            write(stack) { if (clamped == 0) it.remove(KEEP_KEY) else it.putInt(KEEP_KEY, clamped) }
        }

        /** Which Storage Cards an Export draws from or an Import lands on. No channel set
         *  means every card. In Storage mode the same key is the card's own colour. */
        fun getChannelFilter(stack: ItemStack): damien.nodeworks.network.ChannelFilter =
            if (CardChannel.isSet(stack)) damien.nodeworks.network.ChannelFilter.Color(CardChannel.get(stack))
            else damien.nodeworks.network.ChannelFilter.All

        fun setChannelFilter(stack: ItemStack, filter: damien.nodeworks.network.ChannelFilter) {
            when (filter) {
                damien.nodeworks.network.ChannelFilter.All -> CardChannel.clear(stack)
                is damien.nodeworks.network.ChannelFilter.Color -> CardChannel.set(stack, filter.color)
            }
        }

        fun getFilterMode(stack: ItemStack): FilterMode {
            val raw = tagOf(stack)?.getStringOr(FILTER_MODE_KEY, FilterMode.ALLOW.name) ?: return FilterMode.ALLOW
            return runCatching { FilterMode.valueOf(raw) }.getOrDefault(FilterMode.ALLOW)
        }

        fun setFilterMode(stack: ItemStack, mode: FilterMode) {
            if (getFilterMode(stack) == mode) return
            write(stack) { it.putString(FILTER_MODE_KEY, mode.name) }
        }

        /** Rule list is each row's filter expression, using the same syntax
         *  [damien.nodeworks.script.CardHandle.matchesFilter] consumes elsewhere.
         *  Empty rules are stripped on save so a half-edited row doesn't
         *  silently accept everything. */
        fun getFilterRules(stack: ItemStack): List<String> {
            val list = tagOf(stack)?.getList(FILTER_RULES_KEY)?.orElse(null) ?: return emptyList()
            return (0 until list.size).map { list.getStringOr(it, "") }.filter { it.isNotEmpty() }
        }

        fun setFilterRules(stack: ItemStack, rules: List<String>) {
            val cleaned = rules.map { it.trim() }.filter { it.isNotEmpty() }
            if (getFilterRules(stack) == cleaned) return
            write(stack) { tag ->
                if (cleaned.isEmpty()) {
                    tag.remove(FILTER_RULES_KEY)
                } else {
                    val list = ListTag()
                    for (rule in cleaned) list.add(StringTag.valueOf(rule))
                    tag.put(FILTER_RULES_KEY, list)
                }
            }
        }

        fun getStackabilityFilter(stack: ItemStack): StackabilityFilter {
            val raw = tagOf(stack)?.getStringOr(FILTER_STACK_KEY, StackabilityFilter.ANY.name) ?: return StackabilityFilter.ANY
            return runCatching { StackabilityFilter.valueOf(raw) }.getOrDefault(StackabilityFilter.ANY)
        }

        fun setStackabilityFilter(stack: ItemStack, mode: StackabilityFilter) {
            if (getStackabilityFilter(stack) == mode) return
            // ANY is the default, drop the key so a clean card stays clean and
            // the mod-name tooltip line keeps working on otherwise-pristine
            // stacks. Mirrors the priority writer's "skip if equal" pattern.
            write(stack) { if (mode == StackabilityFilter.ANY) it.remove(FILTER_STACK_KEY) else it.putString(FILTER_STACK_KEY, mode.name) }
        }

        fun getNbtFilter(stack: ItemStack): NbtFilter {
            val raw = tagOf(stack)?.getStringOr(FILTER_NBT_KEY, NbtFilter.ANY.name) ?: return NbtFilter.ANY
            return runCatching { NbtFilter.valueOf(raw) }.getOrDefault(NbtFilter.ANY)
        }

        fun setNbtFilter(stack: ItemStack, mode: NbtFilter) {
            if (getNbtFilter(stack) == mode) return
            write(stack) { if (mode == NbtFilter.ANY) it.remove(FILTER_NBT_KEY) else it.putString(FILTER_NBT_KEY, mode.name) }
        }

        /** Player-chosen face override. Null = use the default (face touching
         *  the node). Persisted as [damien.nodeworks.screen.widget.RelDir.name]
         *  in CUSTOM_DATA. The actual world [net.minecraft.core.Direction] is
         *  resolved at capability build-time using the node side the card is
         *  mounted on. */
        fun getCustomSide(stack: ItemStack): damien.nodeworks.screen.widget.RelDir? {
            val raw = tagOf(stack)?.getStringOr(CUSTOM_SIDE_KEY, "") ?: return null
            if (raw.isEmpty()) return null
            return runCatching { damien.nodeworks.screen.widget.RelDir.valueOf(raw) }.getOrNull()
        }

        fun setCustomSide(stack: ItemStack, side: damien.nodeworks.screen.widget.RelDir?) {
            if (getCustomSide(stack) == side) return
            write(stack) { if (side == null) it.remove(CUSTOM_SIDE_KEY) else it.putString(CUSTOM_SIDE_KEY, side.name) }
        }
    }
}
