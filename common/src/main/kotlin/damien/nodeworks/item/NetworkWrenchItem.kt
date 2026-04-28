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
        builder.accept(Component.literal("Connects Nodes").withStyle(net.minecraft.ChatFormatting.GRAY))
        builder.accept(
            Component.literal("Shift + right-click: select node").withStyle(net.minecraft.ChatFormatting.DARK_GRAY)
        )
        builder.accept(
            Component.literal("Right-click: connect to selected").withStyle(net.minecraft.ChatFormatting.DARK_GRAY)
        )
    }

    private data class Selection(val pos: BlockPos, val dimension: ResourceKey<Level>)

    companion object {
        private val selectedNodes = ConcurrentHashMap<UUID, Selection>()

        /** Client-side selected node for highlight rendering. Only meaningful on the client JVM. */
        @JvmField
        var clientSelectedNode: BlockPos? = null

        fun clearSelection(playerUuid: UUID) {
            selectedNodes.remove(playerUuid)
        }
    }

    override fun useOn(context: UseOnContext): InteractionResult {
        val level = context.level
        val pos = context.clickedPos
        val player = context.player ?: return InteractionResult.PASS

        if (NodeConnectionHelper.getConnectable(level, pos) == null) {
            return InteractionResult.PASS
        }
        val isNode = level.getBlockState(pos).block is NodeBlock

        // Client side: track selection for highlight rendering (nodes only)
        if (level.isClientSide) {
            if (player.isShiftKeyDown && isNode) {
                clientSelectedNode = pos
            }
            return InteractionResult.SUCCESS
        }

        // --- Server side below ---
        val serverLevel = level as ServerLevel

        if (player.isShiftKeyDown) {
            // Shift + right-click: select (only Nodes can be selected as connection endpoints)
            if (!isNode) return InteractionResult.PASS
            selectedNodes[player.uuid] = Selection(pos, level.dimension())
            player.sendSystemMessage(Component.translatable("message.nodeworks.node_selected", pos.x, pos.y, pos.z))
            return InteractionResult.SUCCESS
        }

        // Right-click: connect/disconnect
        val selection = selectedNodes[player.uuid]
        if (selection == null) {
            player.sendSystemMessage(Component.translatable("message.nodeworks.no_selection"))
            return InteractionResult.SUCCESS
        }

        // Selection from a different dimension is invalid
        if (selection.dimension != level.dimension()) {
            selectedNodes.remove(player.uuid)
            player.sendSystemMessage(Component.translatable("message.nodeworks.selection_invalid"))
            return InteractionResult.SUCCESS
        }

        val selectedPos = selection.pos

        if (selectedPos == pos) {
            player.sendSystemMessage(Component.translatable("message.nodeworks.same_node"))
            return InteractionResult.SUCCESS
        }

        if (NodeConnectionHelper.getConnectable(level, selectedPos) == null) {
            selectedNodes.remove(player.uuid)
            player.sendSystemMessage(Component.translatable("message.nodeworks.selection_invalid"))
            return InteractionResult.SUCCESS
        }

        if (!NodeConnectionHelper.isWithinRange(selectedPos, pos)) {
            player.sendSystemMessage(Component.translatable("message.nodeworks.too_far"))
            return InteractionResult.SUCCESS
        }

        if (!NodeConnectionHelper.checkLineOfSight(level, selectedPos, pos)) {
            player.sendSystemMessage(Component.translatable("message.nodeworks.no_line_of_sight"))
            return InteractionResult.SUCCESS
        }

        // Check for duplicate controllers before connecting. Walk the structural topology
        // (ignoring LOS) rather than NetworkDiscovery so an LOS-blocked orphan, which still
        // holds its connection to the old controller on-the-books, is correctly treated as
        // belonging to that controller's network. Otherwise a player could wrench-bridge
        // two networks through a blocked orphan and see both light up once LOS is restored.
        val entityA = NodeConnectionHelper.getConnectable(level, selectedPos)
        val entityB = NodeConnectionHelper.getConnectable(level, pos)
        if (entityA != null && entityB != null && !entityA.hasConnection(pos)) {
            val ctlA = NodeConnectionHelper.findTopologyController(serverLevel, selectedPos)
            val ctlB = NodeConnectionHelper.findTopologyController(serverLevel, pos)
            if (ctlA != null && ctlB != null && ctlA != ctlB) {
                player.sendSystemMessage(Component.translatable("message.nodeworks.duplicate_controller"))
                return InteractionResult.SUCCESS
            }
        }

        val connected = NodeConnectionHelper.toggleConnection(serverLevel, selectedPos, pos)

        val msgKey = if (connected) "message.nodeworks.connected" else "message.nodeworks.disconnected"
        player.sendSystemMessage(Component.translatable(msgKey))

        return InteractionResult.SUCCESS
    }
}
