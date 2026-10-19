package damien.nodeworks.card

import damien.nodeworks.platform.PlatformServices
import damien.nodeworks.screen.TransferCardMenu
import damien.nodeworks.screen.TransferCardOpenData
import net.minecraft.ChatFormatting
import net.minecraft.core.component.DataComponents
import net.minecraft.nbt.CompoundTag
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
 * Transfer Card, the no-script way to move items between the inventory a Node faces
 * and the network's Storage Cards. Import mode feeds that inventory into storage,
 * Export mode pulls rule-matched items out of storage into it. The card only declares
 * the move, the Node runs it on the card's interval.
 *
 * Rules, channel and face use the Storage Card's keys and helpers so the same GUI
 * widgets, autocomplete and Card Programmer paths serve both cards.
 */
class TransferCard(properties: Properties) : NodeCard(properties) {
    override val cardType: String = "transfer"

    override fun use(level: Level, player: Player, hand: InteractionHand): InteractionResult {
        tryResetConfig(level, player, hand)?.let { return it }
        if (level.isClientSide) return InteractionResult.SUCCESS

        val serverPlayer = player as ServerPlayer
        val stack = serverPlayer.getItemInHand(hand)
        val cardName = stack.get(DataComponents.CUSTOM_NAME)?.string.orEmpty()
        val openData = TransferCardOpenData(
            handOrdinal = hand.ordinal,
            mode = getMode(stack).ordinal,
            channelNbt = getChannelFilter(stack).toNbtInt(),
            roundRobin = getRoundRobin(stack),
            tickInterval = getTickInterval(stack),
            filterRules = getFilterRules(stack),
            cardName = cardName,
            customSideOrdinal = getCustomSide(stack)?.ordinal ?: -1,
        )
        PlatformServices.menu.openExtendedMenu(
            serverPlayer,
            Component.translatable("container.nodeworks.transfer_card"),
            openData,
            TransferCardOpenData.STREAM_CODEC,
            { syncId, inv, _ -> TransferCardMenu(syncId, inv, hand, initialName = cardName) }
        )
        return InteractionResult.CONSUME
    }

    override fun appendHoverText(stack: ItemStack, context: TooltipContext, display: TooltipDisplay, tooltip: Consumer<Component>, flag: TooltipFlag) {
        super.appendHoverText(stack, context, display, tooltip, flag)
        val mode = if (getMode(stack) == Mode.IMPORT) "Import" else "Export"
        val interval = getTickInterval(stack)
        val every = if (interval == 1) "every tick" else "every $interval ticks"
        tooltip.accept(Component.literal("Mode: $mode, $every").withStyle(ChatFormatting.GRAY))
        if (getMode(stack) == Mode.IMPORT && getRoundRobin(stack)) {
            tooltip.accept(Component.literal("Round robin").withStyle(ChatFormatting.GRAY))
        }
        val channel = getChannelFilter(stack)
        if (channel is damien.nodeworks.network.ChannelFilter.Color) {
            tooltip.accept(Component.literal("Channel: ${channel.color.getName().replace('_', ' ')}").withStyle(ChatFormatting.GRAY))
        }
        val customSide = getCustomSide(stack)
        if (customSide != null) {
            tooltip.accept(Component.literal("Face: ${customSide.displayName}").withStyle(ChatFormatting.GRAY))
        }
        val rules = getFilterRules(stack)
        if (rules.isNotEmpty()) {
            tooltip.accept(Component.literal("Rules:").withStyle(ChatFormatting.GRAY))
            for (rule in rules.take(MAX_TOOLTIP_RULES)) {
                tooltip.accept(Component.literal("    $rule").withStyle(ChatFormatting.DARK_GRAY))
            }
            if (rules.size > MAX_TOOLTIP_RULES) {
                tooltip.accept(Component.literal("    ...").withStyle(ChatFormatting.DARK_GRAY))
            }
        }
    }

