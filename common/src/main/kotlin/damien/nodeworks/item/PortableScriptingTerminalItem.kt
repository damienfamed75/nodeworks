package damien.nodeworks.item

import damien.nodeworks.platform.PlatformServices
import damien.nodeworks.registry.ModDataComponents
import damien.nodeworks.screen.TerminalOpenData
import damien.nodeworks.screen.TerminalScreenHandler
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag
import net.minecraft.world.item.component.TooltipDisplay
import net.minecraft.world.level.Level
import java.util.function.Consumer

/**
 * A handheld script editor with no runtime. It opens the Scripting Terminal editor on
 * the tabs it carries, loads Script items from the inventory and saves back onto them.
 * The draft lives on this item, see [ScriptDraft].
 */
class PortableScriptingTerminalItem(properties: Properties) : Item(properties) {

    override fun use(level: Level, player: Player, hand: InteractionHand): InteractionResult {
        if (level.isClientSide) return InteractionResult.SUCCESS
        val serverPlayer = player as? ServerPlayer ?: return InteractionResult.PASS
        val slot = if (hand == InteractionHand.MAIN_HAND) player.inventory.getSelectedSlot() else Inventory.SLOT_OFFHAND
        open(serverPlayer, slot)
        return InteractionResult.CONSUME
    }

    override fun appendHoverText(
        stack: ItemStack,
        context: TooltipContext,
        display: TooltipDisplay,
        tooltip: Consumer<Component>,
        flag: TooltipFlag,
    ) {
        val draft = draft(stack)
        val hasText = draft.tabs.values.any { it.isNotBlank() }
        if (!hasText && draft.name.isBlank()) {
            tooltip.accept(Component.literal("Empty. Right-click to start writing.").withStyle(ChatFormatting.DARK_GRAY))
            return
        }
        val label = draft.name.ifBlank { "unnamed" }
        tooltip.accept(Component.literal("Editing \"$label\", ${draft.tabs.size} ${if (draft.tabs.size == 1) "tab" else "tabs"}").withStyle(ChatFormatting.GRAY))
    }

    companion object {
        fun draft(stack: ItemStack): ScriptDraft =
            if (!stack.isEmpty && stack.item is PortableScriptingTerminalItem) stack.get(ModDataComponents.SCRIPT_DRAFT) ?: ScriptDraft.EMPTY
            else ScriptDraft.EMPTY

        /** The live terminal stack behind a portable menu, or null once the player no longer holds it there. */
        fun stackIn(player: Player, slot: Int): ItemStack? {
            if (slot !in 0 until Inventory.INVENTORY_SIZE && slot != Inventory.SLOT_OFFHAND) return null
            val stack = player.inventory.getItem(slot)
            return if (stack.item is PortableScriptingTerminalItem) stack else null
        }

        fun open(player: ServerPlayer, slot: Int) {
            val stack = stackIn(player, slot) ?: return
            val draft = draft(stack)
            val openData = TerminalOpenData(
                terminalPos = BlockPos.ZERO,
                scripts = draft.tabs,
                running = false,
                autoRun = false,
                layoutIndex = draft.layoutIndex,
                knownTags = damien.nodeworks.card.KnownCardTags.all(),
                portableSlot = slot,
                scriptName = draft.name,
                loadedSlot = draft.loadedSlot,
                loadedName = draft.loadedName,
            )
            PlatformServices.menu.openExtendedMenu(
                player,
                Component.translatable("item.nodeworks.portable_scripting_terminal"),
                openData,
                TerminalOpenData.STREAM_CODEC,
                { syncId, _, _ -> TerminalScreenHandler.createPortable(syncId, slot, draft) },
            )
        }
    }
}
