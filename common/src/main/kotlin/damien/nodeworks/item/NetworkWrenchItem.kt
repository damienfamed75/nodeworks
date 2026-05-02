package damien.nodeworks.item

import damien.nodeworks.block.NodeBlock
import damien.nodeworks.network.NodeConnectionHelper
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.InteractionResult
import net.minecraft.world.item.Item
import net.minecraft.world.item.context.UseOnContext
import net.minecraft.world.level.Level
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class NetworkWrenchItem(properties: Properties) : Item(properties) {

    @Suppress("DEPRECATION") // Item.appendHoverText, the non-deprecated path is data components, overkill for a static line.
    override fun appendHoverText(
        itemStack: net.minecraft.world.item.ItemStack,
        context: TooltipContext,
        display: net.minecraft.world.item.component.TooltipDisplay,
        builder: java.util.function.Consumer<Component>,
        tooltipFlag: net.minecraft.world.item.TooltipFlag
    ) {
        builder.accept(Component.literal("Connects Nodes to devices").withStyle(net.minecraft.ChatFormatting.GRAY))
        builder.accept(
            Component.literal("Shift + right-click: select endpoint").withStyle(net.minecraft.ChatFormatting.DARK_GRAY)
        )
        builder.accept(
            Component.literal("Right-click: connect to selected").withStyle(net.minecraft.ChatFormatting.DARK_GRAY)
        )
    }

    private data class Selection(val pos: BlockPos, val dimension: ResourceKey<Level>)

    companion object {
        private val selections = ConcurrentHashMap<UUID, Selection>()

        /** Client-side selected endpoint for highlight rendering, holds either a
         *  Node or a device position. Only meaningful on the client JVM. */
        @JvmField
        var clientSelectedPos: BlockPos? = null

        fun clearSelection(playerUuid: UUID) {
            selections.remove(playerUuid)
        }
    }

    override fun useOn(context: UseOnContext): InteractionResult {
        val level = context.level
        val pos = context.clickedPos
        val player = context.player ?: return InteractionResult.PASS

        if (NodeConnectionHelper.getConnectable(level, pos) == null) {
            return InteractionResult.PASS
        }

        // Client side: track selection for highlight rendering. Any Connectable
        // (Node or device) is selectable, the server-side connect step rejects
        // device-to-device pairs separately.
        if (level.isClientSide) {
            if (player.isShiftKeyDown) {
                clientSelectedPos = pos
            }
            return InteractionResult.SUCCESS
        }

        // --- Server side below ---
        val serverLevel = level as ServerLevel

        if (player.isShiftKeyDown) {
            // Shift + right-click: select either a Node or a device. The
            // device-to-device pairing is rejected at connect time so the
            // selection step stays lenient.
            selections[player.uuid] = Selection(pos, level.dimension())
            player.sendSystemMessage(Component.translatable("message.nodeworks.endpoint_selected", pos.x, pos.y, pos.z))
            return InteractionResult.SUCCESS
        }

        // Right-click: connect/disconnect
        val selection = selections[player.uuid]
        if (selection == null) {
            player.sendSystemMessage(Component.translatable("message.nodeworks.no_selection"))
            return InteractionResult.SUCCESS
        }

        // Selection from a different dimension is invalid
        if (selection.dimension != level.dimension()) {
            selections.remove(player.uuid)
            player.sendSystemMessage(Component.translatable("message.nodeworks.selection_invalid"))
            return InteractionResult.SUCCESS
        }

        val selectedPos = selection.pos

        if (selectedPos == pos) {
            player.sendSystemMessage(Component.translatable("message.nodeworks.same_endpoint"))
            return InteractionResult.SUCCESS
        }

        val entityA = NodeConnectionHelper.getConnectable(level, selectedPos)
        if (entityA == null) {
            selections.remove(player.uuid)
            player.sendSystemMessage(Component.translatable("message.nodeworks.selection_invalid"))
            return InteractionResult.SUCCESS
        }

        // Existing connections take the disconnect branch, which skips the LOS
        // gate, a node placed between two already-linked nodes blocks LOS for
        // them and would otherwise trap the link until the obstacle is removed.
        val isDisconnect = entityA.hasConnection(pos)

        if (!NodeConnectionHelper.isWithinRange(selectedPos, pos)) {
            player.sendSystemMessage(Component.translatable("message.nodeworks.too_far"))
            return InteractionResult.SUCCESS
        }

        if (!isDisconnect && !NodeConnectionHelper.checkLineOfSight(level, selectedPos, pos)) {
            player.sendSystemMessage(Component.translatable("message.nodeworks.no_line_of_sight"))
            return InteractionResult.SUCCESS
        }

        // Connect-only validations.
        if (!isDisconnect) {
            // Devices wire through the Node graph, never directly to each
            // other, so a connect needs at least one Node endpoint.
            val selectedIsNode = level.getBlockState(selectedPos).block is NodeBlock
            val clickedIsNode = level.getBlockState(pos).block is NodeBlock
            if (!selectedIsNode && !clickedIsNode) {
                player.sendSystemMessage(Component.translatable("message.nodeworks.device_to_device"))
                return InteractionResult.SUCCESS
            }

            // Check for duplicate controllers before connecting. Walk the structural topology
            // (ignoring LOS) rather than NetworkDiscovery so an LOS-blocked orphan, which still
            // holds its connection to the old controller on-the-books, is correctly treated as
            // belonging to that controller's network. Otherwise a player could wrench-bridge
            // two networks through a blocked orphan and see both light up once LOS is restored.
            val entityB = NodeConnectionHelper.getConnectable(level, pos)
            if (entityB != null) {
                val ctlA = NodeConnectionHelper.findTopologyController(serverLevel, selectedPos)
                val ctlB = NodeConnectionHelper.findTopologyController(serverLevel, pos)
                if (ctlA != null && ctlB != null && ctlA != ctlB) {
                    player.sendSystemMessage(Component.translatable("message.nodeworks.duplicate_controller"))
                    return InteractionResult.SUCCESS
                }
            }
        }

        val connected = NodeConnectionHelper.toggleConnection(serverLevel, selectedPos, pos)

        val msgKey = if (connected) "message.nodeworks.connected" else "message.nodeworks.disconnected"
        player.sendSystemMessage(Component.translatable(msgKey))

        return InteractionResult.SUCCESS
    }
}