    companion object {
        private const val MAX_TOOLTIP_RULES = 3
        private const val MODE_KEY = "mode"
        private const val ROUND_ROBIN_KEY = "roundRobin"
        private const val TICK_INTERVAL_KEY = "tickInterval"

        const val DEFAULT_TICK_INTERVAL = 20
        const val MIN_TICK_INTERVAL = 1
        const val MAX_TICK_INTERVAL = 600

        /** One run moves at most nine full stacks' worth of items, a chest buffer's worth,
         *  however the source is laid out. The scan behind it is not capped: it walks as
         *  many slots as the network's move-time budget allows and resumes from where it
         *  stopped next run, so a large store is covered over several runs. */
        const val MAX_ITEMS_PER_RUN = 9L * 64L

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

        /** Import feeds the faced inventory into the network's Storage Cards. Export
         *  pulls rule-matched items from them into the faced inventory. */
        enum class Mode { IMPORT, EXPORT }

        fun getMode(stack: ItemStack): Mode {
            val customData = stack.get(DataComponents.CUSTOM_DATA) ?: return Mode.IMPORT
            val raw = customData.copyTag().getStringOr(MODE_KEY, Mode.IMPORT.name)
            return runCatching { Mode.valueOf(raw) }.getOrDefault(Mode.IMPORT)
        }

        fun setMode(stack: ItemStack, mode: Mode) {
            if (getMode(stack) == mode) return
            val tag = stack.get(DataComponents.CUSTOM_DATA)?.copyTag() ?: CompoundTag()
            if (mode == Mode.IMPORT) tag.remove(MODE_KEY) else tag.putString(MODE_KEY, mode.name)
            stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag))
        }

        fun getRoundRobin(stack: ItemStack): Boolean {
            val customData = stack.get(DataComponents.CUSTOM_DATA) ?: return false
            return customData.copyTag().getBooleanOr(ROUND_ROBIN_KEY, false)
        }

        /** Kept even while Export mode hides it, so flipping modes loses nothing. */
        fun setRoundRobin(stack: ItemStack, on: Boolean) {
            if (getRoundRobin(stack) == on) return
            val tag = stack.get(DataComponents.CUSTOM_DATA)?.copyTag() ?: CompoundTag()
            if (on) tag.putBoolean(ROUND_ROBIN_KEY, true) else tag.remove(ROUND_ROBIN_KEY)
            stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag))
        }

        fun getTickInterval(stack: ItemStack): Int {
            val customData = stack.get(DataComponents.CUSTOM_DATA) ?: return DEFAULT_TICK_INTERVAL
            return customData.copyTag().getIntOr(TICK_INTERVAL_KEY, DEFAULT_TICK_INTERVAL)
                .coerceIn(MIN_TICK_INTERVAL, MAX_TICK_INTERVAL)
        }

        fun setTickInterval(stack: ItemStack, interval: Int) {
            val clamped = interval.coerceIn(MIN_TICK_INTERVAL, MAX_TICK_INTERVAL)
            if (getTickInterval(stack) == clamped) return
            val tag = stack.get(DataComponents.CUSTOM_DATA)?.copyTag() ?: CompoundTag()
            if (clamped == DEFAULT_TICK_INTERVAL) tag.remove(TICK_INTERVAL_KEY) else tag.putInt(TICK_INTERVAL_KEY, clamped)
            stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag))
        }

        /** Which Storage Cards the card trades with. No channel set means every card,
         *  the default. A set channel keeps the card's usual meaning for the item tint
         *  and the Node glow, so [CardChannel] stores it. */
        fun getChannelFilter(stack: ItemStack): damien.nodeworks.network.ChannelFilter =
            if (CardChannel.isSet(stack)) damien.nodeworks.network.ChannelFilter.Color(CardChannel.get(stack))
            else damien.nodeworks.network.ChannelFilter.All

        fun setChannelFilter(stack: ItemStack, filter: damien.nodeworks.network.ChannelFilter) {
            when (filter) {
                damien.nodeworks.network.ChannelFilter.All -> CardChannel.clear(stack)
                is damien.nodeworks.network.ChannelFilter.Color -> CardChannel.set(stack, filter.color)
            }
        }

        /** Same keys as the Storage Card, so one rule list serves both modes and the
         *  Card Programmer, autocomplete and GUI widgets need no second code path. */
        fun getFilterRules(stack: ItemStack): List<String> = StorageCard.getFilterRules(stack)
        fun setFilterRules(stack: ItemStack, rules: List<String>) = StorageCard.setFilterRules(stack, rules)
        fun getCustomSide(stack: ItemStack): damien.nodeworks.screen.widget.RelDir? = StorageCard.getCustomSide(stack)
        fun setCustomSide(stack: ItemStack, side: damien.nodeworks.screen.widget.RelDir?) = StorageCard.setCustomSide(stack, side)
    }
}
