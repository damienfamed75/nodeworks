package damien.nodeworks.item

import damien.nodeworks.block.NodeBlock
import damien.nodeworks.network.NodeConnectionHelper
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.InteractionResult
import net.minecraft.world.item.Item
import net.minecraft.world.item.context.UseOnContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class NetworkWrenchItem(properties: Properties) : Item(properties) {

    companion object {
        private val selectedNodes = ConcurrentHashMap<UUID, BlockPos>()

        fun clearSelection(playerUuid: UUID) {
            selectedNodes.remove(playerUuid)
        }
    }

    override fun useOn(context: UseOnContext): InteractionResult {
        val level = context.level
        val pos = context.clickedPos
        val player = context.player ?: return InteractionResult.PASS

        // Only process on server
        if (level.isClientSide) return InteractionResult.SUCCESS

        // Must click a node block
        if (level.getBlockState(pos).block !is NodeBlock) return InteractionResult.PASS

        val serverLevel = level as ServerLevel

        if (!player.isShiftKeyDown) {
            // Right-click: select node
            selectedNodes[player.uuid] = pos
            player.displayClientMessage(
                Component.translatable("message.nodeworks.node_selected", pos.x, pos.y, pos.z), false
            )
            return InteractionResult.SUCCESS
        }

        // Shift + right-click: connect/disconnect
        val selectedPos = selectedNodes[player.uuid]
        if (selectedPos == null) {
            player.displayClientMessage(
                Component.translatable("message.nodeworks.no_selection"), false
            )
            return InteractionResult.SUCCESS
        }

        if (selectedPos == pos) {
            player.displayClientMessage(
                Component.translatable("message.nodeworks.same_node"), false
            )
            return InteractionResult.SUCCESS
        }

        if (NodeConnectionHelper.getNodeEntity(level, selectedPos) == null) {
            selectedNodes.remove(player.uuid)
            player.displayClientMessage(
                Component.translatable("message.nodeworks.selection_invalid"), false
            )
            return InteractionResult.SUCCESS
        }

        if (!NodeConnectionHelper.isWithinRange(selectedPos, pos)) {
            player.displayClientMessage(
                Component.translatable("message.nodeworks.too_far"), false
            )
            return InteractionResult.SUCCESS
        }

        if (!NodeConnectionHelper.checkLineOfSight(level, selectedPos, pos)) {
            player.displayClientMessage(
                Component.translatable("message.nodeworks.no_line_of_sight"), false
            )
            return InteractionResult.SUCCESS
        }

        val connected = NodeConnectionHelper.toggleConnection(serverLevel, selectedPos, pos)
        selectedNodes.remove(player.uuid)

        val msgKey = if (connected) "message.nodeworks.connected" else "message.nodeworks.disconnected"
        player.displayClientMessage(Component.translatable(msgKey), false)

        return InteractionResult.SUCCESS
    }
}
