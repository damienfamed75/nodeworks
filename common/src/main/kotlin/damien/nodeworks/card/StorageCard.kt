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
 * Storage Card, registers an adjacent container as passive network storage.
 * Items in storage-card inventories are discoverable by the entire network
 * and available for crafting via Recipe Cards.
 *
 * Right-click in air to set priority (0-99).
 */
class StorageCard(properties: Properties) : NodeCard(properties) {
    override val cardType: String = "storage"

    override fun use(level: Level, player: Player, hand: InteractionHand): InteractionResult {
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
        )
        PlatformServices.menu.openExtendedMenu(
            serverPlayer,
            Component.translatable("container.nodeworks.storage_card"),
            openData,
            StorageCardOpenData.STREAM_CODEC,
            { syncId, inv, _ -> StorageCardMenu(syncId, inv, hand, initialName = cardName) }
        )
        return InteractionResult.CONSUME
    }

    override fun appendHoverText(stack: ItemStack, context: TooltipContext, display: TooltipDisplay, tooltip: Consumer<Component>, flag: TooltipFlag) {
        super.appendHoverText(stack, context, display, tooltip, flag)
        val priority = getPriority(stack)
        tooltip.accept(Component.literal("Priority: $priority").withStyle(ChatFormatting.GRAY))
    }

    companion object {
        private const val FILTER_MODE_KEY = "filterMode"
        private const val FILTER_RULES_KEY = "filterRules"
        private const val FILTER_STACK_KEY = "filterStack"
        private const val FILTER_NBT_KEY = "filterNbt"

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

        fun getPriority(stack: ItemStack): Int {
            val customData = stack.get(DataComponents.CUSTOM_DATA) ?: return 0
            return customData.copyTag().getIntOr("priority", 0)
        }

        fun setPriority(stack: ItemStack, priority: Int) {
            val clamped = priority.coerceIn(0, 999)
            // Skip the write when the value already matches so a clean GUI cycle
            // doesn't dirty CUSTOM_DATA on a previously-pristine stack and drop
            // the mod-name tooltip line.
            if (getPriority(stack) == clamped) return
            // Read-modify-write so we don't clobber sibling keys like the channel
            // color set via [CardChannel.set]. Pre-channel this method always wrote
            // a fresh single-key tag, which is fine when nothing else lives in
            // CUSTOM_DATA, now we merge.
            val tag = stack.get(DataComponents.CUSTOM_DATA)?.copyTag() ?: CompoundTag()
            tag.putInt("priority", clamped)
            stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag))
        }

        fun getFilterMode(stack: ItemStack): FilterMode {
            val customData = stack.get(DataComponents.CUSTOM_DATA) ?: return FilterMode.ALLOW
            val raw = customData.copyTag().getStringOr(FILTER_MODE_KEY, FilterMode.ALLOW.name)
            return runCatching { FilterMode.valueOf(raw) }.getOrDefault(FilterMode.ALLOW)
        }

        fun setFilterMode(stack: ItemStack, mode: FilterMode) {
            if (getFilterMode(stack) == mode) return
            val tag = stack.get(DataComponents.CUSTOM_DATA)?.copyTag() ?: CompoundTag()
            tag.putString(FILTER_MODE_KEY, mode.name)
            stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag))
        }

        /** Rule list is each row's filter expression, using the same syntax
         *  [damien.nodeworks.script.CardHandle.matchesFilter] consumes elsewhere.
         *  Empty rules are stripped on save so a half-edited row doesn't
         *  silently accept everything. */
        fun getFilterRules(stack: ItemStack): List<String> {
            val customData = stack.get(DataComponents.CUSTOM_DATA) ?: return emptyList()
            val list = customData.copyTag().getList(FILTER_RULES_KEY).orElse(null) ?: return emptyList()
            return (0 until list.size).map { list.getStringOr(it, "") }.filter { it.isNotEmpty() }
        }

        fun setFilterRules(stack: ItemStack, rules: List<String>) {
            val cleaned = rules.map { it.trim() }.filter { it.isNotEmpty() }
            if (getFilterRules(stack) == cleaned) return
            val tag = stack.get(DataComponents.CUSTOM_DATA)?.copyTag() ?: CompoundTag()
            if (cleaned.isEmpty()) {
                tag.remove(FILTER_RULES_KEY)
            } else {
                val list = ListTag()
                for (rule in cleaned) list.add(StringTag.valueOf(rule))
                tag.put(FILTER_RULES_KEY, list)
            }
            stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag))
        }

        fun getStackabilityFilter(stack: ItemStack): StackabilityFilter {
            val customData = stack.get(DataComponents.CUSTOM_DATA) ?: return StackabilityFilter.ANY
            val raw = customData.copyTag().getStringOr(FILTER_STACK_KEY, StackabilityFilter.ANY.name)
            return runCatching { StackabilityFilter.valueOf(raw) }.getOrDefault(StackabilityFilter.ANY)
        }

        fun setStackabilityFilter(stack: ItemStack, mode: StackabilityFilter) {
            if (getStackabilityFilter(stack) == mode) return
            val tag = stack.get(DataComponents.CUSTOM_DATA)?.copyTag() ?: CompoundTag()
            // ANY is the default, drop the key so a clean card stays clean and
            // the mod-name tooltip line keeps working on otherwise-pristine
            // stacks. Mirrors the priority writer's "skip if equal" pattern.
            if (mode == StackabilityFilter.ANY) {
                tag.remove(FILTER_STACK_KEY)
            } else {
                tag.putString(FILTER_STACK_KEY, mode.name)
            }
            stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag))
        }

        fun getNbtFilter(stack: ItemStack): NbtFilter {
            val customData = stack.get(DataComponents.CUSTOM_DATA) ?: return NbtFilter.ANY
            val raw = customData.copyTag().getStringOr(FILTER_NBT_KEY, NbtFilter.ANY.name)
            return runCatching { NbtFilter.valueOf(raw) }.getOrDefault(NbtFilter.ANY)
        }

        fun setNbtFilter(stack: ItemStack, mode: NbtFilter) {
            if (getNbtFilter(stack) == mode) return
            val tag = stack.get(DataComponents.CUSTOM_DATA)?.copyTag() ?: CompoundTag()
            if (mode == NbtFilter.ANY) {
                tag.remove(FILTER_NBT_KEY)
            } else {
                tag.putString(FILTER_NBT_KEY, mode.name)
            }
            stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag))
        }
    }
}
